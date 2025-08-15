/*
 * Copyright 2025 The Retrospect Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.retrolang.impl;

import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.TestBlock.IsUint8;
import org.retrolang.impl.CopyPlan.StepType;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.util.Bits;
import java.util.function.Predicate;

/**
 * A CopyEmitter creates Blocks based on a given CopyPlan. The base class assumes that NumVars and
 * RefVars in both source and destination are Registers, but subclasses may override the defaults to
 * load or store values elsewhere.
 */
class CopyEmitter {

  /**
   * Returns a CodeValue for the given NumVar or RefVar.
   *
   * <p>The default implementation just returns the corresponding Register.
   */
  CodeValue getSrcVar(CodeGen codeGen, Template t) {
    return codeGen.register(t);
  }

  /**
   * Sets the given NumVar or RefVar to the given value.
   *
   * <p>The default implementation just sets the corresponding Register.
   */
  void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
    codeGen.emitSet(codeGen.register(t), v);
  }

  /**
   * If this method returns true and a COPY_NUM step has a narrower destination encoding than its
   * source encoding, {@link #emit} will add a block to verify that the source value fits in the
   * destination before calling {@link #setDstVar}. If this method returns false, {@link #setDstVar}
   * is responsible for doing that check.
   *
   * <p>The default implementation returns true.
   */
  boolean checkNarrow() {
    return true;
  }

  /** Emits blocks that will perform the copy, or branch to {@code onFail} if the copy fails. */
  final void emit(CodeGen codeGen, CopyPlan plan, FutureBlock onFail) {
    if (plan.isFail()) {
      codeGen.cb.branchTo(onFail);
    } else {
      for (CopyPlan.Step step : plan.steps) {
        if (!codeGen.cb.nextIsReachable()) {
          break;
        }
        if (step instanceof CopyPlan.Basic basic) {
          switch (basic.type) {
            case COPY_NUM, COPY_REF -> {
              CodeValue srcValue = getSrcVar(codeGen, (Template) basic.src);
              if (basic.type == StepType.COPY_NUM && checkNarrow()) {
                NumEncoding srcEncoding = ((NumVar) basic.src).encoding;
                NumEncoding dstEncoding = ((NumVar) basic.dst).encoding;
                if (srcEncoding.nBytes > dstEncoding.nBytes) {
                  // This is a narrowing, so we need to check that the source value is appropriate.
                  // If srcValue isn't a Register (i.e. it's an Op.Result), compute it and store it
                  // in a register so that we can refer to it more than once.
                  if (!(srcValue instanceof Register)) {
                    Register temp =
                        codeGen.cb.newRegister(
                            srcEncoding == NumEncoding.FLOAT64 ? double.class : int.class);
                    codeGen.emitSet(temp, srcValue);
                    srcValue = temp;
                  }
                  if (srcEncoding == NumEncoding.FLOAT64) {
                    // TODO: check that double is an int
                    throw new UnsupportedOperationException();
                  }
                  if (dstEncoding == NumEncoding.UINT8) {
                    new IsUint8(srcValue).setBranch(false, onFail).addTo(codeGen.cb);
                  }
                  if (!codeGen.cb.nextIsReachable()) {
                    // If we were able to determine that the narrow will always fail, we're done.
                    return;
                  }
                }
              }
              setDstVar(codeGen, (Template) basic.dst, srcValue);
            }
            case SET_NUM, SET_REF -> {
              Value v = (Value) basic.src;
              Object src = v;
              if (basic.type == StepType.SET_NUM) {
                // v is a NumValue, but we just want a Number
                NumVar dstVar = (NumVar) basic.dst;
                if (dstVar.encoding == NumEncoding.FLOAT64) {
                  src = NumValue.asDouble(v);
                } else {
                  src = NumValue.asInt(v);
                }
              } else if (v == Core.EMPTY_ARRAY) {
                RefVar refVar = (RefVar) basic.dst;
                if (refVar.baseType() == Core.VARRAY) {
                  // Setting a vArray refVar to the empty array singleton means setting it to the
                  // empty varray
                  VArrayLayout layout = (VArrayLayout) refVar.frameLayout();
                  src = layout.empty;
                }
              }
              setDstVar(codeGen, (Template) basic.dst, CodeValue.of(src));
            }
            case VERIFY_NUM, VERIFY_REF -> {
              CodeValue src = getSrcVar(codeGen, (Template) basic.src);
              Value v = (Value) basic.dst;
              if (basic.type == StepType.VERIFY_NUM) {
                Number expected =
                    (((NumVar) basic.src).encoding == NumEncoding.FLOAT64)
                        ? NumValue.asDouble(v)
                        : NumValue.asInt(v);
                codeGen.testEqualsNum(src, CodeValue.of(expected), true, onFail);
              } else {
                codeGen.testEqualsObj(src, CodeValue.of(v), true, onFail);
              }
            }
            // TODO: handle VERIFY_REF_TYPE, FRAME_TO_COMPOUND & COMPOUND_TO_FRAME
            default -> throw new UnsupportedOperationException();
          }
        } else {
          CopyPlan.Switch sw = (CopyPlan.Switch) step;
          boolean hasTag = sw.union.tag != null;
          CodeValue switchVal;
          if (hasTag) {
            switchVal = getSrcVar(codeGen, sw.union.tag);
          } else {
            // TODO: implement untagged switches
            throw new UnsupportedOperationException();
          }
          int n = sw.union.numChoices();
          // Emit something like "if <this is choice i> then <do choice i>" for each i, except that
          // we'll skip empty and fail choices on this pass, and handle them as a group when we're
          // done.
          int numFail = 0;
          int numEmpty = 0;
          FutureBlock done = new FutureBlock();
          for (int i = 0; i < n; i++) {
            CopyPlan choice = sw.choice(i);
            if (choice.isFail()) {
              ++numFail;
            } else if (choice.steps.isEmpty()) {
              ++numEmpty;
            } else {
              FutureBlock tryNext = new FutureBlock();
              if (i == n - 1 && numFail == 0 && numEmpty == 0) {
                // If this is the last choice, and we haven't skipped any empty or fail choices,
                // we can skip the test.
              } else {
                // TODO: implement untagged switches
                new TestBlock.IsEq(OpCodeType.INT, switchVal, CodeValue.of(i))
                    .setBranch(false, tryNext)
                    .addTo(codeGen.cb);
              }
              if (codeGen.cb.nextIsReachable()) {
                emit(codeGen, choice, onFail);
                codeGen.cb.branchTo(done);
              }
              codeGen.cb.setNext(tryNext);
              if (!codeGen.cb.nextIsReachable()) {
                break;
              }
            }
          }
          if (numEmpty == 0) {
            // Any remaining cases are fail
            codeGen.cb.branchTo(onFail);
          } else if (codeGen.cb.nextIsReachable() && numFail != 0) {
            // Some but not all of the remaining cases are fail.  We'll test for either the empty
            // choices or the fails, whichever is a smaller set.
            boolean testForFail = (numFail <= numEmpty);
            Predicate<CopyPlan> includeChoice =
                testForFail
                    ? CopyPlan::isFail
                    : choice -> !choice.isFail() && choice.steps.isEmpty();
            Bits choices = Bits.fromPredicate(n - 1, i -> includeChoice.test(sw.choice(i)));
            // TODO: implement untagged switches
            new TestBlock.TagCheck(switchVal, n, choices)
                .setBranch(testForFail, onFail)
                .addTo(codeGen.cb);
          }
          codeGen.cb.mergeNext(done);
        }
      }
    }
  }

  /** An instance of the base class, for copying registers to registers. */
  static final CopyEmitter REGISTER_TO_REGISTER = new CopyEmitter();
}

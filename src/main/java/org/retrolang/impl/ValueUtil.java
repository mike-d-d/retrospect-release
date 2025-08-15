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

import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ValueInfo;
import java.util.Arrays;

/** A static-only class with simple value operations that work equally well with RValues. */
public class ValueUtil {
  private ValueUtil() {}

  /** Returns the same value as {@link Value#numElements}, but as a transient Value. */
  public static Value numElements(TState tstate, Value v) {
    if (v instanceof RValue) {
      CodeGen codeGen = tstate.codeGen();
      v = codeGen.simplify(v);
      if (v instanceof RValue rValue) {
        Template t = rValue.template;
        BaseType baseType = t.baseType();
        if (baseType.isCompositional()) {
          return NumValue.of(baseType.size(), Allocator.UNCOUNTED);
        }
        Register register = codeGen.cb.newRegister(int.class);
        codeGen.emitSet(register, codeGen.vArrayLength((Template.RefVar) t));
        return RValue.fromTemplate(Template.NumVar.INT32.withIndex(register.index));
      }
    }
    return NumValue.of(v.numElements(), Allocator.TRANSIENT);
  }

  /**
   * Returns the element of {@code array} identified by {@code index}, which may be zero-based or
   * one-based.
   */
  @RC.Out
  public static Value element(TState tstate, Value array, Value index, int firstIndex) {
    assert array.baseType().isArray() && (firstIndex == 0 || firstIndex == 1);
    if (!(index instanceof RValue)) {
      return array.element(NumValue.asInt(index) - firstIndex);
    }
    CodeGen codeGen = tstate.codeGen();
    index = codeGen.simplify(index);
    if (!(index instanceof RValue)) {
      return array.element(NumValue.asInt(index) - firstIndex);
    }
    BaseType baseType = array.baseType();
    if (baseType.isCompositional() && baseType.size() == 1) {
      return array.element(0);
    }
    FrameLayout layout = array.layout();
    Template t = RValue.toTemplate(array);
    if (t instanceof Template.RefVar refVar) {
      codeGen.ensureLayout(codeGen.register(refVar), layout);
    }
    // Create an RValue that we'll store the result in.  array is usually a varray, which makes this
    // easy (we can just use the element template); but if it's not we need to construct a template
    // that could hold any of the elements.
    TemplateBuilder elementBuilder;
    if (baseType == Core.VARRAY) {
      elementBuilder = layout.template().toBuilder();
    } else if (!(array instanceof RValue)) {
      // Picking an element from a constant array.
      elementBuilder = Template.EMPTY.addElements(array);
    } else {
      // t is either a Compound or a RefVar with a RecordLayout
      Template.Compound compound = (Template.Compound) (layout == null ? t : layout.template());
      elementBuilder = compound.mergeElementsInto(Template.EMPTY);
    }
    int registerStart = codeGen.cb.numRegisters();
    Template result = elementBuilder.build(codeGen.newAllocator());
    int registerEnd = codeGen.cb.numRegisters();
    CodeValue indexCV = codeGen.register(index);
    if (baseType == Core.VARRAY) {
      Template.RefVar refVar = (Template.RefVar) t;
      Register arrayCV = codeGen.register(refVar);
      if (firstIndex != 0) {
        indexCV =
            codeGen.materialize(
                Op.SUBTRACT_INTS.result(indexCV, CodeValue.of(firstIndex)), int.class);
      }
      CopyPlan plan = CopyPlan.create(layout.template(), result);
      plan = CopyOptimizer.toRegisters(plan, registerStart, registerEnd, result);
      ((VArrayLayout) layout).copyFrom(arrayCV, indexCV).emit(codeGen, plan, codeGen.getEscape());
    } else {
      FutureBlock done = new FutureBlock();
      codeGen.emitSwitch(
          indexCV,
          firstIndex,
          firstIndex + baseType.size() - 1,
          i -> {
            codeGen.emitStore(array.element(i - firstIndex), result, registerStart, registerEnd);
            codeGen.cb.branchTo(done);
          });
      codeGen.cb.setNext(done);
    }
    return RValue.fromTemplate(result);
  }

  /**
   * Returns a copy of {@code array} with the specified element replaced; {@code index} may be
   * zero-based or one-based.
   */
  @RC.Out
  public static Value replaceElement(
      TState tstate, @RC.In Value array, Value index, int firstIndex, @RC.In Value newElement) {
    assert array.baseType().isArray() && (firstIndex == 0 || firstIndex == 1);
    if (!(index instanceof RValue)) {
      return array.replaceElement(tstate, NumValue.asInt(index) - firstIndex, newElement);
    }
    CodeGen codeGen = tstate.codeGen();
    index = codeGen.simplify(index);
    if (!(index instanceof RValue)) {
      return array.replaceElement(tstate, NumValue.asInt(index) - firstIndex, newElement);
    }
    CodeValue indexCV = codeGen.asCodeValue(index);
    FrameLayout layout = array.layout();
    Template t = RValue.toTemplate(array);
    if (t instanceof Template.RefVar refVar) {
      Register frame = codeGen.register(refVar);
      codeGen.ensureLayout(frame, layout);
      if (firstIndex != 0) {
        indexCV =
            codeGen.materialize(
                Op.SUBTRACT_INTS.result(indexCV, CodeValue.of(firstIndex)), int.class);
      }
      CodeValue result = layout.emitReplaceElement(codeGen, frame, indexCV, newElement);
      if (result == frame) {
        return array;
      } else {
        return CodeGen.asValue((Register) result, layout);
      }
    }
    Template newElementT = RValue.toTemplate(newElement);
    BaseType baseType = array.baseType();
    int size = baseType.size();
    if (size == 1) {
      return RValue.fromTemplate(Template.Compound.of(baseType, newElementT));
    }
    Template arrayT = RValue.toTemplate(array);
    CodeBuilder cb = codeGen.cb;
    if (newElementT == Template.EMPTY) {
      assert newElement == Core.TO_BE_SET;
      FutureBlock done = new FutureBlock();
      codeGen.emitSwitch(
          indexCV,
          firstIndex,
          firstIndex + size - 1,
          i -> {
            clear(codeGen, arrayT.element(i - firstIndex));
            cb.branchTo(done);
          });
      cb.setNext(done);
      return array;
    }
    Template[] elements = new Template[size];
    Arrays.setAll(elements, arrayT::element);
    Template[] merged = Arrays.copyOf(elements, size);
    int[] registerStart = new int[size + 1];
    registerStart[0] = cb.numRegisters();
    TemplateBuilder.VarAllocator alloc = codeGen.newAllocator();
    // First allocate new registers to hold each of the elements that might be changed
    ValueInfo info = cb.nextInfoResolved(((Register) indexCV).index);
    for (int i = 0; i < size; i++) {
      if (info.containsValue(firstIndex + i)) {
        merged[i] = elements[i].toBuilder().merge(newElementT.toBuilder()).build(alloc);
      }
      registerStart[i + 1] = cb.numRegisters();
    }
    // Then initialize those registers assuming no change
    for (int i = 0; i < size; i++) {
      if (merged[i] != elements[i]) {
        codeGen.emitStore(elements[i], merged[i], registerStart[i], registerStart[i + 1]);
      }
    }
    // Then switch on the index register and change the corresponding registers
    FutureBlock done = new FutureBlock();
    codeGen.emitSwitch(
        indexCV,
        firstIndex,
        firstIndex + size - 1,
        ii -> {
          int i = ii - firstIndex;
          if (merged[i] != elements[i]) {
            codeGen.emitStore(newElementT, merged[i], registerStart[i], registerStart[i + 1]);
          } else {
            assert newElementT instanceof Template.Constant && merged[i].equals(newElementT);
          }
          cb.branchTo(done);
        });
    cb.setNext(done);
    return RValue.fromTemplate(Template.Compound.of(baseType, merged));
  }

  /** Emits blocks to clear each of the pointer-valued registers in {@code t}. */
  private static void clear(CodeGen codeGen, Template t) {
    // If t contains a Union we may end up emitting multiple instructions that set the same Register
    // to null.  That's OK; the optimizer will clean it up (all but the last will be removed since
    // they're setting a register that's never read).  I don't expect that to have often enough to
    // be worth the (minor) hassle that would be involved in not emitting them in the first place.
    Template.visitVars(t, refVar -> codeGen.emitSet(codeGen.register(refVar), CodeValue.NULL));
  }

  /** Errors unless {@code v} is an array of non-negative integers. */
  public static void checkSizes(TState tstate, Value v) throws Err.BuiltinException {
    Err.INVALID_SIZES.unless(v.isa(Core.ARRAY));
    if (v instanceof RValue) {
      BaseType baseType = v.baseType();
      if (baseType == Core.VARRAY) {
        throw new UnsupportedOperationException();
      }
      int size = baseType.size();
      for (int i = 0; i < size; i++) {
        // verifyInt() throws INVALID_ARGUMENT (rather than INVALID_SIZES), but when we're
        // emitting code they all just escape anyway
        Value e = v.peekElement(i).verifyInt(tstate);
        Err.INVALID_SIZES.unless(Condition.numericLessOrEq(NumValue.ZERO, e));
      }
    } else {
      int size = v.numElements();
      for (int i = 0; i < size; i++) {
        Err.INVALID_SIZES.unless(v.elementAsIntOrMinusOne(i) >= 0);
      }
    }
  }

  /**
   * Returns {@code v} as an integer between or {@code min} and {@code max} (inclusive) or throws an
   * INVALID_ARGUMENT BuiltinException. {@code min} and {@code max} must be integers.
   */
  @RC.Out
  public static Value verifyBoundedInt(TState tstate, Value v, Value min, Value max)
      throws Err.BuiltinException {
    if (v instanceof RValue || min instanceof RValue || max instanceof RValue) {
      v = v.verifyInt(tstate);
      CodeGen codeGen = tstate.codeGen();
      codeGen.escapeUnless(Condition.numericLessOrEq(min, v));
      codeGen.escapeUnless(Condition.numericLessOrEq(v, max));
      return v;
    }
    return NumValue.verifyBoundedInt(v, tstate, NumValue.asInt(min), NumValue.asInt(max));
  }

  /** Returns the sum of two integers; does not check for overflow. */
  @RC.Out
  public static Value addInts(TState tstate, Value v1, Value v2) {
    return IntOp.ADD.apply(tstate, v1, v2);
  }

  /** Returns the difference of two integers; does not check for overflow. */
  @RC.Out
  public static Value subtractInts(TState tstate, Value v1, Value v2) {
    return IntOp.SUBTRACT.apply(tstate, v1, v2);
  }

  /** Returns one more than the difference of two integers; does not check for overflow. */
  @RC.Out
  public static Value rangeSize(TState tstate, Value min, Value max) {
    return IntOp.RANGE_SIZE.apply(tstate, min, max);
  }

  /** Returns one less than the sum of two integers; does not check for overflow. */
  @RC.Out
  public static Value oneBasedOffset(TState tstate, Value v1, Value v2) {
    return IntOp.ONE_BASED_OFFSET.apply(tstate, v1, v2);
  }

  /** A simple binary operation over integers. */
  private enum IntOp {
    ADD {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 + i2;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.ADD_INTS.result(cv1, cv2);
      }
    },
    SUBTRACT {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 - i2;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.SUBTRACT_INTS.result(cv1, cv2);
      }
    },
    RANGE_SIZE {
      @Override
      int applyToInts(int min, int max) {
        return max - min + 1;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue min, CodeValue max) {
        return Op.ADD_INTS.result(Op.SUBTRACT_INTS.result(max, min), CodeValue.ONE);
      }
    },
    ONE_BASED_OFFSET {
      @Override
      int applyToInts(int i1, int i2) {
        return i1 + i2 - 1;
      }

      @Override
      CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2) {
        return Op.SUBTRACT_INTS.result(Op.ADD_INTS.result(cv1, cv2), CodeValue.ONE);
      }
    };

    abstract int applyToInts(int i1, int i2);

    abstract CodeValue applyToCodeValues(CodeValue cv1, CodeValue cv2);

    @RC.Out
    Value apply(TState tstate, Value v1, Value v2) {
      if (v1 instanceof RValue || v2 instanceof RValue) {
        CodeGen codeGen = tstate.codeGen();
        v1 = codeGen.simplify(v1);
        v2 = codeGen.simplify(v2);
        if (v1 instanceof RValue || v2 instanceof RValue) {
          Register register = codeGen.cb.newRegister(int.class);
          CodeValue cv1 = codeGen.asCodeValue(v1);
          CodeValue cv2 = codeGen.asCodeValue(v2);
          codeGen.emitSet(register, applyToCodeValues(cv1, cv2));
          return codeGen.toValue(register);
        }
      }
      int i1 = NumValue.asInt(v1);
      int i2 = NumValue.asInt(v2);
      return NumValue.of(applyToInts(i1, i2), tstate);
    }
  }
}

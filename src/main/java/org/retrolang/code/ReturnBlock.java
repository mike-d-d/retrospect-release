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

package org.retrolang.code;

import com.google.common.collect.ImmutableList;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeBuilder.PrintOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;

/**
 * A Block that emits a return opcode (RETURN, IRETURN, LRETURN, FRETURN, DRETURN, or ARETURN, as
 * appropriate).
 */
public class ReturnBlock extends Block.Terminal {
  private CodeValue returnedValue;

  /**
   * Creates a ReturnBlock. If {@code returnedValue} is non-null, its type must be compatible with
   * the {@code returnType} passed to {@link CodeBuilder#load}; if it is null, {@code returnType}
   * must be void.
   */
  public ReturnBlock(CodeValue returnedValue) {
    this.returnedValue = returnedValue;
  }

  @Override
  public List<CodeValue> inputs() {
    return (returnedValue == null) ? ImmutableList.of() : ImmutableList.of(returnedValue);
  }

  @Override
  void runForwardProp(boolean incremental) {
    if (returnedValue != null) {
      returnedValue = returnedValue.simplify(inlinkRegisterInfosUnresolved());
    }
  }

  @Override
  public SubstitutionOutcome trySubstitute(Register register, CodeValue value) {
    if (returnedValue == null) {
      return SubstitutionOutcome.NO;
    }
    SubstitutionOutcome result = returnedValue.couldSubstitute(register, value);
    if (result == SubstitutionOutcome.YES) {
      returnedValue = returnedValue.substitute(register, value);
    }
    return result;
  }

  @Override
  public @Nullable Block emit(Emitter emitter) {
    if (emitter.returnType == void.class) {
      assert returnedValue == null;
      emitter.mv.visitInsn(Opcodes.RETURN);
    } else {
      returnedValue.push(emitter, emitter.returnType);
      emitter.mv.visitInsn(OpCodeType.forType(emitter.returnType).returnOpcode);
    }
    return null;
  }

  @Override
  public String toString(PrintOptions options) {
    if (returnedValue == null) {
      return "return";
    } else {
      return "return " + returnedValue.toString(options);
    }
  }
}

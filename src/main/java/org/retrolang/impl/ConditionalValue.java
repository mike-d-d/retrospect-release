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

import java.util.function.Function;
import org.retrolang.impl.Condition.ValueSupplier;

/**
 * ConditionalValue are used only during code generation, and only for a few purposes. A
 * ConditionalValue can be used with setResults(), startCall()/saving(), or jump(), but almost
 * nowhere else.
 */
public class ConditionalValue implements Value {

  final Condition condition;
  final ValueSupplier ifTrue;
  final ValueSupplier ifFalse;

  ConditionalValue(Condition condition, ValueSupplier ifTrue, ValueSupplier ifFalse) {
    this.condition = condition;
    this.ifTrue = ifTrue;
    this.ifFalse = ifFalse;
  }

  @Override
  public BaseType baseType() {
    throw new AssertionError();
  }

  /**
   * Converts a ConditionalValue to an RValue, by allocating registers to match the given template
   * and emitting blocks to store this value in them. Escapes if this value does not fit in the
   * template.
   */
  public Value materialize(CodeGen codeGen, Template t) {
    return materialize(codeGen, t.toBuilder()::build);
  }

  /**
   * Converts a ConditionalValue to an RValue, by allocating registers using the given builder and
   * emitting blocks to store this value in them.
   */
  Value materialize(CodeGen codeGen, Function<TemplateBuilder.VarAllocator, Template> builder) {
    int registerStart = codeGen.cb.numRegisters();
    Template dst = builder.apply(codeGen.newAllocator());
    emitStore(codeGen, dst, registerStart, codeGen.cb.numRegisters());
    return RValue.fromTemplate(dst);
  }

  /**
   * Emits blocks to set the registers in {@code dst} (whose indices must be in the range {@code
   * registerStart..registerEnd}) from this ConditionalValue.
   */
  void emitStore(CodeGen codeGen, Template dst, int registerStart, int registerEnd) {
    condition.test(
        () ->
            codeGen
                .escapeOnErr(ifTrue)
                .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd)),
        () ->
            codeGen
                .escapeOnErr(ifFalse)
                .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd)));
  }
}

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

import org.retrolang.code.FutureBlock;
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
   * Emits blocks to set the registers in {@code dst} (whose indices must be in the range {@code
   * registerStart..registerEnd}) from this ConditionalValue. Used to implement {@link
   * CodeGen#emitStore(Value, Template, int, int)} for ConditionalValues.
   */
  void emitStore(CodeGen codeGen, Template dst, int registerStart, int registerEnd) {
    FutureBlock elseBranch = new FutureBlock();
    condition.addTest(codeGen, elseBranch);
    codeGen
        .escapeOnErr(ifTrue)
        .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd));
    FutureBlock done = codeGen.cb.swapNext(elseBranch);
    codeGen
        .escapeOnErr(ifFalse)
        .ifPresent(v -> codeGen.emitStore(v, dst, registerStart, registerEnd));
    codeGen.cb.mergeNext(done);
  }
}

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

import org.retrolang.Vm;

/**
 * Each BaseType with {@code size == 0} has a corresponding {@code Singleton} representing the value
 * of that type.
 */
public final class Singleton extends VmExpr.Constant implements Value, Vm.Singleton {
  final BaseType baseType;
  final Template asTemplate;

  Singleton(BaseType baseType, boolean isToBeSet) {
    assert baseType.isSingleton();
    this.baseType = baseType;
    this.asTemplate = isToBeSet ? Template.EMPTY : Template.Constant.forSingletonConstructor(this);
  }

  @Override
  public Vm.Value asValue() {
    return this;
  }

  @Override
  public VmType asType() {
    return ((BaseType.Named) baseType).asType;
  }

  @Override
  public BaseType baseType() {
    return baseType;
  }

  @Override
  @RC.Out
  public Value removeRange(TState tstate, int keepPrefix, int moveFrom, int moveTo, int moveLen) {
    // Can only be called to add elements to the empty array.
    assert this == Core.EMPTY_ARRAY
        && keepPrefix == 0
        && moveFrom == 0
        && moveTo >= 0
        && moveLen == 0;
    return CompoundValue.of(tstate, Core.FixedArrayType.withSize(moveTo), i -> Core.TO_BE_SET);
  }

  @Override
  public void reserveForChangeOrThrow(TState tstate, int newSize, boolean isShared)
      throws Err.BuiltinException {
    assert this == Core.EMPTY_ARRAY;
    tstate.reserve(null, newSize);
  }

  @Override
  public int hashCode() {
    // Despite our override of equals(), the default hashCode() is still good enough
    // (Frame.hashCode() is responsible for matching our value on empty arrays).
    return super.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    // EMPTY_ARRAY isn't as singular as the other singletons
    return obj == this
        || (this == Core.EMPTY_ARRAY && obj instanceof Frame f && f.numElements() == 0);
  }

  @Override
  public String toString() {
    return baseType.toString();
  }
}

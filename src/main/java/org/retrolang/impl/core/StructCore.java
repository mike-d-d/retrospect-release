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

package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import java.util.Arrays;
import org.retrolang.code.CodeValue;
import org.retrolang.impl.Allocator;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.RefCounted;
import org.retrolang.impl.StringValue;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;

/** Core methods on Structs. */
public class StructCore {
  /**
   * {@code private compound StructIterator is Iterator}
   *
   * <p>Elements are {@code struct}, {@code eKind}, {@code prevIndex}.
   */
  @Core.Private
  static final BaseType.Named STRUCT_ITERATOR =
      Core.newBaseType("StructIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound StructUpdater is Lambda}
   *
   * <p>Elements are {@code struct}, {@code index}.
   */
  @Core.Private
  static final BaseType.Named STRUCT_UPDATER = Core.newBaseType("StructUpdater", 2, Core.LAMBDA);

  private static Value keyToIndex(TState tstate, StructType structType, Value key) {
    if (!(key instanceof RValue)) {
      return NumValue.of(structType.index(key), Allocator.TRANSIENT);
    } else {
      CodeGen codeGen = tstate.codeGen();
      return codeGen.intToValue(
          StructType.INDEX_OP.result(CodeValue.of(structType), codeGen.asCodeValue(key)));
    }
  }

  private static Value indexToKey(TState tstate, StructType structType, Value index) {
    if (!(index instanceof RValue)) {
      return structType.key(NumValue.asInt(index));
    } else {
      CodeGen codeGen = tstate.codeGen();
      CodeValue result =
          StructType.KEY_OP.result(CodeValue.of(structType), codeGen.asCodeValue(index));
      result = codeGen.materialize(result, StringValue.class);
      return codeGen.toValue(result, Core.STRING);
    }
  }

  /** {@code method at(Struct struct, String key) = ...} */
  @Core.Method("at(Struct|StructKeys, String)")
  static Value atStruct(TState tstate, Value struct, Value key) throws BuiltinException {
    BaseType baseType = struct.baseType();
    StructType structType = StructType.from(baseType);
    Value index = keyToIndex(tstate, structType, key);
    Condition indexValid = Condition.numericLessOrEq(NumValue.ZERO, index);
    if (structType == baseType) {
      Err.INVALID_ARGUMENT.unless(indexValid);
      return ValueUtil.element(tstate, struct, index, 0);
    } else {
      // Must be a StructKeys
      return indexValid.choose(() -> addRef(key), () -> Core.ABSENT);
    }
  }

  /**
   * <pre>
   * method iterator(Struct struct, EnumerationKind eKind) {
   *   return StructIterator_({struct, eKind, prevIndex: 0}})
   * }
   * </pre>
   */
  @Core.Method("iterator(Struct|StructKeys, EnumerationKind)")
  static Value iteratorStruct(TState tstate, Value struct, @RC.Singleton Value eKind) {
    return tstate.compound(STRUCT_ITERATOR, struct, eKind, NumValue.ZERO);
  }

  /**
   * <pre>
   * method next(StructIterator it=) {
   *   index = it_.prevIndex
   *   for sequential index {
   *     if index &gt;= size(it_.struct_) {
   *       break { return Absent }
   *     }
   *     index += 1
   *     v = it_.struct_[index]
   *     if v is Absent and it_.eKind is not EnumerateAllKeys {
   *       continue
   *     }
   *     it_.prevIndex = index
   *     break {
   *       return it_.eKind is EnumerateValues ? v : [it_.struct_.keys[index], v]
   *     }
   *   }
   * </pre>
   */
  static class NextStructIterator extends BuiltinMethod {
    @Core.Method("next(StructIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      tstate.jump("startLoop", it.element(2), it);
    }

    @LoopContinuation
    static void startLoop(TState tstate, Value index, @RC.In Value it) {
      Value struct = it.peekElement(0);
      BaseType baseType = struct.baseType();
      StructType structType = StructType.from(baseType);
      Condition.numericLessThan(index, NumValue.of(structType.size(), Allocator.TRANSIENT))
          .test(
              () -> {
                Value newIndex = ValueUtil.addInts(tstate, index, NumValue.ONE);
                Value v;
                if (structType == baseType) {
                  v = ValueUtil.element(tstate, struct, index, 0);
                } else {
                  v = indexToKey(tstate, structType, index);
                }
                Value eKind = it.peekElement(1);
                v.is(Core.ABSENT)
                    .and(eKind.is(LoopCore.ENUMERATE_ALL_KEYS).not())
                    .test(
                        () -> tstate.jump("startLoop", newIndex, it),
                        () -> {
                          Value newIt = it.replaceElement(tstate, 2, newIndex);
                          Value result =
                              eKind
                                  .is(LoopCore.ENUMERATE_VALUES)
                                  .choose(
                                      () -> v,
                                      () -> {
                                        Value key =
                                            (structType == baseType)
                                                ? indexToKey(tstate, structType, index)
                                                : v;
                                        return tstate.arrayValue(key, v);
                                      });
                          tstate.setResults(result, newIt);
                        });
              },
              () -> tstate.setResults(Core.ABSENT, it));
    }
  }

  /** {@code method replaceElement(Struct struct, String key, v) = ...} */
  @Core.Method("replaceElement(Struct, String, _)")
  static Value replaceElementStruct(TState tstate, @RC.In Value struct, Value key, @RC.In Value v)
      throws BuiltinException {
    StructType structType = (StructType) struct.baseType();
    Value index = keyToIndex(tstate, structType, key);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, index));
    return ValueUtil.replaceElement(tstate, struct, index, 0, v);
  }

  /** {@code method startUpdate(Struct struct, String key) = ...} */
  @Core.Method("startUpdate(Struct, String)")
  static void startUpdateStruct(TState tstate, @RC.In Value struct, Value key)
      throws BuiltinException {
    StructType structType = (StructType) struct.baseType();
    Value index = keyToIndex(tstate, structType, key);
    Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, index));
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    Value element = ValueUtil.element(tstate, struct, index, 0);
    struct = ValueUtil.replaceElement(tstate, struct, index, 0, Core.TO_BE_SET);
    tstate.setResults(element, tstate.compound(STRUCT_UPDATER, struct, index));
  }

  /** {@code method at(StructUpdater updater, newElement) = ...} */
  @Core.Method("at(StructUpdater, _)")
  static Value atStructUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    Value struct = updater.element(0);
    Value index = updater.peekElement(1);
    tstate.dropValue(updater);
    return ValueUtil.replaceElement(tstate, struct, index, 0, v);
  }

  /** {@code method keys(Struct updater) = ...} */
  @Core.Method("keys(Struct|StructKeys)")
  static Value keysStruct(Value struct) {
    return StructType.from(struct.baseType()).keySet;
  }

  /** {@code method new(StructKeys keys, initialValue) = ...} */
  @Core.Method("new(StructKeys, _)")
  static Value newStruct(TState tstate, Value keys, Value initial) {
    StructType structType = StructType.from(keys.baseType());
    int size = structType.size();
    if (size == 0) {
      return structType.asValue();
    }
    Object[] elements = tstate.allocObjectArray(size);
    Arrays.fill(elements, 0, size, initial);
    RefCounted.addRef(initial, size);
    return tstate.asCompoundValue(structType, elements);
  }

  private StructCore() {}
}

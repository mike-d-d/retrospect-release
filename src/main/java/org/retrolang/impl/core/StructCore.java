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

import org.retrolang.impl.BaseType;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;

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
   * <p>Elements are {@code struct}, {@code values}, {@code index}.
   */
  @Core.Private
  static final BaseType.Named STRUCT_UPDATER = Core.newBaseType("StructUpdater", 3, Core.LAMBDA);

  /**
   *
   *
   * <pre>
   * method at(Struct struct, String key) = ...
   * </pre>
   */
  @Core.Method("at(Struct, String)")
  static Value atStruct(Value struct, Value key) {
    int index = index(struct, key);
    if (index >= 0) {
      Value values = struct.peekElement(1);
      return values.element(index);
    } else {
      return Core.ABSENT;
    }
  }

  private static int index(Value struct, Value key) {
    Value keys = struct.peekElement(0);
    int nKeys = keys.numElements();
    // This ought to at least be a binary search
    for (int i = 0; i < nKeys; i++) {
      if (keys.peekElement(i).equals(key)) {
        return i;
      }
    }
    return -1;
  }

  /**
   *
   *
   * <pre>
   * method iterator(Struct struct, EnumerationKind eKind) {
   *   if eKind is EnumerateValues {
   *     return iterator(struct_.values, EnumerateValues)
   *   } else {
   *     return StructIterator_({struct, eKind, prevIndex: 0}})
   *   }
   * }
   * </pre>
   */
  @Core.Method("iterator(Struct, EnumerationKind)")
  static Value iteratorStruct(TState tstate, Value struct, @RC.Singleton Value eKind) {
    return eKind
        .is(LoopCore.ENUMERATE_VALUES)
        .choose(
            () -> ArrayCore.iteratorArray(tstate, struct.element(1), eKind),
            () -> tstate.compound(STRUCT_ITERATOR, struct, eKind, NumValue.ZERO));
  }

  /**
   *
   *
   * <pre>
   * method next(StructIterator it=) {
   *   index = it_.prevIndex
   *   for sequential index {
   *     if index &gt;= size(it_.struct_.keys) {
   *       break { return Absent }
   *     }
   *     index += 1
   *     v = it_.struct_.values[index]
   *     if v is Absent and it_.eKind is not EnumerateAllKeys {
   *       continue
   *     }
   *     k = it_.struct_.keys[index]
   *     it_.prevIndex = index
   *     break {
   *       return [k, v]
   *     }
   *   }
   * </pre>
   */
  @Core.Method("next(StructIterator)")
  static void nextStructIterator(TState tstate, @RC.In Value it) {
    Value struct = it.peekElement(0);
    Value keys = struct.peekElement(0);
    Value values = struct.peekElement(1);
    @RC.Singleton Value eKind = it.peekElement(1);
    int index = it.elementAsInt(2);
    int size = keys.numElements();
    assert values.numElements() == size;
    while (index < size) {
      Value v = values.element(index);
      if (v == Core.ABSENT && eKind != LoopCore.ENUMERATE_ALL_KEYS) {
        index++;
        continue;
      }
      Value k = keys.element(index++);
      it = it.replaceElement(tstate, 2, NumValue.of(index, tstate));
      tstate.setResults(tstate.arrayValue(k, v), it);
      return;
    }
    tstate.setResults(Core.ABSENT, it);
  }

  /**
   *
   *
   * <pre>
   * method replaceElement(Struct struct, String key, v) = ...
   * </pre>
   */
  @Core.Method("replaceElement(Struct, String, _)")
  static Value replaceElementStruct(TState tstate, @RC.In Value struct, Value key, @RC.In Value v)
      throws BuiltinException {
    int index = index(struct, key);
    Err.INVALID_ARGUMENT.unless(index >= 0);
    Value values = struct.element(1);
    struct = struct.replaceElement(tstate, 1, Core.TO_BE_SET);
    values = values.replaceElement(tstate, index, v);
    struct = struct.replaceElement(tstate, 1, values);
    return struct;
  }

  /**
   *
   *
   * <pre>
   * method startUpdate(Struct struct, String key) = ...
   * </pre>
   */
  @Core.Method("startUpdate(Struct, String)")
  static void startUpdateStruct(TState tstate, @RC.In Value struct, Value key)
      throws BuiltinException {
    int index = index(struct, key);
    Err.INVALID_ARGUMENT.unless(index >= 0);
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    Value values = struct.element(1);
    struct = struct.replaceElement(tstate, 1, Core.TO_BE_SET);
    Value element = values.element(index);
    values = values.replaceElement(tstate, index, Core.TO_BE_SET);
    tstate.setResults(
        element, tstate.compound(STRUCT_UPDATER, struct, values, NumValue.of(index, tstate)));
  }

  /**
   *
   *
   * <pre>
   * method at(StructUpdater updater, newElement) = ...
   * </pre>
   */
  @Core.Method("at(StructUpdater, _)")
  static Value atStructUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    Value struct = updater.element(0);
    Value values = updater.element(1);
    int index = updater.elementAsInt(2);
    tstate.dropValue(updater);
    values = values.replaceElement(tstate, index, v);
    return struct.replaceElement(tstate, 1, values);
  }

  private StructCore() {}
}

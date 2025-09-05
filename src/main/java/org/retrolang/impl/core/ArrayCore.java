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

import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
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
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;

/** Core methods on Arrays. */
public final class ArrayCore {

  /**
   * {@code private compound ArrayIterator is Iterator}
   *
   * <p>Elements are {@code array}, {@code eKind}, {@code prevIndex}.
   */
  @Core.Private
  static final BaseType.Named ARRAY_ITERATOR =
      Core.newBaseType("ArrayIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound ArrayUpdater is Lambda}
   *
   * <p>Elements are {@code array}, {@code index}.
   */
  @Core.Private
  static final BaseType.Named ARRAY_UPDATER = Core.newBaseType("ArrayUpdater", 2, Core.LAMBDA);

  /** {@code method element(Array array, [i]) = ...} */
  @Core.Method("element(Array, Array)")
  static void elementArray(TState tstate, Value array, Value index) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(index.isArrayOfLength(1));
    if (!(array instanceof RValue || index instanceof RValue)) {
      int i = index.elementAsIntOrMinusOne(0) - 1;
      Err.INVALID_ARGUMENT.unless(isValidIndex(array, i));
      tstate.setResult(array.element(i));
    } else {
      index = validateIndex(tstate, array, index.peekElement(0));
      tstate.setResult(ValueUtil.element(tstate, array, index, 1));
    }
  }

  static Value validateIndex(TState tstate, Value array, Value index) throws BuiltinException {
    return ValueUtil.verifyBoundedInt(
        tstate, index, NumValue.ONE, ValueUtil.numElements(tstate, array));
  }

  /**
   * <pre>
   * method iterate(Array array, EnumerationKind eKind) =
   *     ArrayIterator_({array, eKind, prevIndex: 0})
   * </pre>
   */
  @Core.Method("iterator(Array, EnumerationKind)")
  static Value iteratorArray(TState tstate, @RC.In Value array, @RC.Singleton Value eKind) {
    return tstate.compound(ARRAY_ITERATOR, array, eKind, NumValue.ZERO);
  }

  /**
   * <pre>
   * method next(ArrayIterator it=) {
   *   index = it_.prevIndex
   *   for sequential index {
   *     if index &gt;= size(it_.array) {
   *       break { return Absent }
   *     }
   *     index += 1
   *     v = it_.array[index]
   *     if v is Absent and it_.eKind is not EnumerateAllKeys {
   *       continue
   *     }
   *     it_.prevIndex = index
   *     break { return it_.eKind is EnumerateValues ? v : [[index], v] }
   *   }
   * }
   * </pre>
   */
  static class NextArrayIterator extends BuiltinMethod {
    @Core.Method("next(ArrayIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      tstate.jump("startLoop", it.element(2), it);
    }

    @LoopContinuation
    static void startLoop(TState tstate, Value index, @RC.In Value it) {
      Value array = it.peekElement(0);
      Value size = ValueUtil.numElements(tstate, array);
      Condition.numericLessThan(index, size)
          .test(
              () -> {
                Value newIndex = ValueUtil.addInts(tstate, index, NumValue.ONE);
                Value v = ValueUtil.element(tstate, array, index, 0);
                Value eKind = it.peekElement(1);
                v.is(Core.ABSENT)
                    .and(eKind.is(LoopCore.ENUMERATE_ALL_KEYS).not())
                    .test(
                        () -> tstate.jump("startLoop", newIndex, it),
                        () -> {
                          Value result = nextResult(tstate, eKind, newIndex, v);
                          Value newIt = it.replaceElement(tstate, 2, newIndex);
                          tstate.setResults(result, newIt);
                        });
              },
              () -> tstate.setResults(Core.ABSENT, it));
    }

    /**
     * Returns {@code (eKind is EnumerateValues) ? v : [[index], v]}. Written as a separate function
     * just to stop the code formatter from losing it.
     */
    @RC.Out
    private static Value nextResult(TState tstate, Value eKind, Value index, @RC.In Value v) {
      return eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .choose(() -> v, () -> tstate.arrayValue(tstate.arrayValue(addRef(index)), v));
    }
  }

  /** {@code method size(Array x) = ...} */
  @Core.Method("size(Array)")
  static Value sizeArray(TState tstate, Value x) {
    return ValueUtil.numElements(tstate, x).makeStorable(tstate);
  }

  /** {@code method sizes(Array x) = [size(x)]} */
  @Core.Method("sizes(Array)")
  static Value sizesArray(TState tstate, Value x) {
    return tstate.arrayValue(sizeArray(tstate, x));
  }

  /**
   * <pre>
   * method replaceElement(Array array, [index], value) {
   *   if index is Integer {
   *     ... replace one element of array with value
   *   } else {
   *     assert index is Range and value is Matrix
   *     [vSize] = sizes(value)
   *     min = min(index) is None ? 1 : min(index)
   *     max = max(index) is None ? size(array) : max(index)
   *     ... replace elements min..max of array with elements of value
   *   }
   * }
   * </pre>
   *
   * {@code key} must be a single-element array, containing either an Integer or a Range; if a
   * Range, {@code value} must be a one-dimensional Matrix.
   */
  static class ReplaceElement extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("replaceElement(Array, Array, _)")
    static void begin(TState tstate, @RC.In Value array, Value key, @RC.In Value value)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
      Value k = key.peekElement(0);
      k.isa(Core.RANGE)
          .testExcept(
              () -> {
                Err.INVALID_ARGUMENT.unless(value.isa(Core.MATRIX));
                tstate.startCall(sizes, addRef(value)).saving(array, k.makeStorable(tstate), value);
              },
              () -> {
                if (tstate.hasCodeGen()) {
                  CodeGen codeGen = tstate.codeGen();
                  Value index = validateIndex(tstate, array, k);
                  // TODO: insert check for OUT_OF_MEMORY
                  codeGen.setResults(ValueUtil.replaceElement(tstate, array, index, 1, value));
                } else {
                  int i = NumValue.asIntOrMinusOne(k) - 1;
                  Err.INVALID_ARGUMENT.unless(isValidIndex(array, i));
                  if (!RefCounted.isNotShared(array)) {
                    tstate.reserve(array.layout(), array.numElements());
                  }
                  tstate.setResult(array.replaceElement(tstate, i, value));
                }
              });
    }

    @Continuation
    static void afterSizes(
        TState tstate,
        Value valueSizes,
        @Saved @RC.In Value array,
        Value range,
        Value value,
        @Fn("enumerate:4") Caller enumerate)
        throws BuiltinException {
      Err.INVALID_SIZES.unless(valueSizes.isArrayOfLength(1));
      int vSize = valueSizes.elementAsIntOrMinusOne(0);
      Err.INVALID_SIZES.unless(vSize >= 0);
      int size = array.numElements();
      Value min = range.peekElement(0);
      Value max = range.peekElement(1);
      int keepPrefix = (min == Core.NONE) ? 0 : NumValue.asInt(min) - 1;
      int moveFrom = (max == Core.NONE) ? size : NumValue.asInt(max);
      Err.INVALID_ARGUMENT.unless(keepPrefix >= 0 && moveFrom <= size && moveFrom >= keepPrefix);
      if (keepPrefix == moveFrom && vSize == 0) {
        tstate.setResult(array);
        return;
      } else if (keepPrefix == 0 && moveFrom == size && vSize == 0) {
        tstate.dropValue(array);
        tstate.setResult(Core.EMPTY_ARRAY);
        return;
      }
      array = array.removeRange(tstate, keepPrefix, moveFrom, keepPrefix + vSize, size - moveFrom);
      Value loop = tstate.compound(SaveCore.SAVE_WITH_OFFSET, NumValue.of(keepPrefix, tstate));
      // Wrapping SaveWithOffset (a sequential loop) in SaverLoop makes it parallelizable (since
      // we don't care in which order the elements are computed, as long as each is saved in the
      // corresponding element of the result).
      loop = tstate.compound(SaveCore.SAVER_LOOP, loop);
      // Enumerate the elements of value and store each into the appropriate place in the copy
      // (EnumerateAllKeys because even Absents need to be copied -- we don't want to leave a
      // ToBeSet in lhs).
      tstate.startCall(enumerate, addRef(value), LoopCore.ENUMERATE_ALL_KEYS, loop, array);
    }
  }

  /** Returns true if {@code index} is a valid zero-based index for {@code array}. */
  static boolean isValidIndex(Value array, long index) {
    return index >= 0 && index < array.numElements();
  }

  /**
   * <pre>
   * method startUpdate(Array array=, Array key) { ... }
   * </pre>
   */
  @Core.Method("startUpdate(Array, Array)")
  static void startUpdate(TState tstate, @RC.In Value array, Value key) throws BuiltinException {
    Err.INVALID_SIZES.unless(key.isArrayOfLength(1));
    if (!(array instanceof RValue || key instanceof RValue)) {
      int i = key.elementAsIntOrMinusOne(0) - 1;
      Err.INVALID_ARGUMENT.unless(isValidIndex(array, i));
      Value element = array.element(i);
      array = array.replaceElement(tstate, i, Core.TO_BE_SET);
      tstate.setResults(element, tstate.compound(ARRAY_UPDATER, array, NumValue.of(i, tstate)));
    } else {
      key = validateIndex(tstate, array, key.peekElement(0));
      CodeGen codeGen = tstate.codeGen();
      CodeValue zeroBasedKey =
          codeGen.materialize(
              Op.SUBTRACT_INTS.result(codeGen.asCodeValue(key), CodeValue.ONE), int.class);
      key = codeGen.toValue(zeroBasedKey);
      Value element = ValueUtil.element(tstate, array, key, 0);
      array = ValueUtil.replaceElement(tstate, array, key, 0, Core.TO_BE_SET);
      tstate.setResults(element, tstate.compound(ARRAY_UPDATER, array, key));
    }
  }

  /**
   * <pre>
   * method at(ArrayUpdater updater, newElement) =
   *     replaceElement(updater_.array, [updater_.index], newElement)
   * </pre>
   */
  @Core.Method("at(ArrayUpdater, _)")
  static Value atArrayUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    if (!(updater instanceof RValue || v instanceof RValue)) {
      Value array = updater.element(0);
      int index = updater.elementAsInt(1);
      tstate.dropValue(updater);
      return array.replaceElement(tstate, index, v);
    } else {
      Value array = updater.element(0);
      Value index = updater.element(1);
      return ValueUtil.replaceElement(tstate, array, index, 0, v);
    }
  }

  private ArrayCore() {}
}

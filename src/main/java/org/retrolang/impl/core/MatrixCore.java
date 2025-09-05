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
import java.util.stream.IntStream;
import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.FrameLayout;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.RefCounted;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.ValueUtil;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods for Matrix. */
public final class MatrixCore {
  /**
   * {@code function matrix(sizes)}
   *
   * <p>Returns a Matrix with the given sizes, where each element's value is the same as its key.
   */
  @Core.Public static final VmFunctionBuilder matrix1 = VmFunctionBuilder.create("matrix", 1);

  /**
   * {@code function matrix(sizes, elements)}
   *
   * <p>Returns a Matrix with the given sizes and elements; {@code elements} must be a Matrix with
   * the appropriate number of elements.
   */
  @Core.Public static final VmFunctionBuilder matrix2 = VmFunctionBuilder.create("matrix", 2);

  /** {@code function reverse(vector)} */
  @Core.Public
  static final VmFunctionBuilder reverse1 = VmFunctionBuilder.create("reverse", 1).isOpen();

  /**
   * {@code open function element(matrix, key)}
   *
   * <p>Returns the specified element of a matrix.
   */
  @Core.Public
  static final VmFunctionBuilder element = VmFunctionBuilder.create("element", 2).isOpen();

  /**
   * {@code open function subMatrix(matrix, key)}
   *
   * <p>Returns a matrix containing only the specified elements.
   */
  @Core.Public
  static final VmFunctionBuilder subMatrix = VmFunctionBuilder.create("subMatrix", 2).isOpen();

  /** {@code open function newMatrix(sizes)} */
  @Core.Public static final VmFunctionBuilder newMatrix1 = VmFunctionBuilder.create("newMatrix", 1);

  /** {@code open function newMatrix(sizes, initialValue)} */
  @Core.Public static final VmFunctionBuilder newMatrix2 = VmFunctionBuilder.create("newMatrix", 2);

  /** {@code function concat(vector, vector)} */
  @Core.Public
  static final VmFunctionBuilder concat = VmFunctionBuilder.create("concat", 2).isOpen();

  /** {@code procedure concatUpdate(vector=, vector)} */
  @Core.Public
  static final VmFunctionBuilder concatUpdate =
      VmFunctionBuilder.create("concatUpdate", 2).isOpen().hasInoutArg(0).hasNoResult();

  @Core.Private
  static final VmFunctionBuilder joinAndPipe = VmFunctionBuilder.create("joinAndPipe", 3).isOpen();

  /**
   * {@code private compound SubMatrix is Matrix}
   *
   * <p>Elements are {@code matrix}, {@code baseKey}, {@code axes}, {@code sizes}. {@code baseKey},
   * {@code axes} and {@code sizes} are arrays of ints.
   *
   * <p>{@code baseKey} is the key in {@code matrix} of the first element of the submatrix, i.e. the
   * element with key {@code [1, 1, ..., 1]} in the submatrix.
   *
   * <p>{@code axes} contains strictly increasing zero-based indices into keys of {@code matrix},
   * identifying the dimensions that have a corresponding dimension in the submatrix. The size of
   * {@code axes} is less than or equal to the number of dimensions of {@code matrix}.
   *
   * <p>{@code sizes} is the dimensions of the submatrix; it has the same length as {@code axes},
   * and all elements of {@code sizes} are greater than zero (instances of SubMatrix cannot be
   * empty).
   *
   * <p>For example, if {@code m} is a 3x4 matrix, then
   *
   * <ul>
   *   <li>{@code SubMatrix_(m, [3, 1], [1], [4])} is a 4-element vector containing the third row of
   *       {@code m} (it starts at [3, 1], extends only in the second dimension, and contains 4
   *       elements); and
   *   <li>{@code SubMatrix_(m, [1, 1], [0, 1], [2, 4])} is a 2x4 matrix containing the other two
   *       rows of {@code m}).
   * </ul>
   */
  @Core.Private
  static final BaseType.Named SUB_MATRIX = Core.newBaseType("SubMatrix", 4, Core.MATRIX);

  /**
   * {@code private compound BaseMatrix is Matrix}
   *
   * <p>Element is array of sizes.
   *
   * <p>A BaseMatrix is returned by {@code matrix(sizes)}; its values are the same as its keys.
   */
  @Core.Private
  static final BaseType.Named BASE_MATRIX = Core.newBaseType("BaseMatrix", 1, Core.MATRIX);

  /**
   * {@code private compound BaseIterator is Iterator}
   *
   * <p>Elements are {@code eKind}, {@code prev}, {@code sizes}.
   */
  @Core.Private
  static final BaseType.Named BASE_ITERATOR =
      Core.newBaseType("BaseIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound Reshaped is Matrix}
   *
   * <p>Elements are {@code sizes}, {@code elements}.
   */
  @Core.Private static final BaseType.Named RESHAPED = Core.newBaseType("Reshaped", 2, Core.MATRIX);

  /**
   * {@code private compound ReshapedArray is Matrix}
   *
   * <p>Elements are {@code sizes}, {@code elements}.
   *
   * <p>A variant of Reshaped used when {@code elements} is an Array.
   */
  @Core.Private
  static final BaseType.Named RESHAPED_ARRAY = Core.newBaseType("ReshapedArray", 2, Core.MATRIX);

  /**
   * {@code private compound ReshapedIterator is Iterator}
   *
   * <p>Elements are {@code it}, {@code inSizes}, {@code outSizes}.
   */
  @Core.Private
  static final BaseType.Named RESHAPED_ITERATOR =
      Core.newBaseType("ReshapedIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound ConcatVector is Matrix}
   *
   * <p>Elements are {@code size}, {@code v1}, {@code v2}.
   */
  @Core.Private
  static final BaseType.Named CONCAT_VECTOR = Core.newBaseType("ConcatVector", 3, Core.MATRIX);

  /**
   * {@code private compound ConcatIterator is Iterator}
   *
   * <p>Elements are {@code it1}, {@code it2}, {@code size1}.
   *
   * <p>If {@code it1} is not None, we are still iterating through the first vector. If {@code
   * size1} is None, {@code eKind} was ENUMERATE_VALUES; otherwise {@code size1} is the size of the
   * first vector, and will be added to the keys when enumerating {@code it2}.
   */
  @Core.Private
  static final BaseType.Named CONCAT_ITERATOR =
      Core.newBaseType("ConcatIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound MatrixUpdater is Lambda}
   *
   * <p>Elements are {@code reshaped}, {@code elements}, {@code index}.
   */
  @Core.Private
  static final BaseType.Named MATRIX_UPDATER = Core.newBaseType("MatrixUpdater", 3, Core.LAMBDA);

  /**
   * {@code private compound BinaryUpdateWithScalar is Loop}
   *
   * <p>Elements are {@code lambda} and {@code rhs}. The state is a collection, and inputs are
   * expected to be keys for that collection. {@code nextState()} updates the specified element of
   * the collection by calling {@code binaryUpdate(element=, lambda, rhs)}.
   */
  @Core.Private
  public static final BaseType.Named BINARY_UPDATE_WITH_SCALAR =
      Core.newBaseType("BinaryUpdateWithScalar", 2, Core.LOOP);

  /**
   * {@code private compound BinaryUpdateWithCollection is Loop}
   *
   * <p>Element is a Lambda. The state is a collection, and inputs are expected to be {@code [key,
   * rhsElement]} pairs. {@code nextState()} updates the specified element of the collection by
   * calling {@code binaryUpdate(element=, lambda, rhsElement)}.
   */
  @Core.Private
  public static final BaseType.Named BINARY_UPDATE_WITH_COLLECTION =
      Core.newBaseType("BinaryUpdateWithCollection", 1, Core.LOOP);

  /**
   * <pre>
   * method size(Matrix m) default {
   *   sizes = sizes(m)
   *   assert sizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   return sizes | product
   * }
   * </pre>
   */
  static class SizeMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("size(Matrix) default")
    static void begin(TState tstate, @RC.In Value m) {
      tstate.startCall(sizes, m);
    }

    @Continuation
    static Value afterSizes(TState tstate, Value sizes) throws BuiltinException {
      return sizeFromSizes(tstate, sizes, Err.INVALID_SIZES);
    }
  }

  /**
   * If {@code sizes} is an Array of non-negative integers and their product is an int, returns it;
   * otherwise throws the specified error.
   */
  @RC.Out
  private static Value sizeFromSizes(TState tstate, Value sizes, Err err) throws BuiltinException {
    err.unless(sizes.isa(Core.ARRAY));
    int nDims = sizes.numElements();
    // If iProduct is negative, we overflowed and are using dProduct
    int result = 1;
    for (int i = 0; i < nDims; i++) {
      int si = sizes.elementAsIntOrMinusOne(i);
      err.unless(si >= 0);
      try {
        result = Math.multiplyExact(result, si);
      } catch (ArithmeticException e) {
        throw err.asException();
      }
    }
    return NumValue.of(result, tstate);
  }

  /**
   * <pre>
   * method matrix(Array sizes) {
   *   assert sizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   return BaseMatrix_(sizes)
   * }
   * </pre>
   */
  @Core.Method("matrix(Array)")
  static Value matrix1(TState tstate, @RC.In Value sizes) throws BuiltinException {
    int nDims = sizes.numElements();
    // sizes may contain NumValue.Ds, as long as they are actually integers.
    // For simplicity we only construct BaseMatrix values with sizes that are NumValue.I, so if
    // there were any Ds here we need to notice and construct a new copy without them.
    boolean allInt = true;
    for (int i = 0; i < nDims; i++) {
      Value s = sizes.peekElement(i);
      Err.INVALID_ARGUMENT.unless(NumValue.isNonNegativeInt(s));
      if (!(s instanceof NumValue.I)) {
        allInt = false;
      }
    }
    if (!allInt) {
      Object[] newSizes = tstate.allocObjectArray(nDims);
      for (int i = 0; i < nDims; i++) {
        newSizes[i] = NumValue.asInt(sizes.peekElement(i), tstate);
      }
      tstate.dropValue(sizes);
      sizes = tstate.asArrayValue(newSizes, nDims);
    }
    return tstate.compound(BASE_MATRIX, sizes);
  }

  /**
   * <pre>
   * method matrix(Array newSizes, Matrix base) {
   *   assert newSizes | -&gt; # is Integer and # &gt;= 0 | allTrue
   *   if base is Reshaped {
   *     base = base_.elements
   *   }
   *   baseSizes = sizes(base)
   *   assert baseSizes is Array and baseSizes | -&gt; (# is Integer and # &gt;= 0) | allTrue
   *   if newSizes == baseSizes {
   *     return base
   *   }
   *   assert (newSizes | product) == (baseSizes | product)
   *   return base is Array ? ReshapedArray_({sizes: newSizes, elements: base})
   *                        : Reshaped_({sizes: newSizes, elements: base})
   * }
   * </pre>
   */
  static class Matrix2 extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("matrix(Array, Matrix)")
    static void begin(TState tstate, @RC.In Value newSizes, @RC.In Value base)
        throws BuiltinException {
      Value newSize = sizeFromSizes(tstate, newSizes, Err.INVALID_ARGUMENT);
      base.isa(RESHAPED)
          .test(
              () -> {
                // There's no reason to nest reshapes.
                Value inner = base.element(1);
                tstate.dropValue(base);
                tstate.startCall(sizes, addRef(inner)).saving(newSize, newSizes, inner);
              },
              () -> tstate.startCall(sizes, addRef(base)).saving(newSize, newSizes, base));
    }

    @Continuation
    static Value afterSizes(
        TState tstate,
        Value baseSizes,
        @Saved Value newSize,
        @RC.In Value newSizes,
        @RC.In Value base)
        throws BuiltinException {
      Value baseSize = sizeFromSizes(tstate, baseSizes, Err.INVALID_SIZES);
      boolean sizeMatches = NumValue.equals(baseSize, NumValue.asInt(newSize));
      // Drop the reference that sizeFromSizes() added to its result before we potentially
      // throw an exception.
      tstate.dropValue(baseSize);
      // sizeMatches is true if the reshaped Matrix has the right number of elements
      Err.INVALID_ARGUMENT.unless(sizeMatches);
      int nDims = newSizes.numElements();
      if (baseSizes.numElements() == nDims
          && IntStream.range(0, nDims)
              .allMatch(i -> baseSizes.elementAsInt(i) == newSizes.elementAsInt(i))) {
        // Reshape to the same sizes is a no-op.
        tstate.dropValue(newSizes);
        return base;
      }
      return base.isa(Core.ARRAY)
          .choose(
              () -> tstate.compound(RESHAPED_ARRAY, newSizes, base),
              () -> tstate.compound(RESHAPED, newSizes, base));
    }
  }

  /**
   * <pre>
   * method at(Matrix m, Array key) =
   *   (key^ is Number | allTrue) ? element(m, Key) : subMatrix(m, key)
   * </pre>
   *
   * If {@code key} contains only numbers, calls {@code element()} to get the result; otherwise
   * calls {@code subMatrix()}.
   */
  @Core.Method("at(Matrix, Array)")
  static void at(
      TState tstate,
      @RC.In Value m,
      @RC.In Value key,
      @Fn("element:2") Caller element,
      @Fn("subMatrix:2") Caller subMatrix) {
    allNumbers(key)
        .test(() -> tstate.startCall(element, m, key), () -> tstate.startCall(subMatrix, m, key));
  }

  private static Condition allNumbers(Value key) {
    int n = key.numElements();
    for (int i = 0; i < n; i++) {
      if (key.peekElement(i).baseType() != Core.NUMBER) {
        return Condition.FALSE;
      }
    }
    return Condition.TRUE;
  }

  /**
   * <pre>
   * method subMatrix(Matrix m, Array key) default {
   *   sizes = sizes(m)
   *   assert sizes is Array and (sizes | -&gt; # is Integer and # &gt;= 0 | allTrue)
   *   assert size(key) == size(sizes)
   *   for [i]: key_i in key sequential {
   *     if key_i is Range {
   *       size = sizes[i]
   *       min = min(key_i)
   *       if min is None {
   *         min = 1
   *       }
   *       max = max(key_i)
   *       if max is None {
   *         max = size
   *       }
   *       newSize = max - (min - 1)
   *       assert min &gt;= 1 and max &lt;= size and newSize &gt;= 0
   *       axes &lt;&lt; i
   *       newSizes &lt;&lt; newSize
   *       baseKey &lt;&lt; min
   *       isSubset &lt;&lt; newSize != size
   *       isEmpty &lt;&lt; newSize == 0
   *     } else {
   *       assert key_i is Integer and key_i &gt;= 1 and key_i &lt;= sizes[i]
   *       baseKey &lt;&lt; key_i
   *       isSubset &lt;&lt; True
   *     }
   *   } collect {
   *     axes, newSizes =| saveSequential
   *     baseKey =| save
   *     isSubset, isEmpty =| anyTrue
   *   }
   *   if not isSubset {
   *     return m
   *   } else if isEmpty {
   *     return BaseMatrix_(newSizes)
   *   } else {
   *     return SubMatrix_({matrix: m, baseKey, axes, sizes: newSizes})
   *   }
   * }
   * </pre>
   *
   * Checks that {@code key} is an array of the appropriate length and that each {@code key[i]} is
   * either an integer in {@code 1..sizes()[i]} or a subbrange of that range. Returns an appropriate
   * SubMatrix (or BaseMatrix, if the result is empty).
   */
  static class SubMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("subMatrix(Matrix, Array) default")
    static void begin(TState tstate, @RC.In Value m, @RC.In Value key) {
      tstate.startCall(sizes, addRef(m)).saving(m, key);
    }

    @Continuation
    static void afterSizes(TState tstate, Value sizes, @Saved Value m, Value key)
        throws BuiltinException {
      ValueUtil.checkSizes(tstate, sizes);
      int nDims = sizes.numElements();
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
      int numRanges =
          (int)
              IntStream.range(0, nDims)
                  .filter(i -> key.peekElement(i).baseType() == Core.RANGE)
                  .count();
      @RC.Counted Object[] axes = tstate.allocObjectArray(numRanges);
      tstate.dropOnThrow(axes);
      @RC.Counted Object[] newSizes = tstate.allocObjectArray(numRanges);
      tstate.dropOnThrow(newSizes);
      int baseDims;
      @RC.Counted Object[] baseKey;
      if (m.baseType() == SUB_MATRIX) {
        // Rather than creating a nested SubMatrix, we merge them.  To do that we start with the
        // baseKey of the inner SubMatrix.
        Value innerBaseKey = m.peekElement(1);
        baseDims = innerBaseKey.numElements();
        assert baseDims >= nDims;
        baseKey = copyArray(tstate, innerBaseKey);
      } else {
        baseDims = nDims;
        baseKey = tstate.allocObjectArray(nDims);
      }
      tstate.dropOnThrow(baseKey);
      Condition isEmpty = Condition.FALSE;
      Condition isSubset = Condition.FALSE;
      int numNewSizes = 0;
      for (int i = 0; i < nDims; i++) {
        int keyIndex = i;
        Value offset = null;
        if (m.baseType() == SUB_MATRIX) {
          Value innerAxes = m.peekElement(2);
          keyIndex = innerAxes.elementAsInt(i);
          offset = (Value) baseKey[keyIndex];
          baseKey[keyIndex] = null;
        }
        Value k = key.peekElement(i);
        if (k.baseType() == Core.RANGE) {
          Value size = sizes.peekElement(i);
          Value min = k.peekElement(0);
          min = min.is(Core.NONE).choose(NumValue.ONE, min);
          Value max = k.peekElement(1);
          max = max.is(Core.NONE).choose(size, max);
          baseKey[keyIndex] =
              (offset == null)
                  ? min.makeStorable(tstate)
                  : ValueUtil.oneBasedOffset(tstate, min, offset);
          axes[numNewSizes] = NumValue.of(keyIndex, tstate);
          Value newSize = ValueUtil.rangeSize(tstate, min, max);
          newSizes[numNewSizes] = newSize;
          numNewSizes++;
          isEmpty = isEmpty.or(Condition.numericEq(newSize, NumValue.ZERO));
          isSubset = isSubset.or(Condition.numericLessThan(newSize, size));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessThan(NumValue.ZERO, min));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(max, size));
          Err.INVALID_ARGUMENT.unless(Condition.numericLessOrEq(NumValue.ZERO, newSize));
        } else {
          k = ValueUtil.verifyBoundedInt(tstate, k, NumValue.ONE, sizes.peekElement(i));
          if (offset == null) {
            baseKey[keyIndex] = k;
          } else {
            baseKey[keyIndex] = ValueUtil.oneBasedOffset(tstate, k, offset);
            tstate.dropValue(k);
          }
          isSubset = Condition.TRUE;
        }
      }
      Condition finalIsEmpty = isEmpty;
      isSubset
          .not()
          .test(
              () -> {
                tstate.dropReference(axes);
                tstate.dropReference(newSizes);
                tstate.dropReference(baseKey);
                tstate.setResult(addRef(m));
              },
              () ->
                  finalIsEmpty.test(
                      () -> {
                        tstate.dropReference(axes);
                        tstate.dropReference(baseKey);
                        if (numRanges == 1) {
                          tstate.dropReference(newSizes);
                          tstate.setResult(Core.EMPTY_ARRAY);
                        } else {
                          tstate.setResult(
                              tstate.compound(
                                  BASE_MATRIX, tstate.asArrayValue(newSizes, numRanges)));
                        }
                      },
                      () ->
                          tstate.setResult(
                              tstate.compound(
                                  SUB_MATRIX,
                                  m.baseType() == SUB_MATRIX ? m.element(0) : addRef(m),
                                  tstate.asArrayValue(baseKey, baseDims),
                                  tstate.asArrayValue(axes, numRanges),
                                  tstate.asArrayValue(newSizes, numRanges)))));
    }
  }

  /** Copies the elements of a Retrospect array into a Java array. */
  @RC.Out
  private static Object[] copyArray(TState tstate, Value array) {
    int size = array.numElements();
    Object[] result = tstate.allocObjectArray(size);
    for (int i = 0; i < size; i++) {
      result[i] = array.element(i);
    }
    return result;
  }

  /** {@code method sizes(BaseMatrix base) = base_} */
  @Core.Method("sizes(BaseMatrix)")
  static Value sizesBaseMatrix(Value baseMatrix) {
    return baseMatrix.element(0);
  }

  /** {@code method keys(BaseMatrix base) = base} */
  @Core.Method("keys(BaseMatrix)")
  static Value keysBaseMatrix(@RC.In Value baseMatrix) {
    return baseMatrix;
  }

  /** {@code method keys(Matrix m) default = matrix(sizes(m))} */
  static class KeysMatrix extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("keys(Matrix) default")
    static void begin(TState tstate, @RC.In Value m) {
      tstate.startCall(sizes, m);
    }

    @Continuation
    static Value afterSizes(TState tstate, @RC.In Value sizes) throws BuiltinException {
      return matrix1(tstate, sizes);
    }
  }

  /** {@code method new(BaseMatrix base, initialValue) = newMatrix(sizes(base), initialValue)} */
  @Core.Method("new(BaseMatrix, _)")
  static Value newBaseMatrix(TState tstate, ResultsInfo results, Value baseMatrix, Value initial)
      throws BuiltinException {
    Value sizes = baseMatrix.element(0);
    tstate.dropOnThrow(sizes);
    return newMatrix(tstate, results, sizes, initial);
  }

  /** A TProperty for the layout of the Array element of a ReshapedArray result. */
  private static final TProperty<FrameLayout> RESHAPED_ARRAY_LAYOUT =
      TProperty.ARRAY_LAYOUT.elementOf(RESHAPED_ARRAY, 1);

  /**
   * newMatrix() results with more than this many elements will always be represented with a Frame.
   */
  private static final int MAX_ARRAY_COMPOUND_LENGTH = 6;

  /** {@code method newMatrix(Array sizes) = newMatrix(sizes, Absent)} */
  @Core.Method("newMatrix(Array)")
  static Value newMatrix(TState tstate, ResultsInfo results, @RC.In Value sizes)
      throws BuiltinException {
    return newMatrix(tstate, results, sizes, Core.ABSENT);
  }

  /** {@code method newMatrix(Array sizes, initialValue) = ...} */
  @Core.Method("newMatrix(Array, _)")
  static Value newMatrix(TState tstate, ResultsInfo results, @RC.In Value sizes, Value initial)
      throws BuiltinException {
    Value size = sizeFromSizes(tstate, sizes, Err.INVALID_ARGUMENT);
    int iSize = NumValue.asInt(size);
    tstate.dropValue(size);
    int nDims = sizes.numElements();
    Value result;
    if (iSize == 0) {
      result = Core.EMPTY_ARRAY;
    } else {
      // Find out if our result is going to be cast to a frame.  (Note that if we're producing a
      // multi-dimensional matrix we have to look inside the RESHAPED_ARRAY compound that we'll
      // wrap it in).
      FrameLayout layout =
          results.result(0, nDims == 1 ? TProperty.ARRAY_LAYOUT : RESHAPED_ARRAY_LAYOUT);
      if (layout != null || iSize > MAX_ARRAY_COMPOUND_LENGTH) {
        result = FrameLayout.newArray(tstate, layout, iSize, initial);
      } else {
        tstate.reserve(null, iSize);
        Object[] array = tstate.allocObjectArray(iSize);
        Arrays.fill(array, 0, iSize, initial);
        RefCounted.addRef(initial, iSize);
        result = tstate.asArrayValue(array, iSize);
      }
    }
    if (nDims != 1) {
      result = tstate.compound(RESHAPED_ARRAY, sizes, result);
    } else {
      tstate.dropValue(sizes);
    }
    return result;
  }

  /** {@code method sizes(m) (m is Reshaped or m is ReshapedArray) = m_.sizes} */
  @Core.Method("sizes(Reshaped|ReshapedArray)")
  static Value sizesReshaped(Value m) {
    return m.element(0);
  }

  /** {@code method sizes(TransformedMatrix m) = sizes(m_.base)} */
  @Core.Method("sizes(TransformedMatrix)")
  static void sizesTransformedMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /** {@code method sizes(WithKeysMatrix m) = sizes(m_)} */
  @Core.Method("sizes(WithKeysMatrix)")
  static void sizesWithKeysMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /** {@code method sizes(SubMatrix m) = m_.sizes} */
  @Core.Method("sizes(SubMatrix)")
  static Value sizesSubMatrix(Value m) {
    return m.element(3);
  }

  /** {@code method element(BaseMatrix base, key) = key} */
  @Core.Method("element(BaseMatrix, _)")
  static Value elementBaseMatrix(Value baseMatrix, @RC.In Value key) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(isValidKey(key, baseMatrix.element(0)));
    return key;
  }

  private static Condition isValidKey(Value key, Value sizes) {
    int n = sizes.numElements();
    if (!key.isArrayOfLengthAsBoolean(n)) {
      return Condition.FALSE;
    }
    for (int i = 0; i < n; i++) {
      int ki = key.elementAsIntOrMinusOne(i);
      int si = sizes.elementAsInt(i);
      if (ki <= 0 || ki > si) {
        return Condition.FALSE;
      }
    }
    return Condition.TRUE;
  }

  /**
   * <pre>
   * method element(m, Array key) (m is Reshaped or m is ReshapedArray) =
   *     element(m_.elements, convertKey(key, m_.sizes, sizes(m_.elements))
   * </pre>
   */
  static class ElementReshaped extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("element(Reshaped|ReshapedArray, Array)")
    static void begin(TState tstate, @RC.In Value m, @RC.In Value key) {
      tstate.startCall(sizes, m.element(1)).saving(m, key);
    }

    @Continuation
    static void afterSizes(
        TState tstate, Value outSizes, @Saved Value m, Value key, @Fn("element:2") Caller element)
        throws BuiltinException {
      ValueUtil.checkSizes(tstate, outSizes);
      Value inSizes = m.peekElement(0);
      Value transformedKey = convertKey(tstate, key, inSizes, outSizes);
      tstate.startCall(element, m.element(1), transformedKey);
    }
  }

  /**
   * <pre>
   * method replaceElement(ReshapedArray m, Array key, value) {
   *   arrayKey = convertKey(key, m_.sizes, sizes(m_.elements))
   *   return ReshapedArray_(m_.sizes, replaceElement(m_.elements, arrayKey, value))
   * </pre>
   */
  @Core.Method("replaceElement(ReshapedArray, Array, _)")
  static Value replaceElement(TState tstate, @RC.In Value matrix, Value key, @RC.In Value value)
      throws BuiltinException {
    Value sizes = matrix.peekElement(0);
    Value elements = matrix.element(1);
    long index = convertKey(key, sizes);
    if (!ArrayCore.isValidIndex(elements, index)) {
      tstate.dropValue(elements);
      throw Err.INVALID_ARGUMENT.asException();
    }
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    matrix = matrix.replaceElement(tstate, 1, Core.TO_BE_SET);
    elements = elements.replaceElement(tstate, (int) index, value);
    matrix = matrix.replaceElement(tstate, 1, elements);
    return matrix;
  }

  /**
   * <pre>
   * method startUpdate(ReshapedArray m=, Array key) { ... }
   * </pre>
   */
  @Core.Method("startUpdate(ReshapedArray, Array)")
  static void startUpdate(TState tstate, @RC.In Value matrix, Value key) throws BuiltinException {
    Value sizes = matrix.peekElement(0);
    Value elements = matrix.element(1);
    long longIndex = convertKey(key, sizes);
    if (!ArrayCore.isValidIndex(elements, longIndex)) {
      tstate.dropValue(elements);
      throw Err.INVALID_ARGUMENT.asException();
    }
    int index = (int) longIndex;
    // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
    matrix = matrix.replaceElement(tstate, 1, Core.TO_BE_SET);
    Value element = elements.element(index);
    elements = elements.replaceElement(tstate, index, Core.TO_BE_SET);
    tstate.setResults(
        element, tstate.compound(MATRIX_UPDATER, matrix, elements, NumValue.of(index, tstate)));
  }

  /**
   * <pre>
   * method at(MatrixUpdater updater, newElement) = ...
   * </pre>
   */
  @Core.Method("at(MatrixUpdater, _)")
  static Value atMatrixUpdater(TState tstate, @RC.In Value updater, @RC.In Value v) {
    Value matrix = updater.element(0);
    assert matrix.baseType() == RESHAPED_ARRAY;
    Value elements = updater.element(1);
    int index = updater.elementAsInt(2);
    tstate.dropValue(updater);
    elements = elements.replaceElement(tstate, index, v);
    return matrix.replaceElement(tstate, 1, elements);
  }

  /**
   * <pre>
   * method iterator(m, EnumerationKind eKind) (m is Reshaped or m is ReshapedArray) {
   *   it = iterator(m_.elements, eKind)
   *   if eKind is EnumerateValues {
   *     return it
   *   }
   *   inSizes = sizes(m_.elements)
   *   outSizes = m_.sizes
   *   return ReshapedIterator_({it, inSizes, outSizes})
   *   // equivalent to:   it | [k, v] -&gt; [convertKey(k, inSizes, outSizes), v]
   * }
   * </pre>
   */
  static class IteratorReshaped extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:2", "afterIterator");
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("iterator(Reshaped|ReshapedArray, EnumerationKind)")
    static void begin(TState tstate, Value m, @RC.Singleton Value eKind) {
      tstate
          .startCall(iterator, m.element(1), eKind)
          .saving(eKind.is(LoopCore.ENUMERATE_VALUES).choose(() -> Core.NONE, () -> addRef(m)));
    }

    @Continuation
    static void afterIterator(TState tstate, @RC.In Value it, @Saved Value m) {
      m.is(Core.NONE)
          .test(
              () -> tstate.setResult(it),
              () -> tstate.startCall(sizes, m.element(1)).saving(it, m.element(0)));
    }

    @Continuation(order = 2)
    static Value afterSizes(
        TState tstate, @RC.In Value inSizes, @Saved @RC.In Value it, @RC.In Value outSizes)
        throws BuiltinException {
      ValueUtil.checkSizes(tstate, inSizes);
      return tstate.compound(RESHAPED_ITERATOR, it, inSizes, outSizes);
    }
  }

  /**
   * <pre>
   * method next(ReshapedIterator it=) {
   *   x = next(it_.it=)
   *   if x is Absent {
   *     return Absent
   *   } else {
   *     [k, v] = x
   *     return [convertKey(k, it_.inSizes, it_.outSizes), v]
   *   }
   * }
   * </pre>
   */
  static class NextReshapedIterator extends BuiltinMethod {
    static final Caller next = new Caller("next:1", "afterNext");

    @Core.Method("next(ReshapedIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
      Value innerIt = it.element(0);
      it = it.replaceElement(tstate, 0, Core.TO_BE_SET);
      tstate.startCall(next, innerIt).saving(it);
    }

    @Continuation
    static void afterNext(TState tstate, Value x, @RC.In Value innerIt, @Saved @RC.In Value it)
        throws BuiltinException {
      Value result =
          x.is(Core.ABSENT)
              .chooseExcept(
                  () -> Core.ABSENT,
                  () -> {
                    Err.NOT_PAIR.unless(x.isArrayOfLength(2));
                    Value k = x.peekElement(0);
                    Value inSizes = it.peekElement(1);
                    Value outSizes = it.peekElement(2);
                    Value convertedKey = convertKey(tstate, k, inSizes, outSizes);
                    Value v = x.element(1);
                    return tstate.arrayValue(convertedKey, v);
                  });
      tstate.setResults(result, it.replaceElement(tstate, 0, innerIt));
    }
  }

  /**
   * <pre>
   * method element(SubMatrix m, Array key) = element(m_.matrix, offsetKey(key, m))
   * </pre>
   */
  @Core.Method("element(SubMatrix, Array)")
  static void elementSubMatrix(TState tstate, Value m, Value key, @Fn("element:2") Caller element)
      throws BuiltinException {
    Value axes = m.peekElement(2);
    Value sizes = m.peekElement(3);
    int nDims = axes.numElements();
    Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(nDims));
    Value newKey = m.element(1);
    if (m instanceof RValue || key instanceof RValue) {
      for (int i = 0; i < nDims; i++) {
        Value k =
            ValueUtil.verifyBoundedInt(
                tstate, key.peekElement(i), NumValue.ONE, sizes.peekElement(i));
        if (k instanceof NumValue && NumValue.equals(k, 1)) {
          // No update needed
        } else {
          int keyIndex = axes.elementAsInt(i);
          Value nk = ValueUtil.oneBasedOffset(tstate, newKey.peekElement(keyIndex), k);
          newKey = newKey.replaceElement(tstate, keyIndex, nk);
        }
      }
    } else {
      for (int i = 0; i < nDims; i++) {
        int k = key.elementAsIntOrMinusOne(i);
        if (k < 1 || k > sizes.elementAsInt(i)) {
          tstate.dropValue(newKey);
          throw Err.INVALID_ARGUMENT.asException();
        } else if (k == 1) {
          continue;
        }
        int keyIndex = axes.elementAsInt(i);
        int nk = newKey.elementAsInt(keyIndex) + k - 1;
        newKey = newKey.replaceElement(tstate, keyIndex, NumValue.of(nk, tstate));
      }
    }
    Value base = m.element(0);
    tstate.startCall(element, base, newKey);
  }

  /**
   * <pre>
   * method iterator(SubMatrix m, EnumerationKind eKind) =
   *     iterator(matrix(m_.sizes), eKind) | -&gt; element(m, #)
   * </pre>
   */
  @Core.Method("iterator(SubMatrix, EnumerationKind)")
  static Value iteratorSubMatrix(TState tstate, @RC.In Value m, @RC.Singleton Value eKind) {
    Value sizes = m.element(3);
    return tstate.compound(
        CollectionCore.TRANSFORMED_ITERATOR,
        iteratorForNonEmptyBaseMatrixWithSizes(tstate, sizes, eKind),
        eKind,
        tstate.compound(
            Core.CURRIED_LAMBDA,
            element.asLambdaExpr(),
            tstate.arrayValue(NumValue.NEGATIVE_ONE, NumValue.ZERO),
            tstate.arrayValue(m)));
  }

  /**
   * <pre>
   * method iterator(BaseMatrix m, EnumerationKind eKind) {
   *   sizes = m_
   *   if sizes | -&gt; # == 0 | anyTrue {
   *     return emptyIterator()
   *   } else {
   *     nDims = size(sizes)
   *     prev = newMatrix(nDims, 1)
   *     prev[nDims] = 0
   *     return BaseIterator_({eKind, prev, sizes})
   *   }
   * }
   * </pre>
   */
  @Core.Method("iterator(BaseMatrix, EnumerationKind)")
  static Value iteratorBaseMatrix(TState tstate, Value m, @RC.Singleton Value eKind) {
    Value sizes = m.element(0);
    int nDims = sizes.numElements();
    if (IntStream.range(0, nDims).anyMatch(i -> sizes.elementAsInt(i) == 0)) {
      tstate.dropValue(sizes);
      return LoopCore.EMPTY_ITERATOR;
    } else {
      return iteratorForNonEmptyBaseMatrixWithSizes(tstate, sizes, eKind);
    }
  }

  @RC.Out
  private static Value iteratorForNonEmptyBaseMatrixWithSizes(
      TState tstate, @RC.In Value sizes, @RC.Singleton Value eKind) {
    int nDims = sizes.numElements();
    Object[] prev = tstate.allocObjectArray(nDims);
    Arrays.fill(prev, 0, nDims - 1, NumValue.ONE);
    prev[nDims - 1] = NumValue.ZERO;
    return tstate.compound(BASE_ITERATOR, eKind, tstate.asArrayValue(prev, nDims), sizes);
  }

  /**
   * <pre>
   * method next(BaseIterator it=) {
   *   {eKind, prev: key, sizes} = it_
   *   for i in reverse(1..size(key)) sequential key {
   *     if key[i] &lt; sizes[i] {
   *       key[i] += 1
   *       break {
   *         it_.prev = key
   *         return eKind is EnumerateValues ? key : [key, key]
   *       }
   *     }
   *     key[i] = 1
   *   }
   *   return Absent
   * }
   * </pre>
   */
  @Core.Method("next(BaseIterator)")
  static void nextBaseIterator(TState tstate, @RC.In Value it) {
    @RC.Singleton Value eKind = it.peekElement(0);
    Value key = it.element(1);
    Value sizes = it.peekElement(2);
    // TODO: handle key and/or sizes being a varray
    assert key.baseType() == sizes.baseType();
    int nDims = sizes.numElements();
    if (key instanceof RValue || sizes instanceof RValue) {
      nextBaseIteratorHelper(tstate.codeGen(), nDims - 1, nDims, eKind, key, sizes, it);
      return;
    }
    for (int i = nDims - 1; i >= 0; i--) {
      int k = key.elementAsInt(i);
      if (k < sizes.elementAsInt(i)) {
        // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
        it = it.replaceElement(tstate, 1, Core.TO_BE_SET);
        key = key.replaceElement(tstate, i, NumValue.of(k + 1, tstate));
        for (int j = i + 1; j < nDims; j++) {
          key = key.replaceElement(tstate, j, NumValue.ONE);
        }
        Value result = addRef(key);
        it = it.replaceElement(tstate, 1, key);
        if (eKind != LoopCore.ENUMERATE_VALUES) {
          result = tstate.arrayValue(addRef(result), result);
        }
        tstate.setResults(result, it);
        return;
      }
    }
    tstate.dropValue(key);
    tstate.setResults(Core.ABSENT, it);
  }

  /**
   * Using recursion is the easiest way to implement this but in theory could cause a stack overflow
   * if key/size were (very) implausibly large.
   */
  private static void nextBaseIteratorHelper(
      CodeGen codeGen, int i, int nDims, Value eKind, Value key, Value sizes, Value it) {
    if (i < 0) {
      codeGen.setResults(Core.ABSENT, it);
      return;
    }
    Value k = key.peekElement(i);
    Condition.numericLessThan(k, sizes.peekElement(i))
        .test(
            () -> {
              TState tstate = codeGen.tstate();
              Value updatedIt = it.replaceElement(tstate, 1, Core.TO_BE_SET);
              Value updatedKey =
                  key.replaceElement(tstate, i, ValueUtil.addInts(tstate, k, NumValue.ONE));
              for (int j = i + 1; j < nDims; j++) {
                updatedKey = updatedKey.replaceElement(tstate, j, NumValue.ONE);
              }
              updatedIt = updatedIt.replaceElement(tstate, 1, updatedKey);
              Value result =
                  eKind
                      .is(LoopCore.ENUMERATE_VALUES)
                      .choose(updatedKey, tstate.arrayValue(updatedKey, updatedKey));
              tstate.setResults(result, updatedIt);
            },
            () -> nextBaseIteratorHelper(codeGen, i - 1, nDims, eKind, key, sizes, it));
  }

  /**
   * <pre>
   * method join(Matrix c1, Matrix c2) {
   *   assert sizes(c1) == sizes(c2)
   *   return JoinedMatrix_({c1, c2})
   * }
   * </pre>
   */
  static class JoinMatrix extends BuiltinMethod {
    static final Caller sizesM1 = new Caller("sizes:1", "afterSizesM1");
    static final Caller sizesM2 = new Caller("sizes:1", "afterSizesM2");

    @Core.Method("join(Matrix, Matrix)")
    static void begin(TState tstate, @RC.In Value m1, @RC.In Value m2) {
      tstate.startCall(sizesM1, addRef(m1)).saving(m1, m2);
    }

    @Continuation
    static void afterSizesM1(
        TState tstate, @RC.In Value m1Sizes, @Saved @RC.In Value m1, @RC.In Value m2) {
      tstate.startCall(sizesM2, addRef(m2)).saving(m1, m2, m1Sizes);
    }

    @Continuation(order = 2)
    static Value afterSizesM2(
        TState tstate, Value m2Sizes, @Saved @RC.In Value m1, @RC.In Value m2, Value m1Sizes)
        throws BuiltinException {
      checkSizesMatch(tstate, m1Sizes, m2Sizes);
      return tstate.compound(CollectionCore.JOINED_MATRIX, m1, m2);
    }
  }

  private static void checkSizesMatch(TState tstate, Value sizes1, Value sizes2)
      throws BuiltinException {
    ValueUtil.checkSizes(tstate, sizes1);
    ValueUtil.checkSizes(tstate, sizes2);
    int nDims = sizes1.numElements();
    Err.INVALID_ARGUMENT.unless(
        sizes2.numElements() == nDims
            && IntStream.range(0, nDims)
                .allMatch(i -> sizes1.elementAsInt(i) == sizes2.elementAsInt(i)));
  }

  /**
   * <pre>
   * method sizes(JoinedMatrix m) = sizes(m_.c1)
   * </pre>
   */
  @Core.Method("sizes(JoinedMatrix)")
  static void sizesJoinedMatrix(TState tstate, Value m, @Fn("sizes:1") Caller sizes) {
    tstate.startCall(sizes, m.element(0));
  }

  /**
   * <pre>
   * method concat(Matrix v1, Matrix v2) {
   *   [len1] = sizes(v1)
   *   [len2] = sizes(v2)
   *   if len1 == 0 { return v2 }
   *   if len2 == 0 { return v1 }
   *   return ConcatVector_({size: len1 + len2, v1, v2})
   * }
   * </pre>
   */
  static class Concat extends BuiltinMethod {
    static final Caller sizesV1 = new Caller("sizes:1", "afterSizesV1");
    static final Caller sizesV2 = new Caller("sizes:1", "afterSizesV2");

    @Core.Method("concat(Matrix, Matrix)")
    static void begin(TState tstate, @RC.In Value v1, @RC.In Value v2) {
      tstate.startCall(sizesV1, addRef(v1)).saving(v1, v2);
    }

    @Continuation
    static void afterSizesV1(
        TState tstate, @RC.In Value sizes1, @Saved @RC.In Value v1, @RC.In Value v2) {
      tstate.startCall(sizesV2, addRef(v2)).saving(v1, v2, sizes1);
    }

    @Continuation(order = 2)
    static Value afterSizesV2(
        TState tstate, Value sizes2, @Saved @RC.In Value v1, @RC.In Value v2, Value sizes1)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(sizes1.isArrayOfLength(1).and(sizes2.isArrayOfLength(1)));
      int len1 = sizes1.elementAsIntOrMinusOne(0);
      int len2 = sizes2.elementAsIntOrMinusOne(0);
      Err.INVALID_SIZES.unless(len1 >= 0 && len2 >= 0);
      if (len1 == 0) {
        tstate.dropValue(v1);
        return v2;
      } else if (len2 == 0) {
        tstate.dropValue(v2);
        return v1;
      } else {
        return tstate.compound(CONCAT_VECTOR, NumValue.of(len1 + len2, tstate), v1, v2);
      }
    }
  }

  /**
   * <pre>
   * method sizes(ConcatVector cv) = [cv_.size]
   * </pre>
   */
  @Core.Method("sizes(ConcatVector)")
  static Value sizesConcatVector(TState tstate, Value cv) {
    return tstate.arrayValue(cv.element(0));
  }

  /**
   * <pre>
   * method element(ConcatVector cv, [index]) {
   *   [len1] = sizes(cv_.v1)
   *   return index &lt;= len1 ? element(cv_.v1, [index]) : element(cv_.v2, [index - len1])
   * }
   * </pre>
   */
  static class ElementConcat extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("element(ConcatVector, Array)")
    static void begin(TState tstate, @RC.In Value cv, @RC.In Value key) throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
      Err.INVALID_ARGUMENT.unless(key.elementAsIntOrMinusOne(0) >= 1);
      Value v1 = cv.element(1);
      tstate.startCall(sizes, v1).saving(cv, key);
    }

    @Continuation
    static void afterSizes(
        TState tstate, Value sizes1, @Saved Value cv, Value key, @Fn("element:2") Caller element)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(sizes1.isArrayOfLength(1));
      int size1 = sizes1.elementAsIntOrMinusOne(0);
      Err.INVALID_SIZES.unless(size1 >= 0);
      int diff = key.elementAsInt(0) - size1;
      Value v = cv.element(diff <= 0 ? 1 : 2);
      Value vKey = diff <= 0 ? addRef(key) : tstate.arrayValue(NumValue.of(diff, tstate));
      tstate.startCall(element, v, vKey);
    }
  }

  /**
   * <pre>
   * method iterator(ConcatVector cv, EnumerationKind eKind) {
   *   size1 = eKind is EnumerateValues ? None : size(cv_.v1)
   *   it1 = iterator(cv_.v1, eKind)
   *   it2 = iterator(cv_.v2, eKind)
   *   return ConcatIterator_({it1, it2, size1})
   * </pre>
   */
  static class IteratorConcat extends BuiltinMethod {
    static final Caller size = new Caller("size:1", "afterSize");
    static final Caller iterator1 = new Caller("iterator:2", "afterIterator1");
    static final Caller iterator2 = new Caller("iterator:2", "afterIterator2");

    @Core.Method("iterator(ConcatVector, EnumerationKind)")
    static void begin(TState tstate, @RC.In Value cv, @RC.Singleton Value eKind) {
      eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .test(
              () -> tstate.jump("afterSize", Core.NONE, cv, eKind),
              () -> tstate.startCall(size, cv.element(1)).saving(cv, eKind));
    }

    @Continuation
    static void afterSize(
        TState tstate, @RC.In Value size1, @Saved Value cv, @RC.Singleton Value eKind) {
      Value v1 = cv.element(1);
      Value v2 = cv.element(2);
      tstate.startCall(iterator1, v1, eKind).saving(size1, v2, eKind);
    }

    @Continuation(order = 2)
    static void afterIterator1(
        TState tstate,
        @RC.In Value it1,
        @Saved @RC.In Value size1,
        @RC.In Value v2,
        @RC.Singleton Value eKind) {
      tstate.startCall(iterator2, v2, eKind).saving(size1, it1);
    }

    @Continuation(order = 3)
    static Value afterIterator2(
        TState tstate, @RC.In Value it2, @Saved @RC.In Value size1, @RC.In Value it1) {
      return tstate.compound(CONCAT_ITERATOR, it1, it2, size1);
    }
  }

  /**
   * <pre>
   * method next(ConcatIterator it=) {
   *   if it_.it1 is not None {
   *     x = next(it_.it1=)
   *     if x is not Absent {
   *       return x
   *     } else if it_.size1 is None {
   *       it = it_.it2
   *       return next(it=)
   *     }
   *     it_.it1 = None
   *   }
   *   // it1 is None (we're on it2) and size1 is not None (it's a keyed enumeration)
   *   x = next(it_.it2=)
   *   if x is not Absent {
   *     [[index], v] = x
   *     x = [[index + it_.size1], v]
   *   }
   *   return x
   * }
   * </pre>
   */
  static class NextConcatIterator extends BuiltinMethod {
    static final Caller next1 = new Caller("next:1", "afterNext1");
    static final Caller next2 = new Caller("next:1", "afterNext2");

    @Core.Method("next(ConcatIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      Value it1 = it.element(0);
      it1.is(Core.NONE)
          .test(
              () -> tstate.jump("callNext2", it),
              () ->
                  // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
                  tstate
                      .startCall(next1, it1)
                      .saving(it.replaceElement(tstate, 0, Core.TO_BE_SET)));
    }

    @Continuation
    static void afterNext1(
        TState tstate,
        @RC.In Value x,
        @RC.In Value it1,
        @Saved @RC.In Value it,
        @Fn("next:1") Caller next) {
      x.is(Core.ABSENT)
          .test(
              () -> {
                tstate.dropValue(it1);
                Value size1 = it.peekElement(2);
                size1
                    .is(Core.NONE)
                    .test(
                        () -> {
                          Value it2 = it.element(1);
                          tstate.dropValue(it);
                          tstate.startCall(next, it2);
                        },
                        () -> tstate.jump("callNext2", it.replaceElement(tstate, 0, Core.NONE)));
              },
              () -> tstate.setResults(x, it.replaceElement(tstate, 0, it1)));
    }

    @Continuation(order = 2)
    static void callNext2(TState tstate, @RC.In Value it) {
      Value it2 = it.element(1);
      it = it.replaceElement(tstate, 1, Core.TO_BE_SET);
      tstate.startCall(next2, it2).saving(it);
    }

    @Continuation(order = 3)
    static void afterNext2(TState tstate, @RC.In Value x, @RC.In Value it2, @Saved @RC.In Value it)
        throws BuiltinException {
      x.is(Core.ABSENT)
          .testExcept(
              () -> tstate.setResults(x, it.replaceElement(tstate, 1, it2)),
              () -> {
                Err.NOT_PAIR.unless(x.isArrayOfLength(2));
                Value key = x.element(0);
                tstate.dropOnThrow(key);
                Err.INVALID_ARGUMENT.unless(key.isArrayOfLength(1));
                int size1 = it.elementAsInt(2);
                int k = key.elementAsIntOrMinusOne(0);
                int i = k + size1;
                // Error if the index was garbage or the addition overflowed
                Err.INVALID_ARGUMENT.when(k < 0 || i < 0);
                // That was the last place we might throw an exception, so it's safe to start
                // allocating values.
                Value index = NumValue.of(i, tstate);
                // Start by breaking x's link to key, so that we can potentially modify key in
                // place.
                Value x2 = x.replaceElement(tstate, 0, Core.TO_BE_SET);
                Value key2 = key.replaceElement(tstate, 0, index);
                Value newX = x2.replaceElement(tstate, 0, key2);
                tstate.setResults(newX, it.replaceElement(tstate, 1, it2));
              });
    }
  }

  /**
   * <pre>
   * method concatUpdate(Array lhs, Matrix rhs) = ...
   * </pre>
   */
  static class ConcatUpdate extends BuiltinMethod {
    static final Caller sizes = new Caller("sizes:1", "afterSizes");

    @Core.Method("concatUpdate(Array, Matrix)")
    static void begin(TState tstate, @RC.In Value lhs, @RC.In Value rhs) {
      tstate.startCall(sizes, addRef(rhs)).saving(lhs, rhs);
    }

    @Continuation
    static void afterSizes(
        TState tstate,
        Value sizes,
        @Saved @RC.In Value lhs,
        @RC.In Value rhs,
        @Fn("enumerate:4") Caller enumerate)
        throws BuiltinException {
      Err.INVALID_ARGUMENT.unless(sizes.isArrayOfLength(1));
      int iSize2 = sizes.elementAsIntOrMinusOne(0);
      Err.INVALID_SIZES.unless(iSize2 >= 0);
      if (iSize2 == 0) {
        tstate.dropValue(rhs);
        tstate.setResult(lhs);
        return;
      }
      int iSize1 = lhs.numElements();
      lhs.reserveForChangeOrThrow(tstate, iSize1 + iSize2, false);
      // Add iSize2 TO_BE_SET elements at the end of lhs
      lhs = lhs.removeRange(tstate, iSize1, iSize1, iSize1 + iSize2, 0);
      Value loop = tstate.compound(SaveCore.SAVE_WITH_OFFSET, NumValue.of(iSize1, tstate));
      // Wrapping SaveWithOffset (a sequential loop) in SaverLoop makes it parallelizable (since
      // we don't care in which order the elements are computed, as long as each is saved in the
      // corresponding element of the result).
      loop = tstate.compound(SaveCore.SAVER_LOOP, loop);
      // Enumerate the elements of rhs and store each into the appropriate place in the copy
      tstate.startCall(enumerate, rhs, LoopCore.ENUMERATE_ALL_KEYS, loop, lhs);
    }
  }

  /**
   * Converts a matrix key to the key of the same element after reshaping, or throws
   * INVALID_ARGUMENT in {@code inKey} is not valid.
   *
   * @param inSizes the sizes of the matrix before reshaping
   * @param outSizes the sizes of the matrix after reshaping
   * @param inKey a key for the matrix before reshaping
   * @return the corresponding key for the matrix after reshaping
   */
  @RC.Out
  private static Value convertKey(TState tstate, Value inKey, Value inSizes, Value outSizes)
      throws BuiltinException {
    long sum = convertKey(inKey, inSizes);
    Err.INVALID_ARGUMENT.unless(sum >= 0);
    return convertKey(tstate, sum, outSizes);
  }

  /**
   * Given an array of sizes and an index in the sequential enumeration of a matrix with those sizes
   * returns the corresponding key.
   */
  @RC.Out
  private static Value convertKey(TState tstate, long index, Value outSizes) {
    int outDims = outSizes.numElements();
    if (outDims == 0) {
      return Core.EMPTY_ARRAY;
    }
    @RC.Counted Object[] outKey = tstate.allocObjectArray(outDims);
    for (int i = outDims - 1; i > 0; i--) {
      int si = outSizes.elementAsInt(i);
      outKey[i] = NumValue.of((int) (index % si) + 1, tstate);
      index /= si;
    }
    outKey[0] = NumValue.of((int) (index + 1), tstate);
    return tstate.asArrayValue(outKey, outDims);
  }

  /**
   * Given an array of sizes and a key, returns the index of that key in a sequential enumeration of
   * a matrix with those sizes (if the key is valid) or -1 (if the key is invalid).
   */
  private static long convertKey(Value inKey, Value inSizes) {
    int inDims = inSizes.numElements();
    if (!inKey.isArrayOfLengthAsBoolean(inDims)) {
      return -1;
    }
    long sum = 0;
    for (int i = 0; i < inDims; i++) {
      int ki = inKey.elementAsIntOrMinusOne(i);
      int si = inSizes.elementAsInt(i);
      if (ki < 1 || ki > si) {
        return -1;
      }
      sum = sum * si + ki - 1;
      if (sum < 0) {
        // Overflowed a long -- probably can't happen, but let's be paranoid.
        return -1;
      }
    }
    return sum;
  }

  /**
   * <pre>
   * method binaryUpdate(Matrix lhs=, Lambda lambda, rhs) {
   *   if rhs is Matrix {
   *     assert sizes(rhs) == sizes(lhs)
   *     return iterate(rhs, EnumerateAllKeys, BinaryUpdateWithCollection_(lambda), lhs)
   *   } else {
   *     return iterate(keys(lhs), EnumerateValues, BinaryUpdateWithScalar_({lambda, rhs}), lhs)
   *   }
   * }
   * </pre>
   *
   * {@code binaryUpdate()} is overridden when the left hand side is a Matrix to behave more like an
   * "in place" update to the matrix:
   *
   * <ul>
   *   <li>The calculation is done eagerly rather than lazily (if we didn't override {@code
   *       binaryUpdate()}, the default implementation of e.g. "{@code +=}" would use {@code add()},
   *       which is lazy for matrices).
   *   <li>The left hand side must be an updatable matrix (generally an Array or ReshapedArray).
   * </ul>
   *
   * Could we parallelize this? Wrapping the update loop in a SaverLoop and replacing iterate() with
   * enumerate() would "work", but mostly wouldn't help; most or all of the work is done in the
   * update loop's nextState, which would be serialized by the SaverLoop. The most straightforward
   * way to parallelize is to switch from update-in-place to something like
   *
   * <pre>
   * method binaryUpdate(Matrix lhs=, Lambda lambda, rhs) {
   *   if rhs is Matrix {
   *     return join(lhs, rhs) | lambda | save
   *   } else {
   *     return lhs | -&gt; lambda[#, rhs] | save
   *   }
   * }
   * </pre>
   */
  static class BinaryUpdateMatrix extends BuiltinMethod {
    static final Caller sizesLhs = new Caller("sizes:1", "afterSizesLhs");
    static final Caller sizesRhs = new Caller("sizes:1", "afterSizesRhs");
    static final Caller keys = new Caller("keys:1", "afterKeys");

    @Core.Method("binaryUpdate(Matrix, Lambda, _)")
    static void begin(TState tstate, @RC.In Value lhs, @RC.In Value lambda, @RC.In Value rhs) {
      rhs.isa(Core.MATRIX)
          .test(
              () -> tstate.startCall(sizesLhs, addRef(lhs)).saving(lhs, lambda, rhs),
              () -> tstate.startCall(keys, addRef(lhs)).saving(lhs, lambda, rhs));
    }

    @Continuation
    static void afterSizesLhs(
        TState tstate,
        @RC.In Value lhsSizes,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs) {
      tstate.startCall(sizesRhs, addRef(rhs)).saving(lhs, lambda, rhs, lhsSizes);
    }

    @Continuation(order = 2)
    static void afterSizesRhs(
        TState tstate,
        Value rhsSizes,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs,
        Value lhsSizes,
        @Fn("iterate:4") Caller iterate)
        throws BuiltinException {
      checkSizesMatch(tstate, lhsSizes, rhsSizes);
      Value loop = tstate.compound(BINARY_UPDATE_WITH_COLLECTION, lambda);
      tstate.startCall(iterate, rhs, LoopCore.ENUMERATE_ALL_KEYS, loop, lhs);
    }

    @Continuation(order = 3)
    static void afterKeys(
        TState tstate,
        @RC.In Value keys,
        @Saved @RC.In Value lhs,
        @RC.In Value lambda,
        @RC.In Value rhs,
        @Fn("iterate:4") Caller iterate) {
      Value loop = tstate.compound(BINARY_UPDATE_WITH_SCALAR, lambda, rhs);
      tstate.startCall(iterate, keys, LoopCore.ENUMERATE_VALUES, loop, lhs);
    }
  }

  /**
   * <pre>
   * method nextState(BinaryUpdateWithScalar loop, lhs, key) {
   *   { lambda, rhs } = loop_
   *   binaryUpdate(lhs@key=, lambda, rhs)
   *   return lhs
   * }
   * // Or equivalently:
   * method nextState(BinaryUpdateWithScalar loop, lhs, key) {
   *   element = startUpdate(lhs=, key)
   *   { lambda, rhs } = loop_
   *   binaryUpdate(element=, lambda, rhs)
   *   return lhs@element
   * }
   * </pre>
   */
  static class BinaryUpdateWithScalar extends BuiltinMethod {
    static final Caller startUpdate = new Caller("startUpdate:2", "afterStartUpdate");
    static final Caller binaryUpdate = new Caller("binaryUpdate:3", "afterBinaryUpdate");

    @Core.Method("nextState(BinaryUpdateWithScalar, _, _)")
    static void begin(TState tstate, @RC.In Value loop, @RC.In Value lhs, @RC.In Value key) {
      tstate.startCall(startUpdate, lhs, key).saving(loop);
    }

    @Continuation
    static void afterStartUpdate(
        TState tstate, @RC.In Value element, @RC.In Value lhsUpdater, @Saved Value loop) {
      Value lambda = loop.element(0);
      Value rhs = loop.element(1);
      tstate.startCall(binaryUpdate, element, lambda, rhs).saving(lhsUpdater);
    }

    @Continuation(order = 2)
    static void afterBinaryUpdate(
        TState tstate,
        @RC.In Value element,
        @Saved @RC.In Value lhsUpdater,
        @Fn("at:2") Caller at) {
      tstate.startCall(at, lhsUpdater, element);
    }
  }

  /**
   * <pre>
   * method nextState(BinaryUpdateWithCollection loop, lhs, [key, rhsElement]) {
   *   element = startUpdate(lhs=, key)
   *   // Skip calling the lambda if both elements are Absent, to be consistent with join()
   *   if element is not Absent or rhsElement is not Absent {
   *     binaryUpdate(element=, loop_, rhsElement)
   *   }
   *   return lhs@element
   * }
   * </pre>
   */
  static class BinaryUpdateWithCollection extends BuiltinMethod {
    static final Caller startUpdate = new Caller("startUpdate:2", "afterStartUpdate");
    static final Caller binaryUpdate = new Caller("binaryUpdate:3", "afterBinaryUpdate");

    @Core.Method("nextState(BinaryUpdateWithCollection, _, _)")
    static void begin(TState tstate, @RC.In Value loop, @RC.In Value lhs, Value pair)
        throws BuiltinException {
      Err.NOT_PAIR.unless(pair.isArrayOfLength(2));
      Value key = pair.element(0);
      Value rhsElement = pair.element(1);
      tstate.startCall(startUpdate, lhs, key).saving(loop, rhsElement);
    }

    @Continuation
    static void afterStartUpdate(
        TState tstate,
        @RC.In Value element,
        @RC.In Value lhsUpdater,
        @Saved Value loop,
        @RC.In Value rhsElement) {
      element
          .is(Core.ABSENT)
          .and(rhsElement.is(Core.ABSENT))
          .test(
              () -> tstate.jump("afterBinaryUpdate", Core.ABSENT, lhsUpdater),
              () ->
                  tstate
                      .startCall(binaryUpdate, element, loop.element(0), rhsElement)
                      .saving(lhsUpdater));
    }

    @Continuation(order = 2)
    static void afterBinaryUpdate(
        TState tstate,
        @RC.In Value element,
        @Saved @RC.In Value lhsUpdater,
        @Fn("at:2") Caller at) {
      tstate.startCall(at, lhsUpdater, element);
    }
  }

  /**
   * <pre>
   * method joinAndPipe(Matrix m1, Matrix m2, Lambda lambda) = join(m1, m2) | lambda
   * </pre>
   */
  static class JoinAndPipe extends BuiltinMethod {
    static final Caller join = new Caller("join:2", "afterJoin");

    @Core.Method("joinAndPipe(Matrix, Matrix, Lambda)")
    static void begin(TState tstate, @RC.In Value m1, @RC.In Value m2, @RC.In Value lambda) {
      tstate.startCall(join, m1, m2).saving(lambda);
    }

    @Continuation
    static Value afterJoin(TState tstate, @RC.In Value joined, @Saved @RC.In Value lambda) {
      return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, joined, lambda);
    }
  }

  private static final Value ZERO_NEG1 =
      Core.FixedArrayType.withSize(2).uncountedOf(NumValue.ZERO, NumValue.NEGATIVE_ONE);
  private static final Value NEG1_ZERO =
      Core.FixedArrayType.withSize(2).uncountedOf(NumValue.NEGATIVE_ONE, NumValue.ZERO);

  /**
   * Returns {@code lambda[arg, ^m]} (if {@code argIsLeft} is true) or {@code lambda[^m, arg]} (if
   * {@code argIsLeft} is false).
   */
  @RC.Out
  private static Value curryBinaryOp(
      TState tstate, @RC.In Value m, @RC.In Value lambda, @RC.In Value arg, boolean argIsLeft) {
    Value curriedLambda =
        tstate.compound(
            Core.CURRIED_LAMBDA, lambda, argIsLeft ? NEG1_ZERO : ZERO_NEG1, tstate.arrayValue(arg));
    return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, m, curriedLambda);
  }

  /**
   * <pre>
   * method negative(Matrix m) = m | -&gt; -#
   * </pre>
   */
  @Core.Method("negative(Matrix)")
  static Value negativeMatrix(TState tstate, @RC.In Value m) {
    return tstate.compound(
        CollectionCore.TRANSFORMED_MATRIX, m, NumberCore.negative.asLambdaExpr());
  }

  /**
   * <pre>
   * method add(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x + y)  // x ^+^ y
   * </pre>
   */
  @Core.Method("add(Matrix, Matrix)")
  static void addMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.add.asLambdaExpr());
  }

  /**
   * <pre>
   * method add(Matrix m, Number x) = m | -&gt; # + x
   * </pre>
   */
  @Core.Method("add(Matrix, Number)")
  static Value addMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.add.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method add(Number x, Matrix m) = m | -&gt; x + #
   * </pre>
   */
  @Core.Method("add(Number, Matrix)")
  static Value addNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.add.asLambdaExpr(), x, true);
  }

  /**
   * <pre>
   * method subtract(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x - y)  // x ^-^ y
   * </pre>
   */
  @Core.Method("subtract(Matrix, Matrix)")
  static void subtractMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.subtract.asLambdaExpr());
  }

  /**
   * <pre>
   * method subtract(Matrix m, Number x) = m | -&gt; # - x
   * </pre>
   */
  @Core.Method("subtract(Matrix, Number)")
  static Value subtractMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.subtract.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method subtract(Number x, Matrix m) = m | -&gt; x - #
   * </pre>
   */
  @Core.Method("subtract(Number, Matrix)")
  static Value subtractNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.subtract.asLambdaExpr(), x, true);
  }

  /**
   * <pre>
   * method multiply(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x * y)  // x ^*^ y
   * </pre>
   */
  @Core.Method("multiply(Matrix, Matrix)")
  static void multiplyMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.multiply.asLambdaExpr());
  }

  /**
   * <pre>
   * method multiply(Matrix m, Number x) = m | -&gt; # * x
   * </pre>
   */
  @Core.Method("multiply(Matrix, Number)")
  static Value multiplyMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.multiply.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method multiply(Number x, Matrix m) = m | -&gt; x * #
   * </pre>
   */
  @Core.Method("multiply(Number, Matrix)")
  static Value multiplyNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.multiply.asLambdaExpr(), x, true);
  }

  /**
   * <pre>
   * method divide(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x / y)  // x ^/^ y
   * </pre>
   */
  @Core.Method("divide(Matrix, Matrix)")
  static void divideMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.divide.asLambdaExpr());
  }

  /**
   * <pre>
   * method divide(Matrix m, Number x) = m | -&gt; # / x
   * </pre>
   */
  @Core.Method("divide(Matrix, Number)")
  static Value divideMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.divide.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method divide(Number x, Matrix m) = m | -&gt; x / #
   * </pre>
   */
  @Core.Method("divide(Number, Matrix)")
  static Value divideNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.divide.asLambdaExpr(), x, true);
  }

  /**
   * <pre>
   * method modulo(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x % y)  // x ^%^ y
   * </pre>
   */
  @Core.Method("modulo(Matrix, Matrix)")
  static void moduloMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.modulo.asLambdaExpr());
  }

  /**
   * <pre>
   * method modulo(Matrix m, Number x) = m | -&gt; # % x
   * </pre>
   */
  @Core.Method("modulo(Matrix, Number)")
  static Value moduloMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.modulo.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method modulo(Number x, Matrix m) = m | -&gt; x % #
   * </pre>
   */
  @Core.Method("modulo(Number, Matrix)")
  static Value moduloNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.modulo.asLambdaExpr(), x, true);
  }

  /**
   * <pre>
   * method exponent(Matrix m1, Matrix m2) = joinAndPipe(m1, m2, [x, y] -&gt; x ** y)  // x ^**^ y
   * </pre>
   */
  @Core.Method("exponent(Matrix, Matrix)")
  static void exponentMatrixMatrix(
      TState tstate, @RC.In Value m1, @RC.In Value m2, @Fn("joinAndPipe:3") Caller joinAndPipe) {
    tstate.startCall(joinAndPipe, m1, m2, Core.exponent.asLambdaExpr());
  }

  /**
   * <pre>
   * method exponent(Matrix m, Number x) = m | -&gt; # ** x
   * </pre>
   */
  @Core.Method("exponent(Matrix, Number)")
  static Value exponentMatrixNumber(TState tstate, @RC.In Value m, @RC.In Value x) {
    return curryBinaryOp(tstate, m, Core.exponent.asLambdaExpr(), x, false);
  }

  /**
   * <pre>
   * method exponent(Number x, Matrix m) = m | -&gt; x ** #
   * </pre>
   */
  @Core.Method("exponent(Number, Matrix)")
  static Value exponentNumberMatrix(TState tstate, @RC.In Value x, @RC.In Value m) {
    return curryBinaryOp(tstate, m, Core.exponent.asLambdaExpr(), x, true);
  }

  private MatrixCore() {}
}

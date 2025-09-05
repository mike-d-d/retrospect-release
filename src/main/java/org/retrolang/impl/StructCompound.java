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

import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.retrolang.Vm;
import org.retrolang.impl.Err.BuiltinException;

/**
 * The implementation of VirtualMachine.structWithKeys(), i.e. a Vm.Compound for structs with a
 * specific set of keys.
 *
 * <p>A struct is represented as a pair of (keyArray, valueArray) with base type {@link
 * Core#STRUCT}.
 */
class StructCompound implements Vm.Compound {

  /** A Vm.Compound for the empty struct, {@code {}}. */
  public static final StructCompound EMPTY = new StructCompound();

  /** A (constant) Vm.Expr for the empty struct, {@code {}}. */
  private static final VmExpr.Constant EMPTY_EXPR = constPair(Core.EMPTY_ARRAY, Core.EMPTY_ARRAY);

  /**
   * Returns a (constant) Vm.Expr for a struct with the given keys and values
   *
   * <p>The argument values must not be refcounted.
   */
  private static VmExpr.Constant constPair(Value keys, Value values) {
    return VmExpr.Constant.of(Core.STRUCT.uncountedOf(keys, values));
  }

  final String name;

  /**
   * A (constant) Vm.Expr for an array containing this StructCompound's (string) keys in ascending
   * order.
   */
  final VmExpr.Constant keys;

  /**
   * A Vm.Function that takes an appropriately-keyed Struct and returns each of its elements as a
   * separate result.
   */
  final VmFunction extract;

  /**
   * A lambda value that returns structs with these keys. If {@code keys} has a single element,
   * returns a struct with the arg as its value; otherwise the arg must be an array with length
   * matching the number of keys.
   */
  final VmExpr.Constant asLambdaExpr;

  /** A StructCompound with the given keys, which must be distinct and sorted. */
  StructCompound(String... keys) {
    for (int i = 1; i < keys.length; i++) {
      Preconditions.checkArgument(keys[i - 1].compareTo(keys[i]) < 0);
    }
    this.name = Arrays.stream(keys).collect(Collectors.joining(",", "{", "}"));
    this.keys =
        VmExpr.Constant.of(
            Core.FixedArrayType.withSize(keys.length)
                .uncountedOf(i -> new StringValue(Allocator.UNCOUNTED, keys[i])));
    this.extract = new VmFunction.Simple("un" + name, keys.length, this::extractMethod, "x");
    this.asLambdaExpr = VmExpr.Constant.of(Core.STRUCT_LAMBDA.uncountedOf(this.keys.value));
  }

  /**
   * Throws an exception, since there is no Vm.Type corresponding to a struct with specific keys.
   */
  @Override
  public Vm.Type asType() {
    throw new IllegalArgumentException();
  }

  @Override
  public VmFunction extract() {
    return extract;
  }

  /** Implements our {@link #extract} function. */
  private void extractMethod(TState tstate, Object[] args) throws BuiltinException {
    Value arg = (Value) args[0];
    Err.INVALID_ARGUMENT.unless(arg.isa(Core.STRUCT));
    Err.INVALID_ARGUMENT.unless(matchesKeys(arg.peekElement(0)));
    Value elements = arg.peekElement(1);
    assert elements.numElements() == extract.numResults;
    tstate.setResultsFromElements(extract.numResults, elements);
  }

  private boolean matchesKeys(Value keys) {
    Value expected = this.keys.value;
    if (keys == expected) {
      return true;
    }
    int nKeys = expected.numElements();
    return keys.isArrayOfLengthAsBoolean(nKeys) && Value.equalElements(expected, keys, nKeys);
  }

  @Override
  public Vm.Expr make(Vm.Expr... elements) {
    Preconditions.checkArgument(elements.length == keys.value.numElements());
    if (elements.length == 0) {
      return EMPTY_EXPR;
    }
    VmExpr elementsArray = VmExpr.array(elements);
    if (elementsArray instanceof VmExpr.Constant c) {
      return constPair(keys.value, c.value);
    } else {
      return new VmExpr.Compound(Core.STRUCT, keys, elementsArray);
    }
  }

  @Override
  public Vm.Value make(Vm.ResourceTracker allocator, Vm.Value... elements) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Vm.Expr asLambdaExpr() {
    return asLambdaExpr;
  }

  static String toString(Value struct) {
    assert struct.baseType() == Core.STRUCT;
    Value keys = struct.peekElement(0);
    int nKeys = keys.numElements();
    Value values = struct.peekElement(1);
    if (values.isArrayOfLengthAsBoolean(nKeys)) {
      return IntStream.range(0, nKeys)
          .mapToObj(i -> ((StringValue) keys.peekElement(i)).value + ": " + values.peekElement(i))
          .collect(Collectors.joining(", ", "{", "}"));
    } else {
      // If we somehow end up with a struct containing something other than matched arrays, fall
      // back on the default toString().
      return Core.STRUCT.toString(struct::peekElement);
    }
  }
}

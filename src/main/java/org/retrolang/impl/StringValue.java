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
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.core.CollectionCore;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * An implementation of Value for strings.
 *
 * <p>For now this is a minimal placeholder; I expect to replace it with something more complete
 * later.
 */
public class StringValue extends RefCounted implements Value {

  private static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + SizeOf.PTR);

  public final String value;

  /**
   * Assumes that value is unshared, and will be released when we are dropped. Will overcount if
   * value is shared, but that's probably harmless.
   */
  public StringValue(Allocator allocator, String value) {
    int nChars = value.length();
    // To keep this fast and simple we want to use length() and charAt() rather than
    // codePointCount() and offsetByCodePoints(), but we can only do that if the string contains
    // no surrogate pairs.
    Preconditions.checkArgument(
        value.codePointCount(0, nChars) == nChars,
        "Code points >= 2^16 are unsupported (%s)",
        value);
    this.value = value;
    allocator.recordAlloc(this, OBJ_SIZE + SizeOf.string(value));
  }

  @Override
  public BaseType baseType() {
    return Core.STRING;
  }

  @Override
  long visitRefs(RefVisitor visitor) {
    return OBJ_SIZE + SizeOf.string(value);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof StringValue sv && value.equals(sv.value);
  }

  @Override
  public int hashCode() {
    // Since all StringValues are immutable (counted or not) they can all be hashable.
    return value.hashCode();
  }

  @Override
  public String toString() {
    return StringUtil.escape(value);
  }

  /** {@code open function codePoints(string)} */
  @Core.Public
  static final VmFunctionBuilder codePoints = VmFunctionBuilder.create("codePoints", 1).isOpen();

  /**
   * {@code private compound ToCodePoint is Lambda}
   *
   * <p>Input is an index; output the corresponding code point from the wrapped String.
   */
  @Core.Private
  static final BaseType.Named TO_CODE_POINT = Core.newBaseType("ToCodePoint", 1, Core.LAMBDA);

  /** {@code method codePoints(String s) = 1..size(s) | ToCodePoint_(s)} */
  @Core.Method("codePoints(String)")
  static Value codePoints(TState tstate, @RC.In Value s) {
    int n = ((StringValue) s).value.length();
    if (n == 0) {
      tstate.dropValue(s);
      return Core.EMPTY_ARRAY;
    }
    Value range = tstate.compound(Core.RANGE, NumValue.ONE, NumValue.of(n, tstate));
    Value lambda = tstate.compound(TO_CODE_POINT, s);
    return tstate.compound(CollectionCore.TRANSFORMED_MATRIX, range, lambda);
  }

  /** {@code method at(ToCodePoint cp, Number index) = ...} */
  @Core.Method("at(ToCodePoint, Number)")
  static Value atToCodePoint(TState tstate, Value cp, Value index) throws BuiltinException {
    StringValue s = (StringValue) cp.peekElement(0);
    int i = NumValue.asIntOrMinusOne(index);
    Err.INVALID_ARGUMENT.unless(i > 0 && i <= s.value.length());
    return NumValue.of(s.value.charAt(i - 1), tstate);
  }

  @Core.Method("equal(String, String)")
  static Value equalStrings(Value x, Value y) {
    return Core.bool(((StringValue) x).value.equals(((StringValue) y).value));
  }

  @Core.Method("concat(String, String)")
  static Value concatStrings(TState tstate, Value x, Value y) {
    return new StringValue(tstate, ((StringValue) x).value.concat(((StringValue) y).value));
  }
}

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
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.impl.Allocator;
import org.retrolang.impl.CodeGen;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.NumValue;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.RefCounted;
import org.retrolang.impl.ResultsInfo;
import org.retrolang.impl.StructType;
import org.retrolang.impl.TProperty;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods on Numbers. */
public final class NumberCore {
  @Core.Public
  static final VmFunctionBuilder number = VmFunctionBuilder.create("number", 1).isOpen();

  @Core.Public
  static final VmFunctionBuilder negative = VmFunctionBuilder.create("negative", 1).isOpen();

  @Core.Public static final VmFunctionBuilder floor = VmFunctionBuilder.create("floor", 1).isOpen();

  @Core.Public
  static final VmFunctionBuilder ceiling = VmFunctionBuilder.create("ceiling", 1).isOpen();

  @Core.Public static final VmFunctionBuilder abs = VmFunctionBuilder.create("abs", 1).isOpen();

  @Core.Public static final VmFunctionBuilder asInt = VmFunctionBuilder.create("asInt", 1).isOpen();

  @Core.Public static final VmFunctionBuilder u32 = VmFunctionBuilder.create("u32", 1).isOpen();

  @Core.Public static final VmFunctionBuilder uAdd = VmFunctionBuilder.create("uAdd", 2);

  @Core.Public static final VmFunctionBuilder uSubtract = VmFunctionBuilder.create("uSubtract", 2);

  @Core.Public static final VmFunctionBuilder uMultiply = VmFunctionBuilder.create("uMultiply", 2);

  @Core.Public
  static final VmFunctionBuilder uDivWithRemainder =
      VmFunctionBuilder.create("uDivWithRemainder", 2);

  @Core.Public static final VmFunctionBuilder bitAnd = VmFunctionBuilder.create("bitAnd", 2);

  @Core.Public static final VmFunctionBuilder bitOr = VmFunctionBuilder.create("bitOr", 2);

  @Core.Public static final VmFunctionBuilder bitAndNot = VmFunctionBuilder.create("bitAndNot", 2);

  @Core.Public static final VmFunctionBuilder bitInvert = VmFunctionBuilder.create("bitInvert", 1);

  @Core.Public static final VmFunctionBuilder bitCount = VmFunctionBuilder.create("bitCount", 1);

  @Core.Public
  static final VmFunctionBuilder bitFirstZero = VmFunctionBuilder.create("bitFirstZero", 1);

  @Core.Public
  static final VmFunctionBuilder bitFirstOne = VmFunctionBuilder.create("bitFirstOne", 1);

  @Core.Public
  static final VmFunctionBuilder bitLastZero = VmFunctionBuilder.create("bitLastZero", 1);

  @Core.Public
  static final VmFunctionBuilder bitLastOne = VmFunctionBuilder.create("bitLastOne", 1);

  @Core.Public static final VmFunctionBuilder bitShift = VmFunctionBuilder.create("bitShift", 2);

  @Core.Public static final VmFunctionBuilder bitRotate = VmFunctionBuilder.create("bitRotate", 2);

  @Core.Public static final VmFunctionBuilder sqrt = mathBuiltin("sqrt", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder hypot = mathBuiltin("hypot", 2).isOpen();

  @Core.Public static final VmFunctionBuilder exp = mathBuiltin("exp", 1).isOpen();

  @Core.Public static final VmFunctionBuilder log = mathBuiltin("log", 1).mayReturnNaN().isOpen();

  @Core.Public
  static final VmFunctionBuilder log10 = mathBuiltin("log10", 1).mayReturnNaN().isOpen();

  @Core.Public
  static final VmFunctionBuilder log1p = mathBuiltin("log1p", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder sin = mathBuiltin("sin", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder cos = mathBuiltin("cos", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder tan = mathBuiltin("tan", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder asin = mathBuiltin("asin", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder acos = mathBuiltin("acos", 1).mayReturnNaN().isOpen();

  @Core.Public static final VmFunctionBuilder atan = mathBuiltin("atan", 1).isOpen();

  @Core.Public static final VmFunctionBuilder sinh = mathBuiltin("sinh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder cosh = mathBuiltin("cosh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder tanh = mathBuiltin("tanh", 1).isOpen();

  @Core.Public static final VmFunctionBuilder random0 = mathBuiltin("random", 0);

  @Core.Public static final VmFunctionBuilder pi = constant("pi", Math.PI);

  @Core.Public static final VmFunctionBuilder e = constant("e", Math.E);

  @Core.Public
  static final VmFunctionBuilder degreesPerRadian = constant("degreesPerRadian", 180 / Math.PI);

  @Core.Public
  static final VmFunctionBuilder radiansPerDegree = constant("radiansPerDegree", Math.PI / 180);

  /**
   * Most U32 arithmetic operations return a struct with these keys.
   *
   * <p>Must be in alphabetical order.
   */
  static final StructType HIGH_LOW_KEYS = new StructType("high", "low");

  /**
   * uDivWithRemainder returns a struct with these keys.
   *
   * <p>Must be in alphabetical order.
   */
  static final StructType Q_R_KEYS = new StructType("q", "r");

  /** Create a new builtin with the given name that returns a constant value. */
  private static VmFunctionBuilder constant(String name, double d) {
    return VmFunctionBuilder.fromConstant(name, NumValue.of(d, Allocator.UNCOUNTED));
  }

  /**
   * Create a new builtin with the given signature that is implemented by calling the method of the
   * same name in java.lang.Math.
   */
  private static VmFunctionBuilder.FromOp mathBuiltin(String name, int numArgs) {
    Class<?>[] argTypes = new Class<?>[numArgs];
    Arrays.fill(argTypes, double.class);
    return VmFunctionBuilder.fromOp(name, Op.forMethod(Math.class, name, argTypes).build());
  }

  @Core.Method("number(Number)")
  static Value numberNumber(@RC.In Value x) {
    return x;
  }

  @Core.Method("add(Number, Number)")
  static Value addNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.ADD_INTS_EXACT, Op.ADD_DOUBLES);
    }
    return doMath(tstate, x, y, Math::addExact, (a, b) -> a + b);
  }

  @Core.Method("subtract(Number, Number)")
  static Value subtractNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.SUBTRACT_INTS_EXACT, Op.SUBTRACT_DOUBLES);
    }
    return doMath(tstate, x, y, Math::subtractExact, (a, b) -> a - b);
  }

  @Core.Method("multiply(Number, Number)")
  static Value multiplyNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (x instanceof RValue || y instanceof RValue) {
      return doMath(tstate.codeGen(), x, y, results, Op.MULTIPLY_INTS_EXACT, Op.MULTIPLY_DOUBLES);
    }
    return doMath(tstate, x, y, Math::multiplyExact, (a, b) -> a * b);
  }

  private static void checkForNaNResult(CodeGen codeGen, CodeValue result) {
    result = codeGen.materialize(result, double.class);
    FutureBlock isNotNaN = new FutureBlock();
    codeGen.testIsNaN(result, true, isNotNaN);
    codeGen.setResults(Core.NONE);
    codeGen.cb.setNext(isNotNaN);
    codeGen.setResults(codeGen.toValue(result));
  }

  @Core.Method("divide(Number, Number)")
  static void divideNumbers(TState tstate, ResultsInfo results, Value x, Value y) {
    if (x instanceof RValue || y instanceof RValue) {
      CodeGen codeGen = tstate.codeGen();
      CodeValue cx = codeGen.asCodeValue(x);
      CodeValue cy = codeGen.asCodeValue(y);
      codeGen.setResultWithNaNCheck(Op.DIV_DOUBLES.result(cx, cy));
    } else {
      tstate.setResult(NumValue.orNan(NumValue.asDouble(x) / NumValue.asDouble(y), tstate));
    }
  }

  @Core.Method("negative(Number)")
  static Value negative(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    if (x instanceof RValue) {
      return doMath(tstate.codeGen(), x, results, Op.NEGATE_INT_EXACT, Op.NEGATE_DOUBLE);
    } else if (x instanceof NumValue.I ni) {
      try {
        int i = Math.negateExact(ni.value);
        return NumValue.of(i, tstate);
      } catch (ArithmeticException e) {
        // fall through
      }
    }
    return NumValue.of(-NumValue.asDouble(x), tstate);
  }

  @Core.Method("floor(Number)")
  static Value floor(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    return floorOrCeiling(tstate, results, x, true);
  }

  @Core.Method("ceiling(Number)")
  static Value ceiling(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    return floorOrCeiling(tstate, results, x, false);
  }

  private static final Op FLOOR_OP = Op.forMethod(Math.class, "floor", double.class).build();
  private static final Op CEIL_OP = Op.forMethod(Math.class, "ceil", double.class).build();
  private static final Op FLOOR_DIV_OP =
      Op.forMethod(Math.class, "floorDiv", int.class, int.class).build();

  private static Value floorOrCeiling(TState tstate, ResultsInfo results, Value x, boolean floor)
      throws BuiltinException {
    if (!(x instanceof RValue)) {
      if (x instanceof NumValue.I) {
        return x;
      }
      double d = NumValue.asDouble(x);
      double dResult = floor ? Math.floor(d) : Math.ceil(d);
      int iResult = (int) dResult;
      if (dResult == iResult) {
        return NumValue.of(iResult, tstate);
      } else if (dResult == d) {
        return x;
      } else {
        return NumValue.of(dResult, tstate);
      }
    }
    CodeGen codeGen = tstate.codeGen();
    Register xr = codeGen.register(x);
    if (xr.type() == int.class) {
      return x;
    }
    CodeValue v = (floor ? FLOOR_OP : CEIL_OP).result(xr);
    if (results.result(TProperty.COERCES_TO_FLOAT)) {
      v = codeGen.materialize(v, double.class);
    } else {
      v =
          codeGen.materializeCatchingArithmeticException(
              Op.LONG_TO_INT_EXACT.result(Op.DOUBLE_TO_LONG.result(v)));
    }
    return codeGen.toValue(v);
  }

  @Core.Method("div(Number, Number)")
  static Value divNumbers(TState tstate, Value x, Value y) {
    if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
      int ix = nix.value;
      int iy = niy.value;
      if (iy == 0) {
        return (ix == 0)
            ? Core.NONE
            : (ix < 0) ? NumValue.NEGATIVE_INFINITY : NumValue.POSITIVE_INFINITY;
      } else if (iy != -1 || ix != Integer.MIN_VALUE) {
        return NumValue.of(Math.floorDiv(ix, iy), tstate);
      }
    }
    double q = Math.floor(NumValue.asDouble(x) / NumValue.asDouble(y));
    int iq = (int) q;
    if (q == iq) {
      return NumValue.of(iq, tstate);
    } else {
      return NumValue.orNan(q, tstate);
    }
  }

  private static final Op FLOOR_MOD_OP =
      Op.forMethod(Math.class, "floorMod", int.class, int.class).build();

  @Core.Method("modulo(Number, Number)")
  static void moduloNumbers(TState tstate, ResultsInfo results, Value x, Value y) {
    if (!(x instanceof RValue || y instanceof RValue)) {
      if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
        int ix = nix.value;
        int iy = niy.value;
        tstate.setResult(iy == 0 ? Core.NONE : NumValue.of(Math.floorMod(ix, iy), tstate));
      } else {
        double dx = NumValue.asDouble(x);
        double dy = NumValue.asDouble(y);
        tstate.setResult(NumValue.orNan(modulo(dx, dy), tstate));
      }
      return;
    }
    CodeGen codeGen = tstate.codeGen();
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    if (cx.type() == double.class || cy.type() == double.class) {
      checkForNaNResult(codeGen, MODULO_OP.result(cx, cy));
    } else {
      Condition.intEq(cy, CodeValue.ZERO)
          .test(
              () -> codeGen.setResults(Core.NONE),
              () -> {
                codeGen.setResults(codeGen.intToValue(FLOOR_MOD_OP.result(cx, cy)));
              });
    }
  }

  private static final Op MODULO_OP =
      Op.forMethod(NumberCore.class, "modulo", double.class, double.class).build();

  public static double modulo(double x, double y) {
    if (Double.isInfinite(y)) {
      // Java thinks that x % inf == x, but that's bogus
      return Double.NaN;
    }
    double r = x % y;
    // Java gives us r with the same sign as x, but we want it to have the same sign as y
    if ((Double.doubleToRawLongBits(r) ^ Double.doubleToRawLongBits(y)) < 0 && r != 0) {
      r += y;
    }
    return r;
  }

  @Core.Method("exponent(Number, Number)")
  static Value exponentNumbers(TState tstate, ResultsInfo results, Value x, Value y)
      throws BuiltinException {
    if (NumValue.equals(y, 0)) {
      return NumValue.ONE;
    } else if (NumValue.equals(y, 1)) {
      return addRef(x);
    } else if (NumValue.equals(y, 2)) {
      return multiplyNumbers(tstate, results, x, x);
    } else {
      double d = Math.pow(NumValue.asDouble(x), NumValue.asDouble(y));
      if (x instanceof NumValue.I && y instanceof NumValue.I) {
        int i = (int) d;
        if (d == i) {
          return NumValue.of(i, tstate);
        }
      }
      return NumValue.orNan(d, tstate);
    }
  }

  /**
   * If {@code x} and {@code y} are both integers, try applying {@code intOp} to them. If that
   * succeeds, return its result as a {@link NumValue.I}. If either argument is a double or if
   * {@code intOp} throws an ArithmeticException, apply {@code doubleOp} to {@code x} and {@code y}
   * and return its result as a {@link NumValue.D}.
   */
  @RC.Out
  private static Value doMath(
      TState tstate, Value x, Value y, IntBinaryOperator intOp, DoubleBinaryOperator doubleOp) {
    if (x instanceof NumValue.I nix && y instanceof NumValue.I niy) {
      try {
        int i = intOp.applyAsInt(nix.value, niy.value);
        return NumValue.of(i, tstate);
      } catch (ArithmeticException e) {
        // fall through
      }
    }
    double d = doubleOp.applyAsDouble(NumValue.asDouble(x), NumValue.asDouble(y));
    return NumValue.orNan(d, tstate);
  }

  /**
   * If {@code x} and {@code y} are both integers and our result is to be stored as an integer, emit
   * code that applies {@code intOp} and escapes if it throws an ArithmeticException; otherwise emit
   * code that applies {@code doubleOp}.
   */
  private static Value doMath(
      CodeGen codeGen, Value x, Value y, ResultsInfo results, Op exactIntOp, Op doubleOp)
      throws BuiltinException {
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue cy = codeGen.asCodeValue(y);
    CodeValue result;
    if (cx.type() == double.class
        || cy.type() == double.class
        || results.result(TProperty.COERCES_TO_FLOAT)) {
      result = codeGen.materialize(doubleOp.result(cx, cy), double.class);
    } else {
      result = codeGen.materializeCatchingArithmeticException(exactIntOp.result(cx, cy));
    }
    return codeGen.toValue(result);
  }

  /**
   * If {@code x} is an integer and our result is to be stored as an integer, emit code that applies
   * {@code intOp} and escapes if it throws an ArithmeticException; otherwise emit code that applies
   * {@code doubleOp}.
   */
  private static Value doMath(
      CodeGen codeGen, Value x, ResultsInfo results, Op exactIntOp, Op doubleOp)
      throws BuiltinException {
    CodeValue cx = codeGen.asCodeValue(x);
    CodeValue result;
    if (cx.type() == double.class || results.result(TProperty.COERCES_TO_FLOAT)) {
      result = codeGen.materialize(doubleOp.result(cx), double.class);
    } else {
      result = codeGen.materializeCatchingArithmeticException(exactIntOp.result(cx));
    }
    return codeGen.toValue(result);
  }

  @Core.Method("lessThan(Number, Number)")
  static Value lessThanNumbers(Value x, Value y) {
    return Condition.numericLessThan(x, y).asValue();
  }

  @Core.Method("equal(Number, Number)")
  static Value equalNumbers(Value x, Value y) {
    return Condition.numericEq(x, y).asValue();
  }

  @Core.Method("asInt(Number)")
  static Value asInt(TState tstate, Value x) throws BuiltinException {
    if (x instanceof NumValue.I ni) {
      ni.addRef();
      return ni;
    } else if (x instanceof NumValue.D nd) {
      double d = nd.value;
      int i = (int) d;
      if (i == d) {
        return NumValue.of(i, tstate);
      }
    }
    throw Err.INVALID_ARGUMENT.asException();
  }

  @Core.Method("abs(Number)")
  static Value abs(TState tstate, ResultsInfo results, Value x) throws BuiltinException {
    if (x instanceof RValue) {
      return doMath(tstate.codeGen(), x, results, Op.ABS_INT_EXACT, Op.ABS_DOUBLE);
    } else if (x instanceof NumValue.I ni) {
      int i = ni.value;
      if (i >= 0) {
        ni.addRef();
        return ni;
      } else if (i != Integer.MIN_VALUE) {
        return NumValue.of(-i, tstate);
      }
    }
    double d = NumValue.asDouble(x);
    if (d >= 0) {
      RefCounted.addRef(x);
      return x;
    } else {
      return NumValue.of(-d, tstate);
    }
  }

  /**
   * {@code method u32(Number x) = ...}
   *
   * <p>The {@code x} must be an integer between 0 and 2**32-1
   */
  @Core.Method("u32(Number)")
  static Value u32(TState tstate, Value x) throws BuiltinException {
    if (x instanceof NumValue.I ni) {
      int i = ni.value;
      if (i >= 0) {
        return tstate.compound(Core.U32, addRef(x));
      }
    } else {
      double d = ((NumValue.D) x).value;
      long i = (long) d;
      if (i == d && i >= 0 && i <= 0xffffffffL) {
        return tstate.compound(Core.U32, NumValue.of((int) i, tstate));
      }
    }
    throw Err.INVALID_ARGUMENT.asException();
  }

  @Core.Method("number(U32)")
  static Value numberU32(TState tstate, Value x) {
    NumValue.I iNum = (NumValue.I) x.peekElement(0);
    if (iNum.value >= 0) {
      return iNum.makeStorable(tstate);
    } else {
      return NumValue.of((double) (iNum.value & 0xffffffffL), tstate);
    }
  }

  private static int u32ToInt(Value v) {
    assert v.baseType() == Core.U32;
    return v.elementAsInt(0);
  }

  private static long u32ToLong(Value v) {
    return u32ToInt(v) & 0xffffffffL;
  }

  @Core.Method("lessThan(U32, U32)")
  static Value lessThanU32U32(Value x, Value y) {
    return Core.bool(u32ToLong(x) < u32ToLong(y));
  }

  @Core.Method("lessThan(U32, Number)")
  static Value lessThanU32Number(Value x, Value y) {
    long xl = u32ToLong(x);
    boolean result;
    if (y instanceof NumValue.I niy) {
      result = xl < niy.value;
    } else {
      result = xl < ((NumValue.D) y).value;
    }
    return Core.bool(result);
  }

  @Core.Method("lessThan(Number, U32)")
  static Value lessThanNumberU32(Value x, Value y) {
    long yl = u32ToLong(y);
    boolean result;
    if (x instanceof NumValue.I nix) {
      result = nix.value < yl;
    } else {
      result = ((NumValue.D) x).value < yl;
    }
    return Core.bool(result);
  }

  @Core.Method("equal(U32, U32)")
  static Value equalU32U32(Value x, Value y) {
    return Core.bool(u32ToLong(x) == u32ToLong(y));
  }

  @Core.Method("equal(U32, Number)")
  static Value equalU32Number(Value x, Value y) {
    long xl = u32ToLong(x);
    boolean result;
    if (y instanceof NumValue.I niy) {
      result = xl == niy.value;
    } else {
      result = xl == ((NumValue.D) y).value;
    }
    return Core.bool(result);
  }

  @Core.Method("equal(Number, U32)")
  static Value equalNumberU32(Value x, Value y) {
    return equalU32Number(y, x);
  }

  @RC.Out
  private static Value asU32(TState tstate, int value) {
    return tstate.compound(Core.U32, NumValue.of(value, tstate));
  }

  @RC.Out
  private static Value asHighLow(TState tstate, long value) {
    return tstate.compound(
        HIGH_LOW_KEYS, asU32(tstate, (int) (value >> 32)), asU32(tstate, (int) value));
  }

  @Core.Method("uAdd(U32, U32)")
  static Value uAdd(TState tstate, Value x, Value y) {
    return asHighLow(tstate, u32ToLong(x) + u32ToLong(y));
  }

  @Core.Method("uMultiply(U32, U32)")
  static Value uMultiply(TState tstate, Value x, Value y) {
    return asHighLow(tstate, u32ToLong(x) * u32ToLong(y));
  }

  @Core.Method("uSubtract(U32, U32)")
  static Value uSubtract(TState tstate, Value x, Value y) {
    return asHighLow(tstate, u32ToLong(x) - u32ToLong(y));
  }

  static long highLowToLong(Value struct) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(HIGH_LOW_KEYS.matches(struct));
    Value high = struct.peekElement(0);
    Value low = struct.peekElement(1);
    Err.INVALID_ARGUMENT.unless(high.isa(Core.U32).and(low.isa(Core.U32)));
    return (u32ToLong(high) << 32) + u32ToLong(low);
  }

  @Core.Method("uDivWithRemainder(Struct, U32)")
  static Value uDivWithRemainder(TState tstate, Value x, Value y) throws BuiltinException {
    long xl = highLowToLong(x);
    long yl = u32ToLong(y);
    long q = Long.divideUnsigned(xl, yl);
    long r = Long.remainderUnsigned(xl, yl);
    assert (r & 0xffffffffL) == r;
    return tstate.compound(Q_R_KEYS, asHighLow(tstate, q), asU32(tstate, (int) r));
  }

  @Core.Method("bitAnd(U32, U32)")
  static Value bitAnd(TState tstate, Value x, Value y) {
    return asU32(tstate, u32ToInt(x) & u32ToInt(y));
  }

  @Core.Method("bitOr(U32, U32)")
  static Value bitOr(TState tstate, Value x, Value y) {
    return asU32(tstate, u32ToInt(x) | u32ToInt(y));
  }

  @Core.Method("bitAndNot(U32, U32)")
  static Value bitAndNot(TState tstate, Value x, Value y) {
    return asU32(tstate, u32ToInt(x) & ~u32ToInt(y));
  }

  @Core.Method("bitInvert(U32)")
  static Value bitInvert(TState tstate, Value x) {
    return asU32(tstate, ~u32ToInt(x));
  }

  @Core.Method("bitCount(U32)")
  static Value bitCount(TState tstate, Value x) {
    return NumValue.of(Integer.bitCount(u32ToInt(x)), tstate);
  }

  @Core.Method("bitFirstZero(U32)")
  static Value bitFirstZero(TState tstate, Value x) {
    return NumValue.of(bitFirstOne(~u32ToInt(x)), tstate);
  }

  @Core.Method("bitFirstOne(U32)")
  static Value bitFirstOne(TState tstate, Value x) {
    return NumValue.of(bitFirstOne(u32ToInt(x)), tstate);
  }

  private static int bitFirstOne(int i) {
    int result = Integer.numberOfTrailingZeros(i);
    return result == 32 ? -1 : result;
  }

  @Core.Method("bitLastZero(U32)")
  static Value bitLastZero(TState tstate, Value x) {
    return NumValue.of(bitLastOne(~u32ToInt(x)), tstate);
  }

  @Core.Method("bitLastOne(U32)")
  static Value bitLastOne(TState tstate, Value x) {
    return NumValue.of(bitLastOne(u32ToInt(x)), tstate);
  }

  private static int bitLastOne(int i) {
    return 31 - Integer.numberOfLeadingZeros(i);
  }

  @Core.Method("bitShift(U32, Number)")
  static Value bitShift(TState tstate, Value x, Value nBits) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(NumValue.isInt(nBits));
    int ix = u32ToInt(x);
    int n = NumValue.asInt(nBits);
    if (n >= 0) {
      ix = (n <= 31) ? ix << n : 0;
    } else {
      ix = (n >= -31) ? ix >>> -n : 0;
    }
    return asU32(tstate, ix);
  }

  @Core.Method("bitRotate(U32, Number)")
  static Value bitRotate(TState tstate, Value x, Value nBits) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(NumValue.isInt(nBits));
    return asU32(tstate, Integer.rotateLeft(u32ToInt(x), NumValue.asInt(nBits)));
  }

  @Core.Method("bitRotate(Struct, Number)")
  static Value rotateHighLow(TState tstate, Value x, Value nBits) throws BuiltinException {
    Err.INVALID_ARGUMENT.unless(NumValue.isInt(nBits));
    long result = Long.rotateLeft(highLowToLong(x), NumValue.asInt(nBits));
    return asU32(tstate, (int) result);
  }

  private NumberCore() {}
}

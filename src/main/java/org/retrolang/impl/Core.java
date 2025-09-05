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

import static org.retrolang.impl.Value.addRef;

import com.google.common.collect.ImmutableMap;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.retrolang.impl.BuiltinMethod.AnyFn;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.core.ArrayCore;
import org.retrolang.impl.core.CollectionCore;
import org.retrolang.impl.core.LoopCore;
import org.retrolang.impl.core.MatrixCore;
import org.retrolang.impl.core.NumberCore;
import org.retrolang.impl.core.RangeCore;
import org.retrolang.impl.core.ReducerCore;
import org.retrolang.impl.core.SaveCore;
import org.retrolang.impl.core.StructCore;
import org.retrolang.util.StringUtil;

/**
 * The Core module defines the types and functions that are available to all Retrospect programs;
 * see library_reference.md for details. This statics-only class defines the Core instance of
 * VmModule and an initial subset of its types and functions. Additional types and functions are
 * defined in other classes that are listed in the static constructor near the end of this file.
 */
public class Core {

  /** Types and Functions annotated with @Public are visible to Retrospect code. */
  @Target(ElementType.FIELD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Public {}

  /**
   * Types and Functions annotated with @Private are only accessible from within the VM
   * implementation.
   */
  @Target(ElementType.FIELD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Private {}

  /**
   * Java methods annotated with @Method("<i>signature</i>") define a Retrospect method for a Core
   * function.
   *
   * <p>If the method has return type {@code Value} it is implicitly declared {@code @RC.Out}.
   */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  public @interface Method {
    String value();
  }

  /**
   * The Vm.Module known as "Core".
   *
   * <p>This field should only be used for operations (such as defining types) that do not require
   * an initialized module. If you need to reference the fully-initialized module use {@link
   * #core()}.
   */
  static final VmModule CORE;

  static {
    // We don't use the usual ModuleBuilder methods to set up Core, but we still need an associated
    // ModuleBuilder when adding methods to functions.
    //
    // TODO(mdixon): Once everything settles down, re-evaluate whether it would be feasible to use
    // ModuleBuilder to assemble Core.
    CORE = new ModuleBuilder().module;
  }

  /**
   * The Vm.Module known as "Core", fully initialized. Calling this from a static initializer is
   * likely to result in NullPointerExceptions.
   */
  public static VmModule core() {
    return Initializer.INITIALIZED_CORE;
  }

  // statics only
  private Core() {}

  /** Returns a new Core BaseType with the given name, size, and super types. */
  public static BaseType.Named newBaseType(String name, int size, VmType.Union... superTypes) {
    return new BaseType.Named(CORE, name, size, superTypes);
  }

  /** Returns a new Core Singleton with the given name and super types. */
  public static Singleton newSingleton(String name, VmType.Union... superTypes) {
    return new BaseType.Named(CORE, name, 0, superTypes).asValue();
  }

  /**
   * Returns a new Core union type with the given name and super types. This type will not be open,
   * so only the VM can add additional subtypes to it.
   */
  public static VmType.Union newUnion(String name, VmType.Union... superTypes) {
    return new VmType.Union(CORE, name, false, superTypes);
  }

  /** Returns a new Core open union type with the given name and super types. */
  public static VmType.Union newOpenUnion(String name, VmType.Union... superTypes) {
    return new VmType.Union(CORE, name, true, superTypes);
  }

  @Public public static final VmType.Union BOOLEAN = newUnion("Boolean");
  @Public public static final Singleton FALSE = newSingleton("False", BOOLEAN);
  @Public public static final Singleton TRUE = newSingleton("True", BOOLEAN);

  /** Returns TRUE or FALSE. */
  public static Singleton bool(boolean value) {
    return value ? TRUE : FALSE;
  }

  @Public public static final Singleton NONE = newSingleton("None");
  @Public public static final Singleton ABSENT = newSingleton("Absent");

  /** Used in stack entries as the value of undefined variables. */
  @Private
  public static final Singleton UNDEF =
      new BaseType.Named(CORE, "Undef", 0, BaseType.SORT_ORDER_UNDEF).asValue();

  /**
   * Used as the value of an element that will be replaced later; see
   * docs/ref_counts.md#the-startupdate-idiom-for-compound-values for details.
   */
  @Private
  public static final Singleton TO_BE_SET =
      new BaseType.Named(CORE, "ToBeSet", 0, BaseType.SORT_ORDER_TO_BE_SET).asValue();

  @Public public static final VmType.Union LAMBDA = newOpenUnion("Lambda");
  @Public public static final VmType.Union LOOP = newOpenUnion("Loop");
  @Public public static final VmType.Union COLLECTION = newOpenUnion("Collection", LAMBDA);
  @Public public static final VmType.Union MATRIX = newOpenUnion("Matrix", COLLECTION);

  @Public // exitValue
  public static final BaseType.Named LOOP_EXIT = newBaseType("LoopExit", 1);

  @Public // min, max (each Integer or None)
  public static final BaseType.Named RANGE = newBaseType("Range", 2, MATRIX);

  /**
   * Retrospect structs are implemented as a pair of two arrays, the first containing keys in sorted
   * order, the second containing the corresponding values.
   */
  @Public // keys, values
  public static final BaseType.Named STRUCT =
      new BaseType.Named(CORE, "Struct", 2, COLLECTION) {
        @Override
        String compositionalToString(Value v) {
          return StructCompound.toString(v);
        }
      };

  /**
   * U32 is a compound type that wraps an Integer and interprets it as unsigned.
   *
   * <p>Rather than provide methods for {@code add()}, {@code multiply()}, etc., over U32 values,
   * U32s use specialized functions ({@code uAdd()}, {@code uMultiply()}, etc.) to make it clear
   * that these operations return multiple results (typically a pair {@code {low, high}} of U32s, to
   * handle overflow).
   */
  @Public
  public static final BaseType.Named U32 =
      new BaseType.Named(CORE, "U32", 1) {
        @Override
        String toString(IntFunction<Object> elements) {
          Object element = elements.apply(0);
          if (element instanceof NumValue.I nv) {
            return "0x" + Integer.toHexString(nv.value);
          } else {
            return super.toString(elements);
          }
        }
      };

  @Public
  public static final BaseType.NonCompositional STRING =
      new BaseType.NonCompositional(CORE, "String");

  @Public
  public static final BaseType.NonCompositional NUMBER =
      new BaseType.NonCompositional(CORE, "Number", BaseType.SORT_ORDER_NUM);

  @Public
  public static final VmType INTEGER =
      new VmType(CORE, "Integer") {
        @Override
        boolean contains(BaseType type) {
          // If you're asking whether INTEGER.contains(NUMBER) you're probably try to check
          // numValue.isa(INTEGER), and that's not going to work.
          assert type != NUMBER;
          return false;
        }
      };

  @Public
  public static final VmType ARRAY =
      new VmType(CORE, "Array", MATRIX) {
        @Override
        boolean contains(BaseType type) {
          return type.sortOrder == BaseType.SORT_ORDER_ARRAY;
        }
      };

  /**
   * One instance of FixedArrayType is created for each array size that we represent as a compound
   * value. Note that large arrays will use a different representation, so we expect there to be a
   * relatively small number of these (maybe 20 at most?).
   */
  public static class FixedArrayType extends BaseType {

    /** An implementation of Vm.Compound that will be returned by VirtualMachine.arrayOfSize(). */
    final VmCompound compound;

    private FixedArrayType(int size) {
      super(size, BaseType.SORT_ORDER_ARRAY);
      compound = new VmCompound(this);
    }

    @Override
    VmType vmType() {
      return ARRAY;
    }

    @Override
    String toString(IntFunction<Object> elements) {
      //  "[a, b, c]" looks better than "array3(a, b, c)"
      return StringUtil.joinElements("[", "]", size(), elements);
    }

    @Override
    public String toString() {
      return (size() == 0) ? "[]" : "array" + size();
    }

    /**
     * We allocate an initial set of FixedArrayType instances statically, and any additional ones
     * on-demand.
     *
     * <p>It would make sense for this to be more like 16 (to avoid synchronization in the common
     * cases), but I'm leaving it low for now to ensure that we test the {@link #moreTypes} code
     * path.
     */
    private static final int INITIAL_LIMIT = 4;

    private static final FixedArrayType[] initialTypes = new FixedArrayType[INITIAL_LIMIT];
    private static final List<FixedArrayType> moreTypes = new ArrayList<>();

    static {
      Arrays.setAll(initialTypes, FixedArrayType::new);
    }

    /** Returns the FixedArrayType with the given size. */
    public static FixedArrayType withSize(int size) {
      if (size < INITIAL_LIMIT) {
        return initialTypes[size];
      }
      int index = size - INITIAL_LIMIT;
      synchronized (moreTypes) {
        for (int n = moreTypes.size(); n <= index; n++) {
          moreTypes.add(new FixedArrayType(n + INITIAL_LIMIT));
        }
        return moreTypes.get(index);
      }
    }
  }

  public static final Singleton EMPTY_ARRAY = FixedArrayType.withSize(0).asValue();

  /** Variable-length arrays. */
  @Private
  public static final BaseType VARRAY =
      new BaseType(-1, BaseType.SORT_ORDER_ARRAY) {
        @Override
        VmType vmType() {
          return ARRAY;
        }

        @Override
        public String toString() {
          return "VArray";
        }
      };

  /**
   * {@code private compound CurriedLambda is Lambda}
   *
   * <p>Elements are {@code lambda}, {@code args}, {@code values}.
   */
  @Private
  public static final BaseType.Named CURRIED_LAMBDA =
      Core.newBaseType("CurriedLambda", 3, Core.LAMBDA);

  /**
   * {@code private compound StructLambda is Lambda}
   *
   * <p>Element is an array of keys suitable as the first element of a STRUCT.
   */
  @Private
  static final BaseType.Named STRUCT_LAMBDA = Core.newBaseType("StructLambda", 1, Core.LAMBDA);

  /**
   * A private union that contains baseTypes created by the core for e.g. {@link VmType#testLambda}
   * and {@link VmCompound#asLambdaExpr}.
   */
  @Private static final VmType.Union CORE_LAMBDA = newUnion("CoreLambda", LAMBDA);

  /**
   * A private union that contains baseTypes created by the core for {@link
   * VmFunction#asLambdaExpr}.
   */
  @Private static final VmType.Union FUNCTION_LAMBDA = newUnion("FunctionLambda", LAMBDA);

  @Public static final VmFunctionBuilder not = VmFunctionBuilder.create("not", 1);

  @Public static final VmFunctionBuilder at = VmFunctionBuilder.create("at", 2).isOpen();

  @Public
  static final VmFunctionBuilder binaryUpdate =
      VmFunctionBuilder.create("binaryUpdate", 3).hasInoutArg(0).hasNoResult().isOpen();

  @Public
  static final VmFunctionBuilder replaceElement =
      VmFunctionBuilder.create("replaceElement", 3).isOpen();

  @Public
  static final VmFunctionBuilder startUpdate =
      VmFunctionBuilder.create("startUpdate", 2).hasInoutArg(0).isOpen();

  @Public public static final VmFunctionBuilder add = VmFunctionBuilder.create("add", 2).isOpen();

  @Public
  public static final VmFunctionBuilder subtract = VmFunctionBuilder.create("subtract", 2).isOpen();

  @Public
  public static final VmFunctionBuilder multiply = VmFunctionBuilder.create("multiply", 2).isOpen();

  @Public
  public static final VmFunctionBuilder divide = VmFunctionBuilder.create("divide", 2).isOpen();

  @Public static final VmFunctionBuilder div = VmFunctionBuilder.create("div", 2).isOpen();

  @Public
  public static final VmFunctionBuilder modulo = VmFunctionBuilder.create("modulo", 2).isOpen();

  @Public
  public static final VmFunctionBuilder exponent = VmFunctionBuilder.create("exponent", 2).isOpen();

  @Public static final VmFunctionBuilder pipe = VmFunctionBuilder.create("pipe", 2).isOpen();

  @Public static final VmFunctionBuilder equal = VmFunctionBuilder.create("equal", 2).isOpen();

  @Public
  public static final VmFunctionBuilder lessThan = VmFunctionBuilder.create("lessThan", 2).isOpen();

  @Public static final VmFunctionBuilder keys = VmFunctionBuilder.create("keys", 1).isOpen();

  @Public static final VmFunctionBuilder size = VmFunctionBuilder.create("size", 1).isOpen();

  @Public static final VmFunctionBuilder sizes = VmFunctionBuilder.create("sizes", 1).isOpen();

  @Public static final VmFunctionBuilder min1 = VmFunctionBuilder.create("min", 1).isOpen();

  @Public static final VmFunctionBuilder max1 = VmFunctionBuilder.create("max", 1).isOpen();

  @Public static final VmFunctionBuilder min2 = VmFunctionBuilder.create("min", 2).isOpen();

  @Public static final VmFunctionBuilder max2 = VmFunctionBuilder.create("max", 2).isOpen();

  @Public static final VmFunctionBuilder new1 = VmFunctionBuilder.create("new", 1);

  @Public static final VmFunctionBuilder new2 = VmFunctionBuilder.create("new", 2).isOpen();

  @Public static final VmFunctionBuilder curryLambda = VmFunctionBuilder.create("curryLambda", 3);

  @Core.Method("not(Boolean)")
  public static Value not(@RC.Singleton Value in) {
    if (in instanceof RValue) {
      return in.is(FALSE).asValue();
    } else {
      return bool(in == FALSE);
    }
  }

  /**
   * A subclass of BaseType that implements {@code at()}. Useful for lambdas whose behavior is
   * determined by an object that's not a Value (e.g. a VmFunction or a VmType), and hence can't
   * just be an element of a compositional basetype.
   */
  abstract static class CoreLambda extends BaseType {
    CoreLambda(int size) {
      super(size);
    }

    @Override
    VmType vmType() {
      return CORE_LAMBDA;
    }

    abstract void at(TState tstate, Value lambda, @RC.In Value arg) throws BuiltinException;
  }

  @Core.Method("at(CoreLambda, _)")
  static void atCoreLambda(TState tstate, Value lambda, @RC.In Value arg) throws BuiltinException {
    ((CoreLambda) lambda.baseType()).at(tstate, lambda, arg);
  }

  @Core.Method("at(FunctionLambda, _)")
  static void atFunctionLambda(TState tstate, Value lambda, @RC.In Value arg, @AnyFn Caller caller)
      throws BuiltinException {
    ((VmFunction.AsLambdaExpr) lambda.baseType()).at(tstate, arg, caller);
  }

  /**
   * <pre>
   * method curryLambda(Lambda lambda, Array args, Array values) =
   *     CurriedLambda({lambda, args, values})
   * </pre>
   */
  @Core.Method("curryLambda(Lambda, Array, Array)")
  static Value curryLambda(
      TState tstate, @RC.In Value lambda, @RC.In Value args, @RC.In Value values)
      throws BuiltinException {
    int argsSize = args.numElements();
    int valuesSize = values.numElements();
    if (IntStream.range(0, argsSize)
        .allMatch(i -> isValidCurryArg(args.peekElement(i), valuesSize))) {
      return tstate.compound(CURRIED_LAMBDA, lambda, args, values);
    } else {
      throw Err.INVALID_ARGUMENT.asException();
    }
  }

  /** Returns true if arg is an integer and is not less than -valuesSize. */
  private static boolean isValidCurryArg(Value arg, int valuesSize) {
    if (!NumValue.isInt(arg)) {
      return false;
    }
    int i = NumValue.asInt(arg);
    return i >= -valuesSize;
  }

  /**
   * <pre>
   * method at(CurriedLambda curried, x) {
   *   x = curried_.args | i -&gt; i == 0 ? x : i &lt; 0 ? curried_.values[-i] : x[i] | save
   *   return curried_.lambda @ x
   * }
   * </pre>
   */
  @Core.Method("at(CurriedLambda, _)")
  static void atCurried(TState tstate, Value curried, Value x, @Fn("at:2") Caller at)
      throws BuiltinException {
    Value lambda = curried.element(0);
    Value args = curried.peekElement(1);
    Value values = curried.peekElement(2);
    int argsSize = args.numElements();
    @RC.Counted Object[] newArgs = tstate.allocObjectArray(argsSize);
    for (int i = 0; i < argsSize; i++) {
      int intArg = args.elementAsInt(i);
      Value v;
      if (intArg == 0) {
        v = addRef(x);
      } else if (intArg < 0) {
        v = values.element(-(intArg + 1));
      } else if (x.baseType().isArray() && x.numElements() >= intArg) {
        v = x.element(intArg - 1);
      } else {
        tstate.dropValue(lambda);
        tstate.dropReference(newArgs);
        throw Err.INVALID_ARGUMENT.asException();
      }
      newArgs[i] = v;
    }
    tstate.startCall(at, lambda, tstate.asArrayValue(newArgs, argsSize));
  }

  /**
   * If {@code structLambda} has a single key, returns a struct with the given arg as its value.
   * Otherwise {@code arg} must be an array with length matching the number of keys.
   */
  @Core.Method("at(StructLambda, _)")
  static Value atStruct(TState tstate, Value structLambda, @RC.In Value arg)
      throws BuiltinException {
    Value keys = structLambda.element(0);
    int nKeys = keys.numElements();
    if (nKeys == 1) {
      return tstate.compound(STRUCT, keys, tstate.arrayValue(arg));
    }
    tstate.dropOnThrow(keys);
    Err.INVALID_ARGUMENT.unless(arg.isArrayOfLength(nKeys));
    return tstate.compound(STRUCT, keys, arg);
  }

  /**
   * <pre>
   * method min(x, y) default = y &lt; x ? y : x
   * </pre>
   */
  static class Min2 extends BuiltinMethod {
    static final Caller lessThan = new Caller("lessThan:2", "afterLessThan");

    @Core.Method("min(_, _) default")
    static void begin(TState tstate, @RC.In Value x, @RC.In Value y) {
      tstate.startCall(lessThan, addRef(y), addRef(x)).saving(x, y);
    }

    @Continuation
    static Value afterLessThan(Value yLess, @Saved Value x, Value y) throws BuiltinException {
      return addRef(testBoolean(yLess) ? y : x);
    }
  }

  /**
   * <pre>
   * method max(x, y) default = x &lt; y ? y : x
   * </pre>
   */
  static class Max2 extends BuiltinMethod {
    static final Caller lessThan = new Caller("lessThan:2", "afterLessThan");

    @Core.Method("max(_, _) default")
    static void begin(TState tstate, @RC.In Value x, @RC.In Value y) {
      tstate.startCall(lessThan, addRef(x), addRef(y)).saving(x, y);
    }

    @Continuation
    static Value afterLessThan(Value xLess, @Saved Value x, Value y) throws BuiltinException {
      return addRef(testBoolean(xLess) ? y : x);
    }
  }

  /**
   * <pre>
   * method binaryUpdate(lhs=, Lambda lambda, rhs) default {
   *   lhs = lambda[lhs, rhs]
   * }
   * </pre>
   */
  @Core.Method("binaryUpdate(_, Lambda, _) default")
  static void binaryUpdateDefault(
      TState tstate,
      @RC.In Value lhs,
      @RC.In Value lambda,
      @RC.In Value rhs,
      @Fn("at:2") Caller at) {
    tstate.startCall(at, lambda, tstate.arrayValue(lhs, rhs));
  }

  /**
   * See https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom for why this is in a
   * nested class.
   */
  private static class Initializer {
    /** The same value as Core.CORE, but only set after the module has been fully initialized. */
    static final VmModule INITIALIZED_CORE;

    /**
     * {@code CORE.types} contains all of the publicly-visible types in Core; this map also includes
     * those that aren't public.
     */
    static final ImmutableMap<String, VmType> ALL_TYPES;

    /**
     * {@code CORE.functions} contains all of the publicly-visible functions in Core; this map also
     * includes those that aren't public.
     */
    static final ImmutableMap<String, VmFunction> ALL_FUNCTIONS;

    static {
      // Initialize the type and function maps by scanning these classes for static fields of type
      // VmType, Singleton, or VmFunction.
      Class<?>[] classes =
          new Class<?>[] {
            Core.class,
            StringValue.class,
            FutureValue.class,
            NumberCore.class,
            ArrayCore.class,
            LoopCore.class,
            ReducerCore.class,
            SaveCore.class,
            CollectionCore.class,
            MatrixCore.class,
            RangeCore.class,
            StructCore.class
          };
      Builder<VmType> typesBuilder = new Builder<>(t -> t.name);
      Builder<VmFunction> functionsBuilder =
          new Builder<>(fn -> VmFunction.key(fn.name, fn.numArgs));
      synchronized (CORE.builder()) {
        for (Class<?> klass : classes) {
          // VmTypes are just thrown in the map.
          typesBuilder.addAllFrom(klass, VmType.class, t -> t);
          // If we find a Singleton we extract the VmType from it.
          typesBuilder.addAllFrom(klass, Singleton.class, Singleton::asType);
          // If we find a BaseType we extract the VmType from it.
          typesBuilder.addAllFrom(klass, BaseType.Named.class, bt -> bt.asType);
          functionsBuilder.addAllFrom(
              klass, VmFunctionBuilder.class, builder -> builder.build(CORE));
        }
        ALL_TYPES = typesBuilder.allItems.buildOrThrow();
        ALL_FUNCTIONS = functionsBuilder.allItems.buildOrThrow();
        for (Class<?> klass : classes) {
          BuiltinSupport.addMethodsFrom(klass, ALL_TYPES, ALL_FUNCTIONS);
        }
        // Since we don't use the ModuleBuilder for CORE, we need to do the same finalization its
        // build() method would have done.
        for (VmFunction fn : ALL_FUNCTIONS.values()) {
          if (fn instanceof VmFunction.General generalFn) {
            generalFn.finalizeMethods();
          }
        }
        ALL_TYPES.values().forEach(VmType::finalizeType);
      }
      CORE.initialize(
          typesBuilder.publicItems.buildOrThrow(), functionsBuilder.publicItems.buildOrThrow());
      INITIALIZED_CORE = CORE;
    }
  }

  /**
   * Used during initialization to build two maps (allItems and publicItems) with values of the
   * given type.
   */
  private static class Builder<T> {
    /** Returns the key that should be used for storing the given value. */
    final Function<T, String> nameFn;

    final ImmutableMap.Builder<String, T> allItems = ImmutableMap.builder();
    final ImmutableMap.Builder<String, T> publicItems = ImmutableMap.builder();

    Builder(Function<T, String> nameFn) {
      this.nameFn = nameFn;
    }

    /**
     * Scans {@code klass} for static fields of type {@code ofClass} that are annotated with
     * either @{@link Public} or @{@link Private}. For each field found, calls {@code extract} with
     * the field's value to get the value to be saved.
     */
    <T2> void addAllFrom(Class<?> klass, Class<T2> ofClass, Function<T2, T> extract) {
      for (Field f : klass.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers()) && ofClass.isAssignableFrom(f.getType())) {
          boolean isPublic = f.isAnnotationPresent(Public.class);
          boolean isPrivate = f.isAnnotationPresent(Private.class);
          if (isPublic || isPrivate) {
            assert !(isPublic && isPrivate);
            f.setAccessible(true);
            T2 fValue;
            try {
              fValue = ofClass.cast(f.get(null));
            } catch (IllegalAccessException e) {
              throw new AssertionError(e);
            }
            T extracted = extract.apply(fValue);
            String name = nameFn.apply(extracted);
            allItems.put(name, extracted);
            if (isPublic) {
              publicItems.put(name, extracted);
            }
          }
        }
      }
    }
  }
}

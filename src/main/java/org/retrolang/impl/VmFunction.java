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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.retrolang.Vm;
import org.retrolang.impl.BaseType.SimpleStackEntryType;
import org.retrolang.impl.BaseType.StackEntryType;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.util.Bits;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/** Implements Vm.Function. */
public abstract class VmFunction implements Vm.Function {

  /**
   * We look up functions by (name, numArgs) pairs; the easiest way to turn that pair into a single
   * Java object with appropriate equals() and hashCode() is to make a String out of them.
   */
  static String key(String name, int numArgs) {
    return name + ":" + numArgs;
  }

  final String name;
  final int numArgs;
  final Bits inout;
  final int numResults;
  final Singleton asLambdaExpr;

  VmFunction(String name, int numArgs, Bits inout, int numResults) {
    this.name = name;
    this.numArgs = numArgs;
    this.inout = inout;
    this.numResults = numResults;
    this.asLambdaExpr =
        (numResults == 1 && inout.isEmpty()) ? new AsLambdaExpr(this).asValue() : null;
  }

  @Override
  public int numArgs() {
    return numArgs;
  }

  @Override
  public boolean argIsInout(int i) {
    return inout.test(i);
  }

  @Override
  public int numResults() {
    return numResults;
  }

  @Override
  public Singleton asLambdaExpr() {
    return asLambdaExpr;
  }

  @Override
  public String toString() {
    return key(name, numArgs);
  }

  /**
   * A singleton BaseType that can be passed as the first argument to {@code at()} to execute the
   * given VmFunction. Only created for functions with one result and no inouts. Also used as the
   * StackEntryType for the tail calls made by {@code at()}.
   */
  static class AsLambdaExpr extends StackEntryType {
    final VmFunction function;

    AsLambdaExpr(VmFunction function) {
      super(0);
      this.function = function;
    }

    @Override
    VmType vmType() {
      return Core.FUNCTION_LAMBDA;
    }

    void at(TState tstate, @RC.In Value arg, BuiltinMethod.Caller caller) throws BuiltinException {
      int numArgs = function.numArgs;
      // If numArgs != 1, arg must be an array of that length
      if (numArgs != 1) {
        Err.INVALID_ARGUMENT.unless(arg.isArrayOfLength(numArgs));
      }
      Object[] args = tstate.allocObjectArray(numArgs);
      if (numArgs == 1) {
        args[0] = arg;
      } else {
        for (int i = 0; i < numArgs; i++) {
          args[i] = arg.element(i);
        }
        tstate.dropValue(arg);
      }
      tstate.startCall(caller, function, this, args);
    }

    @Override
    String localName(int i) {
      // Tail call, so no locals
      throw new AssertionError();
    }

    @Override
    VmFunction called() {
      return function;
    }

    @Override
    void resume(TState tstate, @RC.In Value entry, ResultsInfo results, MethodMemo mMemo) {
      assert entry.baseType() == this;
      // Since this is a tail call we just return its results as our own.
      // We're responsible for calling dropValue(entry), but we know it's a singleton so that would
      // be a no-op.
    }

    @Override
    public String toString() {
      return String.format("`%s:%s`", function.name, function.numArgs);
    }
  }

  /** Thrown by {@link VmFunction#findMethod} if more than one method matches. */
  static class MultipleMethodsException extends Exception {
    final VmMethod method1;
    final VmMethod method2;

    MultipleMethodsException(VmMethod method1, VmMethod method2) {
      this.method1 = method1;
      this.method2 = method2;
    }
  }

  /**
   * Returns the method that should be used to execute this function with the given arguments, or
   * null if no method matches. Throws a {@link MultipleMethodsException} if there are multiple
   * matching methods.
   */
  abstract VmMethod findMethod(Object... args) throws MultipleMethodsException;

  /**
   * Executes this function with the given arguments.
   *
   * <p>The caller should assume that any call to {@code doCall()} has indirectly called {@link
   * TState#syncWithCoordinator()}; see its documentation for the implications of that.
   */
  void doCall(
      TState tstate,
      ResultsInfo results,
      MethodMemo callerMemo,
      CallSite callSite,
      @RC.In Object[] args) {
    // Verify that we properly cleaned up after the previous call.
    assert !tstate.hasDropOnThrow();
    // If someone's passing garbage, try to detect it sooner rather than later.
    assert Arrays.stream(args).allMatch(RefCounted::isValidForStore);
    VmMethod method;
    try {
      method = findMethod(args);
    } catch (MultipleMethodsException e) {
      Err.MULTIPLE_METHODS.pushUnwind(
          tstate,
          tstate.asArrayValue(args, numArgs),
          new StringValue(tstate, e.method1.toString()),
          new StringValue(tstate, e.method2.toString()));
      return;
    }
    if (method == null) {
      Err.NO_METHOD.pushUnwind(
          tstate,
          new StringValue(tstate, String.format("%s:%d", name, numArgs)),
          tstate.asArrayValue(args, numArgs));
      return;
    }
    MethodMemo calledMemo = callerMemo.memoForCall(tstate, callSite, method, args);
    if (calledMemo.isExlined()) {
      // Check to see if we have previously generated code for this method, and if so whether the
      // generated code accepts these args.  If it does, call the generated code instead of
      // executing the method directly.
      CodeGenLink cgLink = (CodeGenLink) calledMemo.extra();
      if (cgLink != null) {
        CodeGenTarget target = cgLink.ready();
        if (target != null) {
          Object[] preparedArgs = target.prepareArgs(tstate, args);
          if (preparedArgs != null) {
            tstate.dropReference(args);
            target.call(tstate, preparedArgs);
            return;
          }
        }
      }
    }
    results = callSite.adjustResultsInfo(results);
    method.impl.execute(tstate, results, calledMemo, args);
  }

  /**
   * Emits code to determine the appropriate method for the given arguments and call {@link
   * CodeGen#emitMethodCall}.
   */
  abstract void emitCall(
      CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, @RC.In Object[] args);

  /**
   * An implementation of VmFunction with a single method. The method is applicable to all argument
   * types, and no additional methods can be added for this function.
   */
  static class Simple extends VmFunction implements MethodImpl {

    interface SimpleMethodImpl {
      // Note that args is *not* @RC.In
      void executeSimpleMethod(TState tstate, Object[] args) throws BuiltinException;
    }

    final VmMethod method;
    final SimpleMethodImpl impl;
    // If executeSimpleMethod() throws a BuiltinException we need to push a stack entry
    final SimpleStackEntryType stackEntry;

    Simple(String name, int numResults, SimpleMethodImpl impl, String... argNames) {
      super(name, argNames.length, Bits.EMPTY, numResults);
      this.impl = impl;
      this.method =
          new VmMethod(
              this,
              /* predicate= */ null,
              /* isDefault= */ false,
              this,
              /* baseWeight= */ 1,
              /* memoFactory= */ null);
      this.stackEntry = new SimpleStackEntryType(name, argNames);
    }

    @Override
    VmMethod findMethod(Object... args) {
      return method;
    }

    @Override
    public void execute(
        TState tstate, ResultsInfo results, MethodMemo mMemo, @RC.In Object[] args) {
      try {
        impl.executeSimpleMethod(tstate, args);
      } catch (BuiltinException e) {
        e.push(tstate, stackEntry, args);
        return;
      }
      tstate.dropReference(args);
    }

    @Override
    public void emit(CodeGen codeGen, ResultsInfo results, MethodMemo mMemo, Object[] args) {
      try {
        impl.executeSimpleMethod(codeGen.tstate(), args);
      } catch (BuiltinException e) {
        codeGen.escape();
      }
    }

    @Override
    void emitCall(CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, Object[] args) {
      codeGen.emitMethodCall(this, null, args);
    }
  }

  /**
   * A distinct ArgKey is created for each argument position of each {@link General}, to serve as a
   * key when methods for that function are attached to types.
   */
  static class ArgKey {}

  private static final VarHandle ARG_KEY_ARRAY_ELEMENT =
      MethodHandles.arrayElementVarHandle(ArgKey[].class);

  /**
   * A full implementation of VmFunction. Multiple methods may be defined, each with its own
   * MethodPredicate and "default" flag.
   */
  static class General extends VmFunction {
    /** The module in which this function was defined. */
    final VmModule module;

    /** Methods for this function may be defined by other modules only if {@code open} is true. */
    final boolean open;

    /**
     * While this function's module is being built, an ArrayList with all the methods defined for
     * it. When the module is completed ({@link ModuleBuilder#build}), some of the methods may be
     * moved to an arg type, and any remaining methods are left here in an ImmutableList. If there
     * are no remaining methods this is set to null.
     *
     * <p>This field is immutable after the module is built.
     */
    private List<VmMethod> methods = new ArrayList<>();

    /**
     * If this function is open or finalizeMethods() decided to attach some of this module's methods
     * to types, an ArgKey for each argument position. The ArgKeys are created lazily, so each
     * position will only be non-null if at least one method is attached to an argument type at that
     * position.
     */
    private ArgKey[] argKeys;

    General(VmModule module, String name, int numArgs, Bits inout, int numResults, boolean open) {
      super(name, numArgs, inout, numResults);
      this.module = module;
      this.open = open;
    }

    @Override
    @Nullable VmMethod findMethod(Object... args) throws MultipleMethodsException {
      // First look for a preferred method...
      VmMethod m = findMethod(args, false);
      // ... and if that fails, look for a default method.
      return (m != null) ? m : findMethod(args, true);
    }

    /**
     * Returns the method that should be used to execute this function with the given arguments, or
     * null if no method matches. Only considers methods that are (isDefault=true) or are not
     * (isDefault=false) marked as "default". Throws a {@link MultipleMethodsException} if there are
     * multiple matching methods.
     */
    private VmMethod findMethod(Object[] args, boolean isDefault) throws MultipleMethodsException {
      // First check for methods attached to the function.
      VmMethod result = findMethod(null, (ImmutableList<VmMethod>) methods, args, isDefault);
      // Reading argKeys without synchronization might seem suspect but it should be safe; the only
      // changes that are made to it are monotonic (replacing a null element with an ArgKey), and
      // it's OK if we don't see changes made by a thread that hasn't synchronized with us (since
      // we couldn't encounter any of its types anyway).
      if (argKeys != null) {
        // Check for methods attached to an argument type.
        for (int i = 0; i < argKeys.length; i++) {
          ArgKey argKey = argKeys[i];
          if (argKey == null) {
            continue;
          }
          VmType argType = ((Value) args[i]).baseType().vmType();
          result = findMethod(result, argType.getMethods(argKey), args, isDefault);
          for (VmType superType : argType.superTypes.asSet) {
            result = findMethod(result, superType.getMethods(argKey), args, isDefault);
          }
        }
      }
      return result;
    }

    /**
     * Checks each of the methods in the given list, looking for one that (a) has the default flag
     * as specified, and (b) is applicable to the given arguments.
     *
     * <p>If no matching method is found (or the list is null), return {@code prior}.
     *
     * <p>If one matching method is found and {@code prior} was null, return the matching method.
     *
     * <p>Otherwise (a matching method is found with a prior, or more than one matching method is
     * found) throw a {@link MultipleMethodsException}.
     */
    private static VmMethod findMethod(
        VmMethod prior, ImmutableList<VmMethod> methods, Object[] args, boolean isDefault)
        throws MultipleMethodsException {
      if (methods != null) {
        for (VmMethod m : methods) {
          if (m.isDefault == isDefault && m.predicate.test(args).asBoolean()) {
            if (prior == null) {
              prior = m;
            } else if (prior != m) {
              throw new MultipleMethodsException(prior, m);
            }
          }
        }
      }
      return prior;
    }

    @Override
    void emitCall(CodeGen codeGen, MethodMemo callerMemo, CallSite callSite, Object[] args) {
      // This is an incomplete version that (a) only handles calls that have resolved to a single
      // method, and (b) doesn't properly verify that no other method is applicable.

      // We don't need atomicity here, just an easy way to save something from inside the
      // forEachChild call.
      AtomicReference<MethodMemo> onlyMethod = new AtomicReference<>();
      synchronized (codeGen.tstate().scope().memoMerger) {
        CallMemo callMemo = callerMemo.memoForCall(callSite);
        if (callMemo != null) {
          callMemo.forEachChild(
              (mm, count) -> {
                assert onlyMethod.getPlain() == null;
                onlyMethod.setPlain(mm);
              });
        }
      }
      MethodMemo mm = onlyMethod.getPlain();
      if (mm == null) {
        codeGen.escape();
        return;
      }
      VmMethod method = mm.perMethod.method;
      // TODO: test method predicate
      if (codeGen.cb.nextIsReachable()) {
        codeGen.emitMethodCall(method.impl, mm, args);
      }
    }

    /** Add a method to this function. Only called while building this function's module. */
    void addMethod(VmMethod method) {
      assert Thread.holdsLock(module.builder());
      methods.add(method);
    }

    /** Called from ModuleBuilder.build(). */
    void finalizeMethods() {
      assert Thread.holdsLock(module.builder());
      if (open || methods.size() > 2) {
        // Attach as many methods to their arg types as we can.
        argKeys = new ArgKey[numArgs];
        boolean attachedSome = methods.removeIf(method -> canAttachToTypes(method, module));
        if (!(open || attachedSome)) {
          // There's no reason to keep an all-null array of ArgKeys for a non-open method.
          assert Arrays.stream(argKeys).allMatch(k -> k == null);
          argKeys = null;
        }
      }
      if (methods.isEmpty()) {
        // Setting methods to an empty ImmutableList would work as well, but findMethods is checking
        // for null anyway.
        methods = null;
      } else {
        methods = ImmutableList.copyOf(methods);
      }
    }

    /** Returns the ArgKey for the specified argument, creating it if needed. */
    ArgKey argKey(int index) {
      ArgKey result = argKeys[index];
      if (result != null) {
        return result;
      } else {
        // We could use synchronize here if it seemed simpler; this method is only called during
        // module building, so performance isn't critical.
        result = new ArgKey();
        ArgKey prev =
            (ArgKey) ARG_KEY_ARRAY_ELEMENT.compareAndExchange(argKeys, index, null, result);
        // If we raced and someone else set this element first, use the ArgKey they chose.
        return (prev == null) ? result : prev;
      }
    }

    /**
     * If the given method can be attached to types in the given module, does so and returns true;
     * otherwise returns false.
     *
     * <p>Called from {@link #finalizeMethods} with {@code typesModule} == {@link #module}, and from
     * {@link ModuleBuilder#addMethod} with {@code typesModule} != {@link #module}.
     */
    boolean canAttachToTypes(VmMethod method, VmModule typesModule) {
      assert Thread.holdsLock(typesModule.builder());
      ImmutableSet<MethodPredicate.Simple> argRestriction =
          method.predicate.asArgRestriction(typesModule);
      if (argRestriction == null) {
        return false;
      }
      assert !argRestriction.isEmpty();
      for (MethodPredicate.Simple restriction : argRestriction) {
        assert restriction.positive;
        restriction.type.addMethod(argKey(restriction.argIndex), method);
      }
      return true;
    }
  }
}

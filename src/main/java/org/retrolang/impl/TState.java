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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.impl.BaseType.StackEntryType;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinSupport.BuiltinImpl;
import org.retrolang.impl.BuiltinSupport.ContinuationMethod;
import org.retrolang.impl.Template.VarSource;
import org.retrolang.util.ArrayUtil;

/** A TState is a ThreadLocal that holds all the per-thread state of a computation. */
public final class TState extends MemoryHelper {

  /**
   * A MethodHandle of type `void <- (TState, Object[])` that calls {@link
   * #dropReference(Object[])}.
   */
  static final MethodHandle DROP_REFERENCE_OBJ_ARRAY =
      Handle.forMethod(MemoryHelper.class, "dropReference", Object[].class)
          .asType(MethodType.methodType(void.class, TState.class, Object[].class));

  static final Op DROP_VALUE_OP =
      Handle.opForMethod(MemoryHelper.class, "dropValue", Value.class).build();

  static final Op DROP_REFERENCE_OP =
      Handle.opForMethod(MemoryHelper.class, "dropReference", RefCounted.class).build();

  static final Op CLEAR_ARRAY_ELEMENTS_OP =
      Handle.opForMethod(MemoryHelper.class, "clearElements", Object[].class, int.class, int.class)
          .build();

  private static final ThreadLocal<TState> savedTState = new ThreadLocal<>();

  /**
   * Returns the current thread's TState. Threads should only call TState methods on their own
   * TState.
   */
  public static TState get() {
    TState result = savedTState.get();
    // We don't currently use ThreadLocal.withInitial(TState::new) because we want to be able to
    // check if this thread has a TState, but that may turn out to not be useful, in which case
    // this could be simpler.
    if (result == null) {
      result = new TState();
      savedTState.set(result);
    }
    return result;
  }

  /** Returns the current thread's TState, or null if it has none. */
  static TState getIfPresent() {
    return savedTState.get();
  }

  /**
   * Discards any TState that was previously associated with this Thread, and creates a new one.
   *
   * <p>Intended only for tests, which want to ensure that they are not affected by any
   * previously-interrupted tests that used the same thread.
   */
  @VisibleForTesting
  static TState resetAndGet() {
    TState result = new TState();
    savedTState.set(result);
    return result;
  }

  /**
   * Drops a reference to the given RefCounted, which was allocated by the given ResourceTracker.
   *
   * <p>This entry point is only used by methods like Vm.Value.close() which are invoked from
   * outside the VM; during normal operation the TState is already bound to the appropriate
   * ResourceTracker.
   */
  static void dropReferenceWithTracker(ResourceTracker tracker, @RC.In RefCounted obj) {
    assert tracker != null;
    TState tstate = get();
    ResourceTracker prev = tstate.bindTo(tracker);
    try {
      tstate.dropReference(obj);
    } finally {
      tstate.bindTo(prev);
    }
  }

  @CanIgnoreReturnValue
  @Override
  ResourceTracker bindTo(ResourceTracker tracker) {
    // Before we unbind a TState, make sure that we've cleaned out any references it's holding.
    if (tracker() != null) {
      discardDropOnThrow();
      clearResults();
      dropReference(stackHead);
      stackHead = null;
      dropReference(stackRest);
      stackRest = null;
      dropReference(builtinCallArgs);
      builtinCallArgs = null;
      dropReference(builtinContinuationArgs);
      builtinCallArgs = null;
      builtinCall = null;
      builtinContinuation = null;
      tracker().coordinator.removeActiveThread(this);
    }
    ResourceTracker result = super.bindTo(tracker);
    if (tracker != null) {
      tracker.coordinator.addActiveThread(this);
    }
    return result;
  }

  /**
   * Owned by the current ResourceTracker's Coordinator; used to cache this TState's last sync for
   * faster checks in {@link Coordinator#syncClock}.
   */
  int lastSync;

  /**
   * If {@code rThread} is non-null it will be periodically checked to see if it has been cancelled;
   * if so, any work this TState is doing will be discarded and the TState should be interrupted if
   * possible.
   */
  RThread rThread;

  /** Returns the Scope for the current computation. */
  public Scope scope() {
    return tracker().scope;
  }

  Frame.Replacement replace(Frame f) {
    return tracker().coordinator.replace(this, f);
  }

  /**
   * Synchronizes with the current ResourceTracker's Coordinator. Should be called periodically by
   * all active TStates.
   *
   * <p>Note that this method may cause existing Value references to become out-of-date; if you have
   * a Value in a local variable and directly or indirectly call this method, you should usually
   * call {@link Value#latest} before using it again.
   */
  void syncWithCoordinator() {
    tracker().coordinator.syncClock(this);
  }

  @Override
  public byte[] allocByteArray(int length) {
    // Values allocated during codegen are not reference counted
    return hasCodeGen() ? UNCOUNTED.allocByteArray(length) : super.allocByteArray(length);
  }

  @Override
  public Object[] allocObjectArray(int length) {
    // Values allocated during codegen are not reference counted
    return hasCodeGen() ? UNCOUNTED.allocObjectArray(length) : super.allocObjectArray(length);
  }

  @Override
  public void recordAlloc(RefCounted obj, long size) {
    if (hasCodeGen()) {
      // Values allocated during codegen are not reference counted
      UNCOUNTED.recordAlloc(obj, size);
    } else {
      super.recordAlloc(obj, size);
    }
  }

  @Override
  public void dropReference(RefCounted obj) {
    if (hasCodeGen()) {
      // Values manipulated during codegen should not be reference counted
      assert obj == null || !obj.isRefCounted();
    } else {
      super.dropReference(obj);
    }
  }

  @Override
  public void dropReference(byte[] bytes) {
    // Values manipulated during codegen should not be reference counted
    if (!hasCodeGen()) {
      super.dropReference(bytes);
    }
  }

  @Override
  public void dropReference(Object[] objs) {
    if (hasCodeGen()) {
      // Values manipulated during codegen should not be reference counted
      assert objs == null || !containsRefCounted(objs);
    } else {
      super.dropReference(objs);
    }
  }

  /** Returns true if the given array contains any reference-counted values. */
  private static boolean containsRefCounted(Object[] objs) {
    return Arrays.stream(objs)
        .anyMatch(
            x -> (x instanceof Object[] o2) ? containsRefCounted(o2) : RefCounted.isRefCounted(x));
  }

  @Override
  public void dropValue(Value value) {
    if (hasCodeGen()) {
      // Values manipulated during codegen should not be reference counted
      assert !RefCounted.isRefCounted(value);
    } else {
      super.dropValue(value);
    }
  }

  /**
   * Equivalent to {@code TState.get().dropReference(obj)}, but faster if the object isn't released.
   */
  public static void staticDropReference(@RC.In RefCounted obj) {
    if (obj != null && obj.dropRefInternal()) {
      get().dropped(obj);
    }
  }

  /**
   * RefCounted values and arrays on which we will call dropReference() if the current builtin
   * method step throws an exception.
   */
  private Object[] dropOnThrow = new Object[8];

  private int numDropOnThrow = 0;

  /** Queues a call to {@link #dropValue} if the current builtin method step throws an exception. */
  public void dropOnThrow(Value v) {
    if (RefCounted.isRefCounted(v)) {
      // Values manipulated during codegen should not be reference counted
      assert !hasCodeGen();
      dropOnThrowInternal(v);
    }
  }

  /**
   * Queues a call to {@link #dropReference} if the current builtin method step throws an exception.
   */
  public void dropOnThrow(byte[] array) {
    if (!(hasCodeGen() || array == null || array.length == 0)) {
      dropOnThrowInternal(array);
    }
  }

  /**
   * Queues a call to {@link #dropReference} if the current builtin method step throws an exception.
   */
  public void dropOnThrow(Object[] array) {
    if (!(hasCodeGen() || array == null || array.length == 0)) {
      dropOnThrowInternal(array);
    }
  }

  private void dropOnThrowInternal(Object x) {
    if (numDropOnThrow == dropOnThrow.length) {
      dropOnThrow = Arrays.copyOf(dropOnThrow, numDropOnThrow * 2);
    }
    dropOnThrow[numDropOnThrow++] = x;
  }

  /**
   * Executes all deferred drops queued by {@link #dropOnThrow}. Automatically called if a builtin
   * method's execution throws a BuiltinException.
   */
  void dropForThrow() {
    clearElements(dropOnThrow, 0, numDropOnThrow);
    numDropOnThrow = 0;
  }

  /**
   * Discards any pending drops queued by {@link #dropOnThrow}. Automatically called after each
   * successfully completed step of a builtin method.
   */
  void discardDropOnThrow() {
    Arrays.fill(dropOnThrow, 0, numDropOnThrow, null);
    numDropOnThrow = 0;
  }

  /** Returns true if a call to {@link #discardDropOnThrow} would do anything. */
  boolean hasDropOnThrow() {
    return numDropOnThrow != 0;
  }

  /** Allocates a new array with the given elements. */
  @RC.Out
  public Object[] array(@RC.In Value... elements) {
    if (hasCodeGen()) {
      // If we're generating code nothing is counted, so there's no reason to copy the array.
      return elements;
    }
    Object[] array = allocObjectArray(elements.length);
    System.arraycopy(elements, 0, array, 0, elements.length);
    return array;
  }

  /** Allocates a new CompoundValue with the given type and elements. */
  @RC.Out
  public Value compound(BaseType baseType, @RC.In Value... elements) {
    assert elements.length == baseType.size();
    return elements.length == 0 ? baseType.asValue() : asCompoundValue(baseType, array(elements));
  }

  /** Allocates a new array compound with the given elements. */
  @RC.Out
  public Value arrayValue(@RC.In Value... elements) {
    return compound(Core.FixedArrayType.withSize(elements.length), elements);
  }

  /** Converts a Java array of Values into a Retrospect array. */
  @RC.Out
  public Value asArrayValue(@RC.In Object[] elements, int size) {
    if (size == 0) {
      dropReference(elements);
      return Core.EMPTY_ARRAY;
    }
    return asCompoundValue(Core.FixedArrayType.withSize(size), elements);
  }

  /** Converts a Java array of Values into a Retrospect compound. */
  @RC.Out
  public Value asCompoundValue(BaseType baseType, @RC.In Object[] elements) {
    int size = baseType.size();
    assert size != 0 && Value.containsValues(elements, size);
    if (hasCodeGen() && Arrays.stream(elements, 0, size).anyMatch(e -> e instanceof RValue)) {
      Template[] elementTemplates = new Template[size];
      Arrays.setAll(elementTemplates, i -> RValue.toTemplate((Value) elements[i]));
      return RValue.fromTemplate(Template.Compound.of(baseType, elementTemplates));
    }
    return new CompoundValue(this, baseType, elements);
  }

  /**
   * Non-null if the current computation is unwinding its stack.
   *
   * <p>If non-null, {@link #stackRest} is non-null and {@code stackHead.stream()} includes {@code
   * stackRest}.
   */
  @RC.Counted private TStack stackHead;

  /**
   * If non-null, will be used as the {@code rest} of any stack entries created by the currently-
   * executing method.
   *
   * <p>If {@code stackRest} is non-null on return from a function call, {@code stackRest.isSet()}
   * will be false and the caller of the currently-executing function is responsible for populating
   * it with the call site.
   */
  @RC.Counted private TStack stackRest;

  /**
   * Begins unwinding the current stack. {@code blockingEntry} will be placed at the top of the
   * stack, and its {@link BaseType.BlockingEntryType#suspended} method will be called when stack
   * unwinding is complete.
   */
  void startBlock(@RC.In Value blockingEntry, ResultsInfo results, MethodMemo mMemo) {
    assert stackHead == null && blockingEntry.baseType() instanceof BaseType.BlockingEntryType;
    stackHead = new TStack(this);
    stackHead.set(blockingEntry, results, mMemo);
    stackRest = setTStackRest(stackHead, stackRest);
  }

  static final Op TAKE_STACK_REST_OP =
      Handle.opForMethod(TState.class, "takeStackRest").hasSideEffect().build();

  /**
   * Returns and clears {@link #stackRest}; called from generated code after an exlined method call.
   */
  @RC.Out
  TStack takeStackRest() {
    TStack result = stackRest;
    stackRest = null;
    assert result == null || !result.isSet();
    if (result != null && result.isNotShared()) {
      // Rare, but possible; e.g. we executed some trace instructions, but since then other
      // threads have displaced them.  Dropping the TStack that no one will ever see saves us
      // the expense of populating it all the way out.
      assert stackHead == null;
      dropReference(result);
      result = null;
    }
    return result;
  }

  /** Returns true if {@link #stackRest} is {@link TStack#BASE}; only intended for assertions. */
  boolean stackRestIsBase() {
    return stackRest == TStack.BASE;
  }

  // Since setStackRest(null) is a no-op we can sometimes simplify away these calls.
  static final Op SET_STACK_REST_OP =
      Handle.opForMethod(TState.class, "setStackRest", TStack.class)
          .withSimplifier((tstate, rest) -> CodeValue.NULL.equals(rest) ? CodeValue.NULL : null)
          .build();

  /** Sets {@link #stackRest}. Should only be called when {@link #stackRest} is null. */
  void setStackRest(@RC.In TStack rest) {
    assert stackRest == null;
    stackRest = rest;
  }

  /** True if the current computation is unwinding its stack. */
  boolean unwindStarted() {
    return stackHead != null;
  }

  /**
   * If the current computation was not already unwinding, begins unwinding. Pushes the given stack
   * entry on the top of the stack.
   */
  void pushUnwind(@RC.In Value entry) {
    if (stackRest == null) {
      stackRest = new TStack(this);
    }
    TStack prevHead = stackHead;
    if (prevHead == null) {
      prevHead = stackRest;
      prevHead.addRef();
    }
    stackHead = new TStack(this);
    stackHead.set(entry);
    stackHead.setRest(prevHead);
  }

  /**
   * Called after the current thread has finished unwinding; returns the stack and resets the unwind
   * state.
   */
  @RC.Out
  TStack takeUnwind() {
    assert stackHead != null;
    dropReference(stackRest);
    stackRest = null;
    TStack result = stackHead;
    stackHead = null;
    return result;
  }

  /**
   * Should be called before starting a function call.
   *
   * <p>To correctly populate stacks, each function call should follow this pattern:
   *
   * <pre>
   *   TStack prev = tstate.beforeCall();
   *   ...make function call...
   *   if (tstate.callEntryNeeded()) {
   *     tstate.afterCall(prev, ...call entry for just-completed call...);
   *     if (tstate.unwindStarted()) {
   *       ... abrupt return from currently executing instruction block ...
   *     }
   *   } else {
   *     tstate.afterCall(prev);
   *   }
   * </pre>
   */
  @RC.Out
  TStack beforeCall() {
    assert !unwindStarted();
    TStack result = stackRest;
    stackRest = null;
    return result;
  }

  /**
   * Returns true if a stack entry is needed for the just-completed function call; see {@link
   * #beforeCall}.
   */
  boolean callEntryNeeded() {
    assert stackRest == null || !stackRest.isSet();
    if (stackRest == null) {
      return false;
    } else if (stackRest.isNotShared()) {
      // Rare, but possible; e.g. we executed some trace instructions, but since then other
      // threads have displaced them.  Dropping the TStack that no one will ever see saves us
      // the expense of populating it all the way out.
      assert stackHead == null;
      dropReference(stackRest);
      stackRest = null;
      return false;
    }
    return true;
  }

  /** Should only be called if callEntryNeeded() returns true; see {@link #beforeCall}. */
  void afterCall(
      @RC.In TStack prev, @RC.In Value callEntry, ResultsInfo results, MethodMemo methodMemo) {
    assert stackRest != null;
    stackRest = fillStackEntry(stackRest, callEntry, results, methodMemo, prev);
  }

  /** Should only be called if callEntryNeeded() returns false; see {@link #beforeCall}. */
  void afterCall(@RC.In TStack prev) {
    assert stackRest == null;
    stackRest = prev;
  }

  /**
   * Sets {@code tstack}'s {@link TStack#rest()} to {@code rest}, or to a new TStack if {@code rest}
   * is null; returns {@code rest} or the new TStack.
   */
  @RC.Out
  TStack setTStackRest(TStack tstack, @RC.In TStack rest) {
    if (rest == null) {
      rest = new TStack(this);
    }
    rest.addRef();
    tstack.setRest(rest);
    return rest;
  }

  static final Op FILL_STACK_ENTRY_OP =
      Handle.opForMethod(
              TState.class,
              "fillStackEntry",
              TStack.class,
              Value.class,
              ResultsInfo.class,
              MethodMemo.class,
              TStack.class)
          .hasSideEffect()
          .build();

  /**
   * Adds an entry to a TStack, initialized from {@code first}, {@code results}, {@code methodMemo},
   * and {@code rest}. If {@code tstack} is non-null it is the entry that should be populated;
   * otherwise a new TStack is allocated and saved as {@link #stackHead}. If {@code rest} is null a
   * new TStack is allocated to be the {@link TStack#rest()}; the {@link TStack#rest()} is also
   * returned.
   */
  @RC.Out
  TStack fillStackEntry(
      @RC.In TStack tstack,
      @RC.In Value first,
      ResultsInfo results,
      MethodMemo methodMemo,
      @RC.In TStack rest) {
    TStack initTstack = tstack;
    if (tstack == null) {
      assert stackHead == null;
      tstack = new TStack(this);
      stackHead = tstack;
    }
    tstack.set(first, results, methodMemo);
    rest = setTStackRest(tstack, rest);
    if (initTstack == null) {
      // stackHead has the refCount
    } else {
      dropReference(tstack);
    }
    return rest;
  }

  static final Op TRACE_OP =
      Handle.opForMethod(TState.class, "trace", Instruction.Trace.class, Value.class, TStack.class)
          .hasSideEffect()
          .build();

  /** Saves the stack for a trace instruction. */
  void trace(Instruction.Trace inst, @RC.In Value head) {
    stackRest = trace(inst, head, stackRest);
  }

  /** Saves the stack for a trace instruction. */
  @RC.Out
  TStack trace(Instruction.Trace inst, @RC.In Value head, @RC.In TStack rest) {
    // Create a new stack ...
    TStack.ForTrace trace = new TStack.ForTrace(this, head);
    rest = setTStackRest(trace, rest);
    // ... and save it as one of the traces for the given instruction.
    SavedTraces traces = tracker().tracesFor(inst, this);
    TStack.ForTrace dropped = traces.add(this, trace, tracker().startNanoTime);
    // If that displaced a previously-saved stack, drop the old one.
    if (dropped != null) {
      dropReference(dropped);
    }
    return rest;
  }

  static final int INITIAL_FN_RESULTS_SIZE = 8;
  static final int INITIAL_FN_RESULT_BYTES_SIZE = 32;

  /**
   * If {@code fnResultTemplates} is null, method results are stored as Values in the initial
   * elements of {@link #fnResults}. If {@code fnResultTemplates} is non-null, there is one template
   * for each of the method's results; the values of its RefVars are stored in {@link #fnResults},
   * and the values of its NumVars are stored in {@link #fnResultBytes}.
   */
  private ImmutableList<Template> fnResultTemplates;

  /** Values returned by a method. The array is not counted, but its elements are. */
  @RC.Counted private Value[] fnResults = new Value[INITIAL_FN_RESULTS_SIZE];

  /** The numeric values returned by a method that uses {@link #fnResultTemplates}. */
  private byte[] fnResultBytes = new byte[INITIAL_FN_RESULT_BYTES_SIZE];

  /**
   * If {@link #fnResultTemplates} is non-null, this VarSource can be used to read the method's
   * results.
   */
  private final VarSource fnResultSource =
      new VarSource() {
        @Override
        public int getB(int index) {
          return ArrayUtil.bytesGetB(fnResultBytes, index);
        }

        @Override
        public int getI(int index) {
          return ArrayUtil.bytesGetIAtOffset(fnResultBytes, index);
        }

        @Override
        public double getD(int index) {
          return ArrayUtil.bytesGetDAtOffset(fnResultBytes, index);
        }

        @Override
        public Value getValue(int index) {
          return fnResults[index];
        }
      };

  /** Sets the result for the current function call. */
  public void setResult(@RC.In Value result) {
    assert allNull(fnResults)
        && fnResultTemplates == null
        && result != null
        && RefCounted.isValidForStore(result);
    if (hasCodeGen()) {
      codeGen.setResults(result);
    } else {
      fnResults[0] = result;
    }
  }

  /** Sets the results for the current function call. */
  public void setResults(@RC.In Value result1, @RC.In Value result2) {
    assert allNull(fnResults)
        && fnResultTemplates == null
        && result1 != null
        && result2 != null
        && RefCounted.isValidForStore(result1)
        && RefCounted.isValidForStore(result2);
    if (hasCodeGen()) {
      codeGen.setResults(result1, result2);
    } else {
      fnResults[0] = result1;
      fnResults[1] = result2;
    }
  }

  /** Sets the results for the current function call. */
  public void setResults(@RC.In Value... results) {
    assert fnResultTemplates == null
        && Arrays.stream(results).allMatch(x -> x != null && RefCounted.isValidForStore(x));
    if (hasCodeGen()) {
      codeGen.setResults(results);
    } else {
      System.arraycopy(results, 0, fnResults(results.length), 0, results.length);
    }
  }

  /** Sets the results for the current function call. */
  public void setResults(int numResults, @RC.In Object[] results) {
    assert !hasCodeGen()
        && fnResultTemplates == null
        && Value.containsValues(results, numResults)
        && Arrays.stream(results, 0, numResults).allMatch(RefCounted::isValidForStore);
    Value[] fnResults = fnResults(numResults);
    for (int i = 0; i < numResults; i++) {
      fnResults[i] = (Value) results[i];
      results[i] = null;
    }
    dropReference(results);
  }

  /**
   * Sets the results for the current function call to the elements of the given array or compound
   * value.
   */
  public void setResultsFromElements(int size, Value v) {
    assert fnResultTemplates == null;
    Value[] results = hasCodeGen() ? new Value[size] : fnResults(size);
    for (int i = 0; i < size; i++) {
      results[i] = v.element(i);
      assert RefCounted.isValidForStore(results[i]);
    }
    if (hasCodeGen()) {
      codeGen.setResults(results);
    }
  }

  /** Called after setting results, to harmonize them with {@link MethodMemo#resultsMemo}. */
  void harmonizeResults(MethodMemo memo) {
    assert fnResultTemplates == null;
    assert memo.perMethod == null
        || Value.containsValues(fnResults, memo.perMethod.method.function.numResults);
    memo.harmonizeResults(this, fnResults);
  }

  static final Op SET_RESULT_TEMPLATES_OP =
      Handle.opForMethod(TState.class, "setResultTemplates", ImmutableList.class).build();
  static final Op CHECK_EXLINED_RESULT_OP =
      Handle.opForMethod(TState.class, "checkExlinedResult", ImmutableList.class).build();
  static final Op CLEAR_RESULTS_OP = Handle.opForMethod(TState.class, "clearResults").build();
  static final Op CLEAR_RESULT_TEMPLATES_OP =
      Handle.opForMethod(TState.class, "clearResultTemplates").build();
  static final Op FN_RESULTS_OP = Handle.opForMethod(TState.class, "fnResults", int.class).build();
  static final Op FN_RESULT_BYTES_OP =
      Handle.opForMethod(TState.class, "fnResultBytes", int.class).build();
  static final Op FN_RESULT_OP = Handle.opForMethod(TState.class, "fnResult", int.class).build();

  /**
   * Sets the templates for the results of the current function call. The values of the RefVars and
   * NumVars should then be written to the arrays returned by {@link #fnResults} and {@link
   * #fnResultBytes}.
   */
  void setResultTemplates(ImmutableList<Template> templates) {
    assert !hasCodeGen() && allNull(fnResults) && fnResultTemplates == null && templates != null;
    fnResultTemplates = templates;
  }

  /** Retrieves the given function result; should only be called once for each result. */
  @RC.Out
  public Value takeResult(int index) {
    assert !hasCodeGen() && !unwindStarted();
    if (fnResultTemplates == null) {
      Value result = fnResults[index];
      assert result != null;
      fnResults[index] = null;
      return result;
    } else {
      return fnResultTemplates.get(index).getValue(this, fnResultSource);
    }
  }

  /**
   * If the just-completed method call escaped, resumes direct execution until it returns, errors,
   * or blocks. Returns true if it returned with results that use the given templates. Returns false
   * if it errored, blocked, or returned results in a different representation.
   */
  @SuppressWarnings("ReferenceEquality")
  boolean checkExlinedResult(ImmutableList<Template> expectedTemplates) {
    if (fnResultTemplates == expectedTemplates) {
      // Fast path
      assert !unwindStarted();
      return true;
    }
    while (unwindStarted()) {
      BaseType baseType = stackHead.first().baseType();
      if (baseType instanceof Err || baseType instanceof BaseType.BlockingEntryType) {
        return false;
      }
      // We were interrupted by an escape from generated code; resume execution from that point
      tracker().incrementEscaped();
      TStack head = stackHead;
      TStack rest = stackRest;
      stackHead = null;
      stackRest = null;
      resumeStack(head, rest);
      // After that returns, see what state the resumed execution left the stack in.
      if (stackRest == null && !rest.isNotShared()) {
        // It didn't unwind, but we still need stack entries from our caller for traces that were
        // done before the escape.
        stackRest = rest;
      } else {
        // The current stack state is fine.
        assert stackRest == null || stackRest == rest;
        dropReference(rest);
      }
      // Maybe in the end we got the results we were hoping for?
      if (fnResultTemplates == expectedTemplates) {
        assert !unwindStarted();
        return true;
      }
    }
    // We've got results, but in a different representation.
    assert (fnResultTemplates != null)
        ? fnResultTemplates.size() == expectedTemplates.size()
            && !fnResultTemplates.equals(expectedTemplates)
        : Value.containsValues(fnResults, expectedTemplates.size());
    return false;
  }

  /**
   * Discards any remaining saved function results; should always be called after the desired
   * results have been taken.
   */
  void clearResults() {
    fnResultTemplates = null;
    clearElements(fnResults, 0, fnResults.length);
  }

  /** A cheaper alternative to {@link #clearResults} when there were no pointers in the results. */
  void clearResultTemplates() {
    fnResultTemplates = null;
    assert allNull(fnResults);
  }

  /** Ensures that {@link #fnResults} has at least the given length, and returns it. */
  Value[] fnResults(int minSize) {
    assert allNull(fnResults);
    if (fnResults.length < minSize) {
      fnResults = new Value[chooseCapacityObjects(minSize)];
    }
    return fnResults;
  }

  /** Returns the specified element of {@link #fnResults}. */
  @RC.Out
  Value fnResult(int i) {
    return addRef(fnResults[i]);
  }

  /** Ensures that {@link #fnResultBytes} has at least the given length, and returns it. */
  byte[] fnResultBytes(int minSize) {
    if (fnResultBytes.length < minSize) {
      fnResultBytes = new byte[chooseCapacityBytes(minSize)];
    }
    return fnResultBytes;
  }

  /** Returns true if all elements of the given array are null. For assertions only. */
  private static boolean allNull(Object[] array) {
    return Arrays.stream(array).allMatch(x -> x == null);
  }

  /**
   * Returns true if the results state is appropriate for resuming a stack entry with the given
   * {@link StackEntryType#called}. For assertions only.
   */
  private boolean resultsStateMatches(VmFunction called) {
    // If we're resuming anywhere other than a function call, there should be no results; otherwise
    // the number of saved results should match the function that was called.
    if (called == null) {
      return allNull(fnResults) && fnResultTemplates == null;
    } else if (fnResultTemplates != null) {
      return fnResultTemplates.size() == called.numResults;
    } else {
      return Value.containsValues(fnResults, called.numResults);
    }
  }

  // Execution state for builtin methods
  // See the javadoc on finishBuiltin for how these are used.
  private Caller builtinCall;
  private VmFunction builtinCallFn;
  @RC.Counted private Object[] builtinCallArgs;
  private StackEntryType builtinDuringCall;
  private String builtinContinuation;
  @RC.Counted private Object[] builtinContinuationArgs;

  @CanIgnoreReturnValue
  public BuiltinMethod.Saver startCall(Caller caller, @RC.In Value... args) {
    assert args.length == caller.fn().numArgs;
    startCall(caller, caller.fn(), caller.duringCall(), array(args));
    return this::saveForCall;
  }

  public void startCall(
      Caller caller, VmFunction fn, StackEntryType duringCall, @RC.In Object[] args) {
    assert !unwindStarted();
    assert builtinCall == null && builtinCallArgs == null && builtinContinuationArgs == null;
    assert caller != null && fn != null && duringCall != null && args != null;
    if (hasCodeGen() && duringCall.size() == 0) {
      codeGen.emitCall(fn, args, caller.callSite(), caller, duringCall.asValue());
    } else {
      builtinCall = caller;
      builtinCallFn = fn;
      builtinDuringCall = duringCall;
      builtinCallArgs = args;
    }
  }

  private void saveForCall(@RC.In Value... values) {
    assert !unwindStarted();
    assert builtinContinuationArgs == null && values.length == builtinDuringCall.size();
    if (hasCodeGen()) {
      Caller caller = builtinCall;
      VmFunction fn = builtinCallFn;
      StackEntryType duringCall = builtinDuringCall;
      Object[] args = builtinCallArgs;
      builtinCall = null;
      builtinCallFn = null;
      builtinDuringCall = null;
      builtinCallArgs = null;
      codeGen.emitCall(fn, args, caller.callSite(), caller, asCompoundValue(duringCall, values));
    } else {
      // Allocate an array that will eventually hold the function results followed by these saved
      // values, and copy these values into the appropriate part of it.
      int numResults = builtinCallFn.numResults;
      builtinContinuationArgs = allocObjectArray(numResults + values.length);
      System.arraycopy(values, 0, builtinContinuationArgs, numResults, values.length);
    }
  }

  public void jump(String continuationName, @RC.In Value... args) {
    assert !unwindStarted();
    assert builtinCall == null && builtinCallArgs == null && builtinContinuationArgs == null;
    assert continuationName != null;
    if (hasCodeGen()) {
      codeGen.jump(continuationName, args);
    } else {
      builtinContinuation = continuationName;
      builtinContinuationArgs = array(args);
    }
  }

  /**
   * Called after a builtin method returns, to handle any call to {@link #startCall} or {@link
   * #jump}.
   *
   * <p>There are four possible valid states when a builtin method returns:
   *
   * <ul>
   *   <li>done: {@link #builtinCall}, {@link #builtinCallArgs}, {@link #builtinContinuation}, and
   *       {@link #builtinContinuationArgs} are all null, {@link #unwindStarted} is false, and
   *       {@link #setResults} has been called with the method's results.
   *   <li>unwinding: {@link #builtinCall}, {@link #builtinCallArgs}, {@link #builtinContinuation},
   *       and {@link #builtinContinuationArgs} are all null, {@link #unwindStarted} is true, and
   *       {@link #setResults} has not been called.
   *   <li>calling: {@link #builtinCall} is non-null and {@link #builtinCallArgs} contains {@code
   *       builtinCall.fn.numArgs} Values. Either {@link #builtinContinuationArgs} is null (no saved
   *       values were passed in the call) or it is an array with saved values at indices {@code
   *       builtinCall.fn.numResults} through {@code builtinCall.continuation.numArgs-1}. {@link
   *       #builtinCallFn} is the function that will be called, and {@link #builtinDuringCall} is
   *       the type that will be used if we need to construct a stack entry (its size must match the
   *       number of saved values). {@link #builtinContinuation} is null, {@link #unwindStarted} is
   *       false, and {@link #setResults} has not been called.
   *   <li>jumping: {@link #builtinCall} and {@link #builtinCallArgs} are null, {@link
   *       #builtinContinuation} and {@link #builtinContinuationArgs} are non-null, {@link
   *       #unwindStarted} is false, and {@link #setResults} has not been called.
   * </ul>
   *
   * <p>(See also the "five things a begin or continuation method must do when called" list at
   * docs/builtins.md#general-nested-function-calls; the first two leave the TState in state "done",
   * and each of the others corresponds to a different state in this list.)
   */
  void finishBuiltin(ResultsInfo results, MethodMemo mMemo, BuiltinImpl impl) {
    int previousOrder = 0;
    for (; ; ) {
      // If we get here we didn't throw an exception.
      discardDropOnThrow();
      if (builtinCall == null && builtinContinuation == null) {
        assert builtinCallArgs == null && builtinContinuationArgs == null;
        // done or unwinding
        if (!unwindStarted()) {
          harmonizeResults(mMemo);
        }
        return;
      }
      assert !unwindStarted();
      Caller caller = builtinCall;
      String continuationName = builtinContinuation;
      Object[] callArgs = builtinCallArgs;
      Object[] continuationArgs = builtinContinuationArgs;
      builtinCall = null;
      builtinContinuation = null;
      builtinCallArgs = null;
      builtinContinuationArgs = null;
      ContinuationMethod continuation;
      if (caller != null) {
        // saving() should have been called if and only if this caller has saved values
        assert (continuationArgs == null) == builtinDuringCall.isSingleton();
        // Save these fields before we start the function call, since it might change them
        VmFunction fn = builtinCallFn;
        StackEntryType duringCall = builtinDuringCall;
        continuation = caller.continuation();
        boolean isTailCall = (continuation == BuiltinMethod.TAIL_CALL);
        // If this isn't a tail call and the continuation isn't a loop, verify that they're
        // respecting the continuation order.
        assert isTailCall || continuation.checkCallFrom(previousOrder);
        TStack prev = beforeCall();
        ResultsInfo callResults = isTailCall ? results : continuation.valueMemo(this, mMemo);
        fn.doCall(this, callResults, mMemo, caller.callSite(), callArgs);
        // That function call might have indirectly called TState.syncWithCoordinator(), which
        // means any continuation args we've been saving might have been replaced; if so we should
        // update them before calling the continuation.
        int numArgs = continuation.numArgs();
        for (int i = fn.numResults; i < numArgs; i++) {
          // Note that calling Value.fromArray() here (and discarding its result) would *not* be
          // sufficient: that method assumes that the array is shared with other threads, and only
          // updates it to the latest value if the replacement has completed.  Here the array is
          // unshared, and we need to update it at least for any replacement that we've synced to,
          // since those could be completed at any moment.  (We'll actually update it even for
          // replacements since our last sync, but that's OK.)
          Value v = (Value) continuationArgs[i];
          Value latest = Value.latest(v);
          if (latest != v) {
            continuationArgs[i] = latest;
          }
        }
        if (callEntryNeeded()) {
          // Construct a stack entry with the saved values
          // Just another name for continuationArgs, so that we can refer to it in a lambda
          Object[] finalCA = continuationArgs;
          // If there are no saved values, duringCall is a singleton and it's OK that
          // continuationArgs is null.
          Value entry =
              CompoundValue.of(this, duringCall, i -> addRef((Value) finalCA[fn.numResults + i]));
          afterCall(prev, entry, results, mMemo);
          if (unwindStarted()) {
            dropReference(continuationArgs);
            return;
          }
        } else {
          afterCall(prev);
        }
        if (isTailCall) {
          // Just return these results as our own.
          return;
        } else if (continuationArgs == null) {
          // Since there were no saved values we haven't yet allocated the args array for the
          // continuation.
          continuationArgs = allocObjectArray(fn.numResults);
        }
        for (int i = 0; i < fn.numResults; i++) {
          continuationArgs[i] = takeResult(i);
        }
        clearResults();
      } else {
        continuation = impl.continuation(continuationName);
        Preconditions.checkArgument(
            continuation != null, "No continuation named \"%s\"", continuationName);
        assert continuation.checkCallFrom(previousOrder);
      }
      // This is a good place to check for out-of-memory.
      if (isOverMemoryLimit()) {
        StackEntryType entryType = continuation.builtinEntry.stackEntryType;
        Value stackEntry;
        if (entryType.isSingleton()) {
          stackEntry = entryType.asValue();
          dropReference(continuationArgs);
        } else {
          stackEntry = asCompoundValue(entryType, continuationArgs);
        }
        pushUnwind(stackEntry);
        pushUnwind(Err.OUT_OF_MEMORY.asValue());
        return;
      }
      // Run the continuation, and then loop back to see what state it left us in.
      runContinuation(continuation, continuationArgs, results, mMemo);
      previousOrder = continuation.order;
    }
  }

  /** Execute the given continuation. */
  void runContinuation(
      ContinuationMethod continuation,
      Object[] continuationArgs,
      ResultsInfo results,
      MethodMemo mMemo) {
    continuation.valueMemo(this, mMemo).harmonizeAll(this, continuationArgs, false);
    continuation.builtinEntry.execute(this, results, mMemo, continuationArgs);
  }

  /**
   * Resumes execution of the given stack; continues until it reaches {@code base} or begins
   * unwinding.
   */
  void resumeStack(@RC.In TStack stack, TStack base) {
    for (; ; ) {
      // Pop the top entry off the stack, saving its rest as the TState's stackRest and the
      // other fields in local variables.
      Value first = Value.addRef(stack.first());
      TStack rest = stack.rest();
      rest.addRef();
      setStackRest(rest);
      ResultsInfo results = stack.results();
      MethodMemo methodMemo = stack.methodMemo();
      dropReference(stack);
      // Call resume() on the popped entry
      StackEntryType entryType = (StackEntryType) first.baseType();
      assert resultsStateMatches(entryType.called());
      entryType.resume(this, first, results, methodMemo);
      // The stack tail should be unchanged
      assert stackRest == rest;
      // Exit if we're done
      if (unwindStarted() || stackRest == base || (rThread != null && rThread.isCancelled())) {
        return;
      }
      // Otherwise take the stack tail back and do it again.
      stack = stackRest;
      stackRest = null;
    }
  }

  /** Non-null during code generation */
  private CodeGen codeGen;

  void setCodeGen(CodeGen codeGen) {
    assert this.codeGen == null || codeGen == null;
    this.codeGen = codeGen;
  }

  public boolean hasCodeGen() {
    return codeGen != null;
  }

  public CodeGen codeGen() {
    assert codeGen != null;
    return codeGen;
  }
}

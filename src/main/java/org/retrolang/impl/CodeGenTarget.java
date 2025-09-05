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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.impl.Template.VarSink;
import org.retrolang.impl.TemplateBuilder.VarAllocator;

/**
 * A CodeGenTarget is used to configure a CodeGen. Once code generation is complete it will become
 * the current target of a CodeGenLink until we decide to regenerate the code.
 */
class CodeGenTarget {

  /**
   * The CodeGenLink that points (or pointed to) this target.
   *
   * <p>When first created, a CodeGenTarget is the link's "next" target (only visible to the code we
   * are generating, not to already-running code).
   *
   * <p>Once the CodeGenGroup is completed it will be promoted to be the link's "ready" target.
   *
   * <p>If a subsequent CodeGenGroup regenerates the link the newer version (when completed) will
   * replace this target, which should eventually be garbage-collected.
   */
  final CodeGenLink link;

  /**
   * Used to identify this target in code listings; ideally will be unique, but nothing depends on
   * that. Must be a valid JVM method name, i.e. must contain at least one character and must not
   * contain any of "{@code .;[/<>}".
   */
  final String name;

  /**
   * A template for each of the args; numvar and refvar indices refer to the generated method's
   * arguments, so will be between 1 and {@link #numJavaArgs} (argument 0 is always the TState).
   */
  final ImmutableList<Template> args;

  /**
   * A template for each of the results; numvar indices are offsets into {@link
   * TState#fnResultBytes} and refvar indices are positions in {@link TState#fnResults}.
   */
  final ImmutableList<Template> results;

  /** The number of (Java) arguments that the generated method expects. */
  final int numJavaArgs;

  /** The maximum number of bytes required to represent the generated code's numeric results. */
  final int resultByteSize;

  /** The maximum number of pointers required to represent the generated code's Value results. */
  final int resultObjSize;

  /** A CallSite that will be linked to the generated code when it has been loaded. */
  final MutableCallSite mhCaller;

  /** An Op that will call the generated code. */
  final Op op;

  /**
   * The CodeGen instance used to generate this code. Set to null when code generation completes.
   */
  CodeGen codeGen;

  /** The number of times we have started running this generated code from interpreted code. */
  private volatile int entryCount;

  /**
   * The number of times we have been unable to run this generated code from interpreted code
   * because argument values could not be represented.
   */
  private volatile int rejectCount;

  private static final VarHandle ENTRY_COUNT_VAR;
  private static final VarHandle REJECT_COUNT_VAR;

  static {
    var lookup = MethodHandles.lookup();
    ENTRY_COUNT_VAR = Handle.forVar(lookup, CodeGenTarget.class, "entryCount", int.class);
    REJECT_COUNT_VAR = Handle.forVar(lookup, CodeGenTarget.class, "rejectCount", int.class);
  }

  static CodeGenTarget fromCodeGenLink(
      String name, CodeGenGroup group, CodeGenLink link, ImmutableList<Template> prevResults) {
    return new CodeGenTarget(
        name,
        group,
        link,
        alloc -> build(link.kind.argsMemo(link.mm), alloc),
        alloc -> {
          ImmutableList<Template> results = build(link.mm.resultsMemo, alloc);
          // If the results templates are equal to those of a previous target for this link, keep
          // the old
          // ones; this may allow us to use new code from previously-generated call sites.
          return results.equals(prevResults) ? prevResults : results;
        });
  }

  /** Used only for SimpleCodeGenTest. */
  static CodeGenTarget fromTemplates(
      String name, List<Template> argTemplates, List<Template> resultTemplates) {
    return new CodeGenTarget(
        name,
        null,
        null,
        alloc -> build(argTemplates, alloc),
        alloc -> build(resultTemplates, alloc));
  }

  static CodeGenTarget fromMethodMemo(String name, MethodMemo memo) {
    return new CodeGenTarget(
        name,
        null,
        null,
        alloc -> build(memo.argsMemo(), alloc),
        alloc -> build(memo.resultsMemo, alloc));
  }

  private CodeGenTarget(
      String name,
      CodeGenGroup group,
      CodeGenLink link,
      Function<VarAllocator, ImmutableList<Template>> argBuilder,
      Function<VarAllocator, ImmutableList<Template>> resultBuilder) {
    this.name = name;
    this.link = link;
    this.codeGen = new CodeGen(group, this);
    this.args = argBuilder.apply(new RegisterAllocator(codeGen.cb, true));
    numJavaArgs = codeGen.cb.numArgs();
    RecordLayout.VarAllocator resultsAlloc =
        RecordLayout.VarAllocator.newWithAlignedDoubles().setUpgradeSubInts();
    this.results = resultBuilder.apply(resultsAlloc);
    this.resultObjSize = resultsAlloc.ptrSize();
    this.resultByteSize = resultsAlloc.byteSize();
    mhCaller = new MutableCallSite(codeGen.cb.methodType(void.class));
    op = Op.forMethodHandle(name, mhCaller.dynamicInvoker()).build();
  }

  /** Build a Template for each of the ValueMemo's elements. */
  private static ImmutableList<Template> build(ValueMemo memo, VarAllocator alloc) {
    TProperty<Template> builder = TProperty.build(alloc);
    return IntStream.range(0, memo.size())
        .mapToObj(i -> memo.result(i, builder))
        .collect(ImmutableList.toImmutableList());
  }

  /** Build a Template for each of the List's elements. */
  private static ImmutableList<Template> build(List<Template> templates, VarAllocator alloc) {
    return templates.stream()
        .map(t -> t.toBuilder().build(alloc))
        .collect(ImmutableList.toImmutableList());
  }

  private static final Op INCREMENT_ATOMIC_INTEGER =
      Op.forMethod(AtomicInteger.class, "incrementAndGet").hasSideEffect().build();

  /** Called once (from the CodeGenGroup) to generate code for this target. */
  void generateCode() {
    CodeGen codeGen = this.codeGen;
    this.codeGen = null;
    CodeGenGroup.Monitor monitor = codeGen.group.monitor;
    if (monitor.verbose()) {
      codeGen.cb.verbose = true;
      // CodeBuilder is going to print some log spam, let's add a little context
      System.out.printf("Generating code for %s (%s => %s)\n", name, args, results);
    }
    Object counters = null;
    if (monitor.countCalls()) {
      // The generated code will have a reference to this AtomicInteger, but not to the whole
      // CodeGenTarget
      AtomicInteger callCounter = new AtomicInteger();
      INCREMENT_ATOMIC_INTEGER.block(CodeValue.of(callCounter)).addTo(codeGen.cb);
      // Create an object whose toString() result will include both callCounter and
      counters =
          new Object() {
            @Override
            public String toString() {
              return CodeGenTarget.this.counters(callCounter.get());
            }
          };
    }
    codeGen.emit(link.mm, link.mm.perMethod.method.impl);
    String fileName = link.mm.perMethod.method.toString();
    MethodHandle mh;
    try {
      // TODO: use a more restrictive Lookup?
      mh = codeGen.cb.load(name, fileName, void.class, Handle.lookup);
    } finally {
      // That shouldn't error, but if it does (usually due to some verifier problem) the debugInfo
      // might be useful.
      monitor.loaded(name, args, counters, codeGen.cb.debugInfo);
    }
    mhCaller.setTarget(mh);
  }

  /**
   * Returns an array suitable for passing to {@link #call}, or null if the given arguments cannot
   * be represented by this target's args templates. The returned array is not included in the
   * current ResourceTracker's memory use but its references are counted.
   */
  Object[] prepareArgs(TState tstate, Object[] argValues) {
    ArgSaver saver = new ArgSaver(numJavaArgs);
    for (int i = 0; i < args.size(); i++) {
      if (!saver.saveArg(tstate, args.get(i), (Value) argValues[i])) {
        REJECT_COUNT_VAR.getAndAdd(this, 1);
        return null;
      }
    }
    // If we made it this far we're good to go.
    return saver.args;
  }

  /** A helper class to simplify {@link #prepareArgs}. */
  private static class ArgSaver implements VarSink {
    final Object[] args;

    ArgSaver(int numArgs) {
      // Don't use tstate.allocObjectArray() here, since this array gets passed into
      // invokeWithArguments()
      // and never seen again.
      this.args = new Object[numArgs];
    }

    /**
     * Stores one argument value into the args array, using the given template. Returns true if
     * successful, false if the value cannot be represented by the given template.
     */
    boolean saveArg(TState tstate, Template t, Value v) {
      if (t.setValue(tstate, v, this)) {
        return true;
      }
      // This arg value couldn't be represented by the template.  Clean up any array elements we
      // wrote before failing (since their reference counts were incremented) and bail out.
      tstate.clearElements(args, 0, args.length);
      return false;
    }

    private void saveArg(int index, Object value) {
      // Each prepared arg should be set at most once, and the first slot is reserved for the
      // TState.
      assert index != 0 && args[index] == null;
      args[index] = value;
    }

    @Override
    public void setB(int index, int value) {
      saveArg(index, value);
    }

    @Override
    public void setI(int index, int value) {
      saveArg(index, value);
    }

    @Override
    public void setD(int index, double value) {
      saveArg(index, value);
    }

    @Override
    public void setValue(int index, @RC.In Value value) {
      saveArg(index, value);
    }
  }

  /**
   * Executes the generated code with the given arguments array (which should have been returned by
   * a call to {@link #prepareArgs}); either returns a result in {@code tstate} or errors or blocks.
   */
  void call(TState tstate, Object[] preparedArgs) {
    assert preparedArgs.length == numJavaArgs && preparedArgs[0] == null;
    preparedArgs[0] = tstate;
    // If the arg templates include a union, some args may not have been set.
    // null is what we want for unused pointer-valued args, but the numeric ones must be set to
    // zero.
    for (int i = 1; i < preparedArgs.length; i++) {
      if (preparedArgs[i] == null && mhCaller.type().parameterType(i).isPrimitive()) {
        preparedArgs[i] = 0;
      }
    }
    ENTRY_COUNT_VAR.getAndAdd(this, 1);
    try {
      mhCaller.getTarget().invokeWithArguments(preparedArgs);
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
    // We don't care about the representation of results, but this also handles escapes.
    var unused = tstate.checkExlinedResult(results);
  }

  /**
   * Called from the {@code toString()} method of the object passed to {@link
   * CodeGenGroup.Monitor#loaded}, to format the call counter along with those updated by {@link
   * #prepareArgs} and {@link #call}.
   *
   * <p>(Even if {@link CodeGenGroup.Monitor#countCalls} is false we still maintain the other
   * counters but never read them, just because that's the easiest thing to do and we don't expect
   * {@link #call} to be a hotspot.
   */
  String counters(int totalCallCount) {
    return String.format(
        "%s:(entry=%d reject=%d total=%d)", name, entryCount, rejectCount, totalCallCount);
  }
}

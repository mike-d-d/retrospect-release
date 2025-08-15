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

import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeBuilder.OpCodeType;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ReturnBlock;
import org.retrolang.code.SetBlock;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.code.ValueInfo.BinaryOps;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.Template.RefVar;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Bits;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.invoke.VarHandle.AccessMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * A CodeGen instance manages the process of generating one Java method corresponding to one
 * Retrospect MethodMemo. Its life cycle is
 *
 * <ul>
 *   <li>a CodeGen is created as part of a Scope's current CodeGenGroup, for a single CodeGenTarget;
 *   <li>some initial setup is done at creation time (to determine the signature of the Java method,
 *       by choosing the templates that will represent the arguments) and the target is then queued
 *       in the {@link CodeGenGroup#targets} list;
 *   <li>{@link CodeGenGroup#generateCode} will eventually call the CodeGen's {@link #emit} method,
 *       which walks the Retrospect method definition adding blocks to the CodeGen's CodeBuilder;
 *   <li>{@link CodeBuilder#load} is called, causing it to optimize the blocks and construct a
 *       MethodHandle for the resulting bytecodes; and finally
 *   <li>the MethodHandle is saved in the CodeGenTarget and the CodeGen discarded.
 * </ul>
 *
 * <p>Most of the heavy lifting is done by the CodeBuilder, but the CodeGen maintains some
 * additional state:
 *
 * <ul>
 *   <li>{@link #atEnd}, a stack of code fragments (mostly escape handlers) that will be placed at
 *       the end of emitted blocks (to comply with CodeBuilder's restriction to forward branches);
 *   <li>the current {@link #escape} handler; and
 *   <li>{@link CurrentCall} objects that maintain state while inlining method calls.
 * </ul>
 */
public class CodeGen {
  final CodeGenGroup group;
  final CodeGenTarget target;

  public final CodeBuilder cb;

  /**
   * For the duration of the {@link #emit} call the CodeGen is bound to the thread's TState, so that
   * either can be accessed from the other (see {@link #tstate()} and {@link TState#codeGen()}).
   */
  private TState tstate;

  /** TState is always the first argument to the methods we emit, so always in register 0. */
  static final int TSTATE_REGISTER_INDEX = 0;

  /**
   * The active CurrentCall. We swap in a new CurrentCall instance while emitting code for an
   * inlined method.
   */
  private CurrentCall currentCall;

  /**
   * TStack-valued registers that were allocated as {@link CurrentCall#stackRest} for a
   * now-completed call. We could just allocate a new one for each call, but we emit a lot of calls
   * and it's easy to reuse them so we do.
   */
  private final Deque<Register> spareStackRests = new ArrayDeque<>();

  private static final BinaryOps BINARY_OPS = new BinaryOps();

  CodeGen(CodeGenGroup group, CodeGenTarget target) {
    this.group = group;
    this.target = target;
    this.cb = new CodeBuilder(BINARY_OPS);
    // We can add the first arg here, since it's always the TState; the rest of the args will be
    // added by our caller.
    Register tstateRegister = cb.newArg(TState.class);
    assert tstateRegister.index == TSTATE_REGISTER_INDEX;
  }

  /** Returns a stand-alone CodeBuilder; used for emitting helper methods. */
  static CodeBuilder newCodeBuilder() {
    return new CodeBuilder(BINARY_OPS);
  }

  /** Returns the TState of the thread that is running {@link #emit}. */
  public TState tstate() {
    assert tstate != null;
    return tstate;
  }

  /** Returns the register that will hold the TState of the thread running the emitted code. */
  public Register tstateRegister() {
    return tstateRegister(cb);
  }

  /** Returns the tstate register for the given CodeBuilder. */
  static Register tstateRegister(CodeBuilder cb) {
    return cb.register(TSTATE_REGISTER_INDEX);
  }

  /** Returns the register corresponding to a NumVar that was allocated by a RegisterAllocator. */
  public Register register(NumVar v) {
    return cb.register(v.index);
  }

  /** Returns the register corresponding to a RefVar that was allocated by a RegisterAllocator. */
  public Register register(RefVar v) {
    return cb.register(v.index);
  }

  /** The given template must be a NumVar or a RefVar; returns the corresponding register. */
  public Register register(Template v) {
    return cb.register((v instanceof NumVar nv) ? nv.index : ((RefVar) v).index);
  }

  /**
   * The given value must be an RValue wrapping a NumVar or a RefVar; returns the corresponding
   * register.
   */
  public Register register(Value v) {
    return register(((RValue) v).template);
  }

  /** Given a register containing a numeric value, returns the corresponding RValue. */
  public Value toValue(Register r) {
    Template t;
    if (r.type() == int.class) {
      t = NumVar.INT32.withIndex(r.index);
    } else {
      assert r.type() == double.class;
      t = NumVar.FLOAT64.withIndex(r.index);
    }
    return RValue.fromTemplate(t);
  }

  /** Given a numeric register or constant, returns the corresponding Value. */
  public Value toValue(CodeValue cv) {
    return (cv instanceof Register r)
        ? toValue(r)
        : NumValue.of(cv.numberValue(), Allocator.UNCOUNTED);
  }

  /**
   * Returns a CodeValue representing the given Value. {@code v} must be representable by a simple
   * CodeValue, i.e. it may not be a compound or union RValue.
   */
  public CodeValue asCodeValue(Value v) {
    v = simplify(v);
    if (v instanceof RValue) {
      return register(v);
    } else {
      return CodeValue.of(v instanceof NumValue ? NumValue.asNumber(v) : v);
    }
  }

  /**
   * Each of these will be run when the rest of code generation is completed, in reverse order. That
   * means that any blocks they emit must either end with a Terminal or branch to a FutureBlock that
   * will be filled in by a previously-added Runnable.
   */
  private final List<Runnable> atEnd = new ArrayList<>();

  /**
   * The given Runnable should add one or more blocks, and will be executed after the body of the
   * method has been emitted (but before any blocks added by previous calls to {@code addAtEnd()}).
   * The returned FutureBlock should be used to branch to the blocks that will be added.
   */
  FutureBlock addAtEnd(Runnable addBlocks) {
    FutureBlock link = new FutureBlock();
    atEnd.add(
        () -> {
          if (link.hasInLink()) {
            cb.setNext(link);
            addBlocks.run();
            assert !cb.nextIsReachable();
          }
        });
    return link;
  }

  /** Adds all the blocks that were queued by calls to {@link #addAtEnd}. */
  void runAllAtEnd() {
    for (int i = atEnd.size() - 1; i >= 0; --i) {
      atEnd.get(i).run();
    }
    atEnd.clear();
  }

  /**
   * If v refers to one or more registers whose values are known (or perhaps even partly known) we
   * can simplify it.
   */
  public Value simplify(Value v) {
    return (v instanceof RValue rv) ? rv.simplify(cb::nextInfoResolved) : v;
  }

  /** Top-level driver for code generation. */
  void emit(Runnable addBlocks) {
    // Bind this thread's TState to this CodeGen for the duration of this call.
    assert tstate == null;
    TState tstate = TState.get();
    this.tstate = tstate;
    tstate.setCodeGen(this);
    try {
      addBlocks.run();
      // Emit all the escape handlers
      invalidateEscape();
      runAllAtEnd();
    } finally {
      this.tstate = null;
      tstate.setCodeGen(null);
    }
  }

  /** Top-level call to emit the body of a method. */
  void emit(MethodMemo initialMethod, MethodImpl impl) {
    assert currentCall == null;
    // This Destination will be passed the method's results, and emit the code to return them
    Destination done = Destination.fromTemplates(target.results);
    // The base of the call stack; this will be temporarily replaced when we emit method calls
    currentCall =
        new CurrentCall(
            null,
            done,
            initialMethod.resultsMemo,
            stackRest -> {
              TState.SET_STACK_REST_OP.block(tstateRegister(), stackRest).addTo(cb);
              new ReturnBlock(null).addTo(cb);
            });
    currentCall.methodMemo = initialMethod;
    emit(
        () -> {
          // Call the MethodImpl's emit() method, which does the actual work
          Value[] argValues = target.args.stream().map(RValue::fromTemplate).toArray(Value[]::new);
          impl.emit(this, done, initialMethod, argValues);
          // When that completes we should be back to the original CurrentCall
          assert currentCall.done == done;
          // Now emit the code to write the method's results into the TState
          Value[] results = done.emit(this);
          if (results != null) {
            emitSaveResults(results);
            TState.SET_STACK_REST_OP.block(tstateRegister(), currentCall.stackRest).addTo(cb);
            new ReturnBlock(null).addTo(cb);
          }
        });
    currentCall = null;
  }

  /**
   * Emit blocks to write this method's results into the TState, using the chosen result templates.
   */
  void emitSaveResults(Value[] results) {
    TState.SET_RESULT_TEMPLATES_OP.block(tstateRegister(), CodeValue.of(target.results)).addTo(cb);
    // Get the numeric and/or pointer results arrays from the TState
    Register fnResults = (target.resultObjSize == 0) ? null : fnResults(target.resultObjSize);
    Register fnResultBytes =
        (target.resultByteSize == 0) ? null : fnResultBytes(target.resultByteSize);
    CopyEmitter saveResult =
        new CopyEmitter() {
          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            Op setter;
            CodeValue resultsArray;
            int position;
            if (t instanceof NumVar nv) {
              setter =
                  switch (nv.encoding) {
                    case UINT8 -> Op.SET_UINT8_ARRAY_ELEMENT;
                    case INT32 -> SET_BYTES_FROM_INT_OP;
                    case FLOAT64 -> SET_BYTES_FROM_DOUBLE_OP;
                  };
              resultsArray = fnResultBytes;
              position = nv.index;
            } else {
              setter = Op.SET_OBJ_ARRAY_ELEMENT;
              resultsArray = fnResults;
              position = ((RefVar) t).index;
            }
            setter.block(resultsArray, CodeValue.of(position), v).addTo(codeGen.cb);
          }
        };
    for (int i = 0; i < target.results.size(); i++) {
      CopyPlan plan = CopyPlan.create(RValue.toTemplate(results[i]), target.results.get(i));
      plan = CopyOptimizer.toFnResult(plan, target.resultObjSize, target.resultByteSize);
      saveResult.emit(this, plan, escape);
    }
  }

  /**
   * Returns an Object[]-valued Register initialized from a call to {@link TState#fnResults(int)}.
   */
  Register fnResults(int minSize) {
    // This is only used by emitSaveResults(), so there's no opportunity for reusing it.
    Register fnResults = cb.newRegister(Object[].class);
    new SetBlock(fnResults, TState.FN_RESULTS_OP.result(tstateRegister(), CodeValue.of(minSize)))
        .addTo(cb);
    return fnResults;
  }

  /** A byte[]-valued Register used to cache the result of {@link TState#fnResultBytes(int)}. */
  private Register fnResultBytes;

  /**
   * Returns a byte[]-valued Register initialized from a call to {@link TState#fnResultBytes(int)}.
   */
  Register fnResultBytes(int minSize) {
    // This is called after each exlined call, as well as by emitSaveResults(); reusing the same
    // register (very) slightly reduces the work for CodeBuilder.
    if (fnResultBytes == null) {
      fnResultBytes = cb.newRegister(byte[].class);
    }
    new SetBlock(
            fnResultBytes,
            TState.FN_RESULT_BYTES_OP.result(tstateRegister(), CodeValue.of(minSize)))
        .addTo(cb);
    return fnResultBytes;
  }

  /**
   * The instruction currently being emitted; attached to blocks (via {@link
   * CodeBuilder#setNextSrc}) as an aid to debugging code generation.
   */
  private Instruction currentInstruction;

  /** Called each time we start emitting an instruction. */
  void setCurrentInstruction(Instruction inst) {
    this.currentInstruction = inst;
    cb.setNextSrc(inst.describe());
  }

  /**
   * Called each time we start emitting the next step of a builtin method, to add clues for
   * debugging code generation.
   */
  void setNextSrc(Object info) {
    assert !(info instanceof Instruction);
    // Both the builtin step and the instruction that invoked it are useful to know.
    if (currentInstruction != null) {
      info = info + " // " + currentInstruction; // TODO: .describe() ?
    }
    cb.setNextSrc(info);
  }

  /**
   * Allocates a register to store {@link TState#stackRest} for the duration of one function call
   * and emits an instruction to initialize it to null.
   */
  private Register allocateStackRest() {
    Register result = spareStackRests.poll();
    if (result == null) {
      result = cb.newRegister(TStack.class);
    }
    new SetBlock(result, CodeValue.NULL).addTo(cb);
    return result;
  }

  /**
   * Each CurrentCall instance saves information about a function call that we are currently
   * emitting; each call to {@link #emitCall} creates a new CurrentCall object and sets it as {@link
   * #currentCall} for the duration of that call.
   */
  class CurrentCall {
    /**
     * A TStack-valued register that will be set to null before emitting the function call, and
     * during the call may be set to an empty TStack to indicate that a duringCall stack entry is
     * needed. Used in place of {@link TState#stackRest}, for efficiency.
     */
    final Register stackRest = allocateStackRest();

    /** The CallSite of this call. */
    final CallSite callSite;

    /** Returns from this call are implemented by branching to this Destination. */
    final Destination done;

    /** The ValueMemo that will be pushed on the TStack if this call is interrupted. */
    final ValueMemo resultsInfo;

    /**
     * A link to code that should be executed if we start unwinding the stack during execution of
     * this function. If this is the base of the stack it will save {@link #stackRest} in the TState
     * and exit the generated code; otherwise it will populate {@link #stackRest} appropriately for
     * the current nested call and then branch to the enclosing CurrentCall's continueUnwinding
     * block.
     */
    final FutureBlock continueUnwinding;

    /** The MethodMemo for the method currently being emitted for this call. */
    private MethodMemo methodMemo;

    /**
     * Used by {@link BuiltinSupport.BuiltinImpl#emit} to save additional state while it runs. Null
     * if we are emitting any other type of method.
     */
    private BuiltinSupport.EmitState builtinEmitState;

    CurrentCall(
        CallSite callSite,
        Destination done,
        ValueMemo resultsInfo,
        Consumer<Register> continueUnwinding) {
      this.callSite = callSite;
      this.done = done;
      this.resultsInfo = resultsInfo;
      this.continueUnwinding = addAtEnd(() -> continueUnwinding.accept(stackRest));
    }

    /**
     * Emits blocks to initialize {@code tstack} (or a new TStack, saved as {@link
     * TState#stackHead}, if {@code tstack} is null), with this call's {@link #stackRest} as its
     * tail.
     */
    void emitFillStackEntry(CodeValue tstack, Value stackEntry, MethodMemo mMemo) {
      CodeValue entryCv = StackEntryBlock.create(stackEntry, cb);
      CodeValue[] fillStackArgs =
          new CodeValue[] {
            tstateRegister(),
            tstack,
            entryCv,
            CodeValue.of(resultsInfo),
            CodeValue.of(mMemo),
            stackRest
          };
      new SetBlock(stackRest, TState.FILL_STACK_ENTRY_OP.result(fillStackArgs)).addTo(cb);
    }
  }

  /** Returns information about the (innermost) in-progress function call. */
  CurrentCall currentCall() {
    return currentCall;
  }

  /** Sets {@link CurrentCall#builtinEmitState} for the in-progress function call. */
  void setBuiltinEmitState(BuiltinSupport.EmitState builtinEmitState) {
    assert currentCall.builtinEmitState == null;
    currentCall.builtinEmitState = builtinEmitState;
  }

  /**
   * Implements {@link TState#jump} during code generation, by emitting a branch to the code for the
   * specified continuation.
   */
  void jump(String continuationName, Value... args) {
    currentCall.builtinEmitState.getDestination(this, continuationName).addBranch(this, args);
  }

  /** Emits the blocks to allocate a stack entry and call {@link TState#trace} with it. */
  void emitTrace(Instruction.Trace inst, Value stackEntry) {
    CodeValue entryCv = StackEntryBlock.create(stackEntry, cb);
    CodeValue[] traceArgs =
        new CodeValue[] {tstateRegister(), CodeValue.of(inst), entryCv, currentCall.stackRest};
    new SetBlock(currentCall.stackRest, TState.TRACE_OP.result(traceArgs)).addTo(cb);
  }

  /**
   * Emits a call to {@code fn} passing {@code args}. {@code stackEntry} represents this call site
   * and the saved locals, and will be pushed if the stack is unwound during the call. Returns the
   * Destination that will receive the results.
   */
  @CanIgnoreReturnValue
  Destination emitCall(
      VmFunction fn,
      Object[] args,
      CallSite callSite,
      BuiltinMethod.Caller caller,
      Value stackEntry) {
    // Prepare a new CurrentCall instance to store information about the new call.
    CurrentCall parent = currentCall;
    ValueMemo resultsMemo;
    Destination done;
    if (caller != null && caller.continuation() == BuiltinMethod.TAIL_CALL) {
      // Tail calls just inherit resultsMemo and done from the parent call
      resultsMemo = parent.resultsInfo;
      done = parent.done;
    } else {
      resultsMemo = callSite.valueMemo(tstate, parent.methodMemo);
      if (caller == null) {
        // Calls from Instruction.Call create a new Destination for done
        done = Destination.fromValueMemo(resultsMemo);
      } else {
        // Non-tail calls from built-ins get the Destination corresponding to their continuation
        done = parent.builtinEmitState.getDestination(this, caller.continuation());
      }
    }
    // The Destination size should match the number of results being kept from this call, unless
    // there are some @Saved args passed to the continuation.
    assert callSite.numResultsKept() == done.size()
        || callSite.numResultsKept() == done.size() - stackEntry.baseType().size();
    // Create a child of the Destination that will do the post-call check for creating a stack
    // entry.
    Destination child = done.createChild(callSite.numResultsKept());
    // Now we've got everything we need for a new CurrentCall; create it and make it current.
    MethodMemo parentMemo = parent.methodMemo;
    CurrentCall nested =
        new CurrentCall(
            callSite,
            child,
            resultsMemo,
            stackRest -> {
              cb.setNextSrc("unwinding");
              parent.emitFillStackEntry(stackRest, stackEntry, parentMemo);
              cb.branchTo(parent.continueUnwinding);
            });
    currentCall = nested;
    // VmFunction.emitCall() determines the appropriate method(s) and emits them.
    fn.emitCall(this, parent.methodMemo, callSite, args);
    // Now emit the post-call instructions.
    child.emit(CodeGen.this);
    if (cb.nextIsReachable()) {
      // If we get here the function call completed with a result.
      FutureBlock afterPush = new FutureBlock();
      // If our stackRest is still null there's nothing extra to do.
      new TestBlock.IsEq(OpCodeType.OBJ, nested.stackRest, CodeValue.NULL)
          .setBranch(true, afterPush)
          .addTo(cb);
      if (cb.nextIsReachable()) {
        // Our stackRest is non-null, i.e. at some point the called function traced.
        // Emit blocks to extend an existing stack, with an entry describing this call.
        cb.setNextSrc("extendTrace");
        parent.emitFillStackEntry(nested.stackRest, stackEntry, parentMemo);
      }
      cb.mergeNext(afterPush);
      // Continue on to the original Destination, adding in @Saved values from the stackEntry if
      // needed
      child.branchToParent(
          CodeGen.this, (callSite.numResultsKept() == done.size()) ? null : stackEntry);
    }
    // We're done emitting the function call; restore the previous currentCall and make our
    // stackRest local available for reuse.
    assert currentCall == nested;
    spareStackRests.addFirst(nested.stackRest);
    currentCall = parent;
    return done;
  }

  /** Called from {@link VmFunction#emitCall} to emit the body of the specified method. */
  void emitMethodCall(MethodImpl impl, MethodMemo mMemo, Object[] args) {
    if (mMemo != null && mMemo.isExlined() && !mMemo.isFixed()) {
      CodeGenLink link = (CodeGenLink) mMemo.extra();
      ExlinedCall.emitCall(this, link.next(group, args), args);
    } else {
      currentCall.methodMemo = mMemo;
      Instruction currentInst = currentInstruction;
      impl.emit(this, currentCall.done, mMemo, args);
      currentCall.methodMemo = null;
      currentCall.builtinEmitState = null;
      this.currentInstruction = currentInst;
    }
  }

  /**
   * If non-null, a FutureBlock that should be branched to if generated code encounters an
   * unexpected situation; will push one or more stack entries representing the current state of
   * execution and exit the generated code.
   */
  private FutureBlock escape;

  /** Returns the current escape handler. */
  FutureBlock getEscape() {
    return escape;
  }

  /** Sets the current escape handler. */
  void setEscape(FutureBlock escape) {
    this.escape = escape;
  }

  /** Defines an escape handler that starts unwinding with the given stack entry. */
  void setNewEscape(Value stackEntry) {
    // Save these when setNewEscape is called, since they will probably change before our Runnable
    // is run.
    CurrentCall cc = this.currentCall;
    MethodMemo mMemo = cc.methodMemo;
    escape =
        addAtEnd(
            () -> {
              cb.setNextSrc("startUnwind");
              cc.emitFillStackEntry(CodeValue.NULL, stackEntry, mMemo);
              cb.branchTo(cc.continueUnwinding);
            });
  }

  /** Invalidates the current escape handler. */
  void invalidateEscape() {
    this.escape = null;
  }

  /** Emits a branch to the current escape handler. */
  public void escape() {
    cb.branchTo(escape);
  }

  /** Emits a branch to the current escape handler unless the given condition is true. */
  public void escapeUnless(Condition check) {
    if (cb.nextIsReachable()) {
      check.addTest(this, escape);
    }
  }

  /** Configures the given test to branch to the current escape handler on failure, and emits it. */
  public void escapeUnless(TestBlock newTest) {
    if (cb.nextIsReachable()) {
      newTest.setBranch(false, escape).addTo(cb);
    }
  }

  /** Emits a return from the current function call. */
  public void setResults(Value... results) {
    if (cb.nextIsReachable()) {
      if (currentCall.callSite != null) {
        results = currentCall.callSite.kept(results);
      }
      currentCall.done.addBranch(this, results);
    }
  }

  /**
   * Emits a return from the current function call.
   *
   * <p>{@code result} must be numeric-valued. If it is a constant or register, returns it as-is.
   * Otherwise (i.e. it is an Op.Result) emits a block to check whether it is NaN, and if so returns
   * None instead.
   */
  public void setResultWithNaNCheck(CodeValue rhs) {
    Value v;
    if (rhs instanceof CodeValue.Const) {
      if (rhs.isDouble()) {
        v = NumValue.orNan(rhs.dValue(), Allocator.UNCOUNTED);
      } else {
        v = NumValue.of(rhs.iValue(), Allocator.UNCOUNTED);
      }
    } else if (rhs instanceof Register r) {
      // This assumes that the register has previously been checked for NaN.
      v = toValue(r);
    } else {
      Register result = cb.newRegister(rhs.type());
      emitSet(result, rhs);
      if (rhs.isDouble()) {
        FutureBlock isNotNaN = new FutureBlock();
        testIsNaN(result, true, isNotNaN);
        setResults(Core.NONE);
        cb.setNext(isNotNaN);
      }
      v = toValue(result);
    }
    setResults(v);
  }

  /** Emits a block to set {@code lhs}. */
  public void emitSet(Register lhs, CodeValue rhs) {
    // An assignment of a register to itself would be optimized away later,
    // but we might as well just skip it now.
    if (rhs != lhs) {
      new SetBlock(lhs, rhs).addTo(cb);
    }
  }

  /**
   * Emits a block to set {@code lhs}, wrapped in try/catch(ArithmeticException) that escapes if the
   * exception is thrown.
   */
  public void emitSetCatchingArithmeticException(Register lhs, CodeValue rhs) {
    // If we've somehow already optimized rhs to no longer call any method that could throw
    // we can skip adding the try/catch
    if (rhs.canThrow()) {
      new SetBlock.WithCatch(lhs, rhs, ArithmeticException.class, escape).addTo(cb);
    } else {
      // The rhs simplified to a const or register
      emitSet(lhs, rhs);
    }
  }

  private Optional<Value> escapeOnErr(Condition.ValueSupplier supplier) {
    try {
      return Optional.of(supplier.get());
    } catch (Err.BuiltinException e) {
      escape();
      return Optional.empty();
    }
  }

  /**
   * Emits blocks to set the registers in {@code dst} (whose indices must be in the range {@code
   * registerStart..registerEnd} from {@code v}; after the new blocks have executed, either {@code
   * RValue.fromTemplate(dst)} will have the same value as {@code v} or we will have branched the
   * current escape handler.
   */
  void emitStore(Value v, Template dst, int registerStart, int registerEnd) {
    if (cb.nextIsReachable()) {
      if (v instanceof ConditionalValue conditional) {
        FutureBlock elseBranch = new FutureBlock();
        conditional.condition.addTest(this, elseBranch);
        escapeOnErr(conditional.ifTrue)
            .ifPresent(v2 -> emitStore(v2, dst, registerStart, registerEnd));
        FutureBlock done = cb.swapNext(elseBranch);
        escapeOnErr(conditional.ifFalse)
            .ifPresent(v2 -> emitStore(v2, dst, registerStart, registerEnd));
        cb.mergeNext(done);
      } else {
        emitStore(RValue.toTemplate(v), dst, registerStart, registerEnd);
      }
    }
  }

  /**
   * Emits blocks to set the registers in {@code dst} (whose indices must be in the range {@code
   * registerStart..registerEnd} from {@code src}; after the new blocks have executed, either {@code
   * RValue.fromTemplate(dst)} will have the same value as {@code RValue.fromTemplate(src)} or we
   * will have branched the current escape handler.
   */
  void emitStore(Template src, Template dst, int registerStart, int registerEnd) {
    CopyPlan plan = CopyPlan.create(src, dst);
    plan = CopyOptimizer.toRegisters(plan, registerStart, registerEnd, dst);
    CopyEmitter.REGISTER_TO_REGISTER.emit(this, plan, escape);
  }

  /**
   * Given a register containing a pointer to a Frame and the layout of the frame, returns a Value.
   */
  public static Value asValue(Register register, FrameLayout layout) {
    int resultIndex = register.index;
    return RValue.fromTemplate(new RefVar(resultIndex, layout.baseType(), layout, false));
  }

  /** Returns a CodeValue for the length of the given varray. */
  CodeValue vArrayLength(RefVar refVar) {
    VArrayLayout layout = (VArrayLayout) refVar.frameLayout();
    Register vArray = register(refVar);
    ensureLayout(vArray, layout);
    return layout.numElements(vArray);
  }

  /**
   * Emits a test comparing {@code index} with each value in {@code toTest}; if {@code index}
   * matches, it executes the blocks emitted by {@code emitter} for that value. Falls through if
   * none of the values match.
   */
  public void emitSwitch(CodeValue index, Bits toTest, IntConsumer emitter) {
    if (index instanceof CodeValue.Const) {
      int i = index.iValue();
      if (toTest.test(i) && cb.nextIsReachable()) {
        emitter.accept(i);
      }
      return;
    }
    assert index instanceof Register;
    toTest.forEach(
        i -> {
          FutureBlock next = new FutureBlock();
          new TestBlock.IsEq(OpCodeType.INT, index, CodeValue.of(i))
              .setBranch(false, next)
              .addTo(cb);
          if (cb.nextIsReachable()) {
            emitter.accept(i);
            assert !cb.nextIsReachable();
          }
          cb.setNext(next);
        });
  }

  /**
   * Emits a test comparing {@code iReg} with each value in {@code min..max-1}; if {@code iReg}
   * matches, it executes the blocks emitted by {@code emitter} for that value. If none match it
   * executes the blocks emitted by {@code emitter.accept(max)}.
   */
  public void emitSwitch(CodeValue index, int min, int max, IntConsumer emitter) {
    emitSwitch(index, Bits.forRange(min, max - 1), emitter);
    if (cb.nextIsReachable()) {
      emitter.accept(max);
    }
  }

  /**
   * If {@code v} is an Op.Result, allocates a new register of the specified type, stores {@code v}
   * there, and returns it; otherwise ({@code v} is a register or constant) just returns {@code v}.
   */
  public CodeValue materialize(CodeValue v, Class<?> type) {
    if (v instanceof Op.Result) {
      Register register = cb.newRegister(type);
      emitSet(register, v);
      return register;
    } else {
      return v;
    }
  }

  /** Emits a test to escape unless {@code frame} has the specified layout. */
  void ensureLayout(CodeValue frame, FrameLayout layout) {
    if (frame instanceof Register r) {
      // Small optimization: I expect that most calls to ensureLayout are redundant, and we can
      // recognize that before we waste our time constructing a TestBlock and then discarding it.
      ValueInfo info = cb.nextInfoResolved(r.index);
      if (Boolean.TRUE.equals(PtrInfo.TestLayout.test(info, layout))) {
        return;
      }
    }
    escapeUnless(checkLayout(frame, layout));
  }

  /** Returns a new test that checks if {@code frame} has the specified layout. */
  static TestBlock checkLayout(CodeValue frame, FrameLayout layout) {
    return new PtrInfo.TestLayout(frame, Frame.GET_LAYOUT_OR_REPLACEMENT.result(frame), layout);
  }

  /** Returns a new (non-argument) RegisterAllocator. */
  RegisterAllocator newAllocator() {
    return new RegisterAllocator(cb, false);
  }

  /**
   * Emits a block that will compare {@code v1} and {@code v2} (both numbers) for equality.
   * Continues if they are equal ({@code nextIfTrue=true}) or not equal ({@code nextIfTrue=false}),
   * and branches to {@code elseBranch} otherwise.
   */
  void testEqualsNum(CodeValue v1, CodeValue v2, boolean nextIfTrue, FutureBlock elseBranch) {
    assert !v1.isPtr() && !v2.isPtr();
    boolean asDouble = v1.type() == double.class || v2.type() == double.class;
    new TestBlock.IsEq(asDouble ? OpCodeType.DOUBLE : OpCodeType.INT, v1, v2)
        .setBranch(!nextIfTrue, elseBranch)
        .addTo(cb);
  }

  /**
   * Emits a block that tests if {@code v} (a double) is NaN. Continues if it is ({@code
   * nextIfTrue=true}) or is not ({@code nextIfTrue=false}), and branches to {@code elseBranch}
   * otherwise.
   */
  public void testIsNaN(CodeValue v, boolean nextIfTrue, FutureBlock elseBranch) {
    TestBlock.isFalse(IS_NAN_OP.result(v)).setBranch(nextIfTrue, elseBranch).addTo(cb);
  }

  /**
   * Emits a block that will compare {@code v1} and {@code v2} (both pointers) for equality.
   * Continues if they are equal ({@code nextIfTrue=true}) or not equal ({@code nextIfTrue=false}),
   * and branches to {@code elseBranch} otherwise.
   */
  void testEqualsObj(CodeValue v1, CodeValue v2, boolean nextIfTrue, FutureBlock elseBranch) {
    assert v1.isPtr() && v2.isPtr();
    if (isEmptyArray(v1) && testIsEmptyArray(v2, nextIfTrue, elseBranch)) {
      // testIsEmptyArray() emitted the test
    } else if (isEmptyArray(v2) && testIsEmptyArray(v1, nextIfTrue, elseBranch)) {
      // testIsEmptyArray() emitted the test
    } else if (canTestEq(v1) || canTestEq(v2)) {
      new TestBlock.IsEq(OpCodeType.OBJ, v1, v2).setBranch(!nextIfTrue, elseBranch).addTo(cb);
    } else {
      // This tests whether the result of equals() is false, so we invert the sense of nextIsTrue
      TestBlock.isFalse(Op.EQUAL.result(v1, v2)).setBranch(nextIfTrue, elseBranch).addTo(cb);
    }
  }

  /** True if the given CodeValue is a constant empty array. */
  static boolean isEmptyArray(CodeValue v) {
    return (v instanceof CodeValue.Const c) && (c.value == Core.EMPTY_ARRAY);
  }

  /**
   * If {@code v} is a register with a VArray layout, emits a test for the varray's length being
   * zero and returns true; otherwise returns false.
   */
  private boolean testIsEmptyArray(CodeValue v, boolean nextIfTrue, FutureBlock elseBranch) {
    if (v instanceof Register r) {
      ValueInfo info = cb.nextInfoResolved(r.index);
      if (info instanceof VArrayLayout layout) {
        new TestBlock.IsEq(OpCodeType.INT, layout.numElements(v), CodeValue.ZERO)
            .setBranch(!nextIfTrue, elseBranch)
            .addTo(cb);
        return true;
      }
    }
    return false;
  }

  /**
   * True if an equality comparison with {@code v} can use {@code ==} rather than {@code equals()},
   * i.e. {@code v} is null or a singleton other than EMPTY_ARRAY.
   */
  private static boolean canTestEq(CodeValue v) {
    if (v instanceof CodeValue.Const c) {
      Object x = c.value;
      if (x == null) {
        return true;
      } else if (x == Core.EMPTY_ARRAY) {
        // See Singleton.equals()
        return false;
      } else {
        return x instanceof Singleton;
      }
    }
    return false;
  }

  static final Op IS_NAN_OP = Op.forMethod(Double.class, "isNaN", double.class).build();

  static final Op INT_FROM_BYTES_OP =
      Op.forMethodHandle("int[]", ArrayUtil.BYTES_AS_INTS.toMethodHandle(AccessMode.GET)).build();

  static final Op SET_BYTES_FROM_INT_OP =
      Op.forMethodHandle("setInt[]", ArrayUtil.BYTES_AS_INTS.toMethodHandle(AccessMode.SET))
          .build();

  static final Op DOUBLE_FROM_BYTES_OP =
      Op.forMethodHandle("double[]", ArrayUtil.BYTES_AS_DOUBLES.toMethodHandle(AccessMode.GET))
          .build();

  static final Op SET_BYTES_FROM_DOUBLE_OP =
      Op.forMethodHandle("setDouble[]", ArrayUtil.BYTES_AS_DOUBLES.toMethodHandle(AccessMode.SET))
          .build();
}

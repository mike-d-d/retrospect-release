# Generating Java bytecodes for Retrospect, Part 1

This is an overview of how the Retrospect VM generates Java bytecodes (using
[CodeBuilder](code.md)) to enable fast execution of a Retrospect method.

The policy for triggering bytecode generation is not covered here; we will
simply assume that the VM has decided to generate bytecodes for a method.

[TOC]

## Background: Executing a method

The goal of code generation is to produce bytecodes that have the same behavior
as directly executing a Retrospect method, so it may be helpful to first review
how method execution works.

Method execution starts with the `execute()` method on the MethodImpl interface.
It takes four arguments:

*   `tstate`: A per-thread object that provides access to a variety of
    contextual information (such as how much memory is available); this is also
    where the `execute()` method stores its result and/or stack frames (if it
    unwinds or traces).
*   `results`: A ResultsInfo that provides information about how the method's
    results will be used; only used by a small number of built-in methods.
*   `mMemo`: A MethodMemo that aggregates information over multiple calls to the
    same method that may later be used by code generation.
*   `args`: A Java array of Values representing the arguments passed to the
    method

There are two primary implementations of MethodImpl, one for user methods
(defined by a block of Retrospect instructions) and one for built-in methods
(defined by one or more Java methods).

(There are a couple of additional implementations of MethodImpl that make it
easy to define particularly simple cases of built-in methods; they have a subset
of the capabilities of regular built-in methods, so it is safe to omit them from
this discussion.)

The InstructionBlock implementation of `execute()` operates like a conventional
virtual machine: it starts a program counter at zero (the first instruction in
the block), and uses a Java array to represent the current values of the
InstructionBlock's local variables (initialized from the `args` array). It
sequentially executes instructions (updating the locals array and advancing the
program counter) until it reaches a `return` instruction, at which point it
stores the return value(s) in `tstate` and returns from the `execute()` call.

The most complex instruction type is a function call, which must

*   determine which method will be called (if the function has multiple
    methods), based on the types of the arguments;
*   call `execute()` on that method, passing the arguments;
*   when that Java call returns, check `tstate` to see whether it returned a
    result or has started unwinding the stack;
*   if the stack is being unwound (i.e. the call errored or blocked) *or* a
    `trace` instruction was executed by the called method, add a stack frame
    representing the InstructionBlock's current state (program counter and
    locals);
*   if the stack is being unwound, exit this `execute()` call to continue
    unwinding; and
*   otherwise retrieve the call results from `tstate`, store them in the
    appropriate entries of the locals array, and continue execution with the
    next instruction.

The implementation of `execute()` for built-in methods has some similarities,
except that the execution steps (rather than being defined by Instruction
subclasses) are defined by Java methods specific to that built-in, and rather
than a fixed-sized locals array the method state is represented by Value
arguments passed to the next step. Each step of a built-in method can

*   return from the built-in method with specified results;
*   throw a BuiltinException to start unwinding the stack (beginning with a
    stack frame that represents this built-in step and its arguments);
*   start a function call, specifying another step at which execution will
    resume when the function completes *or* that the function's results should
    be returned as the results of this built-in method (a "tail call"); or
*   proceed to another step of this built-in.

(The steps of a built-in (after the initial step) are usually referred to as
"continuations"; see [the built-in method docs](builtins.md) for more details.)

## RValue

Value is one of the core interfaces defined by the Retrospect VM. When executing
Retrospect methods directly, instances of Value are used throughout — to
represent arguments to functions, the values of local variables, etc. This is
simple and flexible, but has a performance cost; for example, simply adding two
numbers involves retrieving the numbers from inside the NumValue wrappers, doing
the actual arithmetic, and then allocating a new NumValue object to hold the
result — all of which costs at least an order of magnitude more than the
underlying addition.

Much of the performance benefit of generating code comes from being able to
eliminate most of this object manipulation by using
"[unboxed](https://en.wikipedia.org/wiki/Boxing_\(computer_programming\))"
values. Rather than creating a NumValue it can use a native Java `int` or
`double`. Rather than representing a pair of integers with a CompoundValue
containing two NumValues, it can just use two `int`s stored in separate locals.

These "run-time" values are represented by instances of RValue, a special
implementation of Value that is only used during code generation. An RValue is
defined by a [template](templates.md) whose NumVars and RefVars refer to the
CodeBuilder's registers. For example an RValue with template `[i3,i7]`
represents a pair of integers, with the values in registers 3 and 7 (which must
be of type `int`). As another example, an RValue with template `i4⸨0:False,
1:True⸩` represents False if register 4 is zero, and True if it is one (using
this RValue is an assertion that register 4 does not have a value other than
zero or one).

## Calling generated code

Code generation produces a Java method (represented by a MethodHandle) that can
be called in place of directly executing a Retrospect method. The ResultsInfo
and MethodMemo arguments to `execute()` will be baked into the MethodHandle, but
the `tstate` and `args` must be passed to it explicitly. The `tstate` argument
is straightforward; every MethodHandle we generate will have a first argument of
type TState. The representation of the Retrospect method arguments is less
obvious.

When generated code is called from the VM's generic function-calling
infrastructure we already have a Value object for each argument, so passing the
arguments as Values would make sense. But in many cases generated code will be
called from other generated code (or even from itself, recursively), and in
those cases having to create a Value object for each argument might have a
significant performance cost.

Instead we will choose an unboxed representation, which we can represent using
RValues. For example, if a method takes two arguments and code generation
assumes that the first will always be a number and the second a boolean, it
would produce a MethodHandle with three arguments (TState, double, int), and the
arguments would be represented by RValues with templates `d1` and
`i2⸨0:False,1:True⸩`.

These argument templates are determined from the MethodMemo, which has recorded
(with a ValueMemo) the structure of all arguments that have been passed to the
method.

When the MethodHandle returns it must have updated the TState, by storing the
method's results there or by unwinding the stack. To simplify generating code
that calls this MethodHandle we choose a fixed set of templates for its results;
all successful returns are expected to use the same templates.

The templates chosen for arguments and returned values may later turn out to be
insufficient, i.e. an argument or returned value may not be representable by the
template. That is a "normal failure" — as described in Part 2, the generated
code will detect when any of its assumptions have been violated, and recover by
falling back to direct execution.

## Generating bytecodes for a method

Code generation has some similarities with method execution; it is implemented
by the `emit()` method on the MethodImpl interface, which takes almost the same
arguments as `execute()`. Like method execution, the implementations of `emit()`
just sequence calls to the instructions of the InstructionBlock or the steps of
the built-in to do the actual work.

There are, of course, some key differences:

*   The first argument to `emit()` is a CodeGen instance that maintains
    additional state specific to code generation (most importantly, a
    [CodeBuilder](code.md)). The CodeGen and this thread's TState are linked to
    each other for the duration of the code generation, so we can always get one
    from the other.

*   The elements of `args` are typically RValues. (A non-RValue element of
    `args` would represent an argument that always had the same value.)

*   While the call to `emit()` (like `execute()`) is expected to eventually call
    `tstate.setResults()` with the method's results, those will also typically
    also be RValues, and `emit()` will have added blocks to the CodeBuilder to
    set the registers referenced by those RValues appropriately.

## A very simple example

Before we get into more details, here's a very simple example of how we might
generate code for this trivial method:

```
function foo(x, negate) = negate ? -x : x
```

Assuming that `x` will be represented by a double and `negate` by an integer (0
for False, 1 for True), the Java signature for the method we generate will be
`void(TState, double, int)`.

`emit()` on this method will be called with two RValues as `args`, with
templates `d1` for `x` and `i2⸨0:False, 1:True⸩` for `negate`. `emit()` will
create an additional register (`d3`) to store the result of the `?:` expression,
and add these blocks to the CodeBuilder:

```
1: test i2 == 0; T:→ 3
2: d3 = d1; → 4
3: d3 = dNeg(d1);
```

(Block 1 tests the value of `i2` and branches to block 3 if it is zero. Block 2
copies the contents of `d1` to `d3` and then continues execution with block 4.
Block 3 applies the `dNeg` operator (the JVM "negate double" instruction) to
`d1` and stores the result in `d3`.)

At this point `emit()` calls `codeGen.setResult()` with an RValue with template
`d3` (in other words, the method result is the double in register 3). The code
generation framework then adds a few more blocks to the CodeBuilder:

```
4: TState.setResultTemplates(x0, [d0]);
5: x4 ← TState.fnResultBytes(x0, 8);
6: setDouble[](x4, 0, d3);
7: return
```

Block 4 sets the `resultTemplates` field in the TState to a single-element list
containing the template `d0` (the second argument to setResultTemplates is a
constant reference, which here is printed as `[d0]`). This template will be
interpreted by the caller of the generated code; the numeric var indices (0, in
this case) refer not to registers but to offsets in the TState's designated
result array.

Block 5 gets the TState's result array, ensuring that it is at least 8 bytes
long (since we need to store a double). Block 6 writes the double to the first
location in that array.

Finally block 7 returns to the caller.

The generated code for this method is now complete; the CodeBuilder will apply a
few optimizations, translate the blocks to bytecodes, and then load them as a
MethodHandle.

## Generating code for an InstructionBlock

Like the `execute()` method, `emit()` on an InstructionBlock calls `emit()` on
each of the instructions in sequence. There are, however, a few key differences:

*   `execute()` on a conditional branch instruction evaluates the condition and
    continues execution with either the branch target or the next instruction.
    `emit()` on a conditional branch will usually emit a block that evaluates
    the condition at runtime, and then call `emit()` on both the branch target
    and the next instruction, attaching the results to the appropriate outlinks
    of the test block.

*   After a conditional branch, separate code paths may join at a branch target,
    and may have different values for the local variables. To handle this we may
    need to allocate additional registers, construct an RValue that can
    represent the full range of possible values, and add blocks to each incoming
    link to initialize the new registers appropriately so that the values are
    preserved.

If `emit()` on a conditional branch determines that only one outcome is
possible, it can omit both the test and the unreachable instructions. As a
simple example, suppose that the function above had been written

```
function foo(x, negate) {
  assert x is Number
  return negate ? -x : x
}
```

The corresponding instruction block would look like

```
  branch L1 if x is Number
  error "Assertion failed"
L1:
  branch L2 if not negate
  _t0 = negative(x)
  branch L3
L2:
  _t0 = x
L3:
  return _t0
```

Assuming again that the `x` argument is an RValue with template `d1`, `emit()`
on the first instruction will not add any blocks to the CodeBuilder; since that
RValue can only represent Numbers the test must always take the branch. This
means that the second instruction is unreachable, so `emit()` will not be called
on it. In the end the same bytecodes are emitted for this version of the method
as for the earlier one.

## Condition

Condition is a class used by the VM to represent the results of tests that may
only be known when generated code is executed; it can be thought of as the
boolean version of RValue. For example, `Condition.numericEq(v1, v2)` returns a
Condition that is true if `v1` and `v2` (which must both be numbers) are equal.
If neither `v1` nor `v2` is an RValue (i.e. both are NumValues), `numericEq`
returns a constant Condition (either `Condition.TRUE` or `Condition.FALSE`), but
if either is an RValue the returned Condition can be used to add a test block
that branches if the condition is false.

Conditions can be combined using the usual and/or/not operators, and there are
convenience operations that often make it easy to write built-in methods that
work equally well when executing or generating code, e.g.

```
Err.INVALID_ARGUMENT.unless(Condition.numericLessThan(NumValue.ZERO, index));
```

will

*   do nothing if `index` is a NumValue greater than zero;
*   throw a BuiltinException if `index` is a NumValue less than or equal to
    zero; and
*   if `index` is an RValue with template `i5`, emit a TestBlock that tests `0 <
    i5` and escapes from the generated code if the test fails.

The `Condition.test(ifTrue, ifFalse)` method is passed two Runnables, and calls
one based on the condition.

## Generating code for a built-in method

As with an instruction block, `emit()` on a built-in method calls each step once
to add blocks to the CodeBuilder and determine the RValues that are returned,
passed to the next step(s), and/or passed as arguments to another function.
Unlike instruction blocks, steps in a built-in may branch backward to a previous
step; this requires extra handling.

For example, suppose we are generating code for the built-in function
`iterateUnbounded` which repeatedly applies a lambda to update a given state
value until a LoopExit state is reached. The documented behavior is described by
this Retrospect code:

```
function iterateUnbounded(lambda, state) {
  for sequential state {
    if state is LoopExit { break }
    state = at(lambda, state)
  }
  return state
}
```

... but the actual implementation is this built-in method (see
[the built-in method docs](builtins.md) for background on how to read this):

```
static class IterateUnbounded extends BuiltinMethod {
  static final Caller at = new Caller("at:2", "afterAt");

  @Core.Method("iterateUnbounded(_, _)")
  static void begin(TState tstate, Value lambda, Value state) {
    tstate.jump("afterAt", state, lambda);
  }

  @LoopContinuation
  static void afterAt(TState tstate, Value state, @Saved Value lambda) {
    Condition.isa(Core.LOOP_EXIT, state).test(
        () -> tstate.setResult(state),
        () -> tstate.startCall(at, lambda, state).saving(lambda));
  }
}
```

(Careful readers will note that this listing is **wrong**: for simplicity I have
omitted the `@RC.In` annotations and `addRef()` calls that are needed to
maintain correct reference counts during execution. Reference counts will be
addressed later.)

The built-in begins with the `begin()` step, which simply advances to the other
step (`afterAt`) after swapping the order of the arguments. The `afterAt` step
tests whether `state` is a LoopExit:

*   if the test succeeds, the built-in returns with `state` as its result;

*   if the test fails, the built-in calls the `at` function with `lambda` and
    `state`; when that returns, the Caller is configured to resume execution at
    the `afterAt` step (again), passing the function's result as the first
    argument and the saved `lambda` as its second argument.

When executing the built-in, the afterAt step will be run over and over again
until a LoopExit is returned.

When generating code we will only run each step once, but the generated code for
the `afterAt` step will link back to the beginning of the step after the call to
`at` returns. (CodeBuilder doesn't allow simply linking a block back to an
earlier block, but we can use a Loop block to do that.)

## Generating code for a function call

As with execution, a function call is one of the most complex cases for code
generation.

Some complexity comes from the possibility that multiple methods might be
applicable. If the argument types are not sufficiently known at code generation
time to determine the method we must generate code to test them. For example, if
the first argument in a call to `at()` could be either of two lambda types, we
must generate code to test its type and then generate code to call each of the
possible methods.

We have two options when generating the code for each method:

*   we can *inline* it, generating more blocks in the current CodeBuilder; or
*   we can *exline* it, inserting a call to a MethodHandle that will generated
    separately.

The choice between inlined and exlined is based on the MethodMemo; see the doc
on [memos](memos.md) for more details. In practice almost all method calls are
inlined.

(For the exlined case we use a MutableCallSite so that the called MethodHandle
can be generated later, and may be replaced if we re-generate its code after
additional execution.)

Additional blocks must be emitted for the rest of the function call contract:
handling errors and adding stack entries as needed for unwinding or traces. For
inlined methods these extra checks can often be optimized away.

---

\> &nbsp; &nbsp; [Part 2](codegen_2.md) &nbsp; &nbsp; [Part 3](codegen_3.md)
&nbsp; &nbsp; [Part 4](codegen_4.md)
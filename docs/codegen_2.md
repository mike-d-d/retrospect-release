# Generating Java bytecodes for Retrospect, Part 2

For this section we will look in detail at how Retrospect can generate code for
the following method, the traditional recursive implementation of factorial:

```
function factorial(n) = n < 2 ? 1 : n * factorial(n - 1)
```

[TOC]

## The VM instructions

The Retrospect VM does not see the Retrospect source code given above; instead
it works with the output of the Retrospect compiler, which translates the above
method declaration into this sequence of instructions:

```
  _t0 = lessThan(n, 2)
  branch L1 if not _t0
  return 1
L1:
  _t0 = subtract(n, 1)
  _t0 = factorial(_t0)
  _t0 = multiply(n, _t0)
  return _t0
```

There are four call instructions; one is a recursive call to the `factorial`
function, and the others are calls to core functions with built-in methods
(`lessThan`, `subtract`, and `multiply`).

## Setting up code generation

Code generation will be activated after this method has been executed a number
of times. The associated MethodMemo will record information about those calls;
for the purposes of this example we will assume that

*   all calls have passed an integer; and
*   all calls have returned an integer.

Note that Retrospect uses float64 to represent numbers that are too big to fit
in an int32, so calls to `factorial()` with `n` greater than or equal to 13 will
return their result as a float64; we are assuming that there have been no such
calls (yet).

To generate code for this method, the VM creates a new CodeGen instance, which
contains a new CodeBuilder. The CodeBuilder is configured to build a Java method
with signature `void(TState, int)` (`void` return type since method results are
stored in the TState), and we start at the first instruction of the block with
`n` bound to an RValue with template `i1`.

## A simplification

Most Retrospect function calls simply compute a value and return it in the
TState, but under some circumstances they may create stack entries instead of
(error or blocking) or in addition to (tracing) returning a result. In order to
match the behavior of the VM and to enable debugging, the generated code must be
able to create the same stack entries under the same conditions.

To make this introduction to code generation easier to understand we'll omit the
stack management blocks from the first version we present. After seeing how that
works, we'll present the full version with stack management included.

## Generating code instruction-by-instruction

The first block is a call to the `lessThan` core function:

```
  _t0 = lessThan(n, 2)
```

Knowing that the type of `n` is Number (by examining its RValue template), the
code generator can determine that this will call the built-in method for
`lessThan(Number, Number)`, and calls `emit()` on that method.

Since one of its arguments is an RValue, the result of the function call is
unknown at code generation time and must be represented by another RValue. To do
that built-in method allocates another register (`i2`), and uses a test block to
set it to 1 if `lessThan` should return True or 0 if it should return False:

```none
 1: test i1 < 2; F:→ 3
 2: i2 ← 1; → 4
 3: i2 ← 0;
```

(This is a CodeBuilder listing; see [the docs](code.md#examining-the-results)
for some guidance on how to read it.)

We can then record that `_t0` (the function result) is an RValue with template
`i2⸨0:False, 1:True⸩` and proceed to the next instruction.

The next instruction is the conditional branch:

```
  branch L1 if not _t0
```

Given the representation of `_t0` this is easy to generate code for: we just
need a "test `i2 == 0`" block:

```none
 4: test i2 == 0; F:→ 6
```

(Don't worry about the indirectness of using a conditional branch to set the
value of `i2` and then immediately testing it; the CodeBuilder's optimizations
will clean that up for us.)

The next instruction is a simple return:

```none
  return 1
```

This does not translate to a simple JVM `return` instruction. In order to
support returning multiple values and returning unboxed values the
[calling sequence](codegen_1.md?#calling-generated-code) instead requires that
we generate instructions to store the returned value(s) in `tstate`, using an
appropriate template. For now we will simply store the value we need to return
in a new register and branch to the shared return code we will generate later:

```none
 5: i3 ← 1; → 12
```

The next instructions are the branch target referred to by the previous
conditional branch and a call to the `subtract` built-in method:

```
L1:
  _t0 = subtract(n, 1)
```

Again, we will need an RValue (with template `i4`) referencing a new register to
represent the result (`_t0`) of this call.

The JVM `iSub` instruction does almost what we want here, except that it wraps
around on overflow/underflow and Retrospect arithmetic does not. So instead we
call the `Math.subtractExact()` operation and catch the ArithmeticException it
can throw:

```none
 6: i4 ← Math.subtractExact(i1, 1); ArithmeticException:→ 15
```

An ArithmeticException here would indicate that the result cannot be represented
with an int32, and Retrospect would switch to using a float64. It would also
record that in the ValueMemo for this function call's results, and next time we
generated code we would use a `dSub` instruction here.

(CodeBuilder is actually capable of recognizing that since `i1` is known to be
non-negative at this point, the subtraction can't underflow and an `iSub`
instruction would be OK, but that optimization has not yet been implemented. But
since the JVM translates the `subtractExact` call to the same underlying machine
instruction and just adds a "branch on overflow" instruction after it the
performance benefit of such an optimization would be quite small.)

The next instruction is a recursive call to the `factorial` method that we are
generating code for:

```
  _t0 = factorial(_t0)
```

Unlike the other function calls in this sequence, which were inlined (i.e. the
blocks to implement them were added to this CodeBuilder), we do not want to try
to inline the implementation of `factorial`. Instead we will add an "exlined
call" block, which takes care of emitting the JVM instructions necessary to

*   push the appropriate registers on the stack,
*   call the MethodHandle for the exlined method's generated code;
*   when it returns, recover any new `stackRest` that might have been saved in
    the TState;
*   check if the stack is being unwound; and
*   check that if a result was returned, it used the expected template.

```none
 7: factorial_0(x0, i4); x5 ← stackRest; unwind:→ 17
```

(`factorial_0` is an arbitrary name assigned by the VM to this method; we'll see
how `x5`, the recovered `stackRest`, is handled later.)

That block is followed by two more blocks, one to retrieve the method result
(since the exlined call block has already verified the result template, it can
now just pull the result from the first four bytes of the TState's
`fnResultBytes` scratch space), and one to clear the scratch space so that it is
ready for the next use:

```none
 8: i6 ← int[](TState.fnResultBytes(x0, 0), 0);
 9: TState.clearResults(x0);
```

`_t0` is set to an RValue with template `i6`, and we are ready to proceed to the
next instruction:

```none
  _t0 = multiply(n, _t0)
```

As with the earlier call to `subtract`, this is implemented using the
`Math.multiplyExact()` operation to catch the possible overflow:

```none
10: i8 ← Math.multiplyExact(i1, i6); ArithmeticException:→ 15
```

and `_t0` is set to an RValue with template `i8`.

The final instruction

```
  return _t0
```

is implemented by storing the result into our TState, along with an appropriate
template (reversing the steps that were done after the recursive call above):

```none
11: i3 ← i8;
12: TState.setResultTemplates(x0, `[i0]`);
13: setInt[](TState.fnResultBytes(x0, 4), 0, i3);
14: return
```

Note that the last 3 blocks are shared with the `return 1` instruction that was
processed earlier, so we need to copy `i8` into the same location that it used
for the result (`i3`).

Together these blocks complete the "normal path" for our method; all that is
missing is the handlers for the things that can go wrong:

1.  the ArithmeticExceptions that can be thrown from the `subtractExact` and
    `multiplyExact` operations;
2.  the check for "are we unwinding?" after the recursive call; and
3.  the check for "does the method result use the template we expected?" after
    the recursive call.

In the first and third cases we want to *escape*: there isn't necessarily
anything wrong with the user's program, but the generated code has encountered a
situation that it wasn't constructed to handle. It will start unwinding the
stack with enough information about where it was to be able to resume direct
execution without having lost any state.

In the second case the recursive call has already started unwinding the stack;
all we need to do is add a stack entry describing which instruction we were
executing and the values of our local variables and return.

And finally `trace` instructions introduce some additional stack-related
complexity:

*   Each time the method executes a `trace` instruction, it must save a new
    stack entry to the TState's ResourceTracker. Each of those stack entries
    must share a common tail (to be populated by this method's callers), and
    that tail must be saved in the TState when the method returns.

*   Even if an exlined call doesn't start unwinding the stack, the called method
    may have (directly or indirectly) executed a `trace` instruction; if so, we
    need to add the same stack entry that we would have generated when unwinding
    the stack before continuing with normal execution.

## Building stacks

During direct execution of a method the VM uses the `stackRest` field in its
TState to keep track of whether the function caller will need to provide a stack
entry describing the call site. `stackRest` is always set to null before
starting a call, and checked on return from the call; if it is non-null, its
head must be set to an appropriate stack entry by the caller.

(See [this doc](stacks.md) for background on the stack data structures, and
`TState.beforeCall()` for the code that builds stacks during direct execution.)

To avoid having to save and restore the `stackRest` field around each call,
generated code allocates a register to save the value that it will set as
`stackRest` when the current method returns. Before starting the body of the
method we allocate `x2` to store the current value of `stackRest`, and
initialize it to null. Immediately before returning from the method we add a
block that saves the value of `x2` in the TState:

```none
21: TState.setStackRest(x0, x2);
22: return
```

Now we are ready for the first error-handling blocks; these handle the
ArithmeticException from the `subtractExact`:

```none
15: x7 ← newStackEntry(`_t0 = subtract(n, 1) {n=i1}`);
16: x2 ← TState.startUnwind(x0, x7, x2); → 21
```

The first block allocates a Frame with a RecordLayout whose template has

*   a base type that links to the Retrospect instruction (in this case, the call
    to `subtract()`); and
*   an element template for each live variable (in this case, the only live
    variable is `n` and we can represent it with an int32, set from register
    `i1`).

The sequence of JVM instructions that are generated to allocate and initialize
this Frame are only finalized after all optimizations are complete, since they
may simplify the required template.

The second block starts unwinding, using the new stack entry as the head of the
stack. The tail of the stack will be `x2`; if we had previously executed a trace
instruction we would share its tail, but since we haven't (`x2` is always null
when we reach this block) a new stack tail will be allocated and saved as the
new value of `x2`.

The `→ 21` after the second block continues execution with the `setStackRest`
call listed above.

The second error handler, to handle unwinding during the recursive call, is
similar but will use a different RecordLayout since it references a different
instruction. It also differs in that rather than starting to unwind the stack,
it is adding this entry to an already-started stack, so it calls a different
TState method that takes the `stackRest` from the exlined call as an additional
parameter:

```none
17: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
18: x2 ← TState.unwinding(x0, x7, x5, x2); → 21
```

The final error handler, for an ArithmeticException thrown from the
`multiplyExact`, will need a RecordLayout that represents two local variables
(`n` and `_t0` are both live, and will be set from registers `i1` and `i6`
respectively):

```none
19: x7 ← newStackEntry(`_t0 = multiply(n, _t0) {n=i1, _t0=i6}`);
20: x2 ← TState.startUnwind(x0, x7, x2);
```

The final missing piece is a check after the recursive call to see if a stack
entry is needed (i.e. if there was a non-null `stackRest` after the call, which
can only happen if we're unwinding (already handled) or a `trace` instruction
was executed):

```none
 8: test x5 == null; T:→ 12
 9: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
10: TStack.set(x5, x7);
11: x2 ← TState.setTStackRestAndDrop(x0, x5, x2);
```

This creates the same stack entry as the error handler for the recursive call,
but just saves it and continues execution.

## The complete version

Here's what the completed code looks like, after some simple optimizations and
with updated block numbers:

```none
 1: x2 ← null;
 2: test i1 < 2; F:→ 4
 3: i3 ← 1; → 13

 4: i4 ← Math.subtractExact(i1, 1); ArithmeticException:→ 15
 5: factorial_0(x0, i4); x5 ← stackRest; unwind:→ 17
 6: i6 ← int[](TState.fnResultBytes(x0, 0), 0);
 7: TState.clearResults(x0);
 8: test x5 == null; T:→ 12
 9: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
10: TStack.set(x5, x7);
11: x2 ← TState.setTStackRestAndDrop(x0, x5, x2);

12: i3 ← Math.multiplyExact(i1, i6); ArithmeticException:→ 19

13: TState.setResultTemplates(x0, `[i0]`);
14: setInt[](TState.fnResultBytes(x0, 4), 0, i3); → 21

15: x7 ← newStackEntry(`_t0 = subtract(n, 1) {n=i1}`);
16: x2 ← TState.startUnwind(x0, x7, x2); → 21

17: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
18: x2 ← TState.unwinding(x0, x7, x5, x2); → 21

19: x7 ← newStackEntry(`_t0 = multiply(n, _t0) {n=i1, _t0=i6}`);
20: x2 ← TState.startUnwind(x0, x7, x2);

21: TState.setStackRest(x0, x2);
22: return
```

## Escaping from generated code

As noted earlier, this code was generated based on the assumption that the
result of `factorial` could be represented as an int32, which was the case
because it had not been called with `n` greater than 12. As long as the
generated code is called with similarly low values of `n` it should perform
well.

But past performance does not guarantee future results — what will happen if
this code is called with a larger `n`, say 20?

(For now we'll assume that `n` isn't so large as to trigger a stack overflow, a
very real possibility that we will also need to defend against, but not one we
are addressing yet).

The first sign of a problem will be the `multiplyExact` operation throwing an
ArithmeticException when trying to compute the result of `factorial(13)`. The
exception handler (starting at block 19 in the listing above) will start
unwinding the stack with a stack entry that identifies the failing instruction
(`_t0 = multiply(n, _t0)`) and the values of the locals (`n` is 13, `_t0` is
479001600).

This code returns to its caller (the same code, part way through computing
`factorial(14)`) where part of the instruction sequence emitted for the
recursive call block (`TState.checkExlinedResult()`) sees the stack entry
created by the escape and switches back to direct execution at the call to
`multiply`, reconstructing the locals array from the values in the stack entry.
The built-in method for `multiply` will first try the `multiplyExact` again, but
when it catches the ArithmeticException it will cast the arguments to doubles
and multiply them. The result (now represented as 6227020800, a float64) will be
saved back in the locals array, and then the `return _t0` instruction will save
it in the TState and return. The MethodMemo will also be updated, to record that
both the result of the `multiply` call and the result of `factorial` should be
represented as float64.

When the direct execution of `factorial(13)` completes,
`TState.checkExlinedResult()` returns to the in-progress execution of
`factorial(14)`, but returns false to indicate that the called method's result
did not use the expected template (and can't, since the template is an int32).
That causes the `factorial(14)` call to escape as well, this time with a stack
entry (from block 17) for the recursive call.

`TState.checkExlinedResult()` again resumes direct execution, but this time with
the `_t0 = factorial(_t0)` instruction. It retrieves the returned value
(6227020800) and saves it as the value of `_t0`, then continues to the
`multiply` call. After multiplying 14 by 6227020800, the result (87178291200) is
saved as the method result and we return again.

These steps repeat for each of the remaining callers (`n` from 15 to 20), until
the outermost call returns with a result of 2432902008176640000. Although we had
to stop using the generated code once its assumptions were violated, we were
able to continue direct execution from where it left off, and computed the same
answer as if no code had been generated.

If we continue to use the `factorial` method we will trigger a re-run of code
generation, but the results this time will be different due to the MethodMemo
updates. In particular, although the argument is still represented as an int32
the result is represented as a float64, and the generated code now looks like

```none
 1: x2 ← null;
 2: test i1 < 2; F:→ 4
 3: d3 ← 1; → 14

 4: i4 ← Math.subtractExact(i1, 1); ArithmeticException:→ 16
 5: factorial_1(x0, i4); x5 ← stackRest; unwind:→ 18
 6: d6 ← double[](TState.fnResultBytes(x0, 0), 0);
 7: TState.clearResults(x0);
 8: test x5 == null; T:→ 12
 9: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
10: TStack.set(x5, x7);
11: x2 ← TState.setTStackRestAndDrop(x0, x5, x2);

12: d3 ← dMul(i1, d6);
13: test Double.isNaN(d3) == 0; F:→ 20

14: TState.setResultTemplates(x0, `[d0]`);
15: setDouble[](TState.fnResultBytes(x0, 8), 0, d3); → 22

16: x7 ← newStackEntry(`_t0 = subtract(n, 1) {n=i1}`);
17: x2 ← TState.startUnwind(x0, x7, x2); → 22

18: x7 ← newStackEntry(`_t0 = factorial(_t0) {n=i1}`);
19: x2 ← TState.unwinding(x0, x7, x5, x2); → 22

20: x7 ← newStackEntry(`_t0 = multiply(n, _t0) {n=i1, _t0=d6}`);
21: x2 ← TState.startUnwind(x0, x7, x2);

22: TState.setStackRest(x0, x2);
23: return
```

Note that

*   Some registers are now doubles (`d3` and `d6` in place of `i3` and `i6`).
*   The JVM `dMul` bytecode is used in place of the call to
    `Math.multiplyExact`.
*   Although `dMul` cannot throw an ArithmeticException, it may return a NaN. In
    those cases `multiply()` is documented as returning the singleton `None`, so
    the generated code checks for a NaN and escapes if it occurs.

(Since `dMul` only returns a NaN if one of its arguments is a NaN or it is
multiplying 0 by infinity, in this case that check will never actually cause an
escape.)

---

[Part 1](codegen_1.md) &nbsp; &nbsp; <> &nbsp; &nbsp; [Part 3](codegen_3.md)
 &nbsp; &nbsp; [Part 4](codegen_4.md)

# Generating Java bytecodes for Retrospect, Part 3

For this section we will look at code generation for the following method, which
computes the
[Euclidean norm](https://en.wikipedia.org/wiki/Norm_\(mathematics\)#Euclidean_norm)
of an array of numbers:

```
function norm(v) = sqrt(v | x -> x ** 2 | sequentially(sum))
```

Less formally: this method squares each element of its argument, adds them, and
returns the square root of the sum. It would actually work for any kind of
collection (not just an array) and potentially for element types other than
numbers (if the elements implemented the `exponent()` and `add()`) functions,
but for this exercise we will just consider the case where it is applied to
arrays of numbers.

Also, the method (somewhat unusually) specifies that the sum must be computed
sequentially; this was done for this example to avoid the complexity associated
with potentially parallelizing it, although in practice the collection would
have to be very large or very expensive to compute before Retrospect decided to
parallelize such a simple computation.

[TOC]

## The VM instructions

The Retrospect compiler translates the method declaration for `norm` into a
sequence of six instructions:

```
_t0 = pipe(v, Lambda42)
_t1 = sum()
_t1 = sequentially(_t1)
_t0 = pipe(_t0, _t1)
_t0 = sqrt(_t0)
return _t0
```

"Lambda42" is an arbitrary name assigned to a new singleton type created by the
compiler to represent the lambda expression `x -> x ** 2` (since this lambda
doesn’t reference any of the method's local variables, a singleton is
sufficient). These instructions just call five functions in sequence (`pipe`,
`sum`, `sequentially`, `pipe` again, and then `sqrt`, each time saving the
results in a temporary local (`_t0` or `_t1`). Also note that the compiler does
not have any special understanding of pipelines — the instructions generated for
an expression `a | b | c` are exactly the same as the instructions that would be
generated for `a + b + c`, except that it calls the `pipe` function instead of
`add`.

The compiler must also declare the new singleton type and provide an
implementation of the `at(lambda, x)` (apply a lambda) method that is applicable
when the first argument is a `Lambda42`; the body of that method is just these
two instructions:

```
_t0 = exponent(x, 2)
return _t0
```

## Setting up code generation

For this example we will assume that

*   all calls to this method have passed an array of integers; and
*   the sum of squares calculation has never overflowed an integer.

The MethodMemo will ensure that all callers use a consistent representation for
the argument ("harmonization"), in this case a frame whose layout is a
variable-length array with element template `i0` (i.e. each element is an
int32). See [this doc](frames.md) for details, but the short version is:

*   the argument to this method will be an instance of the Frame1i2x Java class
    (a subclass of Frame that has one int32 field and two pointer (Object)
    fields)
*   the frame's int field (`i0`) will contain the number of elements (*n*) in
    the array
*   the frame's first pointer field (`x0`) will contain a `byte[]` that is
    interpreted as an `int[]`; its first *n* elements are the contents of the
    Retrospect array
*   the frame's second pointer field (`x1`) is not used by this layout and will
    always be null

(This is a particularly simple application of a much more general mechanism.)

Code generation will initialize the CodeBuilder to build a Java method with
signature `void(TState, Frame)` and start at the first instruction of the method
with `v` bound to an RValue with template `x1` (that's a RefVar with index 1;
the RefVar also links to the VArray layout described above).

The first block added to the CodeBuilder will verify that `x1` has the expected
layout, and escape if it does not:

```
 1: test x1 is *[]i0; F:→ 26
```

This check is needed because Frame layouts may evolve over time (e.g. if
subsequent code stored a floating-point number or `None` into one of these
arrays the layout would evolve to be able to represent more values than just
int32s). Generated code must be prepared to escape if it discovers that the
layouts it was generated for are no longer current.

(We will elide the code that generates appropriate stack entries for an escape;
it is just like the previous example.)

## Building a loop

The details of the first four function calls are covered in
[an appendix](codegen_a.md); the interesting thing to note is that none of them
emit any blocks until the fourth one (i.e. `pipe(_t0, _t1)`) calls `iterate()`.

`iterate()` is the core implementation of sequential loops, and takes four
arguments:

*   `collection`: the collection to iterate over, which in this case is
    represented by an RValue with template `TransformedCollection(x1, Lambda42)`
    (i.e. the original int32 array `x1`, with each element lazily transformed by
    `Lambda42`)
*   `eKind`: the singleton `EnumerateValues`, indicating that we will only use
    the elements of `collection` (and not their corresponding keys)
*   `loop`: the constant reducer returned by `sum()` (represented by `Sum(0)`)
*   `state`: the initial state for the reducer, i.e. `0`

`iterate` has only one method, which looks like this:

```
method iterate(collection, eKind, loop, state) {
  if state is LoopExit { return state }
  it = iterator(collection, eKind)
  for sequential state, it {
    element = next(it=)
    if element is Absent { break }
    state = nextState(loop, state, element)
    if state is LoopExit { break }
  }
  return state
}
```

i.e. it calls `iterator` on the collection, and then alternates calls to `next`
(to get the next element from the iterator) and `nextState` (to update the loop
state) until the iterator is exhausted (`next` returns `Absent`) or the loop
state is a `LoopExit` (signaling an early exit).

As with other built-ins, `iterate` is not actually implemented by this
Retrospect code (that would be circular, since the compiler translates
sequential loops into calls to `iterate`). Instead it is implemented using four
static Java methods and three associated static `Caller` objects:

```
static final Caller iterator = new Caller("iterator:2", "afterIterator");
static final Caller next = new Caller("next:1", "afterNext");
static final Caller nextState = new Caller("nextState:3", "afterNextState");

@Core.Method("iterate(_, EnumerationKind, _, _)")
static void begin(
    TState tstate, Value collection, Value eKind, Value loop, Value state) {
  state.isa(Core.LOOP_EXIT).test(
      () -> tstate.setResult(state),
      () -> tstate.startCall(iterator, collection, eKind).saving(loop, state));
}

@LoopContinuation
static void afterIterator(
    TState tstate, Value it, @Saved Value loop, Value state) {
  tstate.startCall(next, it).saving(loop, state);
}

@Continuation(order = 2)
static void afterNext(
    TState tstate, Value element, Value it, @Saved Value loop, Value state) {
  element.is(Core.ABSENT).test(
      () -> tstate.setResult(state),
      () -> tstate.startCall(nextState, loop, state, element).saving(loop, it));
}

@Continuation(order = 3)
static void afterNextState(
    TState tstate, Value state, @Saved Value loop, Value it) {
  state.isa(Core.LOOP_EXIT).test(
      () -> tstate.setResult(state),
      () -> tstate.jump("afterIterator", it, loop, state));
}
```

The details are documented [elsewhere](builtins.md), but the thing to note is
that each of the four static methods represents a point in the control flow of
the `iterate` method:

*   `begin` is executed first. Its annotation (`@Core.Method`) indicates that it
    implements the Retrospect function `iterate` with 4 arguments and is only
    applicable when the second argument is an `EnumerationKind`.
*   `afterIterator` corresponds to the beginning of the `for` loop.
*   `afterNext` corresponds to the `if element is Absent ...` test
*   `afterNextState` corresponds to the `if state is LoopExit ...` test at the
    end of the loop.

As the names suggest, each of the methods other than `begin` is used as the
*continuation* for a call to another function (i.e. the code that will be
executed when the function call returns).

(Note that for clarity I have dropped the Java code that manages reference
counts from this version; those calls are necessary for correctness but not
relevant to code generation.)

When directly executing the `iterate` method these steps will be executed over
and over again until the loop completes, but to generate code each of them will
be called once, and the results linked together in the CodeBuilder. This will
result in a sequence of blocks that looks like this:

```none {highlight="content:TODO(?s:.)*?\n\n"}
// begin(
//   collection = TransformedCollection(x1, Lambda42)
//   eKind = EnumerateValues
//   loop = Sum(0)
//   state = 0):

i3 ← 0;

TODO call to iterator(TransformedCollection(x1, Lambda42), EnumerateValues)
storing result as
   TransformedIterator(ArrayIterator(x4, EnumerateValues, i2), EnumerateValues, Lambda42)

start_loop:

// afterIterator(
//   it = TransformedIterator(ArrayIterator(x4, EnumerateValues, i2), EnumerateValues, Lambda42)
//   loop = Sum(0)
//   state = i3):

TODO call to next(TransformedIterator(ArrayIterator(x4, EnumerateValues, i2), EnumerateValues, Lambda42))
storing results as
   i5⸨0:i6; 1:Absent⸩
   TransformedIterator(ArrayIterator(x7, EnumerateValues, i8), EnumerateValues, Lambda42)

// afterNext(
//   element = i5⸨0:i6; 1:Absent⸩
//   it = TransformedIterator(ArrayIterator(x7, EnumerateValues, i8), EnumerateValues, Lambda42)
//   loop = Sum(0)
//   state = i3):

test i5 == 1;  T:→ loop_done

TODO call to nextState(Sum(0), i3, i6)
storing result as
   i9

// afterNextState(
//   state = i9
//   loop = Sum(0)
//   it = TransformedIterator(ArrayIterator(x7, EnumerateValues, i8), EnumerateValues, Lambda42)):

x4 ← x7
i2 ← i8
i3 ← i9
back → start_loop

loop_done:
// result is i3
```

The blocks emitted by each step are preceded by a comment with both the step
name and the RValues that are passed as arguments to that step. The blocks
emitted for the nested function calls are still missing (with a TODO for each of
them), but note that in addition to RValues for their arguments we have created
RValues for their results (allocating new registers as necessary). We do this
based on the information stored in the MethodMemo; e.g. for the call to
`iterator()`, all previous calls at this site have returned a value of the form
`TransformedIterator(ArrayIterator(<x>, EnumerateValues, <i>), EnumerateValues,
Lambda42)` where `<x>` is a Frame with the varray-of-int32 layout and `<i>` is
an integer, so code generation allocates two new registers (`x4` and `i2`) and
commits to using the given RValue for the result.

Note also that the two `isa(Core.LOOP_EXIT)` tests are dropped by the code
generator, since in each case it can determine that the RValue (`i3` in the
first case, `i9` in the second) cannot possibly represent a LoopExit value.

On the other hand, the `element.is(Core.ABSENT)` test could be true, since the
representation of `element` is a tagged union that includes Absent: `i5⸨0:i6;
1:Absent⸩`, so the code generator emits `test i5 == 1` to implement it.

The `tstate.jump` at the end of the loop (to return to the `afterIterator` step)
is implemented by assigning register contents so that the RValues at the
beginning of `afterIterator` match the corresponding values at the `jump`, and
then adding a BackRef block.

## Filling in the rest

What remains is to fill in the code emitted for the three nested function calls
(to `iterator`, `next`, and `nextState`). The relevant `iterator` methods are:

```
method iterator(TransformedCollection tc, eKind) = TransformedIterator_({
        it: iterator(tc_.base, eKind),
        eKind,
        lambda: tc_.lambda})

method iterator(Array array, EnumerationKind eKind) =
    ArrayIterator_({array, eKind, prevIndex: 0})
```

When applied to the RValues `TransformedCollection(x1, Lambda42)` and
`EnumerateValues` these return

```none
TransformedIterator(ArrayIterator(x1, EnumerateValues, 0), EnumerateValues, Lambda42)
```

Comparing this with our desired result:

```none
TransformedIterator(ArrayIterator(x4, EnumerateValues, i2), EnumerateValues, Lambda42)
```

we need only emit two register assignments:

```none
x4 ← x1
i2 ← 0
```

The `next` method on a TransformedIterator has some complexity to handle cases
that will not arise in this example, such as handling the keys when `eKind` is a
value other than EnumerateValues and handling an Absent returned from the
lambda. With that complexity removed it is simply:

```
method next(TransformedIterator it=) {
  item = next(it_.it=)
  return (item is Absent) ? Absent : at(it_.lambda, item)
}
```

i.e. call `next` on the wrapped iterator and then call `at` to apply the lambda
value to its result (unless the wrapped iterator returns Absent, indicating that
the iterator is exhausted).

Here's the corresponding generated code, again with missing pieces for the
nested function calls:

```none {highlight="content:TODO(?s:.)*?\n\n"}
TODO call to next(ArrayIterator(x4, EnumerateValues, i2))
storing results as
   i10⸨0:i11; 1:Absent⸩
   ArrayIterator(x7, EnumerateValues, i8)

  test i10 == 1;  F:→ not_absent
  i5 ← 1; → done_next_transformed
not_absent:

TODO call to at(Lambda42, i11)
storing results as
    i6

  i5 ← 0;
done_next_transformed:
```

The `next` method on an ArrayIterator again has some complexity that does not
apply to this example; without that it looks like

```
method next(ArrayIterator it=) {
  index = it_.prevIndex
  if index >= size(it_.array) {
    return Absent
  }
  index += 1
  it_.prevIndex = index
  return it_.array[index]
}
```

i.e. check whether the iterator is exhausted, and if not advance it and return
the next array element.

The corresponding generated code:

```none
  test i2 < Frame1i2x.i0(x4); T:→ not_done
  i10 ← 1;
  i8 ← i2; → done_next_array
not_done:
  i8 ← iAdd(i2, 1);
  i11 ← int[](Frame1i2x.x0(x4), i2);
  i10 ← 0;
done_next_array:
  x7 ← x4;
```

(`Frame1i2x.i0` and `Frame1i2x.x0` are just field accessors for the Frame1i2x
Java class; note that since we are implementing 1-based Retrospect arrays with
0-based Java arrays we use the `prevIndex` value as an index rather than the
incremented value.)

Then we have the code generated for calling `at(Lambda42, i11)`, which is simply

```none
  i6 ← Math.multiplyExact(i11, i11); ArithmeticException:→ escape
```

The final piece is the call to `nextState(Sum(0), i3, i6)`; the relevant method
is simple:

```
method nextState(Sum, state, value) = state + value
```

... as is the corresponding generated code:

```none
  i9 ← Math.addExact(i3, i6); ArithmeticException:→ escape
```

## The finished code

Putting all those pieces together, adding in the final call to `sqrt` and the
return instruction, and letting CodeBuilder clean up the redundancy, we end up
with

```none
  1: test x1 is *[]i0; F:→ escape
  2: i2 ← 0;
  3: i3 ← 0;
= 4: test i2 < Frame1i2x.i0(x1); F:→ 10
  5: i8 ← iAdd(i2, 1);
  6: i11 ← int[](Frame1i2x.x0(x1), i2);
  7: i6 ← Math.multiplyExact(i11, i11); ArithmeticException:→ escape
  8: i3 ← Math.addExact(i3, i6); ArithmeticException:→ escape
  9: i2 ← i8; → 4

-10: d6 ← Math.sqrt(i3);
 11: test Double.isNaN(d6) == 0; F:→ escape
 12: dropRef(x1);
 13: TState.setResultTemplates(x0, `[d0]`);
 14: setDouble[](TState.fnResultBytes(x0, 8), 0, d6);
 15: return
```

The only unexplained piece there is the call to `dropRef()` just before the
return blocks, which is necessary to maintain correct reference counts; the next
section will cover how code generation handles reference counts.

## The role of the MethodMemo

We have alluded several times to the role the MethodMemo plays in code
generation, and particularly how it provides the kind of type information that
other systems get from explicit declarations in the source code.

But the MethodMemo's information is much finer-grained than conventional type
information; for example, knowing that the array is transformed by Lambda42 (and
not just any lambda value) allows us to generate much simpler code.

We can also see this by revisiting one of the initial assumptions in this
example: that all calls to `norm` (from this call site) had passed a
variable-length array of integers. If instead all calls had passed a two-element
array of doubles the generated code would be completely different, even though
the user-visible type (array of numbers) is unchanged and the same methods are
invoked.

The first difference would be in the Java signature of the generated code:
rather than `void(TState, Frame)` it would be `void(TState, double, double)`
(passing the array elements separately).

An additional simplification is possible when code generation detects that a
loop will execute a small, fixed number of times (and the loop body is not too
large): it can skip the loop setup completely and just replicate the loop body
(a simple version of
[loop unrolling](https://en.wikipedia.org/wiki/Loop_unrolling)).

The final results of code generation with this MethodMemo are just

```none
d3 ← dAdd(dMul(d1, d1), dMul(d2, d2));
d4 ← Math.sqrt(d3);
test Double.isNaN(d4) == 0; F:→ escape
TState.setResultTemplates(x0, [d0]);
setDouble[](TState.fnResultBytes(x0, 8), 0, d4);
return
```

---

[Part 1](codegen_1.md) &nbsp; &nbsp; [Part 2](codegen_2.md) &nbsp; &nbsp; <>
&nbsp; &nbsp; [Part 4](codegen_4.md)

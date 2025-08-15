# Enumeration and Collectors

[TOC]

## Overview

Processing each element of a collection is one of the core operations in
Retrospect, underlying both the `for` statement and many uses of the `|` (pipe)
operator. This document describes the types and functions that are used to
implement this kind of processing.

Note that most Retrospect code doesn't use these relatively low-level APIs;
`for` statements or pipes are usually the clearest way to express computations
over collections. These APIs may be needed

*   when defining a new collection type, or
*   when implementing some algorithms over collections whose flow of control
    doesn't correspond cleanly to the options provided by `for` statements.

First, some terminology:

*   *Enumerating* a collection executes some operation once for each element of
    the collection to produce a final result;
*   *Iterating* is sequential enumeration, where the result of processing the
    previous elements is provided as input when processing the next element.

Iteration is fundamentally sequential (we can only process an element after the
processing of the previous element is complete) and hence depends on the
ordering of the collection, while enumeration is more general and supports
parallel and unordered execution.

Both kinds of enumeration also support *early exit*: when processing any element
we may determine that the final result is now known, and there is no need to
process the rest of the collection.

## Kinds of Enumeration

For the purposes of enumeration we think of a collection as containing zero or
more (key, value) pairs. The value `Absent` may be used to indicate that there
is no value corresponding to a key, even though the key is present in the
collection's set of keys. For example, given the array

```
a = ["a", Absent, "b"]
```

*   `size(a)` returns 3
*   `keys(a)` returns `[[1], [2], [3]]`
*   `a[1]` returns `"a"`
*   `a[2]` returns `Absent`

When enumerating we can choose among three options, each identified by a
singleton:

*   `EnumerateValues`: enumeration will return the value of each (key, value)
    pair, excluding those whose value is `Absent`. For the example above,
    enumeration would return `"a"` and `"b"`.
*   `EnumerateWithKeys`: enumeration will return (key, value) pairs, excluding
    those whose value is `Absent`. For the example above, enumeration would
    return `[[1], "a"]` and `[[3], "b"]`.
*   `EnumerateAllKeys`: enumeration will return all (key, value) pairs,
    including those whose value is `Absent`. For the example above, enumeration
    would return `[[1], "a"]`, `[[2], Absent]` and `[[3], "b"]`.

The type `EnumerationKind` contains these three singletons (and nothing else).

## Sequential Loops

Sequential loops are based on an iteration API, similar to that in many other
languages:

*   an `iterator()` function returns a sequential iterator for a collection; and
*   a `next()` function that returns the next element from an iterator.

```
open type Iterator

// eKind must be one of EnumerateValues, EnumerateWithKeys, or EnumerateAllKeys
open function iterator(collection, eKind)

// Either returns the next element of the iteration (a value or a [key, value]
// pair, depending on the eKind used to construct the iterator) and updates
// iterator to the remainder, or returns Absent if there are no more elements.
// The output value of iterator is unspecified if next() returns Absent.
open function next(iterator=)

// Some useful iterator constructors
function emptyIterator() = TrivialIterator_(Absent)
function oneElementIterator(x) = TrivialIterator_(x)

// pipe() can be used to apply a lambda to each element returned by an iterator.
// elements transforming to Absent are dropped.
method pipe(Iterator it, Lambda lambda) =
    TransformedIterator_({it, eKind: EnumerateValues, lambda})

private compound TrivialIterator is Iterator
method next(TrivialIterator it=) {
  result = it_
  it = emptyIterator()
  return result
}

private compound TransformedIterator is Iterator
method next(TransformedIterator it=) {
  for sequential it {
    result = next(it_.it=)
    if result is not Absent {
      result = transformElement(result, it_.lambda, it_.eKind)
      if result is Absent {
        continue
      }
    }
    break { return result }
  }
}

private function transformElement(element, lambda, eKind) {
  if eKind is EnumerateValues {
    return lambda @ element
  }
  [key, value] = element
  if value is not Absent {
    value = lambda @ value
    if value is Absent and eKind is not EnumerateAllKeys {
      return Absent
    }
  }
  return [key, value]
}
```

For example:

```
// Returns the key of the largest element in the given collection, or None if
// the collection is empty.
function keyOfMax(collection) {
  it = iterator(collection, EnumerateWithKeys)
  first = next(it=)
  if first is Absent {
    return None
  }
  [maxKey, max] = first
  for sequential it, max, maxKey {
    element = next(it=)
    if element is Absent {
      break
    } else if element[2] > max {
      [maxKey, max] = element
    }
  }
  return maxKey
}
```

Again, note that this example is intended as an illustration of how to use the
low-level iterator API; usually we would write such a function using
higher-level APIs, e.g.

```
function keyOfMax(collection) {
  maxKV = withKeys(collection) | maxAt([2])
  return maxKV is Absent ? None : maxKV[1]
}
```

## Processing Each Element of a Collection Sequentially

While Retrospect programs can use `iterator()` and `next()` directly, often it
is more convenient to express iteration as a state machine: given

*   an initial state, and
*   a function `nextState()` that takes the current state and the next element
    and returns the new state,

the function `iterate()` iterates over all elements in a collection by
repeatedly applying the `nextState()` function to each element and carrying
forward the resulting state; the result of the iteration is the final state. We
use values of type `Loop` to represent these state machines.

```
open type Loop

// Returns the next state
open function nextState(loop, state, element)

// Calls nextState with each element of the collection, returning the final
// state.
function iterate(collection, eKind, loop, initialState)
```

The `loop` argument determines the behavior of `nextState`; for example, we
could rewrite `keyOfMax` this way:

```
private singleton KeyOfMaxLoop is Loop

// state is Absent if this is the first element, otherwise [maxKey, maxValue]
method nextState(KeyOfMaxLoop, state, element) =
    (state is Absent or element[2] > state[2]) ? element : state

function keyOfMax(collection) {
  result = iterate(collection, EnumerateWithKeys, KeyOfMaxLoop, Absent)
  return (result is Absent) ? None : result[1]
}
```

`iterate` also provides a means to stop the iteration before all elements have
been processed: if `nextState` returns a state of type `LoopExit`, `iterate`
will just return that state as its result without any further iteration. In
other words, the implementation of `iterate` is equivalent to:

```
compound LoopExit
function loopExit(finalState) = LoopExit_(finalState)
function loopExitState(LoopExit exit) = exit_

function iterate(collection, eKind, loop, state) {
  it = iterator(collection, eKind)
  for sequential state, it {
    if state is LoopExit { break }
    element = next(it=)
    if element is Absent { break }
    state = nextState(loop, state, element)
  }
  return state
}
```

## Sequential loops without a collection

Retrospect also supports sequential loops without a collection. Since there is
no element to pass to the nextState function, it is just a simple Lambda from
state to state. The function `iterateUnbounded` takes such a Lambda an an
initial state, and calls the Lambda repeatedly until it returns a LoopExit
value. For example, we could compute the greatest common divisor of two integers
with:

```
function gcd(x, y) = iterateUnbounded(
        // next state lambda
        [u, v] -> (v == 0) ? loopExit(u) : [v, u % v],
        // initial state
        [abs(x), abs(y)])
```

The implementation of `iterateUnbounded` is equivalent to

```
function iterateUnbounded(lambda, state) {
  for sequential state {
    if state is LoopExit { break }
    state = lambda @ state
  }
  return state
}
```

## Parallel Loops

Enabling parallel enumeration requires a somewhat more complex API. Since we may
now be processing subsets of the collection independently, we must be able to

*   *fork* the state, so that each subset can update its own copy of the state;
    and
*   *combine* multiple separately-updated states to get a final state.

In order to give the implementation maximum flexibility, parallel loops
implement two alternatives for forking state:

*   creating a new "empty" state; or
*   splitting an existing state into two states.

The default implementation of splitting just returns an empty state along with
the state it was given, but in some cases there may be efficiency benefits to
providing a specialized split method for a loop's state.

```
// Returns an empty state for the given loop.
open function emptyState(loop)

// Returns a new state and updates the given state; the result of combining
// those two states should be equivalent to the original state.
open function splitState(loop, state=)

// Combines two states.
// Will not be called with either state Absent or a LoopExit.
open function combineStates(loop, state1, state2)

// The default implementation just returns Absent; if that's not a suitable
// state for your loop you must override this method.
method emptyState(loop) default = Absent

// The default implementation just returns the result of emptyState(),
// leaving the given state unchanged.  It is not necessary to override this
// method, but for some loops there may be an efficiency benefit to doing so.
method splitState(loop, state=) default = emptyState(loop)
```

Given a parallelizable Loop (i.e. one that has appropriate methods for
`emptyState()`, `splitState()`, and `combineStates()`), the `enumerate()`
function is used to run it for each element of a collection. Unlike iteration,
there is no shared implementation of parallel enumeration; each collection type
must provide an appropriate method in order to support parallel enumeration (the
default method just falls back on iteration).

```
// Calls nextState with each element of the collection, returning the final
// state.
// Collections that support parallel enumeration must provide a method for
// this function.
// The default implementation falls back on sequential execution.
open function enumerate(collection, eKind, loop, initialState)

method enumerate(collection, eKind, loop, initialState) default =
    iterate(collection, eKind, loop, initialState)
```

Returning to our previous example, with one small addition we can make
`keyOfMax` parallelizable:

```
function combineStates(KeyOfMaxLoop, state1, state2) =
    (state1[2] > state2[2]) ? state1 : state2

function keyOfMax(collection) {
  result = enumerate(collection, EnumerateWithKeys, KeyOfMaxLoop, Absent)
  return (result is Absent) ? None : result[1]
}
```

## Collectors

Most pipelines connect a source collection to a Collector such as `sum` or
`save`. Collectors describe how to process the elements of a given collection to
produce a final result, in a way that enables them to be composed and
manipulated.

```
open type Collector
// Returns a struct { eKind, loop, initialState, canParallel }
open function collectorSetup(collector, collection)

method pipe(Collection collection, Collector collector) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, collection)
  if initialState is LoopExit {
    state = initialState
  } else if canParallel {
    state = enumerate(collection, eKind, loop, initialState)
  } else {
    state = iterate(collection, eKind, loop, initialState)
  }
  return finalResult(loop, state)
}
```

This uses an additional optional method on Loops, to convert the final state to
a returned value. The default implementation just unwraps LoopExit states and
returns other states unchanged:

```
open function finalResult(loop, finalState)

method finalResult(loop, finalState) default =
    finalState is LoopExit ? loopExitState(finalState) : finalState
```

## Reducers

The simplest way to implement a Collector is as a Reducer, which implements the
parallel loop functions and uses an empty state as its initial state:

```
open type Reducer is Collector

method collectorSetup(Reducer reducer, _) = {
    eKind: EnumerateValues,
    loop: reducer,
    initialState: emptyState(reducer),
    canParallel: True
  }
```

For example, here are some of the simplest Reducer definitions:

```
private compound SummingReducer is Reducer

function sum() = SummingReducer_(True)
function count() = SummingReducer_(False)

method emptyState(SummingReducer) = 0

method nextState(SummingReducer reducer, state, value) = state + (reducer_ ? value : 1)

method combineStates(SummingReducer, state1, state2) = state1 + state2
```

Another example, using LoopExit values:

```
private compound BooleanReducer is Reducer

function anyTrue() = BooleanReducer_({initial: False, exitOn: True})
function anyFalse() = BooleanReducer_({initial: False, exitOn: False})
function allTrue() = BooleanReducer_({initial: True, exitOn: False})
function allFalse() = BooleanReducer_({initial: True, exitOn: True})

// We don't need a combineStates method since our state is only ever
// Absent or LoopExit
method nextState(BooleanReducer br, Absent, Boolean value) =
  (value == br_.exitOn) ? loopExit(not bc_.initial) : Absent
method finalResult(BooleanReducer br, state) =
  state is Absent ? br_.initial : loopExitState(state)
```

One more example:

```
private singleton SaveUnordered is Reducer

function saveUnordered() = SaveUnordered

method emptyState(SaveUnordered) = []
method nextState(SaveUnordered, array, value) = array & [value]
method combineStates(SaveUnordered, state1, state2) = state1 & state2
```

## Transforming collectors

Collectors can also be composed with Lambdas to create new Collectors; for
example,

```
function sumSq() = -> #^2 | sum
```

defines a Collector that computes the sum of the squares of its inputs. More
generally, `lambda | collector` applies the lambda to each incoming value and
collects the results, discarding any that transform to `Absent` unless the
collector's eKind is EnumerateAllKeys.

The implementation is straightforward; note that to avoid conflicting with the
previous method for `pipe` we must explicitly exclude Collections, which are
also Lambdas:

```
method pipe(Lambda lambda, Collector collector) (lambda is not Collection) default =
    TransformedCollector_({lambda, collector})

private compound TransformedCollector is Collector
private compound TransformedLoop is Loop

method collectorSetup(TransformedCollector tc, collection) {
  result = collectorSetup(tc_.collector, collection)
  result.loop = TransformedLoop_({lambda: tc_.lambda, eKind: result.eKind, loop: result.loop})
  return result
}

method emptyState(TransformedLoop loop) = emptyState(loop_.loop)
method splitState(TransformedLoop loop, state=) = splitState(loop_.loop, state=)
method combineStates(TransformedLoop loop, state1, state2) = combineStates(loop_.loop, state1, state2)
method finalResult(TransformedLoop loop, state) = finalResult(loop_.loop, state)

method nextState(TransformedLoop loop, state, element) {
  element = transformElement(element, loop_.lambda, loop_.eKind)
  return element is Absent ? state : nextState(loop_.loop, state, element)
}
```

Collectors may also be forced to be sequential. For example,

```
if test(^bigCollection) | anyTrue { ... }
```

determines whether any element of the collection satisfies `test()`, and may use
parallel and out-of-order execution in doing so. If we had reason to believe
that elements at the beginning of the collection were the most likely to satisfy
`test()`, it might be more efficient to test them sequentially:

```
if test(^bigCollection) | sequentially(anyTrue) { ... }
```

The `sequentially(saveUnordered)` combination is so useful that it's given its
own name:

```
function saveSequential() = sequentially(saveUnordered)
```

e.g.

```
a = 1..10 | filter(-> (# % 2) == 1) | -> #^2 | saveSequential
```

sets `a` to `[1, 9, 25, 49, 81]`.

The implementation of `sequentially` is again straightforward:

```
function sequentially(Collector collector) = SequentialCollector_(collector)

private compound SequentialCollector is Collector

method collectorSetup(SequentialCollector sc, collection) {
  result = collectorSetup(sc_, collection)
  result.canParallel = False
  return result
}
```

## Savers

Not all Collectors are as readily parallelizable as Reducers. The `save`
collector stores its inputs into a new collection, which in most cases requiries
serializing the updates to a single value. For example,

```
// Apply f to each element of a matrix, and save the results as a new matrix.
a = f(^mat) | save
```

is executed using this approach:

```
// Create a new collection with the same keys as mat, initially all Absent
a = new(keys(mat))
// (The next statement isn't valid, because it updates 'a' in a parallel loop.)
for key: value in mat {
  a[key] = f(value)
}
```

Note that each execution of the loop body updates `a`. We could easily make this
work by forcing the loop to be sequential, but we'd like to preserve the
performance potential of parallel and out-of-order execution. We could have an
`emptyState()` method that created a separate new collection for each parallel
thread, but that would likely waste memory (and CPU to merge them in
`combineStates()`).

The function `saverSetup()` (usually called from `collectionSetup()`) is
designed to make it easy to implement such Collectors:

```
// Return a struct suitable for returning from collectorSetup().
// The result will have canParallel True, but loop need not be parallelizable.
function saverSetup(eKind, loop, initialState)
```

The loop returned by `saverSetup()` maintains a single "primary" state (updated
using the given loop).

*   When multiple threads are created on a single machine, it uses locks to
    ensure that updates to the primary state are serialized.
*   When distributed state is necessary, all states other than the primary state
    are just a list of pending updates to be eventually applied to the primary
    state.

This makes it easy to implement `save`:

```
private compound SaveWithDefault is Collector, Loop
private singleton SaveLoop

function saveWithDefault(v) = SaveWithDefault_(v)
function save() = SaveWithDefault_(Absent)

method collectorSetup(SaveWithDefault swd, collection) =
    saverSetup(EnumerateWithKeys, SaveLoop, new(keys(collection), swd_))

method nextState(SaveLoop, state, [key, value]) = replaceElement(state, key, value)
```

The synchronization behavior of `saverSetup()` cannot be expressed in
Retrospect, but this simplified implementation captures the way distributed
state is handled:

```
function saverSetup(eKind, Loop loop, initialState) {
  assert initialState is not SaverUpdates
  return { eKind, loop: SaverLoop_(loop), initialState, canParallel: True }
}

private compound SaverLoop is Loop
// When state is a SaverUpdates, it wraps an array of updates to be applied
// to the primary state.
private compound SaverUpdates

method nextState(SaverLoop loop, state, element) {
  if state is SaverUpdates {
    // We don't have the primary state, so just save this element for now.
    state_ &= [element]
    return state
  }
  // We have the primary state, so we can update it.
  return nextState(loop_, state, element)
}

// A new empty state is just an empty array of updates.
method emptyState(SaverLoop) = SaverUpdates_([])

method combineStates(SaverLoop loop, state1, state2) {
  // We have to
  if state1 is SaverUpdates {
    if state2 is SaverUpdates {
      // No primary state, so all we can do is concatenate the updates.
      state1_ &= state2_
      return state1
    }
    // Swap them so that we're always merging state2 into state1.
    [state1, state2] = [state2, state1]
  } else {
    // They can't both be primary.
    assert state2 is SaverUpdates
  }
  // state1 is primary, state2 is SaverUpdates
  for element in state2_ sequential state1 {
    state1 = nextState(loop_, state1, element)
  }
  return state1
}

method finalResult(SaverLoop loop, finalState) {
  assert finalState is not SaverUpdates
  return finalResult(loop_, finalState)
}
```

## Flattening Collections

It is often convenient to be able to treat a Collection of Collections as single
Collection containing all the elements of the nested collections, with keys that
combine the keys of the outer and inner collections. An example, using an Array
of structs:

```
a = flatten([{a: "A", b: "B"}, {}, {c: "C"}])
b = withKeys(a) | saveSequential
```

returns an Array containing these (key, value) pairs:

```
[ [[[1], "a"], "A"],
  [[[1], "b"], "B"],
  [[[3], "c"], "C"]
]
```

(Why so much nesting? Each element of the result is a `[key, value]`; each key
(of the flattened collection) is `[k0, k1]` where `k0` is a key of the outer
collection (the Array of structs) and `k1` is a key of the struct; and since an
Array is a one-dimensional Matrix, it has keys like `[1]`, `[2]`, etc.)

For reference, the implementation of `flatten()` looks something like this:

```
function flatten(collections) {
  return Flattened_(collections)
}

private compound Flattened_

method at(Flattened f, key) {
  if key is Array and size(key) == 2 {
    [k0, k1] = key
    c = f_ @ k0
    if c is not Absent {
      return c @ k1
    }
  }
  return Absent
}

method size(Flattened f) = f | -> size(#) | sum

// To get the keys of a Flattened, get the keys of each subcollection and
// pair them with the subcollection's key.  Use save so that we call keys()
// on each subcollection and save the results now, rather than doing it lazily;
// that ensures that the result of keys() won't keep all the values around.
method keys(Flattened f) =
    flatten(withKeys(f_) | [k0, c] -> [k0, ^keys(c)] | save)

method new(Flattened f) = flatten(f_ | -> new(keys(#)) | save)

method iterator(Flattened f, eKind) {
  outerEKind = (eKind is EnumerateValues) ? EnumerateValues : EnumerateWithKeys
  return FlattenedIterator_({
            first: emptyIterator(),
            rest: iterator(f_, outerEKind),
            eKind
          })
}

private compound FlattenedIterator is Iterator

method next(FlattenedIterator it=) {
  for sequential it {
    result = next(it_.first=)
    if result is not Absent {
      break { return result }
    }
    next = next(it_.rest=)
    if next is Absent {
      break { return Absent }
    }
    if it_.eKind is EnumerateValues {
      it_.first = iterator(next, EnumerateValues)
    } else {
      [k0, inner] = next
      it_.first = iterator(inner, it_.eKind) | [k1, v] -> [[k0, k1], v]
    }
  }
}

method enumerate(Flattened f, eKind, loop, initialState) {
  asCollector = asCollector(EnumerateValues, loop, initialState)
  if eKind is EnumerateValues {
    for c in f_ {
      result <<^ c
    } collect {
      result =| asCollector
    }
  } else {
    for k0: c in f_ {
      c = (eKind is EnumerateWithKeys) ? withKeys(c) : withAllKeys(c)
      result <<^ c | [k1, v] -> [[k0, k1], v]
    } collect {
      result =| asCollector
    }
  }
  return result
}

// You can update a flattened collection if both the inner and outer collections
// can be updated.
method replaceElement(Flattened f, [k0, k1], v) {
  f_ @ k0 @ k1 = v
  return f
}

method startUpdate(Flattened f=, [k0, k1]) {
  c = startUpdate(f=, k0)
  result = startUpdate(c=, k1)
  f = -> f @ (c @ #)
  return result
}
```

## Compiling `for` loops

While pipelines stream one collection into a single Collector, `for` loops may
stream values into multiple Collectors while enumerating a single collection.
This section will outline the set of helper functions used to do that.

A prototypical `for` loop looks something like this:

`for` [ *key* `:`] *value* `in` *source* [ `sequential` *q1*, *q2* ] `{` \
&nbsp; &nbsp; . . . // references to *z1*, *z2* from enclosing scope \
&nbsp; &nbsp; `if` . . . `{` \
&nbsp; &nbsp; &nbsp; &nbsp; `break {` . . . `}` \
&nbsp; &nbsp; `}` \
&nbsp; &nbsp; . . . \
&nbsp; &nbsp; *out1* `<<` *x1* \
&nbsp; &nbsp; *out2* `<<^` *x2* \
&nbsp; &nbsp; . . . \
`} collect {` \
&nbsp; &nbsp; *out1* `=|` *collector1* \
&nbsp; &nbsp; *out2* `=|` *collector2* \
`}`

To implement this the compiler defines a few new compound types (one for the
loop itself, one for its state variables, and one for each `break` statement),
and methods on the new loop type:

*   for sequential loops over a collection it defines `nextState` and passes a
    loop instance to `iterate`;
*   for parallel loops it defines `nextState`, `emptyState`, `splitState`,
    `combineStates`, and passes a loop instance to `enumerate`.

(Sequential loops without a collection are similar, but define `at` and pass the
loop instance to `iterateUnbounded`.)

For this discussion I will refer to the new types as `Loop0`, `LoopState0`, and
`LoopExit0`.

For the parallel case, LoopState0 values will have two elements: `out1_rw` and
`out2_rw` (i.e. one element for each collected output).

For the sequential case, LoopState0 values will have two additional elements:
`q1` and `q2` (i.e. one element for each variable listed after the `sequential`
keyword), for a total of four elements.

In this example Loop0 values will have five elements: `eKind`, `out1_ro`,
`out2_ro`, `z1`, and `z2` (i.e. one element for the selected EnumerationKind,
one element for each collected output, and one element for each closed-over
variable from the enclosing scope).

(Note that each collected output has an "rw" (read/write) component in the
state, and an "ro" (read-only) component in the Loop).

The code generated for the sequential version of this loop looks like

```
  // Change next line to EnumerateWithKeys if loop uses "key:"
  maxEKind = EnumerateValues
  {ro: out1_ro, rw: out1_rw} = loopHelper(collector1, source, maxEKind=, False)
  {ro: out2_ro, rw: out2_rw} = loopHelper(collector2, source, maxEKind=, False)
  loop = Loop0_({eKind: maxEKind, out1_ro, out2_ro, z1, z2})
  state = LoopState0_({out1_rw, out2_rw, q1, q2})
  state = iterate(source, maxEKind, loop, state)
  if state is LoopExit0 {
    // compiled code for "break" body
  } else {
    {out1_rw, out2_rw, q1, q2} = state_
    out1 = finalResultHelper(out1_ro, out1_rw)
    out2 = finalResultHelper(out2_ro, out2_rw)
  }
```

along with this method definition:

```
method nextState(Loop0 loop, LoopState0 state, value) {
  {eKind, out1_ro, out2_ro, z1, z2} = loop_
  {out1_rw, out2_rw, q1, q2} = state_
  key = Absent
  if eKind is not EnumerateValues {
    [key, value] = value
    // value can only be Absent if eKind was upgraded to EnumerateAllKeys;
    // in that case we don't execute the loop body, just the emitKeys.
    if value is Absent { goto continue }
  }
  // ... insert loop body here, with:
  // - continue statement replaced with branch to "continue" label below
  // - emit statement "out1 << value" replaced with
  //     emitValue(out1_ro, out1_rw=, value)
  // - distributed emit statement "out2 <<^ values" replaced with
  //     emitAll(out2_ro, out2_rw=, values)
  // - break statement replaced with
  //     return LoopExit0_(... any local vars needed for break body...)
continue:
  emitKey(out1_ro, out1_rw=, key)
  emitKey(out2_ro, out2_rw=, key)
  return LoopState0_({out1_rw, out2_rw, q1, q2})
}
```

These code templates reference several helper functions from the core library:

```
private compound RO
private compound RW

private function rw(pendingValue, state) = RW_({pendingValue, state})

private function toState(RW rw) {
  {pendingValue, state} = rw_
  assert pendingValue is Absent
  return state
}

function loopHelper(collector, collection, maxEKind=, isParallel) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, collection)
  assert canParallel or not isParallel, "Can't use sequential collector in parallel loop"
  if eKind is not EnumerateValues and maxEKind is not EnumerateAllKeys {
    maxEKind = eKind
  }
  return {ro: RO_({eKind, loop, canParallel}), rw: rw(Absent, initialState)}
}

// For use in unbounded loops (with no source collection)
function loopHelper(collector) {
  { eKind, loop, initialState, canParallel } = collectorSetup(collector, Absent)
  assert eKind is EnumerateValues
  return {ro: RO_({eKind, loop, canParallel}), rw: rw(Absent, initialState)}
}

// For use when multiple collected vars use the same collector
function anotherRw(collector, collection) {
  return rw(Absent, collectorSetup(collector, collection).initialState)
}

// For use when no collector is specified, usually an inner loop inheriting a
// collected var from an outer loop.  The collector for the outer loop must not
// be keyed.
procedure verifyEV(ro) {
  assert ro_.eKind is EnumerateValues, "Can't inherit a keyed collector"
}

function finalResultHelper(ro, rw) {
  return finalResult(ro_.loop, toState(rw))
}

// For use in a break; combines emitKey and finalResultHelper
function finalResultHelper(ro, rw, key) {
  {pendingValue, state} = rw_
  if ro_.eKind is not EnumerateValues {
    if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
      state = nextState(ro_.loop, state, [key, pendingValue])
    }
  }
  return finalResult(ro_.loop, state)
}

procedure emitValue(ro, rw=, v) {
  if v is Absent {
    return
  }
  {pendingValue, state} = rw_
  assert pendingValue is Absent, "Can't emit twice to keyed collector"
  if state is not LoopExit {
    if if ro_.eKind is EnumerateValues {
      rw = rw(Absent, nextState(ro_.loop, state, v))
    } else {
      rw = rw(v, state)
    }
  }
}

procedure emitAll(ro, rw=, collection) {
  assert ro_.eKind is EnumerateValues, "Can't emitAll to keyed collector"
  state = toState(rw)
  if state is not LoopExit {
    if ro_.canParallel {
      state = enumerate(collection, EnumerateValues, ro_.loop, state)
    } else {
      state = iterate(collection, EnumerateValues, ro_.loop, state)
    }
    rw = rw(Absent, state)
  }
}

procedure emitKey(ro, rw=, key) {
  if ro_.eKind is EnumerateValues {
    return
  }
  {pendingValue, state} = rw_
  if state is not LoopExit and (pendingValue is not Absent or ro_.eKind is EnumerateAllKeys) {
    state = nextState(ro_.loop, state, [key, pendingValue])
    rw = rw(Absent, state)
  }
}
```

The code generated for the parallel version is similar:

```
  // Change next line to EnumerateWithKeys if loop uses "key:"
  maxEKind = EnumerateValues
  {ro: out1_ro, rw: out1_rw} = loopHelper(collector1, source, maxEKind=, True)
  {ro: out2_ro, rw: out2_rw} = loopHelper(collector2, source, maxEKind=, True)
  loop = Loop0_({eKind: maxEKind, out1_ro, out2_ro, z1, z2})
  state = LoopState0_({out1_rw, out2_rw})
  state = enumerate(source, maxEKind, loop, state)
  if state is LoopExit0 {
    // compiled code for "break" body
  } else {
    {out1_rw, out2_rw} = state_
    out1 = finalResultHelper(out1_ro, out1_rw)
    out2 = finalResultHelper(out2_ro, out2_rw)
  }
```

(The only differences, aside from the absence of sequential vars `q1` and `q2`,
are that the `isParallel` argument to `loopHelper` is True and the call to
`enumerate` instead of `iterate`.)

The definition of the `nextState` method on `Loop0` is unchanged, but the
compiler also defines methods for `emptyState`, `splitState`, and
`combineStates`:

```
method emptyState(Loop0 loop) {
  out1_rw = emptyStateHelper(loop_.out1_ro)
  out2_rw = emptyStateHelper(loop_.out2_ro)
  return LoopState0_({out1_rw, out2_rw})
}

method splitState(Loop0 loop, LoopState0 state=) {
  out1_rw = splitStateHelper(loop_.out1_ro, state_.out1_rw=)
  out2_rw = splitStateHelper(loop_.out2_ro, state_.out2_rw=)
  return LoopState0_({out1_rw, out2_rw})
}

method combineStates(Loop0 loop, LoopState0 state1, LoopState0 state2) {
  out1_rw = combineStatesHelper(loop_.out1_ro, state1_.out1_rw, state2_.out1_rw)
  out2_rw = combineStatesHelper(loop_.out2_ro, state1_.out2_rw, state2_.out2_rw)
  return LoopState0_({out1_rw, out2_rw})
}
```

These use some additional helper functions:

```
function emptyStateHelper(ro) = rw(Absent, emptyState(ro_.loop))

function splitStateHelper(ro, rw=) {
  state = toState(rw)
  if state is LoopExit {
    // We can't call splitState with a LoopExit, so just create another
    // LoopExit that we'll discard when it gets back to combineStatesHelper.
    return loopExit(Absent)
  }
  state2 = splitState(ro_.loop, state=)
  rw = rw(Absent, state)
  return rw(Absent, state2)
}

function combineStatesHelper(ro, rw1, rw2) {
  state1 = toState(rw1)
  state2 = toState(rw2)
  if state1 is LoopExit {
    if state2 is LoopExit and loopExitState(state1) is Absent {
      return rw2
    }
    return rw1
  } else if state2 is Absent {
    return rw1
  } else if state1 is Absent or state2 is LoopExit {
    return rw2
  }
  return rw(Absent, combineStates(ro_.loop, state1, state2))
}
```

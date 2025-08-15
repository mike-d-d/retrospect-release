# CallMemos, MethodMemos, and ValueMemos

[TOC]

When executing the body of a method, the Retrospect VM records information that
may be used to optimize future executions of that method. At a high level this
information is made up of three components:

*   What values were passed as arguments to this method?
*   Which methods were executed by each nested function call?
*   What values did those function calls return?

The primary reason to save this information is to enable the VM to identify
frequently-executed methods and to generate efficient Java byte codes for them,
enabling faster execution of subsequent calls.

A secondary benefit is that this information often enables the VM to make a
better choice of FrameLayout when allocating new Frames, reducing the frequency
with which Frames later need to be copied to a new layout. For example, when
allocating a new array that is initially populated only with zeros and ones the
VM will choose a simple FrameLayout that just uses a byte for each element, to
minimize memory use. If subsequent operations replace some elements of the array
with floating-point values (or `None`) the array will need to be copied to a new
Frame with a different FrameLayout; recording that this happened enables future
executions to use a more appropriate FrameLayout right from the start.

These records of past execution are stored using three classes:

*   A ValueMemo records the range of values that have been passed as arguments
    or returned as results. Each ValueMemo maintains a TemplateBuilder for each
    argument or result, and expands the TemplateBuilder as necessary to include
    all values that have been seen.
*   A CallMemo records each method that has been invoked from a function call
    site, with a corresponding MethodMemo for each.
*   A MethodMemo contains a ValueMemo for the method's arguments, and a CallMemo
    and ValueMemo (for the function results) for each function call site within
    the method body.

(Note that while each MethodMemo has a separate ValueMemo for the arguments that
method has been called with, all MethodMemos in a CallMemo share a single
ValueMemo for their results.)

The rest of this document will focus on the inter-relationships between
MethodMemos and CallMemos, omitting the details of ValueMemos — because
ValueMemos do not refer to CallMemos or MethodMemos they are comparatively
self-contained and do not affect the issues considered here.

All of this information is saved relative to a Scope. Requests from different
users are typically executed in different Scopes, so that one user's request
does not affect the execution of another. Requests from a single user may or may
not be executed in the same Scope, but requests that are executing substantially
the same code would typically share a Scope so that they could benefit from the
optimizations derived by earlier requests (e.g. all of the requests for a map's
tiles should probably be executed in the same Scope).

## Shared MethodMemos

One of the most important and complex choices the VM must make is

> When should two executions of the same method share a MethodMemo?

Sharing a MethodMemo is beneficial if the executions are similar enough that the
optimizations derived from one benefit the other, but sharing a MethodMemo may
be detrimental in other cases. For example, consider a simple method that adds
the elements of a matrix. If the method is sometimes called with a fixed-length
array of three doubles, but at other times is called with a variably-sized
matrix of lazily-computed values, combining the information from all those calls
into a single MethodMemo will force the VM to use sufficiently general
representations and implementations to handle the full range of possible
arguments. If instead it used separate MethodMemos for the two types of calls,
it could potentially generate much simpler and faster code for the simple calls.

This is closely related to the problem optimizing compilers face when deciding
whether or not to inline function calls — and in fact the Retrospect VM's
decisions about sharing MethodMemos will largely determine which function calls
it chooses to inline when generating byte codes.

The tradeoffs of sharing MethodMemos are:

*   Shared MethodMemos may allow the VM to execute subsequent calls more
    quickly, by reusing information (and generated code) from more calls.
*   Unshared MethodMemos may allow the VM to use more efficient representations
    and generate more efficient code by reducing the variation it must deal with
    in each case.
*   Shared MethodMemos will often save memory in the VM by reducing the amount
    of extra information that must be stored.

Determining the optimal degree of sharing is hard, so the Retrospect VM relies
on some relatively simple heuristics to make good-enough decisions. The basic
idea is to start by creating new, unshared MethodMemos for most method calls,
and then during execution to look for instances where sharing them would be more
appropriate, i.e.

*   we have multiple MethodMemos for the same method, and
*   they are "large enough", and
*   their arguments are similar enough.

(This last condition is a cheap alternative to doing a deeper comparison of the
MethodMemos; the intuition is that methods are likely to follow similar code
paths when given arguments of the same type, but may follow very different paths
(and therefore not benefit from sharing) when given different argument types.)

There's a lot of hand-waving in that summary; the rest of this doc fills in some
of the details.

## The details: Call Sites and MethodMemos

The first clarification is to the assertion that the VM initially creates new,
unshared MethodMemos for "most" method calls. What does that mean?

Execution of a method will always be done with respect to a MethodMemo for that
method. When a nested function call is made, after the appropriate method has
been determined the caller's MethodMemo will be consulted and either:

*   a new MethodMemo for the to-be-called method will be allocated and saved in
    the caller's MethodMemo, or
*   an existing MethodMemo for the to-be-called method will be found from a
    previous invocation of that method and used.

The details depend on the definition of a *call site*. In user-provided methods
(define by an InstructionBlock) each Call instruction is a distinct call site.
Most Call instructions correspond to either an explicit function call in the
Retrospect source (`myFn(a, b)`) or an operator that provides more compact
syntax for a function call (e.g. `a+b`, which is a synonym for `add(a, b)`). But
other Retrospect-language constructs also expand to less obvious Call
instructions, e.g.

*   updating a component of a variable (e.g. `planets[i].v = v`) is implemented
    with calls to `startUpdate` and/or `replaceElement`;
*   extracting the elements of a compound (e.g. `[x, y, z] = pos`) is
    implemented with a call to a multi-return-value extract function;
*   `for` loops are implemented with calls to `iterate` (sequential loops) or
    `enumerate` (parallel loops)

Built-in methods that make function calls don't use Call instructions, but have
a corresponding API (Caller) that also defines call sites. Note that call sites
are a static property of the Retrospect code being executed; unlike most of the
objects described here, new call sites are not created during program execution.

Call sites determine some of the basic structure of MethodMemos:

*   Each MethodMemo has a CallMemo for each call site in that method.
*   Each CallMemo has a MethodMemo for each method that has been invoked from
    that call site.

An example might help. Consider the following simple program:

```
function a(x) {
  t1 = b(x)       // call_1
  t2 = b(t1)      // call_2
  return t2
}

function b(x) {
  t1 = c(x, 3)    // call_3
  return t1
}

function c(x) {
  ...
}
```

(For clarity this is written in the one-line-per-instruction dialect of
Retrospect that is also used to document the compiler's behavior, not the more
compact version that a user would typically write.)

This program has three call sites (marked by comments). Assuming that we are
executing the method for `a` with a newly-allocated MethodMemo `mm_a`, the steps
would be

1.  The VM determines that it will call the method for `b` from call site
    `call_1`.
2.  It checks `mm_a`'s CallMemo for `call_1`, finds that there is not yet a
    MethodMemo for `b`, and so creates (and saves) one (call it `mm_b1`).
3.  It starts executing `b` using `mm_b1`, gets to `call_3`, and determines that
    it will call the method for `c`.
4.  It creates a MethodMemo for `c` (call it `mm_c1`) and saves it in `mm_b1`'s
    CallMemo for `call_3`.
5.  When done executing `c`, it returns to `b` and then to `a`, where it moves
    on to `call_2`.
6.  Because `mm_a` has a separate CallMemo for `call_2`, it again finds no
    MethodMemo for `b` and so creates another one (call it `mm_b2`).
7.  When execution of `b` using `mm_b2` gets to `call_3`, it finds no MethodMemo
    for `c` (since it's using a different MethodMemo for `b` than it was in step
    4) and so creates another one.

The thing to note here is that we've created two separate MethodMemos for `b`
(corresponding to its two call sites) but we've also created two MethodMemos for
`c` even though it is only called from a single call site.

Creating a separate MethodMemo each time a function call is executed would
clearly be problematic for many programs. One obvious problem is recursion: if
`b` called itself recursively we would create an additional MethodMemo for each
level of recursion, and the MethodMemos could easily end up using more memory
than the program's own data. Even without recursion we can end up with
exponentially many MethodMemos (e.g. if each call to `b` called `c` twice, and
each call to `c` called `d` twice, etc.). Both problems will be dealt with later
(by recognizing that we are creating multiple increasingly large MethodMemos and
deciding to share some of them).

## The details: MethodMemos and loops

So far every function call executed by the VM has resulted in a new MethodMemo.
The first exceptions to that pattern are loops; for example,

```
function a(x, n) {
  for i in 1..n sequential x {
    x = b(x)                      // call_4
  }
  return x
}
```

(`a` again calls `b`, but now a variable number of times.)

When executing `a` with `mm_a` the same MethodMemo is used for each execution of
the loop body, so the same MethodMemo (call it `mm_b`) will be used for each
execution of `b` from call site `call_4`.

More details, for the curious: Since the Retrospect VM does not have any
backwards-branching instructions, a loop like the one above is actually compiled
to something like

```
singleton LoopBody1

function a(x, n) {
  t1 = makeRange(1, n)                               // call_5
  t2 = iterate(t1, EnumerateValues, LoopBody1, x)    // call_6
  return t2
}

// This method is referred to as "nsLB1" below.
method nextState(LoopBody1, x, i) {
  x = b(x)                      // call_4
  return x
}
```

Now

*   `mm_a` will have a CallMemo for `call_6`, with a MethodMemo (call it
    `mm_iterate`) for `iterate` (a built-in function that repeatedly calls
    `nextState` for each element of the given collection).
*   `mm_iterate` will have a single CallMemo for its calls to `nextState`.
*   All of those calls will resolve to `nsLB1` (the method for `nextState` that
    matches when the first argument is our new singleton `LoopBody1`), so
    `mm_iterate` will allocate a MethodMemo for it (call it `mm_nsLB1`) on the
    first call and then and reuse it for each subsequent call.
*   `mm_nsLB1` will have a CallMemo for `call_4`, with a single MethodMemo for
    the `b` method.

## The details: Argument overlap and weights

Our policy for deciding when to share MethodMemos rested on two criteria:

*   they are "large enough", and
*   their arguments are similar enough.

To determine when arguments are similar we compare the arguments ValueMemos that
have been updated by each execution, asking if the corresponding
TemplateBuilders *overlap*. The definition of "overlap" is roughly "are there
any values that are included in both TemplateBuilders", except that a numeric or
string constant is considered to overlap with any other constant of the same
base type. As the Javadoc for `TemplateBuilder.overlaps()` acknowledges:

> This is not a very principled predicate (and may end up getting further
> tweaked to get better inlining decisions).

"Large enough" is more complex. The basic intuition is that we compute a
*weight* for each MethodMemo as the sum of

*   a base weight, which is one for user-provided methods but maybe larger for
    built-in methods; and
*   for each MethodMemo contained by one of its CallMemos, either that
    MethodMemo's weight (if the MethodMemo is unshared, and hence will be
    inlined) or a fixed "non-inlined call weight" if the MethodMemo is shared.

The first component provides for built-in methods that may have substantial
internal complexity even without calling any other functions. MethodMemos that
exceed a weight threshold are deemed "heavy" and become candidates for merging.

## The details: Scope.PerMethod

The final thing we need to implement our sharing policy is the ability to find
all the shared MethodMemos for a given method, and all the MethodMemos that are
not currently shared but are large enough that we would merge them if a similar
MethodMemo was found.

That ability is provided by the Scope's PerMethod object. Each Scope has a map
from method to a corresponding PerMethod, which has two fields:

*   a list of already-shared MethodMemos for that method (the "exlined"
    MethodMemos), and
*   a list of MethodMemos that are not currently shared but are over our size
    threshold (the "heavy" MethodMemos).

Newly-created MethodMemos will be on neither list. If a MethodMemo grows to
reach our size threshold, we check its PerMethod to see if there's already a
heavy MethodMemo with similar argument types; if so, we merge them to create a
shared MethodMemo and replace both of them with references to the shared
MethodMemo. Alternatively if we discover that there is a shared MethodMemo
matching our unshared MethodMemo we simply merge with it (and start using the
shared one).

Merging MethodMemos is conceptually straightforward (corresponding CallMemos are
combined, and if both have a MethodMemo for the same method those MethodMemos
are in turn merged) but may end up affecting many MethodMemos for different
methods. For simplicity all MethodMemo changes in a Scope are guarded by a
single lock (the MemoMerger); this ensures that the merge process cannot race
against conflicting updates.

To reduce the amount of locking needed, MethodMemo and CallMemo updates are done
so that it is safe to try to read MethodMemo state without holding the
MemoMerger lock; the worst that might happen is that you encounter a null field,
which is handled by acquiring the lock and retrying the read.

## Merging in action: A recursive method

To see how all these pieces fit together, let's look at what happens with a
simple recursive method.

```
// function factorial(n) = (n < 3) ? n : n * factorial(n - 1)
//
function factorial(n) {
  t1 = lessThan(n, 3)        // call_1
  branch L1 if not t1
  return n
L1:
  t1 = subtract(n, 1)        // call_2
  t1 = factorial(t1)         // call_3
  t1 = multiply(n, t1)       // call_4
  return t1
}
```

Assume that we are executing a call to `factorial(25)` in a new Scope. The
PerMethod for `factorial` is empty, so we create a new MethodMemo. Executing
`call_1` and `call_2` populates those CallMemos with the (trivial) MethodMemos
for the built-in methods that implement numeric comparisons and subtraction.
Executing `call_3` checks the `factorial` PerMethod to see if there's already a
shared MethodMemo for it; there isn't, so it creates a new one. Now we've got
two MethodMemos for `factorial`, corresponding to the two in-progress
`factorial` calls. The second one has weight 1 (since it hasn't started
executing yet), but the original one now has weight 4 (since it contains 3
MethodMemos, and the simple built-ins also have weight 1).

After 12 such recursive calls we have 13 nested MethodMemos for `factorial`; the
innermost one (for `factorial(13)`) has weight 1 (it hasn't started executing
yet) but each enclosing MethodMemo has an increasing weight until we reach the
outermost MethodMemo (the original call to `factorial(25)`) which now is up to
weight 37. Let's assume that this is high enough to be considered "heavy"; that
outermost MethodMemo will be checked against the existing heavy MethodMemos in
its PerMethod (there are none) and then added to that list.

When we start the next recursive call (`factorial(12)`) each of the enclosing
MethodMemos' weights have been increased by 3, meaning that the
next-from-outermost one (`factorial(24)`) is now also heavy. This prompts a
comparison with the other heavy MethodMemos in that PerMethod, where we find the
outermost (`factorial(25)`) MethodMemo. Since their arguments are similar enough
(i.e. the argument ValueMemos overlap), we decide to

*   merge those two MethodMemos,
*   mark the combined MethodMemo as "exlined" (aka shared), and
*   replace the two original MethodMemos with the combined one.

Since the `factorial(25)` MethodMemo contained a reference to the
`factorial(24)` MethodMemo (in its `call_3` CallMemo) and we're now merging
those two, the combined MethodMemo will end up with a reference to itself -- the
recursive method calls have led us to construct a recursive MethodMemo (which
we'll refer to here as `factorial(*)`).

(We will also merge the memos for `call_1` and `call_2`, but since we don't
bother creating new MethodMemos for trivial built-ins like numeric arithmetic
and comparison there isn't anything to do there.)

But it doesn't stop there: merging those two MethodMemos means merging each of
their CallMemos, which means merging the MethodMemos at `call_3`; for
`factorial(25)` that's now `factorial(*)`, while for `factorial(24)` it was
`factorial(23)`. So we merge `factorial(23)` into `factorial(*)` as well, and
that process continues recursively until all 14 MethodMemos for `factorial` have
been merged into a single exlined MethodMemo.

Execution continues for `factorial(12)` using the `factorial(*)` MethodMemo.
This time when we get to `call_3` and need a MethodMemo for `factorial`, there's
already one there (i.e. the same `factorial(*)` MethodMemo), so each subsequent
call will use the same MethodMemo rather than creating any more of them.

(Also, now that `factorial` has an exlined MethodMemo any call to `factorial`
from a new call site will find and use that MethodMemo in preference to creating
a new one.)

## Q & A

Some answers to a few questions that might arise at this point.

#### Is this really the best way to recognize and handle recursion? It seems pretty complicated.

Tracking MethodMemo weight and merging heavy MethodMemos is a very general
approach to identifying when MethodMemos should be shared. In addition to
recognizing recursion (as in the example above) it recognizes other,
non-recursive situations where we might otherwise allocate excessive numbers of
MethodMemos.

Checking specifically for recursive calls and introducing recursive MethodMemos
in those cases is still an option we could explore later, but for now I'm
inclined to just rely on this more general mechanism and see if its behavior is
sufficient.

#### Why wait for so many recursive calls before triggering? What if the top-level call had been `factorial(10)`?

The initial threshold for deciding that a MethodMemo is heavy (and thus eligible
for merging) was chosen pretty arbitrarily, and will be revisited when we have
more experience to draw on. Note that this is a particularly trivial recursive
method; in most cases there would be more work done before each recursive call,
which would cause the weight to accumulate faster and the threshold to be
reached sooner.

If the top-level call had been `factorial(10)`, execution would have completed
after constructing 9 nested MethodMemos (`factorial(10)` through
`factorial(2)`). In the unlikely event that that execution was re-run enough
times to trigger byte code generation, each of the nested method calls would be
inlined, and the resulting byte code would be end up being the equivalent of

```
return 10 * (9 * (8 * (7 * (6 * (5 * (4 * (3 * 2)))))))
```

(which the JVM would presumably optimize to just a constant).

## The details: ValueMemo updates

Merging MethodMemos also needs to merge ValueMemos (for both the method's
arguments and for the results of each nested function call), which may in turn
lead to updating or merging FrameLayouts but does not affect other MethodMemos
so does not complicate the story above.

ValueMemos have their own synchronization; they can usually be read and updated
without acquiring the MemoMerger lock. The exception is the ValueMemo for the
arguments of a shared or heavy MethodMemo; because the MemoMerger's behavior
depends on these (when it checks for overlap), they are specially marked and can
only be updated while holding the MemoMerger lock.

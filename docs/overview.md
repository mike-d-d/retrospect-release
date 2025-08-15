# Retrospect overview

[TOC]

## Motivation

Retrospect is a programming language designed for scientific computing. In that
respect its goals are similar to those of R, Julia, and many Python extensions.
It differs from those systems in part because it was designed to meet the needs
of "big data services", i.e. systems that maintain a large archive of data and
run programs written by their users to explore and analyze that data. For
example, Google Earth Engine maintains a continuously-updated repository of 10s
of PBs of remote-sensing data and serves 10s of thousands of users across a wide
range of disciplines analyzing different subsets of that data.

This orientation has had some consequences for the design of the language and
its implementation:

1.  In order to be useful for analyzing large quantities of data, the
    implementation must be fast. For example, a typical Earth Engine calculation
    might render one 256x256 tile of a time-series analysis of 5 years of
    bi-weekly images from a satellite with 8 spectral bands. Processing 65
    million input values and producing a result quickly enough for interactive
    exploration is feasible with efficiently-written code on current processors,
    but a language that imposed a 10-100x reduction in performance would be
    disqualified for many applications.

2.  In order to be useful for analyzing even larger quantities of data, the
    language must enable the distribution of a computation across many machines.
    For example, if that same time-series analysis were to be used to determine
    trends in agricultural development over the Uniited States during that
    5-year period (about 170,000 of the tiles described above), running the
    computation on a single machine would be slow no matter how efficiently it
    was implemented. But a big data service could distribute that computation
    among 100s or 1000s of machines if its inherent parallelism was evident in
    the way the computation was expressed.

3.  In order to efficiently share a compute service among many concurrent users
    we need to be able to run their computations in an environment that tracks
    and limits the resources they can consume. Any programming language
    sufficiently expressive for this application will be able to describe
    computations that use unbounded amounts of memory or CPU, and it will be
    infeasible to determine ahead of time the resource requirements of a
    particular computation. Accurately monitoring the resource use allows us to
    make informed decisions about how much parallelism to use, how many machines
    to use, and when to cut off a computation whose requirements are excessive.
    Without sufficient support in the programming language implementation for
    this kind of tracking, limiting, and cancellation, we must fall back on
    coarse-grained OS-level process monitoring facilites at a high cost of
    efficiency and flexibility.

4.  Because the primary users of this system are scientific researchers, not
    software developers, we want to minimize the programming expertise required
    to get results. As much as possible we want use familiar programming
    concepts and notation (e.g. more like Python or Julia than like Haskell,
    Lisp, or APL).

These four design criteria interact; for example:

*   Languages such as R and Python rely on writing the inner loops of a
    demanding computation in another language (typically C or C++) to compensate
    for their performance limitations; this is incompatible with goals (3) and
    (4).
*   Most of these language define their basic semantics in terms of mutable
    objects, which creates a large obstacle to efficiently distributing them.
    Writing a distributable computation in such a language requires that the
    programmer plan for distribution from the beginning and use special
    libraries (such as Spark or Dask) in place of the language’s usual control
    structures; this is an obstacle for (4).

## Pipelines

Here is a Retrospect function to compute the mean and standard deviation of a
collection of numbers, returning a two-element struct (similar to a Python
dictionary or JavaScript Object) with keys "mean" and "sd":

```go
function stats(nums) {
  n = size(nums)
  xSum = nums | sum
  if n < 2 {
    return { mean: n == 0 ? None : xSum, sd: None }
  }
  x2Sum = nums | x -> x**2 | sum
  mean = xSum / n
  sd = sqrt((x2Sum - xSum * mean) / (n - 1))
  // The next line is equivalent to "return {mean: mean, sd: sd}"
  return { mean, sd }
}
```

Much of this is (hopefully) fairly self-explanatory; the most unusual part is
the use of pipelines (the `|` symbol) to process each element of a collection:

*   `nums | sum` passes each element of the `nums` to the `sum` reducer, which
    returns the sum of all the values passed to it.
*   `nums | x -> x**2 | sum` transforms each element of `nums` with a lambda
    (that squares its input) before passing it to the `sum` reducer.

Despite the syntactic similarity to Unix streams, note that these pipelines are
inherently parallel; the elements of `nums` may be passed to the `sum` reducer
in any order, and subsets of `nums` may be summed independently.

(If `nums` is just an array it is unlikely that Retrospect would actually
benefit from (or use) parallel execution, but the language design is intended to
make parallelism easy enough to write that programmers do not force sequential
execution except where algorithmically appropriate. If `nums` were a very large
dataset that needed to be read from storage or a generator that required
expensive per-element computation Retrospect could take advantage of the
flexibility to use multiple cores or even multiple machines.)

Retrospect supports many different types of collections, any of which could be
passed to this function, e.g.

*   A simple array, e.g. `[-5, 0, 1.4e-2]`.
*   A higher-dimensional matrix.
*   A simple struct, e.g. `{red: 21, green: 200, blue: 5}`.
*   A range of sequential integers, e.g. `1..100`.
*   Values lazily read from a file, database, or other storage format.
*   A transformed and/or filtered collection, e.g.

    ```go
    someCollection | filter(x -> x.weight > 0.5) | x -> x.score**2
    ```

There are also many types of collectors that can terminate a pipeline, including

*   Parallel reducers such as `sum` or `min`.
*   Parallel savers such as `save` (materializes the values of a computed
    collection as a new in-memory collection with the same keys) or
    `saveUnordered` (materializes the values of a computed collection as a new
    array, ordering unspecified).
*   Sequential savers such as `saveSequential`.

For example,

```go
a = someCollection | x -> expensiveComputation(x) | save
```

could be used to apply `expensiveComputation()` to each element of an array and
construct an equal-sized array of results. It could equally well be used to
apply `expensiveComputation()` to each element of a multi-dimensional matrix
(returning a matrix of the same dimensions) or each value in a struct (returning
a struct with the same keys).

Alternatively,

```go
a = someCollection | filter(x -> test(x) > 0) | saveUnordered
```

would save the elements of collection for which `test()` returned a positive
value into a new array (no matter what type of collection it started with).
Because the elements may be tested in parallel, the ordering of the elements in
the result array is not specified; using `saveSequential` instead would force
sequential execution and a deterministic order.

## Loops

The `stats` function defined above would perform well for many common types of
collections, but it does two or three passes through the collection (once to sum
the elements, once to sum their squares, and potentially once to count the
elements). If the collection was being read from storage or if it required
non-trivial computation to produce each element, we might prefer an
implementation that only did a single pass; the easiest way to write that is to
use a `for` loop (see below for explanation of the `collect` keyword and `<<`
operator):

```go
function stats(nums) {
  for x in nums {
    n << 1
    xSum << x
    x2Sum << x ** 2
  } collect {
    n, xSum, x2Sum =| sum
  }
  if n < 2 {
    return { mean: n == 0 ? None : xSum, sd: None }
  }
  mean = xSum / n
  sd = sqrt((x2Sum - xSum * mean) / (n - 1))
  return { mean, sd }
}
```

By default `for` loops in Retrospect are parallel, which means that cumulative
state is not passed from one execution of the loop body to the next, so the
usual way of computing a sum (setting a variable to zero before the loop and
adding a value to it on each iteration) is not allowed. In its place we again
use the `sum` reducer:

*   the `collect` keyword at the end of the `for` loop lists the output
    variables (in this case, `n`, `xSum`, and `x2Sum`) and the collector that
    will be used for each (in this case, all three use `sum`); and
*   the `<<` ("emit") statement identifies a value that should be sent to one of
    the loop's output collectors.

Like a pipeline, a loop sends a stream of values to a collector that determines
the final result, but a loop gives us additional flexibility: we can use
multiple collectors in a single loop (in this case three of them), and the loop
can emit different values to each collector under different conditions (in this
case we make minimal use of that flexibility, always emitting a single value to
each collector for each element processed).

Loops can combine different types of collectors, e.g.:

```go
function stats(nums) {
  for x in nums {
    if looksSketchy(x) {
      anomalies << x
    } else {
      n << 1
      xSum << x
      x2Sum << x ** 2
    }
  } collect {
    n, xSum, x2Sum =| sum
    anomalies =| saveUnordered
  }
  if n < 2 {
    return { mean: n == 0 ? None : xSum, sd: None, anomalies }
  }
  mean = xSum / n
  sd = sqrt((x2Sum - xSum * mean) / (n - 1))
  return { mean, sd, anomalies }
}
```

excludes elements that appear suspicious from the statistics computation and
returns them as a separate result.

Not all loops can be parallelized; as an example of where sequential loop
execution is required, here is a function that implements binary search over an
array:

```go
// Assumes that array is sorted.
// Returns i such that array[i] == x,
// or -j if no such i exists and inserting x between
// array[j] and array[j + 1] would keep it sorted.

function binarySearch(x, array) {
  low = 0
  high = size(array)
  // Invariant: x > array[1..low]
  //        and x < array[high+1 ..]
  for sequential low, high {
    if low == high {
      break { return -low }
    }
    mid = div(low + high + 1, 2)
    a_mid = array[mid]
    if a_mid == x {
      break { return mid }
    } else if a_mid < x {
      low = mid
    } else {
      high = mid - 1
    }
  }
}
```

Note that the `for` loop is explicitly marked as `sequential`, and identifies
the variables (`low` and `high`) whose values may be updated by each iteration.
Also note that arrays in Retrospect are indexed from 1 (like Matlab, Fortran,
and Julia) rather than 0 (like C, Python and JavaScript), and that the `div(x,
y)` function implements "floor division" (like Python's `//` operator).

So e.g.

```go
binarySearch("three", ["one", "three", "two"])
```

will return 2, while

```go
binarySearch("twenty", ["one", "three", "two"])
```

will return -2 (since "twenty" would need to be inserted after the element with
index 2 in order to preserve sortedness).

The other novel construct here is that Retrospect’s `break` statement has an
associated block that is executed after the break. The statements in the break
block are conceptually outside of the loop body and can do things (such as
modify other variables, or return from the function) that aren’t allowed inside
the loop body. This is necessary to preserve the parallelizability of loops; for
example, here’s a function that returns the sum of the given numbers if all are
non-negative, or a negative element if one is found:

```go
function sumOrNegativeElement(nums) {
  for x in nums {
    if x < 0 {
      break { return x }
    }
    s << x
  } collect {
    s =| sum
  }
  return s
}
```

Since elements of `num` may be processed concurrently and out-of-order, if there
are multiple negative elements there is no guarantee which of them will be
returned. While the body of the loop may be executed by multiple threads in
parallel, the `break` statement forces a synchronization so that only one thread
can execute the `return` statement.

(If we wanted to be sure that we always returned the first negative value, we
could force sequential execution of the loop by just changing it to `for x in
nums sequential ...`.)

## Lambdas and Transformed Collections

An earlier example showed how lambda expressions could be used in a pipeline to
transform values before they were collected, but transformed collections can
also be used outside of pipelines; for example, if `a` is an array of key/value
structs:

```go
a = [{key: "one", value: 1}, {key: "three", value: 3}, {key: "two", value: 2}]
```

then `a | e -> e.key` is a transformed array where each element is the `key`
field of the corresponding element of `a`, i.e. `["one", "three", "two"]`. We
could use the binarySearch function defined above to find the element of `a`
with a given key, e.g.

```go
binarySearch("three", a | e -> e.key)
```

Transformed arrays are lazy, i.e. the lambda is applied each time an element of
the transformed array is requested, not when the transformation is applied.

Because these kinds of simple transformations are so common, Retrospect provides
a more concise syntax for them, using `^` to indicate when a function or
operator should be *distributed* over the elements of a collection, e.g.:

*   `a^.key` is equivalent to `a | e -> e.key`
*   `a ^- 1` is equivalent to `a | e -> e - 1`
*   `f(x, ^(3 *^ y))` is equivalent to `y | e -> 3 * e | e -> f(x, e)` \
    (which is equivalent to `y | e -> f(x, 3 * e)`)

`^` can also be applied to more than one of a function or operator's arguments,
which uses the `join` function to match elements with the same keys:

*   `x ^+^ y` is equivalent to `join(x, y) | [ex, ey] -> ex + ey`
*   `f(x, ^y, ^z)` is equivalent to `join(y, z) | [ey, ez] -> f(x, ey, ez)`

For example, if `x` and `y` are matrices with the same dimensions and `alpha` is
a number,

```go
z = (alpha *^ x) ^+^ ((1 - alpha) *^ y) | save
```

constructs a new matrix of the same dimensions, by element-wise linear
combination of the given matrices. The `save` causes the new matrix to be
computed eagerly. If the dimensions of `x` and `y` did not match the `join`
would throw an error.

Retrospect also supports a variant of the lambda syntax where `#` is used in
place of the variable name for a single-argument lambda, e.g. these three
assignments are equivalent:

```go
totalMomentum = objects | obj -> obj.mass * obj.v | sum
totalMomentum = objects | -> #.mass * #.v | sum
totalMomentum = (objects^.mass) ^*^ (objects^.v) | sum
```

## Filtered Collections

Filters can also be used to transform collections, but when they remove elements
the remaining elements are not re-indexed, and trying to retrieve a missing
element is an error.

For example,

```go
a = 1..10 | filter(-> (# % 3) != 0)
```

sets `a` to the range `1..10`, but with the numbers divisible by 3 removed:

*   `a | sum` returns `37` (i.e. 1+2+4+5+7+8+10),
*   `a[7]` is still `7`, and
*   `a[3]` is the singleton `Absent`.

If you want to get a new array (without missing elements) from a filtered
collection, the easiest solution is just to use the `saveSequential` collector
introduced earlier, e.g.

```go
b = 1..10 | filter(-> (# % 3) != 0) | saveSequential
```

will set `b` to `[1, 2, 4, 5, 7, 8, 10]`.

## Immutable Values

Here’s an insertion sort in Retrospect:

```go
function insertSort(nums) {
  for i in 2 .. size(nums) sequential nums {
    // nums[1 .. i-1] are already sorted, insert nums[i] appropriately
    n_i = nums[i]
    // If n_i turns out to be the smallest value we've
    // seen, this is where it will be inserted.
    dest = 1
    for j in reverse(1 .. i-1) sequential {
      if n_i >= nums[j] {
        // It's not the smallest; insert after this element
        break { dest = j + 1 }
      }
    }
    if dest != i {
      nums[dest+1 .. i] = nums[dest .. i-1]
      nums[dest] = n_i
    }
  }
  return nums
}
```

This introduces a couple of additional language features:

*   `reverse(seq)` represents the elements of a range or array with their
    ordering reversed
*   arrays can be indexed with ranges to get subarrays

All values in Retrospect programs are immutable, so for example this code
fragment:

```go
x = [3, 1, 4, 1]
y = insertSort(x)
```

will leave y equal to `[1, 1, 3, 4]` but x will still be `[3, 1, 4, 1]` — unlike
many other languages, an assignment like

```go
nums[dest] = n_i
```

updates the value of the variable `nums` but does not mutate any state that
might be shared by other variables.

It may be reassuring to know that this does not mean Retrospect copies the array
on every modification; instead, it uses reference counts to determine when such
copies are needed. As a result the first modification to `nums` within the
insertSort function may copy the array (thereby ensuring that `x` retains its
value in the code fragment above), but each subsequent modification can be done
in-place (since the result of the first copy was an array that is no longer
shared with anyone else). And if the next instructions were

```go
y[0] = 5
y = insertSort(y)
```

Retrospect would sort the modified array in place without any additional
copying, since the assignment of the result back to `y` ensures that its
reference count is 1 at the beginning of insertSort.

### Inout Parameters

One consequence of immutable values is that often it is necessary for a function
to return multiple results. For example, consider a program that transforms a
sequence of values, where the output corresponding to each input depends on how
many occurrences of that value it has previously seen. We could implement this
using a function that maintained a table mapping values to counts, but we would
want that function to return two results:

*   the count of how many times we’ve seen that value before
*   the table updated to reflect the additional occurrence of that value

To make this kind of function easy to write and use, Retrospect supports "inout"
parameters:

```go
function count(key, counts=) {
  i = binarySearch(key, counts^.key)
  if i > 0 {
    result = counts[i].count
    counts[i].count += 1
    return result
  } else {
    i = 1 - i
    // Insert another element before i
    counts[i .. i-1] = [{key: key, count: 1}]
    return 0
  }
}
```

For example,

```go
counts = [{key: 2, count: 100}]
c2 = count(2, counts=)
// now c2 is 100 and counts is [{key: 2, count: 101}]
c3 = count(3, counts=)
// now c3 is 0 and counts is [{key: 2, count: 101}, {key: 3, count: 1}]
```

Note that the second parameter in the function declaration is followed by an "="
to indicate that a new value for that parameter will be returned along with the
function’s regular return value, and the second argument in each call to the
function is similarly followed by an "=" (to make it clear that a new value is
being assigned to that variable by the call). The second argument to the count()
function must be followed by an "=", and must be an expression that could appear
on the left-hand side of an assignment.

It is also valid for a function with inout parameters to have no regular return
value; for example, the most natural way to write quicksort is a function with
an inout parameter and no other return value:

```go
procedure qsort(nums=, start, end) {
  if start >= end { return }
  pivot = nums[div(start + end, 2)]
  lo = start
  hi = end
  for sequential nums, lo, hi {
    // nums[start .. lo-1] ^<= pivot
    // nums[hi+1 .. end] ^>= pivot
    if nums[lo] < pivot {
      lo += 1
    } else if nums[hi] > pivot {
      hi -= 1
    } else {
      t = nums[lo]
      nums[lo] = nums[hi]
      nums[hi] = t
      lo += 1
      hi -= 1
    }
    if lo > hi { break }
  }
  assert lo > start and hi < end and lo == hi + 1
  qsort(nums=, start, hi)
  qsort(nums=, lo, end)
}
```

(The `assert` statement stops with an error if the condition is false.)

## Matrices

Arrays in Retrospect are a special case of its general support for
multi-dimensional matrices. Elements of a matrix are indexed by arrays of
integers.

For example, this function implements (2D) matrix multiplication:

```go
function mmult(x, y) {
  assert sizes(x)[2] == sizes(y)[1]
  return matrix([sizes(x)[1], sizes(y)[2]])
         | [i, j] -> (x[i, ..] ^*^ y[.., j] | sum)
         | save
}
```

A simple use:

```go
a = matrix([2, 3], [2, 0, 10, 0, -2, 11])
b = matrix([3, 1], [4, 5, 1])
c = mmult(a, b)                    // returns matrix([2, 1], [18, 1])
```

`a` is a 2x3 transform matrix, `b` is a 3x1 column vector, and their product `c`
is a 2x1 column vector.

Some new language features introduced in this example:

*   `sizes(m)` returns an array of integers, one for each dimension of the
    matrix m
*   `matrix(sizes)` returns a matrix with the given sizes, where each element's
    value is the same as its key (e.g. `matrix([3, 4])[i, j]` is `[i, j]`, for
    any `i` in `1..3` and `j` in `1..4`)
*   `matrix(sizes, matrix)` is similar to the `reshape` function in NumPy or
    MATLAB.
*   matrices can be indexed with a range for one or more of their dimensions,
    returning a submatrix

## Types and Generic Functions

Retrospect has the usual assortment of core types (e.g. integers, floating-point
numbers, strings, arrays, structs, matrices) but also allows programs to define
new types of values.

For example, we might implement rational numbers in terms of pairs of integers:

```go
// Rationals are represented by a struct of two integers, "num" and "denom",
// normalized so that:
// - num is non-zero
// - denom is greater than one
// - num and denom have no common factor

compound Rational
```

Every value of the newly-declared `Rational` type will have a *representation*
that is another value — in this case we will choose to use structs (e.g. `{num:
1, denom: 3}` will be used to represent 1/3).

The implicitly-defined function `Rational_` converts one of these
representations into a value of type `Rational`, and the postfix `_` operator
converts a `Rational` value back to its representation:

```go
rep = {num: 1, denom: 3}
rep is Rational   // returns false
rep.denom         // returns 3

oneThird = Rational_(rep)
oneThird is Rational // returns true
oneThird.denom       // error!  (Rationals don't have elements named "denom")
oneThird_.denom      // returns 3  (their representations do)
```

(Note that the `Rational_` function and the `_` operator are only accessible
within the module that defines the `Rational` type; outside of that module a
Rational’s representation is hidden.)

We can define a simple function for creating Rational values:

```go
// Returns an Integer or Rational representing
// num divided by denom.
function ratio(Integer num, Integer denom) {
  assert denom != 0
  if num == 0 { return 0 }
  if denom < 0 {
    denom = -denom
    num = -num
  }
  gcd = gcd(abs(num), denom)
  num = div(num, gcd)
  denom = div(denom, gcd)
  return denom == 1 ? num : Rational_({num, denom})
}

function gcd(Integer u, Integer v) {
  for sequential u, v {
    if v == 0 {
      break { return u }
    }
    [u, v] = [v, u % v]
  }
}
```

Each of the functions that we have defined so far has been implemented by single
function body. Retrospect also supports *generic functions*, which may have
multiple implementations (*methods*) each appropriate for different argument
types.

The standard mathematical operators (`+`, `-`, etc.) are syntactic sugar for
generic functions defined by the language core, so we can add additional methods
to these functions for our new type:

```go
method add(Rational a, Rational b) {
  return ratio(a_.num * b_.denom + a_.denom * b_.num, a_.denom * b_.denom)
}

method add(Rational a, Integer b) {
  return ratio(a_.num + b * a_.denom, a_.denom)
}

method add(Integer a, Rational b) {
  return b + a
}
```

```go
oneThird = ratio(1, 3)
twoThirds = oneThird + oneThird
twoThirds            // returns Rational_({num: 2, denom: 3})
oneThird + twoThirds // returns 1
```

A complete implementation would of course define the rest of the mathematical
operators and comparisons.

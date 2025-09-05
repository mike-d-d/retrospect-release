# Compiler tour, Part 2

[TOC]

## More language features

For the next stop on the tour we're going to look at a (slightly) more
interesting Retrospect program:

```
return f(1..4, 2)

// Sum the given numbers, after raising them to the given exponent.
function f(nums, x) = nums | -> # ** x | sum
```

The main program here is a single `return` statement, calling a function `f`
that is defined on the following line. `f` pipes its first argument through a
lambda that raises each element to the power given by its second argument, and
sums the results.

The instructions emitted for the main program are similar to the previous
example:

```
_t0 = range(1, 4)
_t0 = f(_t0, 2)
return _t0
```

(The `range` function implements the "`..`" operator.)

## Defining Functions

In order to emit these instructions the compiler must first create the function
`f`, using ModuleBuilder's `newFunction` method. The MockVM represents that call
as

```
function f(a0, a1)
```

Note that `newFunction` does not define any method for the function; it just
specifies the number of arguments (in this case two) and which of them are inout
(in this case, none). MockVM represents this with a Retrospect-like declaration
using made-up argument names (`a0`, `a1`, etc.).

## Defining Methods

The compiler separately defines a method for `f`, by first creating an
additional InstructionBlock and emitting instructions for the method body to it,
and then calling `addMethod`. MockVM represents that combination of calls as

```
method f(nums, x) {
    _t0 = pipe(nums, lambda@4:29(x))
    _t1 = sum()
    _t0 = pipe(_t0, _t1)
    return _t0
}
```

Compiling a method body is just like compiling the main program, except that we
first specify the locals (in this case `nums` and `x`) that will be initialized
with the function arguments.

Again, the only instructions needed for the method body are Calls and a Return;
the calls this time are to `pipe` (the `|` operator) and `sum` (a zero-argument
function that returns a Collector).

## Defining Compounds

The second argument to the first `pipe` call (`lambda@4:29(x)`) looks like
another function call, but it's actually a *Compound*. The arguments to Set,
Call, and Return instructions are Vm.Exprs, which can be

*   numeric or string constants,
*   Singletons (e.g. `True`),
*   Locals (e.g. `nums` or `_t0`), or
*   Compounds (e.g. `[1, [x, y]]`).

The VM provides standard Compounds for representing arrays with a fixed number
of elements (`arrayOfSize`) and structs with a given set of keys
(`structWithKeys`), but ModuleBuilder also has operations to create new
Compounds, which are used both to implement the Retrospect `compound` type
declaration and to implement the implicit types used for lambdas and loops. For
this example the compiler has created a Compound named `lambda@4:29` for the
lambda expression (on line `4`, column `29` — the compiler is free to choose
names that wouldn't be valid in Retrospect source).

Before using it the compiler must create the Compound with a call to
`newCompoundType`, which MockVM represents as

```
private compound lambda@4:29{x} is Lambda
```

indicating that

*   this compound cannot be referenced from outside the current module
    (`private`) — which also means that its name doesn't need to be unique;
*   values of this compound are of type `Lambda`; and
*   values of this compound will have a single element named `x` (the compiler
    adds one element for each closed-over variable).

The Expr in the `f` method above (`lambda@4:29(x)`) creates an instance of this
Compound with its element set to the value of the local `x`.

## Defining the Lambda Method

In order for the `lambda@4:29` values to be usable as Lambdas, they need a
method for the standard `at(lambda, arg)` function. The compiler emits this
method as well:

```
method at(_self, #) _0 is lambda@4:29 {
    x = unlambda@4:29(_self)
    _t0 = exponent(#, x)
    return _t0
}
```

Like the method defined for `f` this has two Locals that will receive the
function arguments, although their names (`_self` and `#`) are not valid
Retrospect variable names. This method also has a *MethodPredicate*, which
MockVM renders as `_0 is lambda@4:29` and is interpreted as "the first argument
must be of type `lambda@4:29`". The VM allows multiple methods to be defined for
each function, but requires that at runtime the MethodPredicates select a single
method for each call.

The first line calls the *uncompounding* function (represented as
`unlambda@4:29` by the MockVM) that takes a compound value of that type and
returns its elements.

## Start to finish

The overall compilation process for this example is similar to that of the
previous example, with a couple of additional steps:

1.  The parsed program (a `UnitContext`) has both a `BlockContext` for the main
    program and a `FunctionDeclContext` for the declaration of `f`.
2.  `Symbols.doCompile` first scans the declarations and adds a `FunctionDecl`
    to the `functions` table, with key `"f:2"`; this will allow us to find it
    later when compiling the main program. It also calls
    `ModuleBuilder.newFunction`, and saves the resulting Vm.Function in the
    FunctionDecl.
3.  After the `functions` (and `types`) tables have been populated, it creates
    subtasks to compile each of the method declarations (including those that
    are combined with function declarations, as in this example).
4.  The main program compilation then proceeds in parallel with compiling the
    method declarations.

In addition `PassTwo.visitLambdaExpression`

*   runs PassTwo on the lambda body, to identify the closed-over variables;
*   calls `newCompound` (or `newSingleton`, if there were no closed-over
    variables); and
*   creates another subtask to compile the lambda body and add an `at()` method.

## Extra credit: compiling a distributed operator

There's actually a simpler, more idiomatic way to write the function `f` in
Retrospect:

```
// Sum the given numbers, after raising them to the given exponent.
function f2(nums, x) = nums ^** x | sum
```

This uses Retrospect's "`^`" notation for distributing a function or operator
across a collection — in this case, across the left-hand argument to the "`**`"
operator. A distributed function call compiles to essentially the same instructions
as the equivalent pipe-through-a-lambda-expression, but the method for the new
Lambda type is generated automatically by the compiler.

(`ExpressionCompiler.visitOpExpression` uses
`ExpressionCompiler.compileDistributable` to emit the appropriate instructions.)

--------------------------------------------------------------------------------

[Part 1](compiler_tour_1.md) &nbsp; &nbsp; <> &nbsp; &nbsp;
[Part 3](compiler_tour_3.md) &nbsp; &nbsp; [Part 4](compiler_tour_4.md)

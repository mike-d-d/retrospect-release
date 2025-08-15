# The Retrospect Virtual Machine

[TOC]

The Retrospect virtual machine is defined by a set of interfaces in the
`org.retrolang.Vm` namespace. Retrospect source code is
compiled by making calls to these interfaces to construct virtual machine
representations of the functions, types, and methods declared in the source
code, and then other virtual machine interfaces are called to execute the
compiled code.

There are currently two implementations of the virtual machine interfaces
planned: a mock implementation used for testing the compiler (which does not
support actually running the code) and the implementation used for executing
programs.

The virtual machine interface defines about a dozen types of Java objects:

*   A VirtualMachine is the starting point for all virtual machine operations.
    While it is possible to create multiple instances of the VirtualMachine
    implementation they must be used completely independently; the results of
    passing an object returned by one VirtualMachine as an argument to an
    operation on another VirtualMachine are undefined.
*   Type, Singleton, Function, and Value represent the corresponding language
    concepts.
*   A Module provides access to the types and functions defined in a
    previously-compiled Retrospect module. Currently the only available Module
    is Core, which contains all the language-standard types and functions, but
    in the future there will be an option to create a Module from a
    previously-serialized ModuleBuilder
*   A ModuleBuilder provides the context for creating new types and functions,
    and for adding methods to previously-defined functions. ModuleBuilders also
    provide a way to execute code fragments in the context of that module, which
    is the mechanism for executing user-provided code.
*   An InstructionBlock is used to describe either the body of a method or an
    isolated code fragment. InstructionBlocks have methods corresponding to the
    eight instruction types implemented by the virtual machine.
*   A BranchTarget is used to represent the point in the instruction stream
    where a conditional or unconditional branch instruction may transfer to.
*   A Local is used to store the value of a Retrospect variable or
    subexpression.
*   An Expr is used to identify a value when emitting an instruction; Locals are
    Exprs, as are simple constant values (numbers, strings, and singletons), as
    well as compound expressions (e.g. an array or struct with each element
    specified by a separate Expr). Unlike Retrospect language expressions, Exprs
    may not include calls to functions.
*   Compounds include arrays of a fixed size, structs with a fixed set of keys,
    and new compound types declared by the user; they can be used to compose
    compound Exprs and to decompose compound values in a destructuring
    assignment.
*   A MethodPredicate is used to specify the applicability of a method.

(There are a few additional types that are used only when executing code with
the VM (ResourceTracker, RuntimeError, StackEntry) -- these will be more fully
documented when that code is ready for review.)

## InstructionBlocks and the instruction set

A typical request to a Retrospect service is a chunk of text that includes

*   definitions of types, functions, and/or methods; and
*   a block of statements to compute some desired result.

The service handles such a request by

*   creating a new ModuleBuilder to serve as the scope for this request;
*   calling `newSingleton`, `newCompoundType`, `newUnionType`,
    `newTypeFromUnion`, and `newFunction` methods on that ModuleBuilder as
    appropriate;
*   for each method definition, calling `newInstructionBlock`, then calling
    various methods to emit the appropriate sequence of instructions, and then
    calling `done` and `addMethod`;
*   for each lambda expression, defining a compound type (or singleton, if it is
    not closed over any variables) and adding an `at` method for values of that
    type;
*   for each `for` loop, defining compound types for the loop body and for its
    state and adding appropriate methods (depending on whether the loop is
    sequential or parallelizable) for the new loop type; and
*   creating an InstructionBlock for the block of statements defining the
    desired computation and calling `applyToArgs` to execute it.

Each InstructionBlock defines a sequence of instructions that starts with a
fixed number of argument values and returns a fixed number of result values. As
a simple example, this function definition:

```
// For triangle with edge lengths x1, x2, and x3, returns "acute" if the angle
// between x1 and x2 is less than 90° or "obtuse" if it is greater than or equal
// to 90°.
function triangle(x1, x2, x3) = (x1*x1 + x2*x2 < x3*x3) ? "acute" : "obtuse"
```

could be compiled to these instructions:

```
method triangle(x1, x2, x3) {
    _t1 = multiply(x1, x1)
    _t2 = multiply(x2, x2)
    _t1 = add(_t1, _t2)
    _t2 = multiply(x3, x3)
    _t1 = lessThan(_t1, _t2)
    branch L2 if not _t1
    _t0 = "acute"
    branch L1
  L2:
    _t0 = "obtuse"
  L1:
    return _t0
}
```

In addition to the Locals that are initialized with the function arguments
(`x1`, `x2`, and `x3`) these instructions use three additional Locals (`_t0`,
`_t1`, and `_t2`) to store intermediate values.

There are five kinds of instructions shown there:

*   *Call* instructions (such as `_t1 = multiply(x1, x1)`) call a function (in
    this case `multiply`), providing an Expr for each argument and a Local to
    receive each result (`multiply` takes two arguments and returns one result).
*   *Set* instructions (such as `_t0 = "acute"`) set a Local to the value of an
    Expr.
*   *Conditional branch* instructions (such as `branch L2 if not _t1`) test the
    value of a Local and transfer to a BranchTarget if some condition is true
    (in this case it transfer if the value of `_t1` is `False`).
*   *Unconditional branch* instructions (such as `branch L1`) and *Return*
    instructions do just what you would expect.

To define this method the compiler could execute this sequence of VM operations:

```
ModuleBuilder mb = vm.newModule(null);
Function fn = mb.newFunction("triangle", 3, i -> false, true, Access.VISIBLE);
InstructionBlock ib = mb.newInstructionBlock(3, 1, null);
Local x1 = ib.addArg("x1");
Local x2 = ib.addArg("x2");
Local x3 = ib.addArg("x3");
Function multiply = vm.core().lookupFunction("multiply", 2);
Function add = vm.core().lookupFunction("add", 2);
Function lessThan = vm.core().lookupFunction("lessThan", 2);
Local t1 = ib.newLocal("_t1");
ib.emitCall(t1, multiply, x1, x1);
Local t2 = ib.newLocal("_t2");
ib.emitCall(t2, multiply, x2, x2);
ib.emitCall(t1, add, t1, t2);
ib.emitCall(t2, multiply, x3, x3);
ib.emitCall(t1, lessThan, t1, t2);
BranchTarget b1 = ib.newTarget();
BranchTarget b2 = ib.newTarget();
ib.emitConditionalBranch(b2, t1, false);
Local t0 = ib.newLocal("_t0");
ib.emitSet(t0, vm.asExpr("acute"));
ib.emitBranch(b1);
ib.defineTarget(b2);
ib.emitSet(t0, vm.asExpr("obtuse"));
ib.defineTarget(b1);
ib.emitReturn(t0);
ib.done();
ib.addMethod(fn, null, false);
```

As one more example, this method definition:

```
method pop({values, size}=) {
  assert size > 0, "Stack is empty"
  result = values[size]
  size -= 1
  return result
}
```

could be compiled to these instructions:

```
method pop(_t0) (_t0 is Struct) {
    size, values = unstruct{size,values}(_t0)
    _t0 = lessThan(0, size)
    branch L1 if _t0
    error "Stack is empty"
  L1:
    result = at(values, [size])
    size = subtract(size, 1)
    return result, {size: size, values: values}
}
```

Note that the (implicit) destructuring assignment of a struct with keys `size`
and `values` is implemented by calling a function with one argument and two
results. This call could be emitted by this sequence of VM operations:

```
Compound struct = vm.structWithKeys("size", "values");
ib.emitCall(new Locals[]{size, values}, struct.extract(), t0);
```

Also note that:

*   the inout argument is implemented by returning two values from the
    InstructionBlock, the second of which is the new value of the inout
    argument; and
*   compound Exprs (constructed with `Compound.make()`) are used for the second
    argument to `at` and the second return value.

## Compiling lambda expressions

Each lambda expression is compiled by defining a new type and an `at` method for
that type. For example,

```
function foo(nums, x, y) = nums | n -> (n-x)**y | sum
```

could be compiled as

```
private compound Lambda1{x,y} is Lambda
function foo(nums, x, y)

method at(_self, n) (_self is Lambda1) {
    x, y = unLambda1(_self)
    _t0 = subtract(n, x)
    _t0 = exponent(_t0, y)
    return _t0
}

method foo(nums, x, y) {
    _t0 = pipe(nums, Lambda1(x, y))
    _t1 = sum()
    _t0 = pipe(_t0, _t1)
    return _t0
}
```

In this case the new type (`Lambda1`) is a compound type with two named elements
(`x` and `y`), the two variables that are referenced in the lambda expression;
if there had been no closed-over variables it would instead be declared as a
singleton.

The `at` method applies when its first argument is a `Lambda1`, and starts by
extracting the elements of that argument to use in computing its result.

The `foo` method constructs a `Lambda1` compound value and passes that as the
second argument in its first `pipe` call.

To compile this code the compiler could execute this sequence of VM operations:

```
ModuleBuilder mb = vm.newModule(null);

Compound lambda1 = mb.newCompoundType(
    "Lambda1", new String[]{"x", "y"}, Access.PRIVATE,
    vm.core().lookupType("Lambda"));
Function foo = mb.newFunction("foo", 3, i -> false, true, Access.VISIBLE);
{
  InstructionBlock ib = mb.newInstructionBlock(2, 1, null);
  Local self = ib.addArg("_self");
  Local n = ib.addArg("n");
  Local x = ib.newLocal("x");
  Local y = ib.newLocal("y");
  Local t0 = ib.newLocal("_t0");
  ib.emitCall(new Local[]{x, y}, lambda1.extract(), self);
  ib.emitCall(t0, vm.core().lookupFunction("subtract", 2), n, x);
  ib.emitCall(t0, vm.core().lookupFunction("exponent", 2), t0, y);
  ib.emitReturn(t0);
  ib.done();
  ib.addMethod(vm.core().lookupFunction("at", 2), lambda1.argType(0, true), false);
}
{
  InstructionBlock ib = mb.newInstructionBlock(3, 1, null);
  Local nums = ib.addArg("nums");
  Local x = ib.addArg("x");
  Local y = ib.addArg("y");
  Local t0 = ib.newLocal("_t0");
  Local t1 = ib.newLocal("_t1");
  Function pipe = vm.core().lookupFunction("pipe", 2);
  ib.emitCall(t0, pipe, nums, lambda1.make(x, y));
  ib.emitCall(t1, vm.core().lookupFunction("sum", 0));
  ib.emitCall(t0, pipe, t0, t1);
  ib.emitReturn(t0);
  ib.done();
  ib.addMethod(foo, null, false);
}
```

## Compiling loops

Like lambda expressions, each loop body is compiled as one or more methods
applicable to a new type. There are a number of interacting complexities,
including sequential loops vs. parallelizable, the use of collectors, and the
implementation of `break`; we will give just one example here and leave the
details for a separate document.

Consider this function, which computes the product of a collection of numbers
(stopping early if it encounters a zero):

```
function product(nums) {
  result = 1
  for n in nums sequential result {
    if n == 0 {
      break { return 0 }
    }
    result *= n
  }
  return result
}
```

This could be compiled as

```
private singleton Loop1
private compound Loop1State{result}

method product(nums) {
    result = 1
    _t0 = iterateAll(Loop1, nums, EnumerateValues, Loop1State(result))
    branch L1 if _t0 is not LoopExit
    return 0
  L1:
    result = unLoop1State(_t0)
    return result
}

method iterateOne(_self, _element, _state) _self is Loop1 {
    result = unLoop1State(_state)
    n = _element
    _t0 = equals(n, 0)
    branch L1 if not _t0
    _t0 = loopExit(None)
    return _t0
  L1:
    result = multiply(result, n)
    return Loop1State(result)
}
```

The compiler is defining two new types: one (`Loop1`) for the loop body and
another (`Loop1State`) for the state that gets passed from one iteration to the
next. Since the loop body is not closed over any variables (the only variable it
references from outside the loop is `result`, which is part of its state)
`Loop1` can be a singleton. The state has one component, the
sequentially-updated variable `result` (if this loop used collectors there would
be additional state elements for them).

The `product` method calls the `iterateAll` function, which in turn calls
`iterateOne` for each element of the given collection. The arguments to
`iterateAll` are

*   the loop value (the singleton `Loop1`) -- this is analogous to the lambda
    value passed to `pipe` earlier;
*   the collection (`nums`);
*   `EnumerateValues`, to indicate that each call to `iterateOne` need only pass
    a value from the collection (rather than (key, value) pairs); and
*   the initial state (`Loop1State(result)`).

The result of calling `iterateAll` is either the final state after processing
each collection element, or a `LoopExit` value if one of the calls returned that
in place of a next state. An early exit indicates a `break`, in which case
`product` should return zero. If we didn't get an early exit `product` extracts
the final value of `result` from the final state and returns that.

The second method definition, for `iterateOne`, is analogous to the `at` methods
we saw earlier for lambda values. `iterateOne` will be passed three arguments:

*   the loop closure (`_self`),
*   a value from the collection (`_element`), and
*   the loop state,

and is expected to return the state for the next iteration, or a `LoopExit`
value to terminate iteration immediately. `LoopExit` values can contain
additional information, but in this case the caller only needs to know that we
requested early exit so we just return `loopExit(None)`.

## The instruction set

The Retrospect virtual machine only has eight instruction types, and we've
already seen most of them:

*   `set(Local output, Expr input)`
*   `call(Local... outputs, Function fn, Expr... inputs)`
*   `branch(BranchTarget)`
*   `conditionalBranch(BranchTarget, Local input, boolean branchIfTrue)`
*   `typeCheckBranch(BranchTarget, Local input, Type type, boolean
    branchIfContains)`
*   `return(Expr... input)`
*   `error(String message, Local... include)`
*   `trace(String message, Local... include)`

The `error` and `trace` instructions are similar, in that each saves a snapshot
of the current execution state including

*   the message (if provided) and the values of any Locals that were specified
    in the instruction;
*   the statement being executed and the values of all live variables in the
    current method; and
*   for each call instruction on the execution stack, the statement being
    executed and the values of all live variables in that method.

The `error` instruction then terminates execution, while `trace` continues with
normal execution.

Note that the Retrospect instruction set only supports *forward* branches, i.e.
all branches that reference a BranchTarget must be emitted before that target is
defined. All looping in Retrospect is implemented by calling a function such as
`iterateAll`, rather than a backward branch.

# Generating Java bytecodes for Retrospect, Part 4

The previous examples have skipped over one important aspect of code generation:
ensuring that generated code properly updates the
[reference counts](ref_counts.md) of the objects it handles.

Correct reference counts are critical both to accurately monitoring memory use
and to optimizing code that builds complex structures incrementally. While
reference counts are maintained automatically when executing user-provided code,
built-in methods must accurately annotate their reference counting assumptions
and explicitly increment and decrement reference counts as needed.

It may come as a surprise, then, that no explicit reference count manipulation
is needed during code generation, and (since most built-in code is shared
between execution and code generation) any calls to addRef and dropRef are
simply ignored.

Instead reference count management is added as a final fix-up pass to the
generated code, after it has been optimized and simplified as much as possible
and immediately before CodeBuilder's translation from blocks to JVM
instructions. This fixup pass is implemented by creating a subclass of
CodeBuilder (RcCodeBuilder) that overrides a few key methods (in particular,
`finalizeBlocks`).

[TOC]

## Background: What does it mean to maintain correct reference counts?

Objects that inherit from RefCounted have a `refCount` field, which intuitively
stores the number of pointers to that object.

*   When the refCount is zero we know that the object is no longer reachable and
    should no longer be included in our "memory in use" measure.

*   When the refCount is one we know that the object is not shared; in some
    situations this may allow us to make in-place mutations to otherwise
    immutable objects.

*   When the refCount is greater than one we know that the object is shared;
    such objects can never be mutated.

The challenge with defining what it means for a refCount to be correct is that
we need to get precise about what it means to have a pointer to an object.

The simple case is when a pointer to an object X is stored in another
(reachable) object Y, e.g. as a field in an object or an element of an array.
Those pointers are considered "visible", since any thread with access to Y may
also have access to X, and are always represented in X's refCount.

The more complex case is when a pointer to X is in a local variable or on the
operand stack of an in-progress Java method execution. Some of these pointers
are clearly not included in the refCount (e.g. fetching an array element or
pushing a local variable on the operand stack creates an additional pointer, but
does not change any refCounts), while others must be (e.g. the pointer returned
by a `new` operation must be counted, since until it has been stored somewhere
it is the only pointer to that object).

We will call pointers in local variables or on the operand stack that are
counted *roots*, and say that an object's stored refCount is correct if

$$refCount = visibleCount + rootCount$$

This isn't yet a real definition, since we haven't specified which local
variables and operand stack entries are roots; even if we had, it would not
appear to be a useful definition since there would be no way to actually
determine the correct refCount without freezing all in-progress threads,
examining their stacks, and examining the entire reachable heap.

But it does allow us to verify that a sequence of JVM instructions *preserves*
refCount-correctness (i.e. if refCounts were correct before the instructions
were executed, they will remain correct after executing those instructions), as
long as we know which of the locals and operand stack values that are changed by
those instructions were considered roots.

For example, this sequence of operations

```
void foo(Value[] array, Value x) {
  assert array[0] == null;
  RefCounted.addRef(x);
  array[0] = x;
}
```

preserves refCount correctness: it adds one visible pointer to `x` (by storing
it in `array`), and it increases `x`s refCount (`addRef` is implemented as an
atomic operation so that even if we race with other threads updating `x`s
refCount, this thread's contribution will not be lost).

(The `assert` is necessary to ensure that we're not overwriting some other
pointer; doing so would *decrease* the visible count of the object that had been
pointed to by `array[0]` without updating its refCount, and so would not
preserve refCount correctness.)

Note that

*   Before executing `addRef`, `x` was not a root.

*   After executing `addRef` but before setting the array element, `x` *was* a
    root (which makes the increased refCount correct).

*   After executing the array update, `x` is no longer a root (since the visible
    count increased but the refCount didn't, the root count must have
    decreased). This is important, since `x` is about to disappear when the
    method exits; if it was still a root that would break refCount correctness.

In order to reason about refCount correctness within a sequence of instructions,
we need to know which locals are roots (and when — as the example above shows,
`x` may be a root at some points during execution and not others) and which
operations change rootness (e.g. we need to know that after storing `x` into
`array[0]` `x` was no longer a root).

The `addRef` and `dropRef` operations, which just increment and decrement an
object's refCount, can also be understood as

*   The argument to `addRef` must not be a root; after the call it is a root.
*   The argument to `dropRef` must be a root; after the call it is not a root
    (and should not be referenced again).

## Background: Arrays are sort-of reference counted

Although Java arrays cannot inherit from `RefCounted` and so do not have
explicit reference counts, it is useful to apply the same type of analysis to
them, with the restriction that an array's refCount can only be one (in use) or
zero (released).

What does this mean?

*   Arrays cannot be shared, since that would require a refCount greater than
    one. Arrays are usually part of a RefCounted object, which can be shared (in
    the same way an `ImmutableList` may contain a hidden `Object[]`).

*   The same rules around refCount correctness apply to arrays, but since there
    is no `addRef` operation they are more restrictive (e.g. if a local is not a
    root, it cannot be converted to a root; if it is a root (e.g. because it was
    returned by a `new` operation) it can only be passed as an `@RC.In` argument
    once).

*   Code *can* call `dropRef` on an array; doing so always causes the associated
    memory to be immediately released.

For simplicity the Retrospect VM currently only uses two general-purpose array
types: `byte[]` and `Object[]`. Other numeric types (such as `int` or `double`)
are stored by reinterpreting a `byte[]`, and an `Object[]` is used to store any
pointer type.

## Step 1: Annotating Ops

One of CodeBuilder's building blocks is the Op class, which represents a simple
operation that can be performed on a fixed number of CodeValues, returning
(usually) a CodeValue. Many Ops correspond to single JVM instructions (such as
adding two doubles, fetching a field of an object, or setting one element of a
`byte[]`). Others correspond to a Java method call (e.g. to `Math.addExact`, or
`TState.setResultTemplates`).

RcCodeBuilder provides an augmented subclass of Op (RcOp) that records
additional information about its
[reference counting contract](ref_counts.md#visible-counts-root-counts-and-annotations):

*   for each pointer-valued argument, whether that pointer will have its root
    count decremented (`@RC.In`) or its content modified (`@RC.InOut`); and
*   if the Op returns a pointer, whether or not its caller is responsible for
    decrementing the root count of the result (`@RC.Out`).

When an RcOp is created for a Java method, the existing `@RC.In`, `@RC.InOut`,
and `@RC.Out` annotations can be used as the source of this information; when an
RcOp is created for a JVM instruction it may need to be provided explicitly.

For example, the SET_OBJ_ARRAY_ELEMENT RcOp (corresponding to the JVM
instruction `aastore`, which sets one element of an `Object[]`) records that

*   its first argument (the array that is modified) is `@RC.InOut`, since its
    contents are modified; and

*   its third argument (the object to be stored) is `@RC.In`, since storing a
    pointer in another object decrements that pointer's root count.

This means that if the caller to `SET_OBJ_ARRAY_ELEMENT` intends to maintain its
reference to the third argument after calling SET_OBJ_ARRAY_ELEMENT it is
responsible for incrementing that object's reference count before the call.

As another example, the Ops that allocate and initialize new Frame objects are
marked as `@RC.Out`, since the caller is responsible for either storing or
calling `dropRef` on the object they return.

Note that OBJ_ARRAY_ELEMENT (corresponding to the JVM instruction `aaload`,
which returns one element of an `Object[]`) is *not* `@RC.Out`, since the value
it returns has not had its reference count incremented; if the caller wants to
store the pointer somewhere else it is responsible for first calling `addRef` on
it.

(Note that `@RC.In`, `@RC.InOut`, and `@RC.Out` are only relevant when the
corresponding object is reference counted. For example, since Singletons are not
reference counted the `@RC.In` is ignored if a Singleton is passed as the third
argument to SET_OBJ_ARRAY_ELEMENT.)

## Step 2: Representing register status

The first step in RcCodeBuilder's analysis is to enumerate the registers that
have been constructed and identify those that must be *tracked*, i.e. those that
can hold a pointer to a reference-counted object. Registers of type RefCounted
or one of its subtypes (e.g. Frame) must be tracked, as well as registers of
type Object or Value. Registers holding a `byte[]` or `Object[]` are also
tracked.

We will assign a *status* to each live tracked register at each block in the
CodeBuilder's block graph. The status may be

*   **counted:** If the value of the register is a RefCounted, this register is
    a root: it is included in the object's reference count, and the code is
    responsible for either decrementing the reference count or storing the
    pointer somewhere visible before it exits.

*   **uncounted:** This register's value is known to be uncounted, either
    because it is a type that does not inherit from RefCounted (such as
    Singleton), or because it is a RefCounted object on which `setUncounted()`
    has been called.

*   **dependent:** This register is not a root, but its value is known to be
    reachable from some other register that is a root (i.e. is counted). This is
    the status of a value returned from an Op like OBJ_ARRAY_ELEMENT.

To see the importance of a "dependent" status, consider this trivial method that
extracts a value from nested arrays:

```
@RC.Out Value getNested(@RC.In Object[] array) {
  Object[] x1 = (Object[]) array[0];
  Value x2 = (Value) x1[0];
  addRef(x2);
  dropRef(array);
  return x2;
}
```

This method is safe:

*   `array` is a root, since it is an `@RC.In` argument to the method. It
    remains a root until the call to `dropRef(array)`, which ensures that it is
    no longer a root when the method exits.

*   `x1` is not a root, since it is returned by the OBJ_ARRAY_ELEMENT operation.

*   `x2` is initially not a root, since it is returned by the OBJ_ARRAY_ELEMENT
    operation, but the `addRef` call makes it a root, which is necessary for the
    `return` to match the `@RC.Out` declaration.

On the other hand, this minor change would make it unsafe:

```
@RC.Out Value getNested(@RC.In Object[] array) {
  Object[] x1 = (Object[]) array[0];
  dropRef(array);
  Value x2 = (Value) x1[0];
  addRef(x2);
  return x2;
}
```

The modified version is unsafe because

*   If the refCount of `array` was one on entry (i.e. this was the last
    reference to `array`) the call to `dropRef` will cause it to be released,
    which will in turn cause any arrays it contains to be released. `x1[0]` will
    probably have been set to null by the time we read it, but it's also
    possible that the array will be reused and have some completely unrelated
    value stored there.

*   If the refCount of `array` was two on entry (i.e. this was the second-last
    reference to `array`) the call to `dropRef` will make it appear (to
    whichever thread has the other reference) that `array` is now unshared. That
    thread would be entitled to implement an update to `array` by mutating it,
    potentially changing the result that is returned for `x1[0]`.

Representing `x1` as dependent on `array` enables us to detect this danger,
because a dependent register will only remain valid as long as

*   the register on which it depends remains a root, and
*   the register on which it depends is not passed as an `@RC.InOut` argument.

Similarly we record that `x2` is dependent on `array` (it was derived from `x1`,
but since `x1` is dependent we copy its dependency rather than creating
dependency chains). Only when we convert `x2` to counted (by calling `addRef`)
does it become safe to `dropRef(array)` (it is no longer needed for `x1`, since
`x1` is no longer live).

A register may depend on more than one other register; in this case all of the
registers it depends on must remain roots and unmutated until the dependent
register is no longer live or is converted to a root with `addRef`.

## Step 3: Ensuring consistent, valid register status at each block

The goal of RcCodeBuilder's analysis is to determine the status of each live
register at each block, and to ensure that the final block graph maintains
refCounts correctly.

It does this with a handful of simple rules, such as:

*   If a counted register stops being live, and no other registers depend on it,
    call `dropRef`.

*   If a dependent register is passed as an `@RC.In` argument or returned from
    an `@RC.Out` method, it must first be converted to a counted register with
    `addRef`.

*   If a register that other registers depend on is passed as an `@RC.InOut`
    argument, each of the dependent registers must first be converted to counted
    with `addRef`.

*   If a counted register is passed as an `@RC.In` argument and this is not the
    last reference to it, an additional `addRef` is needed before the call.

*   If multiple control paths merge and a register is counted on some of them
    and dependent on others, insert `addRef` as necessary so that it is counted
    on all incoming paths.

*   If multiple control paths converge and a register is dependent on all of
    them, after the merge it is dependent on the union of their dependencies.

A simple constraint propagation enables us to determine where `addRef` and
`dropRef` must be inserted. RcCodeBuilder does this, and then lets CodeBuilder
proceed with the final stages of register assignment and translating to JVM
instructions.

--------------------------------------------------------------------------------

[Part 1](codegen_1.md) &nbsp; &nbsp; [Part 2](codegen_2.md) &nbsp; &nbsp;
[Part 3](codegen_3.md) &nbsp; &nbsp; <

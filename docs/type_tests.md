# Implementing type tests and method selection

[TOC]

This doc describes how the Retrospect VM answers two questions:

*   Does value *x* have type *T*?
*   Given arguments *x1*, *x2*, ... for function *F*, which method should be
    used?

## Retrospect type declarations and base types

There are four [declarations](language_reference.md#type-declarations) in
Retrospect that introduce new types:

```
singleton S [is SuperType1, SuperType2, ...]
compound C [is SuperType1, SuperType2, ...]
type U1 [is SuperType1, SuperType2, ...]
type U2 contains SubType1, SubType2, ...
```

Each of these is implemented by a corresponding Vm method (`newSingleton`,
`newCompoundType`, `newUnionType`, and `newTypeFromUnion`).

Every Retrospect value has a *base type*, the most specific type that includes
that value. For example, the value `1..5` has base type Range, but also has type
Matrix and Collection.

The first two forms of type declaration introduce new values with the
corresponding base types:

*   A `singleton` declaration introduces one new value whose base type is the
    new type. The new value is written the same way as the type name, so e.g.
    after

    ```
    singleton EmptyTree is Tree
    ```

    the value `EmptyTree` has base type `EmptyTree`.

*   A `compound` declaration introduces an unbounded number of new values whose
    base type is the new type. The new values are created using the new type's
    *compounding function*, e.g. after

    ```
    compound NonEmptyTree is Tree
    ```

    the value `NonEmptyTree_({left: x, right: y})` has base type `NonEmptyTree`
    (as would `NonEmptyTree_(4)` and `NonEmptyTree_([x, y])` if they were used).

*Union types* (those introduced by the two forms of `type` declaration) are not
the base type of any value, but may be the type of a value with some other base
type. For example, after the declarations above both `EmptyTree` and
`NonEmptyTree_({left: x, right: y})` have type `Tree` (because it is a supertype
of their base types).

## Definining `x is T`

The behavior of the Retrospect type test `x is T` is defined in a few steps.

We say that T1 is an *explicit supertype* of T2 if

*   T1 is the same as T2, or
*   T2 is declared to have a supertype T3:

    ```
    (singleton | compound | type) T2 is ..., T3, ...
    ```

    and T1 is an explicit supertype of T3.

Note that because types must declared before they can be referenced in other
type declarations, the explicit supertype relation defines a
[partial ordering](https://en.wikipedia.org/wiki/Partially_ordered_set).

Similarly, we say that T1 is an *explicit subtype* of T2 if

*   T1 is the same as T2, or
*   T2 is declared to have a subtype T3:

    ```
    type T2 contains ..., T3, ...
    ```

    and T1 is an explicit subtype of T3.

Explicit subtype also defines a partial ordering. Note that explicit subtype is
*not* the converse of explicit supertype, i.e. if T1 is an explicit supertype of
T2 it does not follow that T2 is an explicit subtype of T1.

We say that a type T1 *contains* T2 if there exists a T3 such that

*   T3 is an explicit subtype of T1, and
*   T3 is an explicit supertype of T2.

Conceptually, we need to be able to descend from T1 (following explicit subtype
links) and ascend from T2 (following explicit supertype links) to some common
type T3. If such a T3 exists, we have a containment relation; otherwise we
don't.

Type containment is also a partial ordering.

Finally, we say that `x is T` if `T` contains the base type of `x`.

(There is one exception to this definition: the type `Integer`. All numbers have
base type `Number`, and some of them return true for `x is Integer`. The type
`Integer` is a special case in many ways, and in hindsight probably shouldn't be
a VM type at all.)

## Implementing type tests

As each new type T is created, the VM computes that type's *supertype set* and
*subtype set*, i.e. the set of all explicit supertypes of T and the set of all
explicit subtypes of T.

For the first three forms of type declaration

```
(singleton | compound | type) T [is SuperType1, SuperType2, ...]
```

the subtype set contains only T itself, and the supertype set is just the union
of the supertype sets of SuperType1, SuperType2, etc., plus T itself.

Similarly, for the last form of type declaration

```
type T contains SubType1, SubType2, ...
```

the supertype set contains only T itself, and the subtype set is the union of
the subtype sets of SubType1, SubType2, etc., plus T itself.

Note that since the listed supertypes/subtypes must have been created
previously, their supertype/subtype sets will already have been computed. \
Also note that no subsequent declarations can change T's supertype or subtype
set.

To test if `x is T`, we now only need to test if there is a non-empty
intersection between `T`'s subtype set and the supertype set of `x`'s base type.

## Types and Modules

Supertypes and subtypes may be in the same module as the type being declared, or
in another module:

*   A supertype from another module must have been declared `open`.
*   A subtype from another module must not have been declared `private`.

Although module boundaries affect how subtype and supertype relations may be
established, they do not affect the definitions and algorithms in the previous
sections.

## Method Predicates and Method Selection

A Retrospect function is implemented by one or more methods, each with an
associated method predicate. A method predicate restricts the types of arguments
the method is applicable to; for example, a method for a function of two
arguments `add(x, y)` could have a method predicate:

*   that restricts it to only apply when both arguments have type `Number`; or
*   that restricts it to apply when either argument has type `Number`; or
*   that restricts it to apply when `x` is an `Array`; or
*   that restricts it to apply when `x` is a `Matrix` but *not* an `Array`; or
*   that places no restrictions at all.

Methods may also be marked as "default", indicating that they may be overridden
by a preferred (non-default) method.

When a function is called, all methods defined for that function are checked for
applicability:

*   It is an error ("No matching method") if there are no applicable methods.
*   If there is exactly one applicable method, it is executed.
*   If there is more than one applicable method but exactly one of the
    applicable methods is preferred (i.e. is not marked "default"), the
    preferred method is executed.
*   Otherwise it is an error ("Multiple matching methods").

Note that methods for functions that have been declared `open` need not be
defined in the same module as the function.

The goal of the method selection algorithm is to find the matching method (or
report an appropriate error) given the base types of the function arguments,
subject to two constraints:

*   We would like it to be relatively efficient, even in cases where there are
    many methods defined for the function. In particular, every lambda
    expression defines a new base type (with `Lambda` as a supertype) and a
    method for the `at(x, y)` function restricted to that type; we don't want
    each `at` call to take time proportional to the total number of lambda
    expressions.

*   In a server environment we anticipate the VM processing many requests,
    creating a new (transient) module for each request's local definitions but
    sharing other modules (such as `Core`) between requests. It should be
    possible for a request's module to add a method to an open function defined
    in one the shared modules (such as `at`, which is in `Core`) in a way that
    is visible to the current request but without modifying the shared module's
    state.

## Defining method applicability

The VM provides three functions for creating method predicates:

*   `Type.argType(int argIndex, boolean positive)` returns a MethodPredicate
    that is true if the argument with the given index is (`positive=true`) or is
    not (`positive=false`) a value of the given Type.

*   `MethodPredicate.and(MethodPredicate other)` returns a MethodPredicate that
    is true if both of the given MethodPredicates are true.

*   `MethodPredicate.or(MethodPredicate other)` returns a MethodPredicate that
    is true if either of the given MethodPredicates are true.

The language places
[a restriction](language_reference.md#cross-module-method-declarations) on
method predicates when adding a method to a function from another module:

> Methods declared for functions from other modules must restrict at least one
> argument to a type declared in this module.

(Or: "You can't implement other people's functions over other people's types")

This restriction provides the important property that the behavior of your
program is only affected by modules that it directly and indirectly refers to;
no other modules can have relevant declarations.

We formalize this restriction with a few definitions.

First, a method predicate is *attachable* in a given module if it was returned
by a call to `type.argType(index, true)` where `type` is defined by that module.

Second, for a method predicate `mp` and module `module` we define
`argRestriction(mp, module)` to be either

*   a set of method predicates that are attachable in `module`, with the
    property that if `mp` is true of an argument sequence `args` then there is
    at least one `type.argType(index, true)` in the set such that `type` is an
    explicit supertype of the base type of `args[index]`; or

*   null if no such set can be found.

A cross-module method declaration in `module` is only allowed if the
argRestriction of the method's predicate to `module` is non-null.

The intuition is that if we're not allowed to attach the method to the function
directly (because it's in another module), we can attach it to each type in the
argRestriction (by definition all those types are in our module). Then if we
look for

*   methods attached to the function itself, and
*   methods attached to each explicit supertype of each argument's base type

... we are guaranteed to find all applicable methods.

For example, when calling a lambda defined in our module we won't find the
appropriate method attached to `Core.at`, but we will find it attached to the
base type of the first argument (since it had a method predicate restricting
that argument to the newly-defined base type).

How hard is it to compute `argRestriction(mp, module)`? Not very:

*   if `mp` was returned by `type.argType(argIndex, positive)`, then
    *   if `positive` is true and all elements of `type`'s subtype set were
        defined in `module`, then `argRestriction` consists of
        `t.argType(argIndex, true)` for each `t` in `type`'s subtype set
    *   otherwise `argRestriction` is null
*   if `mp` was returned by `mp1.or(mp2)`, then
    *   if both `argRestriction(mp1, module)` and `argRestriction(mp2, module)`
        are non-null, then `argRestriction(mp, module)` is their union
    *   otherwise `argRestriction` is null
*   if `mp` was returned by `mp1.and(mp2)`, then
    *   if both `argRestriction(mp1, module)` and `argRestriction(mp2, module)`
        are null, then `argRestriction(mp, module)` is null
    *   otherwise `argRestriction` is either `argRestriction(mp1, module)` or
        `argRestriction(mp2, module)`, whichever is non-null (if both are
        non-null, either will do)

For example, if I define a method for `Core.add` that requires both arguments be
of type `Foo` (a compound defined in my module), its `argRestriction` will be a
set containing either `Foo.argType(1, true)` or `Foo.argType(2, true)` (i.e. the
method will be attached to `Foo`, but can be associated with either arg), while
if the method predicate allowed either argument to be a `Foo` (`or` instead of
`and`) the `argRestriction` would contain both `Foo.argType(1, true)` and
`Foo.argType(2, true)`.

## Implementing method selection

As suggested above, the key idea is that each method is either attached to the
function, or attached to each type in the `argRestriction` of its method
predicate.

Methods attached to the function are just kept in a simple list; on every
function call, each of them will be tested for applicability.

To attach methods to types, we create a new *key object* for each argument of
each function (i.e. a 3-argument function will have 3 key objects, each unique
to that function and argument index). Each type has a map (`type.methods`) from
key object to a list of attached methods.

To find all applicable methods for when applying a function `f` (with key
objects `argKeys`) to a sequence of arguments `args`:

1.  Every method attached to `f` is potentially applicable.
2.  For each argument `args[i]`:
    1.  For each type `t` in the supertype set of `args[i]`'s base type:
        1.  Every method in `t.methods[argKeys[i]]` is potentially applicable.

All of the potentially applicable methods must have their method predicates
tested to see if they are actually applicable.

A small optimization: we can wait to create each `argKeys` element until we need
it (i.e. until we need to attach a method whose `argRestriction` refers to that
index). Many functions won't end up populating all of their `argKeys`; we can
then skip step 2 for each index that does not have an `argKey`.

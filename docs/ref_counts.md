# Reference Counting in the Retrospect VM

[TOC]

During execution the Retrospect virtual machine uses
[reference counting](https://en.wikipedia.org/wiki/Reference_counting) to answer
two basic questions:

*   What is the total memory use of all live objects that have been allocated
    during execution? The most important reason to answer this question is to
    ensure that a computation does not exceed the available Java memory, which
    would cause Java to abort the process; instead we want to gracefully stop
    the execution and save its state in a form that allows the user to view what
    progress it had made and determine what caused the excessive memory use.

    A secondary benefit of being able to measure memory use is to be able to
    make informed decisions about how much parallelism to use. More parallelism
    can often improve the performance of a program, but too much parallelism may
    cause it to exceed the available memory.

*   Is a specific Java object shared, i.e. is there more than one reference to
    it? When a new Retrospect value is derived from an existing one (e.g.
    changing one element of an array), we may have a choice of either modifying
    the Java object that represents the original value to represent the new
    value or allocating a new object. If the object is shared, we must allocate
    a new object to ensure that other uses of it are unaffected, but if the
    object is unshared it is safe (and usually more efficient) to modify it in
    place.

## ResourceTracker: Tracking total memory use

The VM begins a new computation by creating a ResourceTracker object that will
maintain an up-to-date measure of the computation's memory use over its
lifetime, and setting its memory limit. The key function of ResourceTracker is
just a running total of the number of bytes in live objects, although it tracks
some additional counters in order to provide more insight into the computation's
performance:

*   separate counters for allocated and released (live = allocated - released);
    and
*   separate counters for number of objects as well as number of bytes.

A ResourceTracker records allocations and releases of three kinds of Java
objects:

*   Subclasses of RefCounted: RefCounted is an abstract class that defines a
    reference count field and some methods to manipulate it. Every non-array
    object whose memory is tracked must be a subclass of RefCounted.

*   Java byte arrays (`byte[]`): these are used whenever variable-sized blocks
    of non-pointer data are needed. Using a single primitive array type together
    with methods that allow them to be read or written as other primitive
    numeric types (`int[]` or `double[]`) minimizes the number of cases that
    need to be dealt with in memory management.

    Rather than try to store a reference count in the array, these arrays are
    never shared; instead they are usually wrapped in a RefCounted, which can be
    shared. As long as the array is reachable it is assumed to have a reference
    count of one.

*   Java object arrays (`Object[]`): pointers cannot be stored in byte arrays,
    so we also need object arrays. Again we simplify memory management by using
    untyped object arrays everywhere, and again we assume a reference count of
    one for the array.

Thus the core operations on a ResourceTracker are:

*   record a new allocation of a RefCounted, byte[], or Object[];
*   record that a previously-allocated object is no longer reachable; and
*   determine how much of the original memory budget is still available.

A ResourceTracker can also be configured in a special "debugging mode", which
records stack traces on each allocation and matches allocations and releases,
providing a valuable tool to find errors in the VM's reference-counting logic.
This mode is quite expensive and is only intended for use during VM development.

Note that tracked objects do not record which ResourceTracker they are
associated with; it is the VM's responsibility to ensure that each object's
release is recorded by the same ResourceTracker that recorded its allocation. In
general this is not an issue, since objects from one computation don't mix with
objects from other computations, but would require some care in the case of e.g.
a cache shared by multiple computations.

## MemoryHelper: Releasing memory and reducing contention

When a RefCounted or Object[] is no longer reachable, we don't just need to
record its release; we also need to enumerate each of the tracked objects it had
a counted reference to, and either decrement that object's reference count (if
it's a RefCounted) or release it (if it's a byte[] or Object[]). This
potentially recursive process is implemented by a class called MemoryHelper;
thus while incrementing a reference count is done by a method on RefCounted,
decrementing a reference count is done with a call to MemoryHelper.

MemoryHelper serves another function: it can perform a series of allocations and
deallocations and accumulate the aggregate change to memory use, then update the
ResourceTracker with a single call. This potentially reduces contention on the
ResourceTracker when many threads are assigned to the same computation. The
reduced contention is made possible because the MemoryHelper classes are *not*
shared (they are not thread-safe) — each thread will have its own MemoryHelper,
and can perform allocate and release operations on it without any
synchronization. Each MemoryHelper only needs to synchronize with the
ResourceTracker periodically (or when about to make a large allocation) in order
to keep the total memory use numbers more-or-less up-to-date.

## Object sizes and counted references

For each tracked object we need to know:

*   each of the tracked objects it has a counted reference to; and
*   the total size in bytes of the object (including any non-tracked objects it
    contains).

For tracked byte arrays these are easy:

*   The size of a byte array can be computed from its length using a simple
    formula.
*   Byte arrays cannot have references to any other objects.

Tracked object arrays are a little more complex:

*   The size of an object array can be computed from its length using a simple
    formula.
*   Every element of a tracked array that could be tracked (i.e. is a
    RefCounted, byte[], or Object[]) is assumed to be a counted reference; any
    other object types are ignored.

Each subclass of RefCounted has two associated responsibilities:

*   Its constructor will be passed an *Allocator*, and it must call
    `recordAlloc()` on the allocator exactly once with `this` and its total size
    in bytes.

*   It must define implement RefCounted's `visitRefs()` method to call the given
    visitor once with each tracked object it has a counted reference to, and
    return the total size in bytes of the object (which must match the value it
    passed to `recordAlloc()`).

(Both ResourceTracker and MemoryHelper implement Allocator. The rest of the
Allocator API will be discussed later.)

For example, a simple reference-counted `Pair` class might look like

```
import static org.retrolang.impl.Value.addRef;
class Pair extends RefCounted implements Value {
  static final long OBJ_SIZE = SizeOf.object(RefCounted.BASE_SIZE + 2 * SizeOf.PTR);

  final Value x;
  final Value y;

  Pair(Allocator allocator, Value x, Value y) {
    this.x = addRef(x);
    this.y = addRef(y);
    allocator.recordAlloc(this, OBJ_SIZE);
  }

  @Override
  long visitRefs(RefVisitor visitor) {
    visitor.visitIfRefCounted(x);
    visitor.visitIfRefCounted(y);
    return OBJ_SIZE;
  }
}
```

Note that the constructor does three things:

*   It increments the reference counts of its arguments, if they are RefCounted.
    (Not all classes that implement `Value` need be subclasses of RefCounted;
    `Value.addRef()` is a no-op for anything that isn't.)

*   It stores the arguments in its fields. These fields are considered counted
    references, which is why we first incremented the reference counts.

*   It calls `allocator.recordAlloc()` with the size of the object. Note that
    this does not include the size of `x` or `y` — either they are RefCounted
    and their size is being tracked separately, or they are not because their
    size does not need to be counted (e.g they are statically allocated). Also
    note that `recordAlloc()` will not throw an exception even if the
    Allocator's memory limit is exceeded; see the section on Memory Limits below
    for details.

The `visitRefs` method will be called when the `Pair` is about to be released,
with a visitor that decrements the reference counts of the objects that are
passed to it. It may also be called at other times when the ResourceTracker is
in debugging mode, so it should not assume that the `Pair` is being released.

## Visible counts, Root counts, and annotations

Reference counting logic is tricky, and getting it wrong will usually lead to an
error very fair from the point at which the mistake was made (e.g. memory-in-use
will be too high or too low, a reference count for a reachable object will be
zero, or a shared object will appear unshared). An incorrect reference count is
a global property, but we can only reason about a program in relatively small
pieces — preferably not more than one method at a time.

To make it possible to reason about reference counts locally the Retrospect VM
uses annotations on fields and methods:

*   For fields, we want to know which fields of a RefCounted are considered
    counted references.

*   For methods, we want to know when a method's caller is transferring a
    reference count for one or more of its arguments to the method, and when the
    returned value includes a reference count.

What does it mean to "transfer a reference count"? To make this all more precise
we introduce *visible counts* and *root counts*. These are aggregate properties
of the VM's state, and so not directly available to the VM, but defining them
enables us to reason about its correctness.

The visible count of a tracked object is the number of counted references to it
from live tracked objects. In other words, if we enumerated every object with a
recorded allocation but not a recorded release, and enumerated each of the
counted references of those objects, the number of times we saw *x* would be the
visible count of *x*.

For a RefCounted object, the visible count should always be less than or equal
to the recorded reference count (i.e. the value of the `refCnt` field in the
RefCounted base class). For a byte or object array, the visible count should
always be zero or one (since they may not be shared).

The difference between the an object's visible count and its recorded reference
count (or between its visible count and one, for arrays) is its *root count*.
Intuitively if the root count of *x* is *n*, there are *n* local variables on
active thread stacks whose value is *x*, where the code has yet to call
dropRef().

For example, consider a fragment of code that creates an instance of the `Pair`
class defined above:

```
Pair p = new Pair(allocator, x, y);
```

Newly-created objects always have their refCnt field set to one. There can't yet
be any other objects with pointers to `p`, so it follows that its visibleCount
is zero and thus its root count is one. In general any code that manipulates
RefCounted objects is required to eventually return their root counts to zero,
which this code could do by either

*   incrementing the visible count, i.e. by storing `p` as a field in a tracked
    RefCounted or as an element in a tracked object array; or

*   decrementing the refCnt field (by calling `RefCounted.dropRef()`).

Alternatively it can hand that responsibility off to another piece of code, by
either

*   calling a function that "takes the ref count" of its argument (i.e. takes
    the responsibility for decrementing its root count); or

*   returning `p` as the result of the current function, with the understanding
    that its caller will now be responsible for decrementing its root count.

We document these responsibilities using two annotations:

*   Annotating a function argument with `@RC.In` indicates that the caller is
    transferring responsibility to decrement that argument's root count by one.

*   Annotating a function (or constructor) with `@RC.Out` indicates that the
    function is transferring responsibility to its caller to decrement the root
    count of the value returned.

Using these annotations, the `Pair` constructor would have been written

```
  @RC.Out Pair(Allocator allocator, Value x, Value y) {
    this.x = addRef(x);
    this.y = addRef(y);
    allocator.recordAlloc(this, OBJ_SIZE);
  }
```

since the constructed object is returned with a root count of one, and it is the
caller's responsibility to decrement it. Note that the arguments should not be
annotated; the constructor increments their `refCnt` (by calling `addRef()`)
*and* increments their visibleCount (by storing them in fields of `this` which
are counted references), for no net change to the root count.

If the caller were not going to do anything further with the arguments, it might
be more convenient to write the constructor take their reference counts:

```
  @RC.Out
  Pair(Allocator allocator, @RC.In Value x, @RC.In Value y) {
    this.x = x;
    this.y = y;
    allocator.recordAlloc(this, OBJ_SIZE);
  }
```

Since this version increments their visibleCounts (by storing them in counted
fields) and doesn't change their `refCnt`s, it decrements their root counts and
thereby fulfills the commitment of the `@RC.In` annotations.

RefCounted objects should also use the `RC.Counted` annotation on each of their
fields that store counted references (e.g. the `x` and `y` fields of `Pair`).

These annotations allow us to check that each function updates reference counts
consistently with the annotations by looking only at that function and the
annotated signatures of the functions it calls. Currently that checking must be
done manually, but perhaps one day someone will write a static checker to do it.

## The Allocator interface and Allocator.UNCOUNTED

Functions that allocate new tracked objects should generally take an instance of
the Allocator interface as an argument:

```
interface Allocator {

  void recordAlloc(RefCounted obj, long size);

  @RC.Out byte[] allocByteArray(int size);

  @RC.Out Object[] allocObjectArray(int size);

  void adjustAlloc(RefCounted obj, long sizeDelta);

  static final Allocator UNCOUNTED = ...
}
```

We have already seen how the `recordAlloc()` method is used; it should be called
exactly once for every RefCounted object allocated, to ensure that memory use is
properly updated. `allocByteArray()` and `allocObjectArray()` are used to
allocate (and record) arrays; note that they are annotated `RC.Out`, since the
caller is responsible for ensuring that their root count is decremented, usually
by storing them somewhere visible.

`adjustAlloc()` can be used if it is necessary to modify the recorded size of a
previously-allocated RefCounted. This is rare; more often a RefCounted would
change its size by allocating a new array of a different size, copying over the
array contents, and then releasing the previous array.

Finally, there is a special implementation of Allocator called UNCOUNTED for
cases in which we wish to create instances of RefCounted that are not associated
with any ResourceTracker. For example, consider a shared library that has a
constant Value as part of its implementation. Creating that Value using
Allocator.UNCOUNTED will result in a RefCounted whose size is not included in
any ResourceTracker and whose refcounting is disabled (methods to increment and
decrement its refcount have no effect, so it will never be released).

## Enforcing memory limits

Although each ResourceTracker has a limit on the amount of memory it is supposed
to use, the Allocator calls will succeed even if that limit is exceeded. Rather
than deal with the complexity of a potential out-of-memory error at every
allocation site, the VM relies on

*   periodically checking that it is still within memory limits while at known
    safe spots (e.g. just before beginning the execution of the next
    instruction); and
*   checking before making any particularly large array allocations.

This policy means that we may go a little over the ResourceTracker's memory
limit, but lets the VM be simpler and faster. As long as we set the
ResourceTracker limit somewhat below the Java VM limit that is a good tradeoff.

## The "startUpdate" idiom for compound values

Many built-ins take a compound value as an argument and return a modified
version of it as one of their results. Simple modifications are easily
implemented with the `Value.replaceElement()` Java method, which returns the
compound with a single element replaced. If the CompoundValue is unshared,
`replaceElement()` can modify it in place; otherwise it makes a copy with the
modification.

Modifying a nested value is a little trickier. For example, structs are
represented by a Struct compound with two elements, an array of keys and a
parallel array of values. The obvious way to replace one value in a Struct would
look something like this:

```
Value values = struct.element(1);
values = values.replaceElement(tstate, index, newValue);
struct = struct.replaceElement(tstate, 1, values);
```

(i.e. replace the chosen element of `values`, and then replace the values array
in the original Struct). The problem with this approach is that the call to
`values.replaceElement()` will never be able to modify the array in place, since
it will always have at least two references.

We could try to work around that by using `struct.peekElement(1)` instead of
`struct.element(1)`, but that would just be wrong; we can't pass `values` as an
`@RC.In` parameter if we haven't increased its root count. (If `struct` was
shared (i.e. its refCount was greater than one) but its second element (the
values array) was unshared, the `values.replaceElement()` call would modify the
array in place and hence modify `struct`, which it shouldn't.)

Instead we need to add a step that severs the connection from `struct` to
`values`, then update `values`, and then make a second update to `struct` to
reference the updated `values`:

```
Value values = struct.element(1);
struct = struct.replaceElement(tstate, 1, Core.TO_BE_SET);
values = values.replaceElement(tstate, index, newValue);
struct = struct.replaceElement(tstate, 1, values);
```

(`TO_BE_SET` is a singleton intended for use in situations like this, where one
element of a compound value will be provided in a subsequent step. We could use
`NONE` or `ABSENT`, but using a distinguished value makes the intent clearer and
enables some optimizations later.)

Note that although it might appear that this could lead to extra copying, it
won't. If `struct` is unshared then neither call to `struct.replaceElement()`
must copy, but even if `struct` is shared only the first call must copy; the
result of `replaceElement()` is always unshared, so the second call to
`struct.replaceElement()` will always be able to modify it in place.

As an aside, this is analogous to how Retrospect implements explicit updates to
nested values with the `startUpdate()` function, e.g. the Retrospect code

```
x[i].foo = v
```

is compiled as

```
// startUpdate() returns the specified element and an updater that will replace
// that element
x_i, x_i_updater = startUpdate(x, i)
// ... then we make the desired change to the extracted x[i] ...
x_i = replaceElement(x_i, "foo", v)
// ... and then we use the updater to get the final value of x
x = x_i_updater @ x_i
```

The `x_i_updater` value contains `x` except for `x[i]`, so that the call to
`replaceElement()` can modify `x_i` if there were no other references to the
values of `x` or `x[i]`.

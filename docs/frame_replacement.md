# Frame replacement

[TOC]

As described in [FrameLayout Evolution](frames.md#framelayout-evolution), the
Retrospect VM will choose FrameLayouts automatically to represent the values
being stored, but may need to evolve those FrameLayouts after further execution.
When a FrameLayout is evolved there are usually some already-allocated Frames
using the older FrameLayout. Over time the VM may evolve a FrameLayout multiple
times, leading to several generations of now-obsolete FrameLayouts that are
still used by existing Frames.

Forcing generated code to handle every obsolete FrameLayout could add a lot of
complexity, so we choose instead to replace Frames that have obsolete layouts
with updated versions when we encounter them. This may introduce some additional
cost at conversion time (in the worst case we may have to rewrite substantial
amounts of data, e.g. converting an `int[]` to the corresponding `double[]` or
rewriting a union tag to accommodate additional possible choices), but we prefer
to pay that cost once per value over having to handle multiple layouts for the
rest of the program execution.

(There's an assumption here that for the (long-running) programs whose
performance we care most about, there may be a few iterations of FrameLayout
evolution during early stages but for the bulk of execution FrameLayouts will be
stable. We want to optimize the VM's performance during that (presumed) long
period of stability, even if it requires some additional work while layouts are
evolving.)

Frame replacement has a lot of details to get right; in order to make the
presentation more digestible, this doc describes how it works in two stages:

*   A simplified version that overlooks some of the complexities that result
    from multi-threading.
*   A complete version that ensures correct behavior in the presence of multiple
    threads.

## The simple version

At a high level this is straightforward:

*   If we encounter a Frame with an obsolete layout, create a replacement using
    the evolved layout and link the old Frame to the new Frame.
*   If we encounter a pointer to a replaced Frame (e.g. as an element of a
    CompoundValue or another Frame), switch it to point to the new Frame.

The idea is that as the program executes, each out-of-date Frame will be
converted to the new layout once, and then each pointer to the old version will
be updated to the new version. Eventually the old version will no longer be
reachable and Java will garbage collect it.

Without a scan of all reachable objects (or back pointers for all object
references) we cannot ensure that all pointers to the old version will be
converted promptly; instead we assume that we will encounter (and hence update)
all the pointers for most objects, and try to minimize the memory overhead of
any remaining converted-but-still-reachable old objects.

Rather than introduce a new field to link the old Frame to its replacement, we
will reuse the field currently used for its FrameLayout (and rename it from
"layout" to "layoutOrReplacement"). This has two benefits:

*   We avoid the memory cost of adding an extra mostly-unused field to every
    Frame.
*   Since we already need to check the FrameLayout before we can do anything
    with a Frame, we don't need to check a separate field to recognize replaced
    Frames.

The layoutOrReplacement field of a replaced Frame will point to a
Frame.Replacement object, which in turn will point to the new Frame. The extra
level of indirection may appear unmotivated at this stage but will be useful
later.

So the outline of our process looks like this:

1.  Load the Value (e.g. from a local variable or an element of CompoundValue).
2.  If it's not a Frame, skip the remaining steps.
3.  Load its layoutOrReplacement field; if that's a Frame.Replacement:
    *   Follow the replacement link to get the new Frame.
    *   If we can update the source of this value (from step 1), make it point
        to the new Frame.
    *   Go back to step 3 (to see if the replacement has itself been replaced).
4.  We got a FrameLayout in step 3, so check if that layout has been evolved.
    *   If it hasn't, we're done.
5.  This Frame's layout has evolved, but the Frame doesn't yet have a
    replacement. Create a new Frame using the new layout, with the same value as
    the original Frame.
6.  Set the original Frame's layoutOrReplacement to a new Frame.Replacement
    object that references the new Frame.
    *   We could also fix up the source of the original pointer (from step 1),
        or just leave that for the next time we encounter it.
7.  Clear all the pointers in the old Frame (restoring the correct reference
    counts for referenced values, and recovering the memory used for a VArray's
    element arrays).
8.  Continue, using the new Frame.

Note that the original Frame and its replacement have separate reference counts:

*   The refCnt of the original reflects how many references remain to the
    original Frame
*   The refCnt of the replacement reflects how many references there are to the
    new Frame, including the one from the original Frame.

When the refCnt of the original drops to zero (potentially during step 3 above,
as it replaces references to the old Frame with references to the new Frame) we
know that it is no longer reachable and we can drop the reference from the old
Frame to the new Frame.

Although the old Frame will continue to use memory until its refCnt gets to
zero, we have minimized that memory use.

## What's wrong with the simple version

The simple version is vulnerable to several race conditions. Here's one example:

*   Thread T1 encounters Frame F1, loads its layout L1, and starts using the
    elements of F1.
*   Thread T2 causes L1 to evolve to L2
*   Thread T2 encounters F1, determines that it needs to be replaced, and
    replaces it with F2

Now T1 is using the elements of F1 at the same time that T2 is clearing them,
expecting everyone to use F2 instead.

Retrospect avoids many race conditions by only allowing a thread to modify an
object if the object's refcount is 1, but we can't rely on that here; often the
longest-lived Frames in a program (and hence the ones that it is most important
to update to a new layout) will be an input to many subcomputations and hence
shared among many threads.

We could try to solve this problem by introducing a Reader/Writer lock that
would be held by any object while it was reading the contents of a Frame, but
that would introduce a lot of overhead on every object read.

## The more complex version

The idea behind our solution to this problem is to postpone step 7 (clearing the
pointers in the old frame), rather than doing it immediately after step 6
(installing the Frame.Replacement pointer) — we'll continue with step 8, and do
step 7 asynchronously. In the interval between steps 6 and 7 other threads may
use either the original or the replacement interchangeably; we won't start step
7 until we are sure that all threads will see and follow the Frame.Replacement
pointer.

This requires coordination among all the threads that might access a given
Frame, i.e. all threads that are executing using the same ResourceTracker. We
introduce a per-ResourceTracker Coordinator, which all active threads are
expected to synchronize with periodically. After a Coordinator synchronization
an active thread must re-check any Frames it accesses for possible replacement,
but any frame replacements that happen after its synchronization are guaranteed
to not be cleared until its next synchronization.

In order to implement this the Coordinator maintains a replacementClock value,
which is incremented each time a frame is replaced. For each active thread we
keep track of the replacementClock value the last time it synchronized with the
Coordinator. If all threads have synchronized since the frame was replaced, it's
safe to clear the frame. Frames that have not yet been cleared are kept in a
queue, and whichever thread is last to synchronize pops the queue and clears the
frame. The Coordinator saves the last-synchronized time for each active thread,
enabling it to determine when the frame is safe to clear.

Note that even after a replaced Frame has been cleared and its refCount has gone
to zero it is not safe to reuse that Frame.

*   Assume that F1 has been replaced with F2, and all active threads have since
    synchronized with the Coordinator but not all pointers to F1 have been
    fixed.
*   Assume that there is one remaining pointer to F1 (so its refCount is 1), and
    that both T1 and T2 encounter it (i.e. start at step 1 in the process
    above).
*   T1 fixes up the pointer (so that it now points to F2), and decrements F1's
    refCount to 0. Normally that would be a sign that we could reuse F1, but
    doing so (in particular, resetting its layoutOrReplacement field) will cause
    T2 to do something unpredictable. Fortunately we don't need to -- T1 can
    update the ResourceTracker to declare that the memory is now released, but
    leave the Frame for Java's GC to recover. That will almost always be
    immediately (i.e. it's garbage as soon as T1 replaces the original pointer),
    but in pathological cases it will be kept around for as long as it needs to
    be so that T2 can load the layoutOrReplacement field and see the
    replacement.

## Another problem

Another potential issue with frame replacement in a multi-threaded environment
is that we're sharing objects without using `synchronized`. Java guarantees that
if events happen in this order:

1.  thread T1 constructs an object X and stores it somewhere
2.  T1 releases a lock L (i.e. exits a block that was `synchronized` on L)
3.  T2 acquires L (i.e. enters a block that is `synchronized` on L)
4.  T2 loads a pointer to X and reads its contents

... then T2 will see the values that T1 stored in X. Without steps 2 and 3,
however, T2 may not see the correct values in X -- e.g. fields that were set by
T1 may appear null or zero. The plan above is vulnerable to this problem in two
places:

*   T1 writes a Frame.Replacement into an existing Frame (step 6) and T2 reads
    it (step 3): T2 may get incomplete data from the Frame.Replacement, or from
    the new Frame that it points to.
*   T1 updates a reference to a replaced frame to point directly to the
    replacement (step 3 substep) and T2 reads it: T2 may get incomplete data for
    the new Frame.

Since `synchronized` can be expensive, we don't want to require it before every
read of data from a Frame.

Java provides another way to ensure that we see fully-initialized objects, using
operations that have explicitly-specified "memory ordering effects":

1.  T1 constructs an object X and stores it somewhere *using StoreRelease memory
    ordering*
2.  T2 loads X *using LoadAcquire memory ordering* and reads its contents

Although StoreRelease and LoadAcquire operations may be more expensive than
regular writes and reads, they are significantly cheaper than `synchronized`
blocks and do not risk blocking other threads.

This provides a simple fix to the first manifestation of the problem:

*   Use a StoreRelease write when setting the layoutOrReplacement field to a
    Frame.Replacement.
*   Use a LoadAcquire read when loading a Frame.Replacement from the
    layoutOrReplacement field.

Of course we usually don't know when reading the layoutOrReplacement field
whether we'll get a FrameLayout (always safe) or a Frame.Replacement (unsafe
without the LoadAcquire). We could handle this by always reading the field with
LoadAcquire, but since we expect frame replacement to be relatively rare we
instead use a normal read at first, and go back for a LoadAcquire read if we
discover that we've got a Frame.Replacement. (Java guarantees that even an
incompletely-initialized object will have the correct type).

The second manifestation is trickier: in this case there will be no
Frame.Replacement to warn T2 that it's encountering an object that may have been
written by another thread, it will just get a Frame pointer like any other. We
could use a LoadAcquire every time we read a Value from potentially-shared
memory, but that seems likely to cause an unfortunate performance hit.

Our solution is to instead postpone fixing pointers to the replaced Frame until
we're sure that every other thread will be able to read the new Frame correctly
without additional synchronization. Fortunately we already know when that will
be: it's the same point at which it becomes safe to start clearing out the old
Frame. So we mark the old Frame as "now cleared" when we clear it, and the
substep of step 3 becomes

*   If the old Frame has been cleared, and we can update the source of this
    value (from step 1), make it point to the new Frame.

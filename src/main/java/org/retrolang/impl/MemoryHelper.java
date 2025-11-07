/*
 * Copyright 2025 The Retrospect Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.retrolang.impl;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.retrolang.util.SizeOf;

/**
 * A MemoryHelper handles memory allocation and deallocation for a single thread; MemoryHelpers are
 * not thread-safe.
 *
 * <p>Before use a MemoryHelper must be bound to a ResourceTracker; allocations and deallocations
 * will update that tracker's totals.
 *
 * <p>When bound to a non-debug ResourceTracker, MemoryHelper does not notify the ResourceTracker
 * separately of each allocation and release; instead it aggregates the quantities and updates the
 * ResourceTracker only on calls to {@link #reserve} or {@link #checkAvailable}, or when the
 * ResourceTracker is unbound. When bound to a debug ResourceTracker each allocation and
 * deallocation is passed through immediately.
 *
 * <p>The Allocator implementation also includes a very small optimization: the last-released byte[]
 * and Object[] are saved, and if the next allocation needs an array of the same size the released
 * one is re-used. This is just a placeholder for additional optimizations to be added in the
 * future.
 *
 * <p>MemoryHelper's {@link #reserve} method is similar to {@link #checkAvailable}, except that if
 * it succeeds the specified amount of memory has been set aside and cannot be claimed by other
 * threads sharing the ResourceTracker. Subsequent calls to {@link #allocByteArray}, {@link
 * #allocObjectArray}, or {@link #recordAlloc} will automatically use the reserved memory until it
 * has been exhausted, after which it reverts to claiming additional memory from the ResourceTracker
 * as needed.
 */
public class MemoryHelper implements Allocator {

  /**
   * The ResourceTracker to which this MemoryHelper is bound, or null if it is currently unbound.
   */
  private ResourceTracker tracker;

  /** A cache of {@code tracker.debugTracker}, since we check it so frequently. */
  private DebugTracker debugTracker;

  // These are accumulated values not yet transferred to the ResourceTracker.
  private int allocedObjs;
  private long allocedBytes;
  private int releasedObjs;
  private long releasedBytes;

  /**
   * The amount we have reserved with the ResourceTracker. This amount of memory has already been
   * set aside for this thread's use.
   */
  private long reservedBytes;

  final ReleaseVisitor releaseVisitor = new ReleaseVisitor();

  /**
   * Binds this MemoryHelper to the given ResourceTracker; any subsequent allocations or
   * deallocations will update the ResourceTracker's totals. If {@code tracker} is null, leaves the
   * MemoryHelper unbound.
   *
   * <p>If this MemoryHelper was already bound to a ResourceTracker, first unbinds it and returns
   * the previous value; otherwise returns null.
   */
  @CanIgnoreReturnValue
  ResourceTracker bindTo(ResourceTracker tracker) {
    // Don't carry over allocations from any previous tracker.
    ResourceTracker prev = this.tracker;
    if (prev != null) {
      updateTracker(-1);
    }
    // At this point we should have flushed all changes to any previously-bound ResourceTracker,
    // so the new one should get a clean start.
    assert allocedBytes == 0 && allocedObjs == 0 && releasedBytes == 0 && releasedObjs == 0;
    this.tracker = tracker;
    this.debugTracker = (tracker == null) ? null : tracker.debugTracker;
    return prev;
  }

  /**
   * Returns the ResourceTracker to which this MemoryHelper is bound, or null if it is currently
   * unbound.
   */
  ResourceTracker tracker() {
    return tracker;
  }

  private void mustBeBound() {
    if (tracker == null) {
      throw new IllegalStateException();
    }
  }

  /**
   * The minimum change in memory use that's worth reporting to the ResourceTracker; waiting until
   * we've allocated or freed at least this much before reporting it reduces the frequency of thread
   * synchronization.
   */
  private static final long MIN_TRACKER_UPDATE = 4096;

  /** Returns true if this computation has exceeded the available memory. */
  boolean isOverMemoryLimit() {
    long unreservedAlloc = Math.max(allocedBytes - reservedBytes, 0);
    if (Math.abs(unreservedAlloc - releasedBytes) >= MIN_TRACKER_UPDATE || debugTracker != null) {
      return updateTracker(0) < 0;
    } else {
      return false;
    }
  }

  /**
   * Returns the amount of memory still available from the ResourceTracker (negative if the memory
   * limit has been exceeded). Includes memory reserved by this MemoryHelper.
   *
   * <p>Inherently racy, so intended primarily for testing purposes.
   */
  long checkAvailable() {
    mustBeBound();
    return updateTracker(0) + reservedBytes;
  }

  /**
   * Reserves the given number of bytes for upcoming allocations and returns true, or returns false
   * if there is not enough available memory.
   */
  public boolean tryReserve(long size) {
    assert size >= 0;
    mustBeBound();
    return (size + allocedBytes - releasedBytes < reservedBytes + MIN_TRACKER_UPDATE)
        || updateTracker(size) >= 0;
  }

  /**
   * Reserves the given number of bytes for upcoming allocations or throws OUT_OF_MEMORY if there is
   * not enough available memory. Should be called before making large allocations.
   */
  public void reserve(long size) throws Err.BuiltinException {
    Err.OUT_OF_MEMORY.unless(tryReserve(size));
  }

  /**
   * Reserves space for an upcoming array allocation or throws OUT_OF_MEMORY if there is not enough
   * available memory.
   */
  public void reserve(FrameLayout layout, int length) throws Err.BuiltinException {
    if (length != 0) {
      // length < 0 probably means we overflowed an int
      Err.OUT_OF_MEMORY.when(length < 0);
      reserve(layout == null ? CompoundValue.sizeOf(length) : layout.sizeOf(length));
    }
  }

  /**
   * Visible only for {@link TState#staticDropReference}; must be called with an object whose
   * refCount has just been reduced to zero.
   */
  void dropped(RefCounted obj) {
    mustBeBound();
    releaseVisitor.dropped(obj);
  }

  /**
   * Decrements the reference count of the given RefCounted, and releases it if the reference count
   * is now zero. Does nothing if {@code obj} is null or uncounted.
   */
  public void dropReference(@RC.In RefCounted obj) {
    mustBeBound();
    releaseVisitor.visitRefCounted(obj);
  }

  /** Releases the given byte array. Does nothing if {@code bytes} is null. */
  public void dropReference(@RC.In byte[] bytes) {
    mustBeBound();
    releaseVisitor.visitByteArray(bytes);
  }

  /**
   * Releases the given Object array. Drops a reference to each of its non-null elements. Does
   * nothing if {@code objs} is null.
   */
  public void dropReference(@RC.In Object[] objs) {
    mustBeBound();
    releaseVisitor.visitObjArray(objs);
  }

  /**
   * Decrements the reference count of the given Value if it is RefCounted, and releases it if the
   * reference count is now zero. Does nothing if {@code value} is not a RefCounted or is uncounted.
   */
  public void dropValue(@RC.In Value value) {
    mustBeBound();
    if (value instanceof RefCounted rc) {
      releaseVisitor.visitRefCounted(rc);
    }
  }

  /** Releases the given object if it is reference counted. */
  void dropAny(Object obj) {
    mustBeBound();
    releaseVisitor.visit(obj);
  }

  /**
   * Sets the specified element of the given Object[] to null, calling {@link #dropReference} if it
   * is a RefCounted, byte[], or Object[].
   */
  public void clearElement(Object[] array, int index) {
    clearElements(array, index, index + 1);
  }

  /**
   * Sets the specified elements of the given Object[] to null, calling {@link #dropReference} on
   * each RefCounted, byte[], or Object[].
   *
   * @param start index of the first element to be cleared
   * @param end one more than the index of the last element to be cleared
   */
  public void clearElements(Object[] objs, int start, int end) {
    mustBeBound();
    releaseVisitor.clearElements(objs, start, end);
  }

  /**
   * Returns true if the given RefVisitor is a ReleaseVisitor, i.e. we are dropping the objects
   * visited.
   */
  static boolean isReleaser(RefVisitor visitor) {
    return visitor instanceof ReleaseVisitor;
  }

  @Override
  public String toString() {
    if (tracker == null) {
      return "unbound";
    }
    String s =
        String.format(
            "alloced=%s/%s, released=%s/%s (tracker: %s)",
            allocedBytes, allocedObjs, releasedBytes, releasedObjs, tracker);
    if (releaseVisitor.releaseDepth != 0 || releaseVisitor.toRelease != null) {
      s =
          String.format(
              "%s (depth=%s, toRelease=%s)",
              s,
              releaseVisitor.releaseDepth,
              (releaseVisitor.toRelease == null ? null : releaseVisitor.toRelease.size()));
    }
    return s;
  }

  // Implementation of Allocator

  // Placeholder for a real free list allocator.
  // Note that if freeObjs is non-null, its elements are all null.
  private byte[] freeBytes;
  private Object[] freeObjs;

  /**
   * Limits the maximum size of array we'll save for possible reuse. Big arrays are less likely to
   * be rapidly allocated and released, and it costs more to have them lying around unused.
   */
  static final int MAX_FREE_LIST_BYTES = 16384;

  static final int MAX_FREE_LIST_PTRS = MAX_FREE_LIST_BYTES / SizeOf.PTR;

  /** Returns the size in bytes of a byte[] with the given length. */
  private static long simpleByteArraySize(int length) {
    return SizeOf.array(length, SizeOf.BYTE);
  }

  /** Returns the size in bytes of an Object[] with the given length. */
  private static long simpleObjectArraySize(int length) {
    return SizeOf.array(length, SizeOf.PTR);
  }

  /**
   * Returns the number of bytes that will be allocated by a call to {@link #allocByteArray} on this
   * MemoryHelper with its current binding.
   */
  long byteArraySize(int length) {
    mustBeBound();
    return byteArraySize(length, debugTracker != null);
  }

  /**
   * Returns the number of bytes that will be allocated by a call to {@link #allocByteArray} on a
   * MemoryHelper bound to a ResourceTracker of the given type.
   */
  static long byteArraySize(int length, boolean debug) {
    // We don't allocate empty arrays.
    if (length == 0) {
      return 0;
    }
    // In debug mode we allocate exactly the length you ask for, but in non-debug mode we may
    // round the size up.
    return simpleByteArraySize(debug ? length : chooseCapacityBytes(length));
  }

  /**
   * Returns the number of bytes that will be allocated by a call to {@link #allocObjectArray} on
   * this MemoryHelper with its current binding.
   */
  long objectArraySize(int length) {
    mustBeBound();
    return objectArraySize(length, debugTracker != null);
  }

  /**
   * Returns the number of bytes that will be allocated by a call to {@link #allocObjectArray} on a
   * MemoryHelper bound to a ResourceTracker of the given type.
   */
  static long objectArraySize(int length, boolean debug) {
    if (length == 0) {
      return 0;
    }
    return simpleObjectArraySize(debug ? length : chooseCapacityObjects(length));
  }

  @Override
  @RC.Out
  public byte[] allocByteArray(int length) {
    mustBeBound();
    if (length == 0) {
      return EMPTY_BYTES;
    } else if (debugTracker != null) {
      return tracker.allocByteArray(length, this);
    }
    length = chooseCapacityBytes(length);
    byte[] result;
    if (freeBytes != null && freeBytes.length == length) {
      result = freeBytes;
      freeBytes = null;
    } else {
      result = new byte[length];
    }
    allocedObjs++;
    allocedBytes += simpleByteArraySize(length);
    return result;
  }

  @Override
  @RC.Out
  public Object[] allocObjectArray(int length) {
    mustBeBound();
    if (length == 0) {
      return EMPTY_OBJECTS;
    } else if (debugTracker != null) {
      return tracker.allocObjectArray(length, this);
    }
    length = chooseCapacityObjects(length);
    Object[] result;
    if (freeObjs != null && freeObjs.length == length) {
      result = freeObjs;
      freeObjs = null;
    } else {
      result = new Object[length];
    }
    allocedObjs++;
    allocedBytes += simpleObjectArraySize(length);
    return result;
  }

  @Override
  public void recordAlloc(RefCounted obj, long size) {
    assert obj != null && SizeOf.isValidSize(size);
    mustBeBound();
    if (debugTracker != null) {
      tracker.recordAlloc(obj, size, this);
    } else {
      allocedObjs++;
      allocedBytes += size;
    }
  }

  /**
   * Returns the number of reserved bytes that can be used for an allocation of the given size, and
   * updates {#link #reservedBytes} accordingly. Only intended for use by ResourceTracker.
   */
  long useReservation(long size) {
    long result = Math.min(size, reservedBytes);
    reservedBytes -= result;
    return result;
  }

  /**
   * Adjusts the given array size upwards to the next round-ish number.
   *
   * <p>We try to reuse recently-deallocated arrays to reduce GC, but our odds of finding a free
   * array of the desired size are limited if there are too many different sizes. We try to improve
   * our chances by rounding the array size upwards to the nearest int that is (a) a multiple of
   * unit (which must be a power of 2), and (b) of the binary form 1xx0*.
   *
   * <p>For example, if allocating an array with 4-byte elements the minUnit would be 2 (since Java
   * memory allocations are always in 8-byte units), and the allowed sizes are 2, 4, 6, 8, 10, 12,
   * 14, 16, 20, 24, 28, 32, 40, 48, 56, 64, 80, ...; all sizes up to 16K will be mapped to one of
   * 48 allowed sizes (and all sizes up to 1M will be mapped to one of 72 sizes).
   *
   * <p>The maximum overhead (extra storage used due to this policy) is less than 25%. The expected
   * overhead is hard to predict (since it depends on the distribution of requested sizes), but with
   * uniformly distributed sizes over 1 to 1 million the average overhead is a little over 9%.
   */
  private static int chooseCapacity(int size, int unit) {
    assert size > 0 && Integer.bitCount(unit) == 1;
    int maxAdd = Math.max((Integer.highestOneBit(size) >> 2) - 1, unit - 1);
    int result = size + maxAdd;
    if (result < 0) {
      // If we're close enough to MAX_VALUE to cause overflow, just cap the size at the largest
      // multiple of unit that is less than or equal to MAX_VALUE, but never less than size.
      return Math.max(Integer.MAX_VALUE & -unit, size);
    } else {
      return result & ~maxAdd;
    }
  }

  /**
   * Returns the size of byte[] to be used to satisfy a request of the given size. See {@link
   * #chooseCapacity} for details of how the result is chosen.
   */
  static int chooseCapacityBytes(int size) {
    return chooseCapacity(size, 8);
  }

  /**
   * Returns the size of Object[] to be used to satisfy a request of the given size. See {@link
   * #chooseCapacity} for details of how the result is chosen.
   */
  static int chooseCapacityObjects(int size) {
    return chooseCapacity(size, 2);
  }

  /**
   * Returns true if the given byte[] can reasonably be used to satisfy a request of the given size.
   * This must
   *
   * <ul>
   *   <li>return false if {@code bytes.length} is less than {@code size}, and
   *   <li>return true if {@code bytes.length} is what {@link #chooseCapacityBytes} would return for
   *       {@code size}.
   * </ul>
   *
   * <p>May return true if {@code bytes.length} is larger than {@link #chooseCapacityBytes} would
   * return but not too much larger (the current policy is that it should not be more than double
   * {@code size}).
   */
  static boolean isOkForSize(byte[] bytes, int size) {
    // Note that if size is less than 5, chooseCapacity will return 8 and we should never require
    // something smaller than that.  In all other cases chooseCapacity will be less than double the
    // given size.
    // And if bytes.length is 16 it's probably not worth replacing it with something that's only one
    // word shorter.
    return bytes.length >= size && bytes.length <= Math.max(size * 2, 16);
  }

  /**
   * Returns true if the given Object[] can reasonably be used to satisfy a request of the given
   * size. This must
   *
   * <ul>
   *   <li>return false if {@code objs.length} is less than {@code size}, and
   *   <li>return true if {@code objs.length} is what {@link #chooseCapacityObjects} would return
   *       for {@code size}.
   * </ul>
   *
   * <p>May return true if {@code objs.length} is larger than {@link #chooseCapacityObjects} would
   * return but not too much larger (the current policy is that it should not be more than double
   * {@code size}).
   */
  static boolean isOkForSize(Object[] objs, int size) {
    // If objs has only 4 elements it doesn't seem worth replacing it with something that's only one
    // word shorter.
    return objs.length >= size && objs.length <= Math.max(size * 2, 4);
  }

  /** Applies the {@link Value#removeRange} transformation in-place to the given array of Values. */
  void removeRange(
      Object[] values, int size, int keepPrefix, int moveFrom, int moveTo, int moveLen) {
    if (moveLen == 0) {
      clearElements(values, keepPrefix, size);
      return;
    }
    clearElements(values, keepPrefix, moveFrom);
    clearElements(values, moveFrom + moveLen, size);
    if (moveFrom != moveTo) {
      System.arraycopy(values, moveFrom, values, moveTo, moveLen);
      // That will have left behind some values that should be nulled
      if (moveFrom < moveTo) {
        Arrays.fill(values, moveFrom, moveTo, null);
      } else {
        Arrays.fill(values, moveTo + moveLen, moveFrom + moveLen, null);
      }
    }
  }

  @Override
  public void adjustAlloc(RefCounted obj, long sizeDelta) {
    mustBeBound();
    if (debugTracker != null) {
      tracker.adjustAlloc(obj, sizeDelta);
    } else if (sizeDelta < 0) {
      releasedBytes -= sizeDelta;
    } else {
      allocedBytes += sizeDelta;
    }
  }

  /**
   * Propagates our accumulated changes to the ResourceTracker and checks how much memory is still
   * available, optionally also trying to reserve memory for future allocations. If {@code reserve}
   * is negative then any existing reservation is released.
   *
   * <p>Returns the number of available bytes remaining. If the result is non-negative then any
   * requested reservation has been made; otherwise the reservation failed.
   */
  @CanIgnoreReturnValue
  private long updateTracker(long reserve) {
    int allocedObjs = this.allocedObjs;
    long allocedBytes = this.allocedBytes;
    int releasedObjs = this.releasedObjs;
    long releasedBytes = this.releasedBytes;
    this.allocedObjs = 0;
    this.allocedBytes = 0;
    this.releasedObjs = 0;
    this.releasedBytes = 0;
    long unreserve;
    if (reserve < 0) {
      // Release any existing reservation.
      reserve = 0;
      unreserve = reservedBytes;
      reservedBytes = 0;
    } else {
      // Release any existing reservation that we have already consumed.
      unreserve = Math.min(reservedBytes, allocedBytes);
      reservedBytes -= unreserve;
      // Figure out how much we need to increase the remaining reservation.
      reserve = Math.max(reserve - reservedBytes, 0);
    }
    long result =
        tracker.update(allocedObjs, allocedBytes, releasedObjs, releasedBytes, unreserve, reserve);
    if (result >= 0) {
      this.reservedBytes += reserve;
    }
    return result;
  }

  /**
   * An implementation of RefVisitor that decrements the refcount of each RefCounted it visits,
   * releasing it if the refcount is now zero, and releases each byte[] or Object it visits.
   *
   * <p>This is only separated from MemoryHelper in order to keep more things private; these methods
   * and fields could just as easily be on MemoryHelper.
   */
  private class ReleaseVisitor implements RefVisitor {

    /**
     * Tracks how many times we've recursed in {@link #dropNow}, to avoid causing a
     * StackOverflowException.
     */
    private int releaseDepth;

    /**
     * Objects whose call to {@link #dropNow} has been deferred to avoid a stack overflow. Each
     * element of {@code toRelease} is either a RefCounted with refCnt zero or an Object[].
     */
    private List<Object> toRelease;

    /** The maximum recursion depth we allow in {@link #dropNow}. */
    static final int MAX_RELEASE_DEPTH = 48;

    /**
     * The recursion depth at which we will begin to process previously-saved items from {@link
     * #toRelease}. We want this to low enough that we don't immediately hit the stack limit again,
     * but high enough that we're not accumulating objects unnecessarily.
     */
    static final int DRAIN_DEPTH = MAX_RELEASE_DEPTH / 2;

    /**
     * Visiting a RefCounted with a MemoryHelper decrements its refCount; if the refCount is now
     * zero its refs are visited.
     */
    @Override
    public void visitRefCounted(RefCounted obj) {
      if (obj != null && obj.dropRefInternal()) {
        dropped(obj);
      }
    }

    @Override
    public void visitByteArray(byte[] bytes) {
      if (bytes != null && bytes.length != 0) {
        long releasedSize = SizeOf.array(bytes);
        releasedBytes += releasedSize;
        releasedObjs++;
        if (debugTracker != null) {
          debugTracker.recordRelease(bytes, releasedSize);
        } else if (bytes.length < MAX_FREE_LIST_BYTES) {
          freeBytes = bytes;
        }
      }
    }

    @Override
    public void visitObjArray(Object[] objs) {
      if (objs != null && objs.length != 0) {
        dropped(objs);
      }
    }

    void clearElements(Object[] objs, int start, int end) {
      for (int i = start; i < end; i++) {
        Object e = objs[i];
        if (e != null) {
          objs[i] = null;
          visit(e);
        }
      }
    }

    /**
     * Called with a RefCounted whose refCnt has just gone to zero, or an Object[] that has been
     * released. Normally we just call {@link #dropNow}, which visits each counted reference of the
     * object (or element of the array) and then increments our released counters, but that can
     * unbounded recursion and stack overflow, so we count our recursion depth and if it gets
     * alarming we switch to just pushing the to-be-dropped objects onto an ArrayList (still a
     * stack, but one that grow much deeper). Once our execution stack starts to get back to a more
     * reasonable size we start popping objects from the external stack and processing them.
     */
    private void dropped(Object arrayOrRefCounted) {
      if (releaseDepth >= MAX_RELEASE_DEPTH) {
        if (toRelease == null) {
          toRelease = new ArrayList<>();
        }
        toRelease.add(arrayOrRefCounted);
        return;
      }
      dropNow(arrayOrRefCounted);
      if (releaseDepth == 0) {
        assert (toRelease == null) || toRelease.isEmpty();
        // If we used an external stack we can release it now.
        toRelease = null;
        if (debugTracker != null) {
          // Allocations are getting sent to the ResourceTracker immediately; if we don't also send
          // releases promptly we'll end up with erroneous peak byte measurements.
          updateTracker(0);
        }
      } else if (releaseDepth == DRAIN_DEPTH && toRelease != null) {
        // We're far enough back up the stack to work through our backlog.
        while (!toRelease.isEmpty()) {
          dropNow(toRelease.remove(toRelease.size() - 1));
        }
      }
    }

    /**
     * Called with either a RefCounted with refCnt zero or a non-empty Object[]; visits each of the
     * argument's elements and then declares it freed.
     */
    private void dropNow(Object arrayOrRefCounted) {
      int prevDepth = releaseDepth++;
      try {
        long releasedSize;
        if (arrayOrRefCounted instanceof RefCounted rc) {
          // The RefCounted is responsible for enumerating its counted references.
          releasedSize = rc.visitRefs(this);
          assert SizeOf.isValidSize(releasedSize);
        } else {
          // All the elements of an Object[] are considered counted references.
          Object[] array = (Object[]) arrayOrRefCounted;
          clearElements(array, 0, array.length);
          if (array.length < MAX_FREE_LIST_PTRS && debugTracker == null) {
            freeObjs = array;
          }
          releasedSize = SizeOf.array(array);
        }
        releasedBytes += releasedSize;
        releasedObjs++;
        if (debugTracker != null) {
          debugTracker.recordRelease(arrayOrRefCounted, releasedSize);
        }
      } finally {
        assert releaseDepth == prevDepth + 1;
        releaseDepth = prevDepth;
      }
    }
  }
}

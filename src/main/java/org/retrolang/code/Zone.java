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

package org.retrolang.code;

/**
 * Blocks are grouped in Zones to simplify the process of assigning an ordering to them. An outlink
 * from any Block {@code b1} (other than a BackRef) can only go to a Block {@code b2} if {@code b1}
 * is ordered before {@code b2}, i.e. if {@code b1.compare(b2) < 0}, which is only possible if
 * {@code b1} is in the same Zone as {@code b2} or if {@code b1.zone.index < b2.zone.index}.
 *
 * <p>CodeBuilder creates
 *
 * <ul>
 *   <li>an initial Zone that contains Blocks before the first Loop;
 *   <li>for each Loop, a Zone that contains Blocks in that Loop (up to the start of any nested
 *       Loop, which will introduce its own Zone), and a Zone that contains Blocks after the Loop is
 *       completed (up to the start of any following Loop); and
 *   <li>a final Zone that contains all Return blocks.
 * </ul>
 */
public final class Zone {

  /**
   * Adding a link back to the CodeBuilder from each Zone provides a relatively memory-efficient way
   * to access the CodeBuilder from every Block (since there are typically many more Blocks than
   * Zones).
   */
  final CodeBuilder cb;

  /**
   * Null if this Zone is not in any Loop; otherwise the innermost Loop that contains it.
   * Optimization may change a Zone's containingLoop by hoisting it into that loop's parent, or out
   * of a Loop with no parent.
   */
  Loop containingLoop;

  /**
   * This Zone's position in the {@code cb.zones}. Hoisting a Zone out of a Loop may also change its
   * index (and that of other Zones) to maintain consistency.
   */
  int index;

  Zone(CodeBuilder cb, Loop containingLoop, int index) {
    this.cb = cb;
    this.containingLoop = containingLoop;
    this.index = index;
  }

  /**
   * If this Zone is directly contained in {@code from}, updates {@link #containingLoop} to instead
   * be {@code from}'s parent (which may be null). If instead this Zone is contained in a nested
   * loop, promote the loop.
   */
  void promoteFrom(Loop from) {
    assert containingLoop != null;
    if (containingLoop == from) {
      containingLoop = from.nestedIn();
    } else {
      containingLoop.promoteFrom(from);
    }
  }

  @Override
  public String toString() {
    // Zones are not usually printed anywhere, but this makes debugging a little more pleasant.
    String s = "z" + index;
    return (containingLoop == null) ? s : s + "↑" + containingLoop.index;
  }

  /**
   * An Ordered is an object with a zone and an integer order within that zone, which together
   * define an ordering; this ordering is used to ensure that we process blocks in an order that
   * respects linking. Although Block is the primary subclass of Ordered, each Loop also has an
   * Ordered instance to handle processing that should be done before (for forward propagation) or
   * after (for reverse propagation) all of the blocks in the loop.
   *
   * <p>Being Ordered is unrelated to being a LinkTarget (and the Loop processor isn't used as a
   * LinkTarget), but Java doesn't support multiple superclasses and we want Block to inherit from
   * both of them, so we arbitrarily make Ordered a subclass of LinkTarget.
   */
  public abstract static class Ordered extends LinkTarget implements Comparable<Ordered> {
    private Zone zone;
    private int order;

    /** True iff {@code cb().forwardPropQueue.contains(this)}. */
    private boolean isForwardQueued;

    /** True iff {@code cb().reversePropQueue.contains(this)}. */
    private boolean isReverseQueued;

    /**
     * Does one step of forward propagation. If {@code incremental} is false or the Ordered's state
     * has changed since the previous call, should queue all of the Ordered's outlink target for
     * forward propagation before returning.
     */
    abstract void runForwardProp(boolean incremental);

    /**
     * Does one step of reverse propagation. If {@code incremental} is false or the Ordered's state
     * has changed since the previous call, should queue all of the Ordered's inlink origins for
     * reverse propagation before returning.
     */
    abstract void runReverseProp(boolean incremental);

    @Override
    public int compareTo(Ordered other) {
      return compareTo(other.zone, other.order);
    }

    int compareTo(Zone otherZone, int otherOrder) {
      if (zone != otherZone) {
        return Integer.compare(zone.index, otherZone.index);
      } else {
        return Integer.compare(order, otherOrder);
      }
    }

    /** A convenient way to reach the CodeBuilder from any Ordered instance. */
    public final CodeBuilder cb() {
      return zone.cb;
    }

    final Zone zone() {
      return zone;
    }

    final int order() {
      return order;
    }

    void setZone(Zone zone) {
      // Changing the ordering of blocks that are already queued is likely to break the
      // PriorityQueue's invariant.
      assert !isForwardQueued && !isReverseQueued;
      this.zone = zone;
    }

    void setOrder(int order) {
      // Changing the ordering of blocks that are already queued is likely to break the
      // PriorityQueue's invariant.
      assert !isForwardQueued && !isReverseQueued;
      this.order = order;
    }

    /** Sets or clears the {@link #isForwardQueued} flag, and returns its prior state. */
    boolean setIsForwardQueued(boolean isQueued) {
      boolean result = this.isForwardQueued;
      this.isForwardQueued = isQueued;
      return result;
    }

    /** Sets or clears the {@link #isReverseQueued} flag, and returns its prior state. */
    boolean setIsReverseQueued(boolean isQueued) {
      boolean result = this.isReverseQueued;
      this.isReverseQueued = isQueued;
      return result;
    }
  }
}

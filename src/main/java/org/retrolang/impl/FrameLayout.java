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

import org.retrolang.code.CodeValue;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.Evolver.Evolution;
import org.retrolang.util.StringUtil;
import com.google.errorprone.annotations.Keep;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import org.jspecify.annotations.Nullable;

/**
 * A FrameLayout determines the interpretation of the fields in an instance of Frame. It has two
 * subclasses: RecordLayout and VArrayLayout.
 *
 * <p>A FrameLayout may also be used as the ValueInfo for a pointer-valued register, indicating that
 * it points to a Frame with this layout.
 */
public abstract class FrameLayout extends Frame.LayoutOrReplacement implements PtrInfo {
  /**
   * The Scope that this FrameLayout belongs to. All computations that use this layout must share
   * this Scope.
   */
  final Scope scope;

  /** The FrameClass that will be used to allocate Frames using this layout. */
  final FrameClass frameClass;

  /** The number of Object fields that will be used in each Frame. */
  final int nPtrs;

  /** A RefVar template with this FrameLayout. */
  final Template.RefVar asRefVar;

  /**
   * If non-null, this FrameLayout has evolved; the updated FrameLayout should be preferred.
   *
   * <p>Can only be changed from null to non-null by the Evolver, while {@code scope.evolver} is
   * locked. Once non-null can be swapped (to track further evolutions) without locking (details in
   * {@link #evolution()}). Always read and written with getAcquire/setRelease (or
   * compareAndExchange, which is strongly stronger) to ensure cross-thread sanity.
   */
  @Keep private Evolution evolution;

  private static final VarHandle EVOLUTION =
      Handle.forVar(MethodHandles.lookup(), FrameLayout.class, "evolution", Evolution.class);

  /**
   * For the Evolver's private use during evolution operations; only read or written while {@code
   * scope.evolver} is locked. Null if the {@code scope.evolver} is not locked.
   */
  private Evolver.Pending pending;

  /**
   * A ValueInfo that indicates that the register points to a Frame with this layout and with
   * refCount=1.
   */
  final ValueInfo notSharedInfo;

  FrameLayout(Scope scope, BaseType baseType, FrameClass frameClass, int nPtrs) {
    assert nPtrs >= 0 && nPtrs <= frameClass.nPtrs;
    this.scope = scope;
    this.frameClass = frameClass;
    this.nPtrs = nPtrs;
    this.asRefVar = new Template.RefVar(0, baseType, this, true);
    this.notSharedInfo = new PtrInfo.IsNotShared(this);
  }

  @Override
  public final BaseType baseType() {
    // This relies on RefVar.baseType *not* being updated if the layout evolves!
    return asRefVar.baseType;
  }

  /**
   * Returns a FrameLayout that can store all values that can be stored by this FrameLayout as well
   * as the given value. If the result is not identical to this, this layout will have been evolved
   * to the result.
   *
   * <p>Requires that {@code v.baseType().sortOrder == this.baseType().sortOrder}.
   */
  final FrameLayout addValue(Value v) {
    assert v.baseType().sortOrder == baseType().sortOrder;
    if (v instanceof Frame) {
      FrameLayout vLayout = v.layout();
      assert vLayout != null;
      FrameLayout layout = latest();
      return (vLayout == layout) ? layout : scope.evolver.merge(layout, vLayout);
    } else {
      return addValueImpl(v);
    }
  }

  /**
   * Returns the template for this Value (for RecordLayouts) or for each element of this Value (for
   * VArrayLayouts).
   */
  abstract Template template();

  /** Returns the template for the specified element of this Value. */
  abstract Template elementTemplate(int index);

  /** Implements {@link #addValue} when {@code v} is not a Frame. */
  abstract FrameLayout addValueImpl(Value v);

  /**
   * Returns a value greater than the index of any NumVar in {@link #template} (0 if there are no
   * NumVars).
   */
  abstract int numVarIndexLimit();

  /**
   * Returns a value greater than the index of any RefVar in {@link #template}, (0 if there are no
   * RefVars).
   */
  abstract int refVarIndexLimit();

  /**
   * Returns a FrameLayout that can store all values that can be stored by this FrameLayout or are
   * included in the given TemplateBuilder. The result may or may not be identical to this.
   *
   * <p>Requires that {@code compound.baseType.sortOrder == this.baseType().sortOrder}.
   */
  abstract FrameLayout merge(TemplateBuilder.CompoundBase compound);

  /** Evolves this layout so that the specified element can store the given value. */
  abstract FrameLayout evolveElement(int index, Value newElement);

  /**
   * Evolves this layout so that elements from start (inclusive) to end (exclusive) can store the
   * given value.
   */
  public abstract FrameLayout evolveElements(int start, int end, Value newElement);

  /**
   * If this FrameLayout has been evolved, returns the FrameLayout to use in its place; otherwise
   * returns this.
   */
  FrameLayout latest() {
    Evolution ev = evolution();
    return (ev == null) ? this : ev.newLayout;
  }

  boolean hasEvolved() {
    // Since we're only checking for null/non-null we can use a (potentially cheaper) getOpaque()
    // instead of getAcquire().
    return EVOLUTION.getOpaque(this) != null;
  }

  /** If this FrameLayout has been evolved, returns the latest Evolution; otherwise returns null. */
  Evolution evolution() {
    // Use getAcquire since this pointer may have been written asynchronously by another thread
    Evolution current = (Evolution) EVOLUTION.getAcquire(this);
    if (current == null) {
      return null;
    }
    // We might have to follow more than one evolution pointer to get to the current FrameLayout
    Evolution last = current;
    for (; ; ) {
      Evolution next = (Evolution) EVOLUTION.getAcquire(last.newLayout);
      if (next != null) {
        last = next;
      } else if (last == current) {
        return last;
      } else {
        // If we had to follow more than one pointer, create a new Evolution that does it in
        // one step.
        Evolution combined = new Evolution(this, last.newLayout);
        Evolution prev = (Evolution) EVOLUTION.compareAndExchange(this, current, combined);
        if (prev == current) {
          return combined;
        }
        // If the compareAndExchange fails we raced against someone else also updating it; just
        // start over.  With any luck we can just use the one they stored.
        current = prev;
        last = current;
      }
    }
  }

  /**
   * Update this FrameLayout's {@link #pending} field. Only called from the Evolver while the
   * Evolver is locked.
   */
  void setPending(Evolver.Pending pending, Evolver.Pending prev) {
    assert this.pending == prev && this.evolution == null;
    assert Thread.holdsLock(scope.evolver);
    this.pending = pending;
  }

  /**
   * Set this FrameLayout's {@link #evolution} field, which should be null. Only called from the
   * Evolver while the Evolver is locked.
   */
  void setEvolution(FrameLayout newLayout) {
    assert this.evolution == null && newLayout != this && newLayout.evolution == null;
    assert Thread.holdsLock(scope.evolver);
    EVOLUTION.setRelease(this, new Evolution(this, newLayout));
  }

  /**
   * Return this FrameLayout's {@link #pending} field. Only called from the Evolver while the
   * Evolver is locked.
   */
  Evolver.Pending pending() {
    return pending;
  }

  /**
   * Returns a Value equal to {@code value} and suitable for storing in a RefVar with this layout.
   * May evolve this layout if necessary to include the given value. If {@code value} is already a
   * frame with this layout just increments its reference count.
   *
   * <p>Requires that {@code value.baseType().sortOrder == this.baseType().sortOrder}.
   */
  @RC.Out
  Frame cast(TState tstate, Value value) {
    assert value.baseType().sortOrder == baseType().sortOrder;
    if (value instanceof Frame f) {
      FrameLayout fLayout = f.layout();
      if (fLayout.latest() != this) {
        scope.evolver.merge(this, fLayout);
        // TODO(mdixon): update f to this new layout
      }
      f.addRef();
      return f;
    }
    Frame result = tryCopyOf(tstate, value);
    if (result == null) {
      FrameLayout layout = addValueImpl(value);
      result = layout.tryCopyOf(tstate, value);
      assert result != null;
    }
    return result;
  }

  /**
   * Tries to allocate a new instance of this layout equal to {@code value}. Returns null if this
   * layout cannot represent {@code value}.
   */
  @RC.Out
  private @Nullable Frame tryCopyOf(TState tstate, Value value) {
    int n = value.numElements();
    if (this instanceof RecordLayout && n != baseType().size()) {
      return null;
    }
    Frame result = alloc(tstate, n);
    for (int i = 0; i < n; i++) {
      if (!setElement(tstate, result, i, value.peekElement(i))) {
        tstate.dropReference(result);
        return null;
      }
    }
    return result;
  }

  /**
   * Returns the number of bytes of memory required for a Frame with this layout and the specified
   * number of elements.
   */
  abstract long sizeOf(int numElements);

  /**
   * Allocates a new Frame using this FrameLayout.
   *
   * <p>If this is a RecordLayout {@code numElements} is ignored.
   */
  @RC.Out
  public abstract Frame alloc(TState tstate, int numElements);

  /**
   * Allocates a new Frame using this FrameLayout and copies the contents of the given Frame (which
   * must use this layout) into it.
   */
  @RC.Out
  abstract Frame duplicate(TState tstate, Frame f);

  /** Returns the amount of memory that would be used by a call to {@link #duplicate}. */
  abstract long bytesForDuplicate(Frame f);

  /** Implements {@link Value#numElements} for a Frame that uses this FrameLayout. */
  abstract int numElements(Frame f);

  /** Implements {@link Value#element} for a Frame that uses this FrameLayout. */
  @RC.Out
  abstract Value element(Frame f, int i);

  /** Implements {@link Value#peekElement} for a Frame that uses this FrameLayout. */
  abstract Value peekElement(Frame f, int i);

  /** Nulls any pointers associated with the specified element. */
  abstract void clearElement(TState tstate, Frame f, int index);

  /** Emits blocks to clear any pointers associated with the specified element. */
  abstract void emitClearElement(CodeGen codeGen, CodeValue f, CodeValue index);

  /**
   * Sets the specified element, which must have previously been cleared.
   *
   * <p>Returns true if successful, false if the value cannot be stored. If the returned value is
   * false the element remains cleared.
   */
  abstract boolean setElement(TState tstate, Frame f, int index, Value newElement);

  /**
   * Emits blocks to set the specified element, which must have previously been cleared. Vars in
   * newElement are interpreted as registers.
   */
  abstract void emitSetElement(CodeGen codeGen, CodeValue f, CodeValue index, Template newElement);

  /**
   * Sets elements from start (inclusive) to end (exclusive), which must have previously been
   * cleared.
   *
   * <p>Returns true if successful, false if the value cannot be stored. If the returned value is
   * false the elements remain cleared.
   */
  public abstract boolean setElements(TState tstate, Frame f, int start, int end, Value newElement);

  /** Returns a CopyEmitter who source is the specified element of a frame with this layout. */
  abstract CopyEmitter copyFrom(Register frame, int index);

  /**
   * The default implementation of {@link Evolver.FrameReplacer}. Only used for RecordLayouts
   * (although it would work for VArrayLayouts those are instead handled by the optimized
   * implementation in VArrayReplacer).
   */
  @RC.Out
  Frame simpleReplace(TState tstate, Frame src, FrameLayout newLayout) {
    int numElements = src.numElements();
    Frame dst = newLayout.alloc(tstate, numElements);
    for (int i = 0; i < numElements; i++) {
      // Note that setElement() is responsible for incrementing the refCount of any references it
      // stores.
      boolean ok = newLayout.setElement(tstate, dst, i, peekElement(src, i));
      // Since the newLayout is an evolution of this layout, it should be impossible for that
      // setElement() to fail.
      assert ok;
    }
    return dst;
  }

  /**
   * Returns a Frame representing an array of length {@code numElements}, with all elements equal to
   * {@code initialValue}. If {@code initialLayout} is non-null, the frame will use that layout
   * (evolving it if necessary); otherwise it will create a new VArrayLayout.
   */
  @RC.Out
  public static Frame newArray(
      TState tstate, FrameLayout initialLayout, int numElements, Value initialValue)
      throws BuiltinException {
    assert initialLayout == null || initialLayout.baseType().isArray();
    assert numElements > 0;
    FrameLayout layout = initialLayout;
    if (initialLayout == null) {
      TemplateBuilder element = TemplateBuilder.newBuilder(initialValue);
      layout = VArrayLayout.newFromBuilder(tstate.scope(), element);
    } else if (initialLayout instanceof RecordLayout rLayout
        && rLayout.template.baseType.size() != numElements) {
      // initialLayout is a RecordLayout of the wrong length
      TemplateBuilder element = TemplateBuilder.newBuilder(initialValue);
      layout = rLayout.evolveToVArray(element);
    }
    // If we try with the initialLayout and setElements() fails (i.e. initialLayout can't represent
    // initialValue) then we'll expand the layout and try again.
    // We should never do this loop more than twice.
    for (; ; ) {
      tstate.reserve(layout, numElements);
      Frame f = layout.alloc(tstate, numElements);
      if (initialValue == Core.TO_BE_SET
          || layout.setElements(tstate, f, 0, numElements, initialValue)) {
        return f;
      }
      // This layout can't hold the initial value; that should only be possible if we're still using
      // the original layout.
      assert layout == initialLayout;
      tstate.dropReference(f);
      layout = layout.evolveElements(0, numElements, initialValue);
      assert layout != initialLayout;
    }
  }

  private static final Op ENSURE_UNSHARED_OP =
      Handle.opForMethod(
              FrameLayout.class, "ensureUnshared", TState.class, Frame.class, boolean.class)
          .withOpSimplifier(
              (args, registerInfos) -> {
                // If we already know that the frame is unshared we can simplify away this call.
                // args are (FrameLayout, TState, Frame, boolean)
                CodeValue frameArg = args.get(2);
                if (frameArg instanceof Register r) {
                  if (PtrInfo.isUnshared(registerInfos.apply(r.index))) {
                    return frameArg;
                  }
                }
                return null;
              })
          .build();

  /**
   * If {@code f} is unshared, returns {@code f}. Otherwise, if {@code checkFirst} is true and
   * either there is insufficient memory to duplicate {@code f} or this layout has evolved, returns
   * null. Otherwise, returns a duplicate of {@code f}.
   */
  @RC.Out
  Frame ensureUnshared(TState tstate, @RC.In Frame f, boolean checkFirst) {
    assert f.layout() == this;
    if (f.isNotShared()) {
      return f;
    } else if (checkFirst && (hasEvolved() || !tstate.tryReserve(bytesForDuplicate(f)))) {
      return null;
    }
    Frame result = duplicate(tstate, f);
    tstate.dropReference(f);
    return result;
  }

  /**
   * If {@code f} is known to be unshared, returns it; otherwise returns the result of a call to
   * {@link #ensureUnshared}. Escapes if {@code checkFirst} is true and the call returns null.
   */
  Register emitEnsureUnshared(CodeGen codeGen, CodeValue f, boolean checkFirst) {
    if (f instanceof Register r && PtrInfo.isUnshared(codeGen.cb.nextInfoResolved(r.index))) {
      return r;
    }
    Register result = codeGen.cb.newRegister(Frame.class);
    CodeValue rhs =
        ENSURE_UNSHARED_OP.resultWithInfo(
            notSharedInfo,
            CodeValue.of(this),
            codeGen.tstateRegister(),
            f,
            CodeValue.of(checkFirst));
    codeGen.emitSet(result, rhs);
    if (checkFirst) {
      codeGen.escapeUnless(Condition.isNull(result).not());
    }
    return result;
  }

  /** Implements {@link Value#replaceElement} for a Frame that uses this FrameLayout. */
  @RC.Out
  Value replaceElement(TState tstate, @RC.In Frame f, int index, @RC.In Value newElement) {
    if (!f.isNotShared()) {
      Frame f2 = duplicate(tstate, f);
      tstate.dropReference(f);
      f = f2;
    }
    clearElement(tstate, f, index);
    if (setElement(tstate, f, index, newElement)) {
      tstate.dropValue(newElement);
      return f;
    }
    FrameLayout newLayout = evolveElement(index, newElement);
    assert newLayout != this;
    int size = numElements(f);
    Frame f2 = newLayout.alloc(tstate, size);
    for (int i = 0; i < size; i++) {
      Value element = (i == index) ? newElement : peekElement(f, i);
      boolean success = newLayout.setElement(tstate, f2, i, element);
      assert success;
    }
    tstate.dropValue(newElement);
    tstate.dropReference(f);
    return f2;
  }

  /**
   * Returns a CodeValue representing the result of a call to {@link #replaceElement} on a frame
   * with this layout.
   */
  CodeValue emitReplaceElement(CodeGen codeGen, CodeValue f, CodeValue index, Value newElement) {
    // TODO: should we sometimes be checking here?
    f = emitEnsureUnshared(codeGen, f, false);
    emitClearElement(codeGen, f, index);
    if (newElement != Core.TO_BE_SET) {
      emitSetElement(codeGen, f, index, RValue.toTemplate(newElement));
    }
    return f;
  }

  /** Implements {@link Value#removeRange} for a Frame that uses this FrameLayout. */
  @RC.Out
  abstract Value removeRange(
      TState tstate, @RC.In Frame f, int keepPrefix, int moveFrom, int moveTo, int moveLen);

  /** Implements {@link Value#reserveForChangeOrThrow} for a Frame that uses this FrameLayout. */
  abstract void reserveForChangeOrThrow(TState tstate, Frame f, int newSize, boolean isShared)
      throws Err.BuiltinException;

  // ValueInfo methods

  @Override
  public boolean containsValue(Object value) {
    return value != null && ((Value) value).layout() == this;
  }

  @Override
  public ValueInfo unionConst(CodeValue.Const constInfo) {
    return containsValue(constInfo.value) ? this : ValueInfo.ANY;
  }

  /** Implements RefCounted.visitRefs() for a Frame that uses this FrameLayout. */
  @Override
  final long visitRefs(Frame f, RefVisitor visitor) {
    // TODO(mdixon): if visitor is a releaser, add f to a freeList on TState *unless* we've been
    // called from Frame.Replacement.clearOriginal()
    return frameClass.visitRefs(f, visitor, nPtrs);
  }

  /** Implements Object.equals() for a Frame that uses this FrameLayout. */
  abstract boolean equals(Frame f, Value other);

  /** Implements {@link Object#toString} for a Frame that uses this FrameLayout. */
  abstract String toString(Frame f);

  @Override
  public final String toString() {
    return toStringPrefix() + template() + "@" + StringUtil.id(this);
  }

  /** Renders this layout's template using the given Template.Printer. */
  final String toString(Template.Printer printer) {
    return toStringPrefix() + template().toBuilder().toString(printer);
  }

  /** A more compact {@code toString()}. */
  final String toStringNoTemplate() {
    return toStringPrefix() + "@" + StringUtil.id(this);
  }

  /** Returns {@code "*"} (for RecordLayouts) or {@code "*[]"} (for VArrayLayouts). */
  private String toStringPrefix() {
    return (this instanceof RecordLayout) ? "*" : "*[]";
  }
}

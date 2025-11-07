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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.retrolang.code.CodeBuilder;
import org.retrolang.code.CodeValue;
import org.retrolang.code.FutureBlock;
import org.retrolang.code.Op;
import org.retrolang.code.Register;
import org.retrolang.code.ReturnBlock;
import org.retrolang.code.SetBlock;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;
import org.retrolang.impl.Template.NumVar;
import org.retrolang.impl.TemplateBuilder.CompoundBase;
import org.retrolang.util.ArrayUtil;
import org.retrolang.util.Bits;
import org.retrolang.util.SizeOf;
import org.retrolang.util.StringUtil;

/**
 * Frames using this FrameLayout represent a Retrospect array using parallel Java arrays. Each
 * element of the Retrospect array is described by the template, with variable values supplied by
 * corresponding elements of the Java arrays.
 */
class VArrayLayout extends FrameLayout {
  final Template template;

  /** Maps the index of each NumVar in {@link #template} to its NumEncoding. */
  private final NumEncoding[] nvEncodings;

  /**
   * The length of any pointer overflow array required, or zero if this layout's element arrays fit
   * in the chosen FrameClass.
   */
  final int overflowSize;

  /** A zero-length, immutable instance of this VArrayLayout. */
  final Frame empty;

  /**
   * The number of bytes required for an instance of this VArrayLayout, not counting the elements.
   */
  final long baseSize;

  /**
   * The number of additional bytes required for each element by an instance of this VArrayLayout.
   */
  final long perElementSize;

  /** One more than the maximum RefVar index in this layout's template. */
  final int numRV;

  private final Choice numberChoice;

  /**
   * A {@link Frame.Replacement} that points to {@link #empty}; returned by {@link
   * Frame#getReplacement} for the empty instances of VArrayLayouts that have evolved to this.
   */
  final Frame.Replacement emptyReplacement;

  /**
   * An Op with arguments (TState, Frame, int start, int end) that clears all pointers associated
   * with elements between {@code start} (inclusive) and {@code end} (exclusive). Built lazily, and
   * only for layouts with at least one RefVar.
   */
  private Op clearElementsOp;

  private static final VarHandle CLEAR_ELEMENTS_OP =
      Handle.forVar(MethodHandles.lookup(), VArrayLayout.class, "clearElementsOp", Op.class);

  /** Builds a template with the given builder, and creates a VArrayLayout using the result. */
  static VArrayLayout newFromBuilder(Scope scope, TemplateBuilder builder) {
    VarAllocator allocator = new VarAllocator();
    Template template = builder.build(allocator);
    VArrayLayout result = newWithAllocator(scope, template, allocator);
    scope.evolver.recordNewLayout(result);
    return result;
  }

  /**
   * Creates a VArrayLayout from a template and the allocator that was used to build it.
   *
   * <p>Should only be called directly by the Evolver; other callers should use {@link
   * #newFromBuilder}.
   */
  static VArrayLayout newWithAllocator(Scope scope, Template template, VarAllocator alloc) {
    int ptrSize = alloc.ptrSize();
    FrameClass frameClass = FrameClass.select(NUM_INTS, ptrSize);
    return new VArrayLayout(scope, frameClass, ptrSize, template, alloc.shared.nvEncodings());
  }

  /** Each varray Frame has a single int field, its length. */
  private static final int NUM_INTS = 1;

  /** The index of the length field (an int). */
  private static final int LENGTH_FIELD = 0;

  /** The byte offset of the length field (an int). */
  private static final int LENGTH_FIELD_OFFSET = 0;

  /** The index of the overflow field (a ptr). */
  private static final int OVERFLOW_FIELD = 0;

  private VArrayLayout(
      Scope scope,
      FrameClass frameClass,
      int ptrSize,
      Template template,
      NumEncoding[] nvEncodings) {
    super(scope, Core.VARRAY, frameClass, Math.min(ptrSize, frameClass.nPtrs));
    this.template = template;
    this.nvEncodings = nvEncodings;
    // Since ptrSize includes one array for each NumVar and one array for each RefVar,
    // (ptrSize - nvEncodings.length) is the number of RefVars.
    numRV = ptrSize - nvEncodings.length;
    overflowSize = (ptrSize <= frameClass.nPtrs) ? 0 : (ptrSize - (frameClass.nPtrs - 1));
    empty = frameClass.alloc(Allocator.UNCOUNTED);
    // Initialize the layout and length of our empty instance.  We can leave all the element arrays
    // null, since they'll never be accessed.
    empty.layoutOrReplacement = this;
    frameClass.setI(empty, LENGTH_FIELD_OFFSET, 0);
    emptyReplacement = new Frame.Replacement(null, empty);
    baseSize =
        frameClass.byteSize
            + ptrSize * SizeOf.ARRAY_HEADER
            + (overflowSize == 0 ? 0 : SizeOf.array(overflowSize, SizeOf.PTR));
    long perElementSize = numRV * (long) SizeOf.PTR;
    for (NumEncoding nvEncoding : nvEncodings) {
      perElementSize += nvEncoding.nBytes;
    }
    this.perElementSize = perElementSize;
    this.numberChoice = Choice.forBaseType(Core.NUMBER, template);
  }

  @Override
  Template template() {
    return template;
  }

  @Override
  Template elementTemplate(int index) {
    return template;
  }

  @Override
  int numVarIndexLimit() {
    return nvEncodings.length;
  }

  @Override
  int refVarIndexLimit() {
    return numRV;
  }

  @Override
  FrameLayout addValueImpl(Value v) {
    return evolveTemplate(template.toBuilder().addElements(v));
  }

  @Override
  FrameLayout merge(CompoundBase compound) {
    return evolveTemplate(compound.mergeElementsInto(template.toBuilder()));
  }

  @Override
  FrameLayout evolveElement(int index, Value newElement) {
    return evolveTemplate(template.toBuilder().add(newElement));
  }

  @Override
  public FrameLayout evolveElements(int start, int end, Value newElement) {
    return (start < end) ? evolveElement(start, newElement) : this;
  }

  private FrameLayout evolveTemplate(TemplateBuilder newTemplate) {
    return (newTemplate == template) ? this : scope.evolver.evolve(this, newTemplate, true);
  }

  @Override
  long sizeOf(int numElements) {
    // This may overestimate a bit due to different allocation policies for different element sizes,
    // e.g. if numElements is 3 we'll allocate 8-element arrays for uint8 NumVars, but only
    // 4-element arrays for RefVars and int32 NumVars and 3-element arrays for float64 NumVars.
    // Since this is only used to make reservations I'm OK with small overestimates, but if we
    // really cared it wouldn't be hard to store a couple more sizes in the constructor and make
    // this slightly more complicated.
    return baseSize + perElementSize * TState.chooseCapacityBytes(numElements);
  }

  /**
   * Given a RefVar or NumVar in {@link #template}, returns the index used to identify the
   * corresponding Java array. NumVars will return indices between 0 (inclusive) and {@link
   * #numVarIndexLimit} (exclusive); RefVars will return indices between {@link #numVarIndexLimit}
   * (inclusive) and {@link #numVarIndexLimit}+{@link #numRV} (exclusive).
   */
  int varIndex(Object v) {
    return (v instanceof Template.NumVar nv)
        ? nv.index
        : ((Template.RefVar) v).index + numVarIndexLimit();
  }

  /**
   * Allocates a byte[] or Object[] of the appropriate size for the specified variable; see {@link
   * #varIndex} for the interpretation of {@code index}.
   */
  private Object allocElementArray(TState tstate, int index, int size) {
    if (index < numVarIndexLimit()) {
      return tstate.allocByteArray(size << nvEncodings[index].nBytesLog2);
    } else {
      return tstate.allocObjectArray(size);
    }
  }

  /**
   * Returns the index of the pointer field containing the first element array, i.e. 0 if there is
   * no overflow array, 1 if there is.
   */
  private int firstElementArray() {
    return (overflowSize == 0) ? 0 : 1;
  }

  @Override
  @RC.Out
  public Frame alloc(TState tstate, int size) {
    return alloc(tstate, size, null);
  }

  /**
   * Allocates a new Frame using this layout. If {@code omitElementArrays} is non-null, element
   * arrays with indices in that set will not be allocated and must be set by calls to {@link
   * #setElementArray}.
   */
  @RC.Out
  Frame alloc(TState tstate, int size, Bits omitElementArrays) {
    if (size == 0) {
      return empty;
    }
    Frame result = frameClass.alloc(tstate);
    result.layoutOrReplacement = this;
    frameClass.setI(result, LENGTH_FIELD_OFFSET, size);
    // Each pointer field (except the first, if we're using an overflow array) contains an
    // element array.
    int start = firstElementArray();
    for (int i = start; i < nPtrs; i++) {
      if (omitElementArrays == null || !omitElementArrays.test(i - start)) {
        frameClass.setX(result, i, allocElementArray(tstate, i - start, size));
      }
    }
    if (overflowSize != 0) {
      Object[] overflow = tstate.allocObjectArray(overflowSize);
      frameClass.setX(result, OVERFLOW_FIELD, overflow);
      for (int i = 0; i < overflowSize; i++) {
        if (omitElementArrays == null || !omitElementArrays.test(i + nPtrs - 1)) {
          overflow[i] = allocElementArray(tstate, i + nPtrs - 1, size);
        }
      }
    }
    return result;
  }

  /**
   * Returns the byte[] or Object[] for the specified variable; see {@link #varIndex} for the
   * interpretation of {@code index}.
   */
  Object getElementArray(Frame f, int index) {
    // Skip over the overflow array, if present
    index += firstElementArray();
    if (index < nPtrs) {
      return frameClass.getX(f, index);
    } else {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      return overflow[index - nPtrs];
    }
  }

  byte[] getElementArray(Frame f, Template.NumVar var) {
    assert nvEncodings[var.index] == var.encoding;
    return (byte[]) getElementArray(f, var.index);
  }

  Object[] getElementArray(Frame f, Template.RefVar var) {
    return (Object[]) getElementArray(f, var.index + numVarIndexLimit());
  }

  private CodeValue getElementArray(CodeValue frame, Template t) {
    int index = firstElementArray() + varIndex(t);
    if (index < nPtrs) {
      return frameClass.getPtrField.get(index).result(frame);
    } else {
      CodeValue overflow = frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame);
      return Op.OBJ_ARRAY_ELEMENT.result(overflow, CodeValue.of(index - nPtrs));
    }
  }

  /**
   * Sets the element array used for the specified variable; see {@link #varIndex} for the
   * interpretation of {@code index}.
   *
   * <p>Should only be used to complete the initialization of frames allocated by {@link
   * #alloc(TState, int, Bits)} with a non-empty {@code omitElementArrays}.
   */
  void setElementArray(Frame f, int index, Object array) {
    assert (index < numVarIndexLimit())
        ? ((byte[]) array).length >= numElements(f) * nvEncodings[index].nBytes
        : ((Object[]) array).length >= numElements(f);
    index += firstElementArray();
    if (index < nPtrs) {
      assert frameClass.getX(f, index) == null;
      frameClass.setX(f, index, array);
    } else {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      assert overflow[index - nPtrs] == null;
      overflow[index - nPtrs] = array;
    }
  }

  /**
   * Clears the given frame's element array for the specified variable; see {@link #varIndex} for
   * the interpretation of {@code index}.
   *
   * <p>Used when a replacement frame is sharing this element array and will take responsibility for
   * releasing it.
   */
  void clearElementArrays(Frame f, int[] indices) {
    for (int index : indices) {
      index += firstElementArray();
      if (index < nPtrs) {
        frameClass.setX(f, index, null);
      } else {
        Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
        overflow[index - nPtrs] = null;
      }
    }
  }

  /**
   * A record describing how the elements of a VArray are to be copied to a new VArray or moved in
   * place.
   *
   * <p>Elements [0, keepPrefix) will be preserved in their current positions. Elements [moveFrom,
   * moveFrom+moveLen) will be moved to [moveTo, moveTo+moveLen). Any other elements will be
   * removed.
   */
  private static class ElementMove {
    final int originalSize;
    final int keepPrefix;
    final int moveFrom;
    final int moveTo;
    final int moveLen;

    ElementMove(int originalSize, int keepPrefix, int moveFrom, int moveTo, int moveLen) {
      assert keepPrefix >= 0
          && moveLen >= 0
          && moveFrom >= keepPrefix
          && moveTo >= keepPrefix
          && moveFrom + moveLen <= originalSize;
      this.originalSize = originalSize;
      if (moveFrom == 0 && moveTo == 0) {
        // Convert to an equivalent but slightly more efficient form.
        this.keepPrefix = moveLen;
        this.moveFrom = moveLen;
        this.moveTo = moveLen;
        this.moveLen = 0;
      } else {
        this.keepPrefix = keepPrefix;
        this.moveFrom = moveFrom;
        this.moveTo = moveTo;
        this.moveLen = moveLen;
      }
    }

    /**
     * Returns an ElementMove appropriate for moving the elements from start (inclusive) to end
     * (exclusive) to the start of the array and discarding everything else.
     */
    static ElementMove forRange(int originalSize, int start, int end) {
      return new ElementMove(originalSize, 0, start, 0, end - start);
    }

    /** Returns the size of the resulting VArray. */
    int newSize() {
      return moveTo + moveLen;
    }

    /** Copies the corresponding elements of a byte array. */
    void copyBytes(byte[] src, byte[] dst, int bytesPerElementLog2) {
      System.arraycopy(src, 0, dst, 0, keepPrefix << bytesPerElementLog2);
      System.arraycopy(
          src,
          moveFrom << bytesPerElementLog2,
          dst,
          moveTo << bytesPerElementLog2,
          moveLen << bytesPerElementLog2);
    }

    /**
     * Copies the corresponding elements of a Value array.
     *
     * <p>If {@code destructive} is true, the copied values will be nulled in the source array (so
     * the reference counts are unchanged). If {@code destructive} is false, the source array is
     * unchanged and each copied value's reference count will be incremented.
     */
    void copyValues(Object[] src, Object[] dst, boolean destructive) {
      if (keepPrefix != 0) {
        copyValues(src, 0, dst, 0, keepPrefix, destructive);
      }
      if (moveLen != 0) {
        copyValues(src, moveFrom, dst, moveTo, moveLen, destructive);
      }
    }

    private static void copyValues(
        Object[] src, int srcStart, Object[] dst, int dstStart, int length, boolean destructive) {
      System.arraycopy(src, srcStart, dst, dstStart, length);
      if (destructive) {
        Arrays.fill(src, srcStart, srcStart + length, null);
      } else {
        for (int i = 0; i < length; i++) {
          RefCounted.addRef(src[srcStart + i]);
        }
      }
    }

    /** Moves the corresponding elements of a byte array in place. */
    void updateBytes(byte[] bytes, int bytesPerElementLog2) {
      System.arraycopy(
          bytes,
          moveFrom << bytesPerElementLog2,
          bytes,
          moveTo << bytesPerElementLog2,
          moveLen << bytesPerElementLog2);
    }

    /** Moves the corresponding elements of a Value array in place. */
    void updateValues(TState tstate, Object[] values) {
      tstate.removeRange(values, originalSize, keepPrefix, moveFrom, moveTo, moveLen);
    }
  }

  /** Copies elements from the given element array into a new array. */
  @RC.Out
  private Object duplicateElementArray(TState tstate, int index, Object src, ElementMove mover) {
    if (index < numVarIndexLimit()) {
      int bytesPerElementLog2 = nvEncodings[index].nBytesLog2;
      byte[] result = tstate.allocByteArray(mover.newSize() << bytesPerElementLog2);
      mover.copyBytes((byte[]) src, result, bytesPerElementLog2);
      return result;
    } else {
      Object[] result = tstate.allocObjectArray(mover.newSize());
      mover.copyValues((Object[]) src, result, false);
      return result;
    }
  }

  @Override
  @RC.Out
  Frame duplicate(TState tstate, Frame f) {
    assert f.layout() == this;
    int size = numElements(f);
    return (size == 0) ? empty : duplicate(tstate, f, ElementMove.forRange(size, 0, size));
  }

  @Override
  long bytesForDuplicate(Frame f) {
    assert f.layout() == this;
    return sizeOf(numElements(f));
  }

  @RC.Out
  private Frame duplicate(TState tstate, Frame f, ElementMove mover) {
    assert f.layout() == this;
    Frame result = frameClass.alloc(tstate);
    result.layoutOrReplacement = this;
    frameClass.setI(result, LENGTH_FIELD_OFFSET, mover.newSize());
    int start = firstElementArray();
    for (int i = start; i < nPtrs; i++) {
      frameClass.setX(
          result, i, duplicateElementArray(tstate, i - start, frameClass.getX(f, i), mover));
    }
    if (overflowSize != 0) {
      Object[] newOverflow = tstate.allocObjectArray(overflowSize);
      frameClass.setX(result, OVERFLOW_FIELD, newOverflow);
      Object[] fOverflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      for (int i = 0; i < overflowSize; i++) {
        newOverflow[i] = duplicateElementArray(tstate, i + nPtrs - 1, fOverflow[i], mover);
      }
    }
    return result;
  }

  @Override
  int numElements(Frame f) {
    return frameClass.getI(f, LENGTH_FIELD_OFFSET);
  }

  CodeValue numElements(CodeValue frame) {
    return frameClass.getIntField.get(LENGTH_FIELD).resultWithInfo(NON_NEGATIVE_INT, frame);
  }

  private static final ValueInfo NON_NEGATIVE_INT = ValueInfo.IntRange.of(0, Integer.MAX_VALUE);

  @Override
  void clearElement(TState tstate, Frame f, int index) {
    clearElements(tstate, f, index, index + 1);
  }

  private void clearElements(TState tstate, Frame f, int start, int end) {
    int firstPtrElement = firstElementArray() + numVarIndexLimit();
    for (int i = firstPtrElement; i < nPtrs; i++) {
      Object[] elementArray = (Object[]) frameClass.getX(f, i);
      tstate.clearElements(elementArray, start, end);
    }
    if (overflowSize != 0) {
      Object[] overflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
      for (int i = Math.max(0, firstPtrElement - nPtrs); i < overflowSize; i++) {
        Object[] elementArray = (Object[]) overflow[i];
        tstate.clearElements(elementArray, start, end);
      }
    }
  }

  @Override
  void emitClearElement(CodeGen codeGen, CodeValue f, CodeValue index) {
    if (numRV != 0) {
      emitClearElements(codeGen, f, index, Op.ADD_INTS.result(index, CodeValue.ONE));
    }
  }

  /** Emits a call to this layout's {@link #clearElementsOp}, building it if necessary. */
  private void emitClearElements(CodeGen codeGen, CodeValue f, CodeValue start, CodeValue end) {
    clearElementsOp(true).block(codeGen.tstateRegister(), f, start, end).addTo(codeGen.cb);
  }

  /**
   * Returns this layout's {@link #clearElementsOp}; if {@code build} is true and the op is null,
   * builds it first.
   */
  private Op clearElementsOp(boolean build) {
    assert numRV != 0;
    Op op = (Op) CLEAR_ELEMENTS_OP.getAcquire(this);
    return (op == null && build) ? buildClearElements() : op;
  }

  /** Builds and stores this layout's {@link #clearElementsOp}. */
  synchronized Op buildClearElements() {
    if (this.clearElementsOp != null) {
      // Another thread beat us to it.
      return this.clearElementsOp;
    }
    CodeBuilder cb = CodeGen.newCodeBuilder();
    Register tstate = cb.newArg(TState.class);
    Register frame = cb.newArg(Frame.class);
    Register start = cb.newArg(int.class);
    Register end = cb.newArg(int.class);
    FutureBlock rangeEmpty = new FutureBlock();
    new TestBlock.IsEq(CodeBuilder.OpCodeType.INT, start, end)
        .setBranch(true, rangeEmpty)
        .addTo(cb);
    int firstPtrElement = firstElementArray() + numVarIndexLimit();
    for (int i = firstPtrElement; i < nPtrs; i++) {
      CodeValue elementArray = frameClass.getPtrField.get(i).result(frame);
      TState.CLEAR_ARRAY_ELEMENTS_OP.block(tstate, elementArray, start, end).addTo(cb);
    }
    if (overflowSize != 0) {
      Register overflow = cb.newRegister(Object[].class);
      new SetBlock(overflow, frameClass.getPtrField.get(OVERFLOW_FIELD).result(frame)).addTo(cb);
      for (int i = Math.max(0, firstPtrElement - nPtrs); i < overflowSize; i++) {
        CodeValue elementArray = Op.OBJ_ARRAY_ELEMENT.result(overflow, CodeValue.of(i));
        TState.CLEAR_ARRAY_ELEMENTS_OP.block(tstate, elementArray, start, end).addTo(cb);
      }
    }
    cb.mergeNext(rangeEmpty);
    new ReturnBlock(null).addTo(cb);
    MethodHandle mh =
        cb.load("clearVArray_" + StringUtil.id(this), null, void.class, Handle.lookup);
    Op result = Op.forMethodHandle("clear" + this, mh).build();
    CLEAR_ELEMENTS_OP.setRelease(this, result);
    return result;
  }

  @Override
  boolean setElement(TState tstate, Frame f, int index, Value newElement) {
    if (newElement == Core.TO_BE_SET
        || template.setValue(tstate, newElement, asVarSink(f, index))) {
      return true;
    }
    clearElement(tstate, f, index);
    return false;
  }

  @Override
  void emitSetElement(CodeGen codeGen, CodeValue f, CodeValue pos, Template newElement) {
    emitSetElement(codeGen, f, pos, newElement, codeGen.escapeLink());
  }

  /**
   * Implements {@link #emitSetElement(CodeGen, CodeValue, CodeValue, Template)}; the emitted blocks
   * will branch to {@code onFail} if the {@code newElement} cannot be stored with evolving the
   * layout.
   */
  void emitSetElement(
      CodeGen codeGen, CodeValue f, CodeValue pos, Template newElement, FutureBlock onFail) {
    CopyEmitter emitter =
        new CopyEmitter() {
          @Override
          void setDstVar(CodeGen codeGen, Template t, CodeValue v) {
            setCodeValue(codeGen, t, f, pos, v);
          }
        };
    CopyPlan plan = CopyPlan.create(newElement, template);
    plan = CopyOptimizer.optimize(plan, this, CopyOptimizer.Policy.NO_CONFLICTS);
    emitter.emit(codeGen, plan, onFail);
  }

  @Override
  public boolean setElements(TState tstate, Frame f, int start, int end, Value newElement) {
    assert start < end && newElement != Core.TO_BE_SET;
    Template.VarSink sink =
        new Template.VarSink() {
          @Override
          public void setB(int index, int value) {
            assert value >= 0 && value < 256;
            byte[] bytes = (byte[]) getElementArray(f, index);
            Arrays.fill(bytes, start, end, (byte) value);
          }

          @Override
          public void setI(int index, int value) {
            // TODO(mdixon): maybe optimize this?
            byte[] bytes = (byte[]) getElementArray(f, index);
            for (int i = start; i < end; i++) {
              ArrayUtil.bytesSetI(bytes, i, value);
            }
          }

          @Override
          public void setD(int index, double value) {
            // TODO(mdixon): maybe optimize this?
            byte[] bytes = (byte[]) getElementArray(f, index);
            for (int i = start; i < end; i++) {
              ArrayUtil.bytesSetD(bytes, i, value);
            }
          }

          @Override
          public void setValue(int index, @RC.In Value value) {
            Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
            assert IntStream.range(start, end).allMatch(i -> array[i] == null);
            Arrays.fill(array, start, end, value);
            RefCounted.addRef(value, end - start - 1);
          }
        };
    if (!template.setValue(tstate, newElement, sink)) {
      clearElements(tstate, f, start, end);
      return false;
    }
    return true;
  }

  @Override
  CopyEmitter copyFrom(Register frame, int index) {
    return copyFrom(frame, CodeValue.of(index));
  }

  /** Returns a CopyEmitter who source is the specified element of a frame with this layout. */
  CopyEmitter copyFrom(Register frame, CodeValue index) {
    return new CopyEmitter() {
      @Override
      CodeValue getSrcVar(CodeGen codeGen, Template t) {
        return codeValue(t, frame, index);
      }
    };
  }

  /**
   * Given a NumVar or RefVar in this layout's template, a CodeValue for a Frame with this layout,
   * and a CodeValue for an element index return a CodeValue for the NumVar or RefVar's value.
   */
  CodeValue codeValue(Template t, CodeValue frame, CodeValue pos) {
    CodeValue elementArray = getElementArray(frame, t);
    if (t instanceof NumVar nv) {
      return switch (nv.encoding) {
        case UINT8 -> Op.UINT8_ARRAY_ELEMENT.result(elementArray, pos);
        case INT32 ->
            CodeGen.INT_FROM_BYTES_OP.result(
                elementArray, Op.SHIFT_LEFT_INT.result(pos, CodeValue.TWO));
        case FLOAT64 ->
            CodeGen.DOUBLE_FROM_BYTES_OP.result(
                elementArray, Op.SHIFT_LEFT_INT.result(pos, CodeValue.THREE));
      };
    } else {
      return Value.FROM_ARRAY_OP.result(elementArray, pos);
    }
  }

  /**
   * Given a NumVar or RefVar in this layout's template, a CodeValue for a Frame with this layout,
   * and a CodeValue for an element index, stores {@code v} as the new value.
   */
  void setCodeValue(CodeGen codeGen, Template t, CodeValue frame, CodeValue pos, CodeValue v) {
    CodeValue elementArray = getElementArray(frame, t);
    if (t instanceof NumVar nv) {
      setNumericArrayElement(codeGen, elementArray, nv.encoding, pos, v);
    } else {
      RcOp.SET_OBJ_ARRAY_ELEMENT.block(elementArray, pos, v).addTo(codeGen.cb);
    }
  }

  /** Sets one element of a numeric array with the given encoding. */
  private static void setNumericArrayElement(
      CodeGen codeGen, CodeValue elementArray, NumEncoding encoding, CodeValue pos, CodeValue v) {
    Op op;
    switch (encoding) {
      case UINT8 -> {
        op = Op.SET_UINT8_ARRAY_ELEMENT;
      }
      case INT32 -> {
        op = CodeGen.SET_BYTES_FROM_INT_OP;
        pos = Op.SHIFT_LEFT_INT.result(pos, CodeValue.TWO);
      }
      case FLOAT64 -> {
        op = CodeGen.SET_BYTES_FROM_DOUBLE_OP;
        pos = Op.SHIFT_LEFT_INT.result(pos, CodeValue.THREE);
      }
      default -> throw new AssertionError();
    }
    op.block(elementArray, pos, v).addTo(codeGen.cb);
  }

  @Override
  boolean equals(Frame f, Value other) {
    int size = numElements(f);
    if (other.baseType().isArray() && other.numElements() == size) {
      return Value.equalElements(f, other, size);
    }
    return false;
  }

  @Override
  @RC.Out
  Value element(Frame f, int i) {
    return template.getValue(TState.get(), asVarSource(f, i));
  }

  @Override
  Value peekElement(Frame f, int i) {
    return template.peekValue(asVarSource(f, i));
  }

  @Override
  void reserveForChangeOrThrow(TState tstate, Frame f, int newSize, boolean isShared)
      throws Err.BuiltinException {
    long reservation;
    if (!isShared && f.isNotShared()) {
      int size = numElements(f);
      if (size <= newSize) {
        return;
      }
      reservation = (newSize - size) * perElementSize;
      // The actual requirement may be less (e.g. we may not need any additional allocations if the
      // element arrays are already big enough) or more (because we round array sizes up).  For
      // large sizes (the only ones we care about) it will be at most 25% more than our calculation
      // (see {@link MemoryHelper#chooseCapacity}), so let's be pessimistic (we risk getting a
      // premature OOM when you're close to the limit and try to do a large allocation).
      reservation += reservation / 4;
    } else {
      reservation = sizeOf(newSize);
    }
    tstate.reserve(reservation);
  }

  /**
   * Moves elements in the given element array as specified by {@code mover}. The move will be done
   * in place (returning {@code src}) if possible, but if the resulting array is bigger than {@code
   * src} or much smaller than it (so that we'd be wasting too much memory) we'll allocate a new
   * array, copy the desired values, and then release {@code src}.
   */
  @RC.Out
  private Object updateElementArray(
      TState tstate, int index, @RC.In Object src, ElementMove mover) {
    if (index < numVarIndexLimit()) {
      int bytesPerElementLog2 = nvEncodings[index].nBytesLog2;
      int dstByteSize = mover.newSize() << bytesPerElementLog2;
      byte[] srcBytes = (byte[]) src;
      if (MemoryHelper.isOkForSize(srcBytes, dstByteSize)) {
        mover.updateBytes(srcBytes, bytesPerElementLog2);
        return srcBytes;
      }
      byte[] result = tstate.allocByteArray(dstByteSize);
      assert result.length != srcBytes.length;
      mover.copyBytes(srcBytes, result, bytesPerElementLog2);
      tstate.dropReference(srcBytes);
      return result;
    } else {
      Object[] srcValues = (Object[]) src;
      if (MemoryHelper.isOkForSize(srcValues, mover.newSize())) {
        mover.updateValues(tstate, srcValues);
        return srcValues;
      }
      Object[] result = tstate.allocObjectArray(mover.newSize());
      assert result.length != srcValues.length;
      mover.copyValues(srcValues, result, true);
      tstate.dropReference(srcValues);
      return result;
    }
  }

  @Override
  @RC.Out
  Value removeRange(
      TState tstate, @RC.In Frame f, int keepPrefix, int moveFrom, int moveTo, int moveLen) {
    int size = numElements(f);
    assert keepPrefix >= 0
        && moveLen >= 0
        && moveFrom >= keepPrefix
        && moveTo >= keepPrefix
        && moveFrom + moveLen <= size;
    if (keepPrefix == 0 && moveLen == 0) {
      // No elements are being kept
      tstate.dropReference(f);
      return alloc(tstate, moveTo);
    }
    ElementMove mover = new ElementMove(size, keepPrefix, moveFrom, moveTo, moveLen);
    if (!f.isNotShared()) {
      Frame result = duplicate(tstate, f, mover);
      tstate.dropReference(f);
      return result;
    } else {
      frameClass.setI(f, LENGTH_FIELD_OFFSET, mover.newSize());
      int start = firstElementArray();
      for (int i = start; i < nPtrs; i++) {
        frameClass.setX(f, i, updateElementArray(tstate, i - start, frameClass.getX(f, i), mover));
      }
      if (overflowSize != 0) {
        Object[] fOverflow = (Object[]) frameClass.getX(f, OVERFLOW_FIELD);
        for (int i = 0; i < overflowSize; i++) {
          fOverflow[i] = updateElementArray(tstate, i + nPtrs - 1, fOverflow[i], mover);
        }
      }
      return f;
    }
  }

  @Override
  String toString(Frame f) {
    return StringUtil.joinElements("[", "]", numElements(f), i -> peekElement(f, i));
  }

  Template.VarSource asVarSource(Frame f, int pos) {
    assert f.layout() == this;
    return new Template.VarSource() {
      @Override
      public int getB(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetB(array, pos);
      }

      @Override
      public int getI(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetI(array, pos);
      }

      @Override
      public double getD(int index) {
        byte[] array = (byte[]) getElementArray(f, index);
        return ArrayUtil.bytesGetD(array, pos);
      }

      @Override
      public Value getValue(int index) {
        Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
        return Value.fromArray(array, pos);
      }
    };
  }

  Template.VarSink asVarSink(Frame f, int pos) {
    assert f.layoutOrReplacement == this;
    return new Template.VarSink() {
      @Override
      public void setB(int index, int value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetB(array, pos, value);
      }

      @Override
      public void setI(int index, int value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetI(array, pos, value);
      }

      @Override
      public void setD(int index, double value) {
        byte[] array = (byte[]) getElementArray(f, index);
        ArrayUtil.bytesSetD(array, pos, value);
      }

      @Override
      public void setValue(int index, @RC.In Value value) {
        Object[] array = (Object[]) getElementArray(f, index + numVarIndexLimit());
        assert array[pos] == null;
        array[pos] = value;
      }
    };
  }

  /**
   * VArrayLayout's VarAllocator assigns sequential indices from separate pools for RefVars and
   * NumVars. Related VarAllocators (i.e. ones that were created by a chain of duplicate() calls)
   * will not assign NumVars with different encodings to the same index.
   */
  static class VarAllocator implements TemplateBuilder.VarAllocator {
    // Related VarAllocators will share an instance of VarAllocatorShared.
    private final VarAllocatorShared shared;

    /** The minimum index at which the next NumVar of each encoding could be allocated. */
    private final int[] nextNumVar;

    /** The index of the next refvar. */
    private int nextX;

    VarAllocator() {
      shared = new VarAllocatorShared();
      nextNumVar = new int[Template.NUM_ENCODINGS];
    }

    VarAllocator(VarAllocator toDuplicate) {
      shared = toDuplicate.shared;
      nextNumVar = Arrays.copyOf(toDuplicate.nextNumVar, Template.NUM_ENCODINGS);
      nextX = toDuplicate.nextX;
    }

    /** Returns the minimum nPtrs required for a Frame to hold these vars. */
    int ptrSize() {
      return nextX + shared.nvEncodings.size();
    }

    @Override
    @SuppressWarnings("EnumOrdinal")
    public NumVar allocNumVar(NumVar forEncoding) {
      int encodingIndex = forEncoding.encoding.ordinal();
      int result = shared.allocNumVar(forEncoding.encoding, nextNumVar[encodingIndex]);
      nextNumVar[encodingIndex] = result + 1;
      return forEncoding.withIndex(result);
    }

    @Override
    public int allocRefVar() {
      return nextX++;
    }

    @Override
    public VarAllocator duplicate() {
      return new VarAllocator(this);
    }

    @Override
    public void resetTo(TemplateBuilder.VarAllocator otherAllocator) {
      VarAllocator other = (VarAllocator) otherAllocator;
      assert other.shared == shared;
      System.arraycopy(other.nextNumVar, 0, nextNumVar, 0, Template.NUM_ENCODINGS);
      nextX = other.nextX;
    }

    @Override
    public void union(TemplateBuilder.VarAllocator otherAllocator) {
      VarAllocator other = (VarAllocator) otherAllocator;
      assert other.shared == shared;
      for (int i = 0; i < Template.NUM_ENCODINGS; i++) {
        nextNumVar[i] = Math.max(nextNumVar[i], other.nextNumVar[i]);
      }
      this.nextX = Math.max(this.nextX, other.nextX);
    }
  }

  /** Keeps track of which encoding each NumVar index has. */
  private static class VarAllocatorShared {
    /**
     * If we have allocated a NumVar with index {@code i}, {@code nvEncodings.get(i)} is the
     * encoding of that NumVar.
     */
    private final List<NumEncoding> nvEncodings = new ArrayList<>();

    /**
     * Returns the first index starting from {@code next} that can be used for a NumVar with the
     * specified encoding.
     */
    int allocNumVar(NumEncoding encoding, int next) {
      int size = nvEncodings.size();
      for (int i = next; i < size; i++) {
        if (nvEncodings.get(i) == encoding) {
          return i;
        }
      }
      nvEncodings.add(encoding);
      return size;
    }

    /** Returns the NumEncoding of each used NumVar index. */
    NumEncoding[] nvEncodings() {
      return nvEncodings.toArray(NumEncoding[]::new);
    }
  }

  /**
   * A Choice locates the Template that is used for array elements with a given baseType (since it
   * actually checks sort order, if the baseType is an array type then it will locate any element
   * that is an array).
   *
   * <p>There three flavors of Choice:
   *
   * <ul>
   *   <li>NEVER: the array cannot hold elements of baseType
   *   <li>ALWAYS: the array can only elements of baseType (i.e. template.baseType().sortOrder ==
   *       baseType.sortOrder)
   *   <li>all other Choices: template is a Union, and template.choice(index).baseType().sortOrder
   *       == baseType.sortOrder
   * </ul>
   */
  private static class Choice {
    static final Choice NEVER = new Choice(-1);
    static final Choice ALWAYS = new Choice(-1);

    // Because we expect this to be very common, we'll avoid allocating a new one each time.
    // (Choice is currently only used for numbers; since SORT_ORDER_NUM is 1, Numbers will always be
    // the first choice in any union.)
    private static final Choice CHOICE_0 = new Choice(0);

    /** The index of this choice in a Union template. -1 if the layout template is not a Union. */
    final int index;

    private Choice(int index) {
      this.index = index;
    }

    /** True if this sort order is one choice in a Union. */
    boolean isInUnion() {
      return index >= 0;
    }

    /**
     * Returns the template used for values with this sort order, or null if this is {@link #NEVER}.
     */
    Template template(Template t) {
      return isInUnion() ? ((Template.Union) t).choice(index) : (this == ALWAYS ? t : null);
    }

    /** Returns the Choice for the given BaseType, given the layout's template. */
    static Choice forBaseType(BaseType baseType, Template t) {
      if (t instanceof Template.Union union) {
        int i = union.indexOf(baseType.sortOrder);
        if (i < 0) {
          return NEVER;
        } else {
          assert (i == 0) || baseType != Core.NUMBER;
          return (i == 0) ? CHOICE_0 : new Choice(i);
        }
      } else if (t == Template.EMPTY) {
        return NEVER;
      } else {
        return (t.baseType().sortOrder == baseType.sortOrder) ? ALWAYS : NEVER;
      }
    }
  }
}

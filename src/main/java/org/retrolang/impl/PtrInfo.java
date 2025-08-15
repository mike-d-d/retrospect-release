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

import org.retrolang.code.CodeBuilder.PrintOptions;
import org.retrolang.code.CodeValue;
import org.retrolang.code.ConditionalBranch;
import org.retrolang.code.Emitter;
import org.retrolang.code.Register;
import org.retrolang.code.TestBlock;
import org.retrolang.code.ValueInfo;

/**
 * A ValueInfo that represents information about a pointer-valued register. There is currently only
 * one implementation of PtrInfo: FrameLayout, which when used as a PtrInfo represents that the
 * pointer is a Frame with that layout.
 */
public interface PtrInfo extends ValueInfo {

  BaseType baseType();

  /** Returns true if the given ValueInfo implies that the value is unshared. */
  public static boolean isUnshared(ValueInfo info) {
    return info instanceof IsNotShared;
  }

  /**
   * A PtrInfo indicating that the pointer is a reference to a Frame with the given layout and for
   * which {@link RefCounted#isNotShared()} returns true.
   */
  class IsNotShared implements PtrInfo {
    final FrameLayout layout;

    IsNotShared(FrameLayout layout) {
      this.layout = layout;
    }

    @Override
    public BaseType baseType() {
      return layout.baseType();
    }

    @Override
    public boolean containsValue(Object value) {
      // Constant values must be uncounted, so can't be unshared.
      return false;
    }

    @Override
    public ValueInfo unionConst(CodeValue.Const constInfo) {
      throw new UnsupportedOperationException();
    }

    // We create a single canonical IsNotShared instance for each FrameLayout, so we don't need to
    // implement equals()

    @Override
    public String toString() {
      return String.format("notShared(%s)", layout);
    }
  }

  /**
   * A TestBlock that checks if a Frame-typed CodeValue has the specified FrameLayout.
   *
   * <p>Note that although this test takes two values ({@code frame} and {@code layoutValue}) it is
   * only testing one of them; {@code layoutValue} must be the result of {@link
   * Frame#GET_LAYOUT_OR_REPLACEMENT} applied to {@code frame}. (We emit code that references {@code
   * layoutValue}, but any info is attached to {@code frame}).
   */
  static class TestLayout extends TestBlock {
    final FrameLayout layout;

    TestLayout(CodeValue frame, CodeValue layoutValue, FrameLayout layout) {
      super(frame, layoutValue);
      assert layout != null;
      this.layout = layout;
    }

    @Override
    protected Boolean test(ValueInfo info1, ValueInfo info2) {
      return test(info1, layout);
    }

    static Boolean test(ValueInfo info, FrameLayout layout) {
      if (info == layout) {
        return true;
      } else if (info instanceof IsNotShared ins && ins.layout == layout) {
        return true;
      } else if (info instanceof CodeValue.Const c) {
        return ((Value) c.value).layout() == layout;
      } else {
        return null;
      }
    }

    @Override
    protected void updateInfos(ValueInfo info1, ValueInfo info2) {
      if (value1 instanceof Register r) {
        next.info.updateInfo(r, layout);
      }
    }

    @Override
    public ConditionalBranch emitTest(Emitter emitter) {
      value2.push(emitter, Object.class);
      emitter.pushX(layout, FrameLayout.class);
      return ConditionalBranch.IF_ACMPEQ;
    }

    @Override
    public String toString(PrintOptions options) {
      return String.format("test %s is %s", value1.toString(options), layout);
    }
  }
}

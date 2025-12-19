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
import org.retrolang.code.CodeValue.Const;
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

  static boolean isPtrInfo(ValueInfo info) {
    if (info instanceof PtrInfo) {
      return true;
    } else if (info instanceof Const c) {
      if (c.value instanceof Value v) {
        assert v instanceof Frame || !v.baseType().usesFrames();
        return true;
      }
    }
    return false;
  }

  // isPtrInfo(info) must be true
  static long sortOrder(ValueInfo info) {
    return baseType(info).sortOrder;
  }

  static BaseType baseType(ValueInfo info) {
    if (info instanceof PtrInfo pi) {
      return pi.baseType();
    } else {
      return ((Value) ((Const) info).value).baseType();
    }
  }

  // isPtrInfo() should be true of each argument
  static ValueInfo union(ValueInfo info1, ValueInfo info2) {
    if (info1 == CodeValue.NULL) {
      return info2;
    } else if (info2 == CodeValue.NULL) {
      return info1;
    }
    long o1 = sortOrder(info1);
    long o2 = sortOrder(info2);
    if (o1 == o2) {
      return unionSameOrder(info1, info2);
    } else {
      throw new UnsupportedOperationException();
    }
  }

  // isPtrInfo() should be true of each argument
  static ValueInfo intersection(ValueInfo info1, ValueInfo info2) {
    assert sortOrder(info1) == sortOrder(info2);
    // Prefer Const or IsNotShared over FrameLayout over CurrentFrame over BaseType, and prefer
    // info1 over info2
    if (info1 == info2 || info2 instanceof BaseType) {
      return info1;
    } else if (info1 instanceof BaseType) {
      return info2;
    } else if (info2 instanceof FrameLayout) {
      // The only surprising case here is that we could end up with two distinct FrameLayouts; that
      // should only be possible if we've raced against another thread that's evolving them as we
      // generate code.
      assert info1 instanceof Const
          || info1 instanceof IsNotShared
          || ((FrameLayout) info1).hasEvolved()
          || ((FrameLayout) info2).hasEvolved();
      return info1;
    } else if (info1 instanceof FrameLayout) {
      return info2;
    } else {
      assert info1.equals(info2);
      return info1;
    }
  }

  // isPtrInfo() should be true of each argument
  static boolean intersects(ValueInfo info1, ValueInfo info2) {
    if (info1 == info2) {
      return true;
    } else if (info1 instanceof Const && info2 instanceof Const) {
      return info1.equals(info2);
    } else {
      return sortOrder(info1) == sortOrder(info2);
    }
  }

  // isPtrInfo() should be true of each argument
  static boolean containsAll(ValueInfo info1, ValueInfo info2) {
    return intersection(info2, info1) == info2;
  }

  /** Converts an IsNotShared or Const to a FrameLayout or (for non-Frame constants) BaseType. */
  private static ValueInfo widen(ValueInfo ptrInfo) {
    if (ptrInfo instanceof IsNotShared) {
      return ((IsNotShared) ptrInfo).layout;
    } else if (ptrInfo instanceof Const) {
      Value v = (Value) ((Const) ptrInfo).value;
      if (v == Core.EMPTY_ARRAY) {
        return ptrInfo;
      }
      if (v instanceof Frame) {
        return v.layout();
      }
      assert !(v.baseType().isCompositional() || v.baseType().isArray());
      return v.baseType();
    } else {
      assert ptrInfo instanceof FrameLayout;
      return ptrInfo;
    }
  }

  private static ValueInfo unionSameOrder(ValueInfo info1, ValueInfo info2) {
    // Const and IsNotShared override equals(), for everything else this is an == check
    if (info1.equals(info2) || info1 instanceof BaseType) {
      return info1;
    } else if (info2 instanceof BaseType) {
      return info2;
    }
    info1 = widen(info1);
    info2 = widen(info2);
    if (info1 == info2 || info2 instanceof Const) {
      return info1;
    } else if (info1 instanceof Const) {
      return info2;
    } else {
      // We can get here due to unions, or possibly because FrameLayouts are evolving while we
      // generate code.
      throw new UnsupportedOperationException();
    }
  }

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
      return ANY;
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

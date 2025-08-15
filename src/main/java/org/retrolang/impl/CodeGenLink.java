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

import com.google.errorprone.annotations.Keep;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Objects (such as exlined methods) that may have generated code use a CodeGenLink to maintain a
 * link to the currently runnable code and to any in-progress-but-not-yet-runnable code.
 */
class CodeGenLink {

  /**
   * The relationship between the MethodMemo and the generated code. Currently there is only one
   * option.
   */
  enum Kind {
    /** The MethodMemo is an exlined method, and the generated code executes the method. */
    EXLINED {
      @Override
      ValueMemo argsMemo(MethodMemo mm) {
        return mm.argsMemo;
      }
    };

    abstract ValueMemo argsMemo(MethodMemo mm);
  }

  final MethodMemo mm;

  final Kind kind;

  /**
   * If non-null, a BranchTargetCodeGen that is ready to be called. Updated asynchronously, so
   * should only be read by the {@link #ready()} method or the scope's current codegen thread.
   */
  @Keep private CodeGenTarget ready;

  /**
   * If non-null, code is currently being generated for this target as part of {@link #group}. New
   * code will refer to this target, but running code won't see it until it becomes ready. Only read
   * or written by the group thread.
   *
   * <p>Since a Scope can only have one active CodeGenGroup at a time, if this is non-null it must
   * either be the current CodeGenGroup or a previous failed attempt.
   */
  private CodeGenTarget next;

  private CodeGenGroup group;

  CodeGenLink(MethodMemo mm, Kind kind) {
    this.mm = mm;
    this.kind = kind;
  }

  private static final VarHandle READY =
      Handle.forVar(MethodHandles.lookup(), CodeGenLink.class, "ready", CodeGenTarget.class);

  /**
   * Returns the most recently completed target for this link, or null if code generation for this
   * link has not yet completed.
   */
  CodeGenTarget ready() {
    // Use getAcquire since this pointer may have been written asynchronously by another thread
    return (CodeGenTarget) READY.getAcquire(this);
  }

  /**
   * Called during code generation before emitting a call through this link.
   *
   * <ul>
   *   <li>If {@link #group} has already created a new target for this link, return it.
   *   <li>Otherwise, if there is an existing ready target and it is suitable for the given args,
   *       return it.
   *   <li>Otherwise create a new CodeGenTarget and add it to the group.
   * </ul>
   */
  CodeGenTarget next(CodeGenGroup group, Object[] args) {
    assert group != null && TState.getIfPresent().codeGen().group == group;
    if (this.group == group) {
      return next;
    }
    // These will usually be null, but just in case an earlier CodeGen call was interrupted we
    // might as well clean things up.
    this.group = null;
    this.next = null;
    // Don't actually need to sync here because no other thread can be updating ready while we're
    // generating code, but it's easiest to just be consistent.
    CodeGenTarget ready = ready();
    if (ready != null) {
      ValueMemo argsMemo = kind.argsMemo(mm);
      assert argsMemo.size() == ready.args.size();
      // If the existing target can handle these args, or regenerating it wouldn't handle them any
      // better, use it; otherwise we'll re-generate.
      for (int i = 0; ; i++) {
        if (i == ready.args.size()) {
          // All args passed our check
          return ready;
        }
        Template readyArg = ready.args.get(i);
        if (RValue.toTemplate((Value) args[i]).toBuilder().isSubsetOf(readyArg.toBuilder())) {
          // The target can handle this arg
          continue;
        } else if (argsMemo.result(
            i, TProperty.isSubsetOf(readyArg, TemplateBuilder.TestOption.UPGRADE_SUB_INTS))) {
          // Regenerated code wouldn't be able to handle more arg values
          continue;
        }
        // It looks like regenerating would help
        break;
      }
    }
    startCodeGen(group);
    return next;
  }

  /**
   * Create a new target for this link, and add it to the current group.
   *
   * <p>Usually only called from {@link #next(CodeGenGroup, Object[])}, but can also be directly for
   * testing purposes.
   */
  void startCodeGen(CodeGenGroup group) {
    assert this.group == null && this.next == null;
    this.group = group;
    String name = mm.perMethod.method.function.name + "_" + group.nextTargetSuffix();
    // If we are regenerating code and the result templates haven't changed, we want to keep
    // them (rather than replace them with a list that is equals() but not identical) so that
    // the == check in TState.checkExlinedResult continues to pass.
    next = CodeGenTarget.fromCodeGenLink(name, group, this, (ready != null) ? ready.results : null);
    group.add(next);
  }

  /** Called when we have completed code generation for all targets in the group. */
  void makeReady(CodeGenTarget next) {
    assert this.next == next;
    READY.setRelease(this, next);
    this.group = null;
    this.next = null;
  }
}

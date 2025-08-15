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

import java.util.function.Predicate;

/**
 * A Scope stores information that has been inferred by the VM during the execution of a
 * computation. This information is not required for correct execution but may enable more efficient
 * execution choices.
 *
 * <p>Each Retrospect computation executes relative to a Scope. Computations that share code should
 * typically share a Scope so that they can benefit from optimizations based on their cumulative
 * history.
 *
 * <p>Since reference counted objects are associated with a single ResourceTracker and Scopes may be
 * shared by multiple ResourceTrackers, Scopes may not hold pointers to reference counted objects.
 */
class Scope {
  final Evolver evolver = new Evolver();

  final MemoMerger memoMerger = new MemoMerger();

  private int nextCodeGenIndex;

  /**
   * Any method created after this call for which the given predicate returns true will use a single
   * exlined for all calls. For testing only.
   */
  void setForceExlined(Predicate<VmMethod> methods) {
    memoMerger.setForceExlined(methods);
  }

  /**
   * Forces code generation for each method that was matched by the predicate in a previous call to
   * {@link #setForceExlined}.
   */
  void generateCodeForForcedMethods(CodeGenGroup.Monitor monitor) {
    CodeGenGroup group = new CodeGenGroup(monitor, nextCodeGenIndex);
    memoMerger.generateCodeForForcedMethods(group);
    nextCodeGenIndex += group.generateCode();
  }
}

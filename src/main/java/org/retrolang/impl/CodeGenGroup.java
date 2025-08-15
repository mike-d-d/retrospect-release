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

import com.google.common.collect.ImmutableList;
import org.retrolang.code.DebugInfo;
import java.util.ArrayList;

/**
 * A CodeGenGroup contains one or more CodeGenTargets that we are generating code for. Once code
 * generation has completed for all of them, we will make them all ready. (If one CodeGenTarget has
 * exlined calls to another, we don't want to make the first ready until code generation for the
 * second has also completed.)
 *
 * <p>A Scope should only have one CodeGenGroup active at a time.
 */
class CodeGenGroup {

  /**
   * A Monitor is an object that will be updated as code is generated, to enable logging or other
   * tracking of progress.
   */
  interface Monitor {
    /**
     * Called once for each target in the group, after code has been generated for that target.
     *
     * @param name will appear in any stack traces thrown while executing the constructed method
     * @param args templates for the args that the generated code can accept
     * @param counter if {@link #countCalls} returned true, an object whose {@code toString()}
     *     method will return the counter's current value together with the name of the generated
     *     method
     * @param debugInfo the CodeBuilder's {@link DebugInfo}
     */
    void loaded(String name, ImmutableList<Template> args, Object counter, DebugInfo debugInfo);

    /**
     * Returns true if each generated method should include code to count the number of times it is
     * called. The default implementation returns false.
     */
    default boolean countCalls() {
      return false;
    }

    /**
     * Returns true if CodeBuilders should be configured to generate verbose output. The default
     * implementation returns false.
     */
    default boolean verbose() {
      return false;
    }
  }

  final Monitor monitor;

  /** We append an index to the method name to get a unique identifier for each generated method. */
  private final int firstIndex;

  /** The CodeGenTargets for which we are generating code. */
  private ArrayList<CodeGenTarget> targets = new ArrayList<>();

  CodeGenGroup(Monitor monitor, int firstIndex) {
    this.monitor = monitor;
    this.firstIndex = firstIndex;
  }

  /** Returns a distinct numeric suffix for the next CodeGenTarget to be added to this group. */
  int nextTargetSuffix() {
    return firstIndex + targets.size();
  }

  /**
   * Adds a CodeGenTarget to this group. May be called while generating code for another target in
   * the group.
   */
  void add(CodeGenTarget target) {
    targets.add(target);
  }

  /**
   * Generates code for each target in the group, then makes them all ready and returns the final
   * number of targets.
   */
  int generateCode() {
    // First generate code for the added targets, which may cause more targets to be added
    for (int i = 0; i < targets.size(); i++) {
      targets.get(i).generateCode();
    }
    // Once we have code for the original target and any dependencies we can install it
    for (CodeGenTarget target : targets) {
      target.link.makeReady(target);
    }
    int finalSize = targets.size();
    targets = null;
    return finalSize;
  }
}

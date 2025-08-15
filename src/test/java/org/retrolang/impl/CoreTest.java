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

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CoreTest {
  @Test
  public void booleans() {
    assertThat(Core.core().lookupSingleton("True")).isEqualTo(Core.TRUE);
    assertThat(Core.core().lookupType("Boolean")).isEqualTo(Core.BOOLEAN);
    assertThat(Core.TRUE.isa(Core.BOOLEAN)).isSameInstanceAs(Condition.TRUE);
    assertThat(Core.FALSE.isa(Core.BOOLEAN)).isSameInstanceAs(Condition.TRUE);
    assertThat(Core.NONE.isa(Core.BOOLEAN)).isSameInstanceAs(Condition.FALSE);
  }

  @Test
  public void arrays() {
    assertThat(Core.EMPTY_ARRAY.isa(Core.ARRAY)).isSameInstanceAs(Condition.TRUE);
    // pair = [True, None]
    Value pair = Core.FixedArrayType.withSize(2).uncountedOf(Core.TRUE, Core.NONE);
    assertThat(pair.isa(Core.ARRAY)).isSameInstanceAs(Condition.TRUE);
    assertThat(pair.isa(Core.BOOLEAN)).isSameInstanceAs(Condition.FALSE);
  }

  @Test
  public void newTypeContainment() {
    // Constructs some new types in a simple hierarchy and verifies that the expected containment
    // relationships hold.
    VmModule myModule = new VmModule("MyModule", null);
    // type U1 is Lambda
    VmType.Union u1 = new VmType.Union(myModule, "U1", false, Core.LAMBDA);
    // singleton X is U1
    Singleton x = new BaseType.Named(myModule, "X", 0, u1).asValue();
    // type U2 contains U1, Boolean
    VmType u2 = VmType.Union.withSubTypes(myModule, "U2", false, u1, Core.BOOLEAN);
    // singleton Z is U2
    Singleton z = new BaseType.Named(myModule, "Z", 0, u2).asValue();
    // type U3 contains U2
    VmType u3 = VmType.Union.withSubTypes(myModule, "U3", false, u2);
    assertThat(u1.contains((BaseType.Named) x.baseType)).isTrue();
    assertThat(Core.LAMBDA.contains((BaseType.Named) x.baseType)).isTrue();
    assertThat(u1.contains((BaseType.Named) z.baseType)).isFalse();
    assertThat(u2.contains((BaseType.Named) x.baseType)).isTrue();
    assertThat(u2.contains((BaseType.Named) z.baseType)).isTrue();
    assertThat(u2.contains((BaseType.Named) Core.TRUE.baseType)).isTrue();
    assertThat(u2.contains((BaseType.Named) Core.NONE.baseType)).isFalse();
    assertThat(u3.contains((BaseType.Named) x.baseType)).isTrue();
  }
}

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

import com.google.common.collect.ImmutableSet;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;

/**
 * Implements Vm.MethodPredicate, and provides support for determining when a method is attachable
 * in a module.
 */
abstract class MethodPredicate implements Vm.MethodPredicate {

  /**
   * Returns true if this MethodPredicate is true for the given args.
   *
   * <p>The {@code args} array must contain a Value for each argument of the associated method; any
   * additional elements are ignored.
   */
  abstract Condition test(Object[] args);

  /**
   * Tries to find a set of {@link Simple}s such that
   *
   * <ul>
   *   <li>each Simple in the returned set is positive and is for a type in the given module; and
   *   <li>this MethodPredicate is true for a given sequence of args only if at least one of the
   *       returned Simples' type is an explicit supertype of the corresponding argument's base
   *       type.
   * </ul>
   *
   * <p>Returns null if it is unable to find such a set.
   */
  abstract ImmutableSet<Simple> asArgRestriction(VmModule module);

  /** The trivial MethodPredicate that is true of any args. */
  static final MethodPredicate TRUE =
      new MethodPredicate() {
        @Override
        Condition test(Object[] args) {
          return Condition.TRUE;
        }

        @Override
        @Nullable ImmutableSet<Simple> asArgRestriction(VmModule module) {
          return null;
        }

        @Override
        public String toString() {
          return "TRUE";
        }
      };

  /**
   * A MethodPredicate which is true if the argument in position {@code argIndex} is ({@code
   * positive}=true) or is not ({@code positive}=false) a value of the given type.
   */
  static class Simple extends MethodPredicate {
    final int argIndex;
    final VmType type;
    final boolean positive;

    Simple(int argIndex, VmType type, boolean positive) {
      // Using Integer as a MethodPredicate wouldn't work properly (since it doesn't contain any
      // BaseTypes) and is prohibited by the VM spec.
      assert type != Core.INTEGER;
      this.argIndex = argIndex;
      this.type = type;
      this.positive = positive;
    }

    @Override
    Condition test(Object[] args) {
      // The caller is responsible for ensuring that all args are instances of Value.
      Condition c = ((Value) args[argIndex]).isa(type);
      return positive ? c : c.not();
    }

    @Override
    @Nullable ImmutableSet<Simple> asArgRestriction(VmModule module) {
      if (!positive || type.module != module) {
        return null;
      } else if (!(type instanceof VmType.Union union) || union.subTypes.isEmpty()) {
        // A positive Simple for a type without subtypes is its own argRestriction.
        return ImmutableSet.of(this);
      }
      // Unions with explicit subtypes are a little trickier; we have to include all their
      // explicit subtypes, and they must all be in this module.
      // First check the contained types; if any are from a different module, give up.
      VmType.Union union = (VmType.Union) type;
      if (union.subTypes.stream().anyMatch(t -> t.module != module)) {
        return null;
      }
      ImmutableSet.Builder<Simple> builder = ImmutableSet.builder();
      builder.add(this);
      union.subTypes.forEach(t -> builder.add(new Simple(argIndex, t, true)));
      return builder.build();
    }

    @Override
    public String toString() {
      return String.format("_%s %s %s", argIndex, positive ? "is" : "is not", type);
    }
  }

  @Override
  public MethodPredicate and(Vm.MethodPredicate other) {
    return and(this, (MethodPredicate) other);
  }

  private static MethodPredicate and(MethodPredicate p1, MethodPredicate p2) {
    if (p1 == TRUE) {
      return p2;
    } else if (p2 == TRUE) {
      return p1;
    }
    return new MethodPredicate() {
      @Override
      Condition test(Object[] args) {
        return p1.test(args).and(() -> p2.test(args));
      }

      @Override
      ImmutableSet<Simple> asArgRestriction(VmModule module) {
        ImmutableSet<Simple> r1 = p1.asArgRestriction(module);
        ImmutableSet<Simple> r2 = p2.asArgRestriction(module);
        if (r1 != null && (r2 == null || r1.size() <= r2.size())) {
          return r1;
        } else {
          return r2;
        }
      }

      @Override
      public String toString() {
        return String.format("(%s) and (%s)", p1, p2);
      }
    };
  }

  @Override
  public MethodPredicate or(Vm.MethodPredicate other) {
    return or(this, (MethodPredicate) other);
  }

  private static MethodPredicate or(MethodPredicate p1, MethodPredicate p2) {
    if (p1 == TRUE || p2 == TRUE) {
      // could return TRUE, but I don't see us doing this intentionally.
      throw new AssertionError();
    }
    return new MethodPredicate() {
      @Override
      Condition test(Object[] args) {
        return p1.test(args).or(() -> p2.test(args));
      }

      @Override
      @Nullable ImmutableSet<Simple> asArgRestriction(VmModule module) {
        ImmutableSet<Simple> r1 = p1.asArgRestriction(module);
        ImmutableSet<Simple> r2 = p2.asArgRestriction(module);
        if (r1 == null || r2 == null) {
          return null;
        } else {
          return new ImmutableSet.Builder<Simple>().addAll(r1).addAll(r2).build();
        }
      }

      @Override
      public String toString() {
        return String.format("(%s) or (%s)", p1, p2);
      }
    };
  }
}

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

package org.retrolang.impl.core;

import static org.retrolang.impl.Value.addRef;

import org.retrolang.impl.BaseType;
import org.retrolang.impl.BuiltinMethod;
import org.retrolang.impl.BuiltinMethod.Caller;
import org.retrolang.impl.BuiltinMethod.Fn;
import org.retrolang.impl.Condition;
import org.retrolang.impl.Core;
import org.retrolang.impl.Err;
import org.retrolang.impl.Err.BuiltinException;
import org.retrolang.impl.RC;
import org.retrolang.impl.RValue;
import org.retrolang.impl.TState;
import org.retrolang.impl.Value;
import org.retrolang.impl.VmFunctionBuilder;

/** Core methods providing support for collections. */
public final class CollectionCore {

  /**
   * {@code private compound Filter is Lambda}
   *
   * <p>Filter wraps a Boolean-valued Lambda to create a Lambda that returns either its argument (if
   * the wrapped Lambda returns True) or Absent (if it returns False).
   */
  @Core.Private static final BaseType.Named FILTER = Core.newBaseType("Filter", 1, Core.LAMBDA);

  /** {@code function filter(lambda)} */
  @Core.Public
  static final VmFunctionBuilder filterFn = VmFunctionBuilder.create("filter", 1).isOpen();

  /** {@code private compound WithKeys is Collection} */
  @Core.Private
  static final BaseType.Named WITH_KEYS = Core.newBaseType("WithKeys", 1, Core.COLLECTION);

  /** {@code private compound WithKeysMatrix is Matrix} */
  @Core.Private
  static final BaseType.Named WITH_KEYS_MATRIX = Core.newBaseType("WithKeysMatrix", 1, Core.MATRIX);

  /** {@code private compound WithKeysIterator is Iterator} */
  @Core.Private
  static final BaseType.Named WITH_KEYS_ITERATOR =
      Core.newBaseType("WithKeysIterator", 1, LoopCore.ITERATOR);

  /** {@code function withKeys(collection)} */
  @Core.Public
  static final VmFunctionBuilder withKeys = VmFunctionBuilder.create("withKeys", 1).isOpen();

  @Core.Public static final VmFunctionBuilder join = VmFunctionBuilder.create("join", 2).isOpen();

  /**
   * {@code private compound TransformedLambda is Lambda}
   *
   * <p>Elements are {@code first}, {@code second} (both Lambdas).
   *
   * <p>Applying a TransformedLambda applies its elements in sequence (aka lambda composition). If
   * the result of {@code first} is Absent, {@code second} is skipped and the overall result is
   * Absent.
   */
  @Core.Private
  static final BaseType.Named TRANSFORMED_LAMBDA =
      Core.newBaseType("TransformedLambda", 2, Core.LAMBDA);

  /**
   * {@code private compound TransformedCollection is Collection}
   *
   * <p>Elements are {@code base} (a Collection) and {@code lambda}.
   *
   * <p>A TransformedCollection has the same keys as its {@code base}; its value at each key is the
   * the result of applying {@code lambda} to the corresponding value of {@code base} (unless that
   * value is Absent).
   *
   * <p>If Retrospect allowed compound types to be subtypes of other compound types it would make
   * sense for TransformedCollection to be a subtype of TransformedLambda, and at least one method
   * definition (AtTransformed) treats them that way. Without complicating the type system we could
   * also get this effect by making TransformedLambda a union containing two compounds
   * (TransformedCollection and TransformedNonCollection), but just using a more complex
   * MethodPredicate (and implicitly relying on them having their elements in the same order) seems
   * like the least clunky alternative.
   */
  @Core.Private
  static final BaseType.Named TRANSFORMED_COLLECTION =
      Core.newBaseType("TransformedCollection", 2, Core.COLLECTION);

  /**
   * {@code private compound TransformedMatrix is Matrix}
   *
   * <p>Elements are {@code base}, {@code lambda}.
   *
   * <p>Following the comments on {@link #TRANSFORMED_COLLECTION}, this would be a subtype of
   * TransformedCollection if such a thing were possible.
   */
  @Core.Private
  public static final BaseType.Named TRANSFORMED_MATRIX =
      Core.newBaseType("TransformedMatrix", 2, Core.MATRIX);

  /**
   * {@code private compound TransformedIterator is Iterator}
   *
   * <p>Elements are {@code it}, {@code eKind}, {@code lambda}.
   *
   * <p>A TransformedIterator applies {@code lambda} to each value returned by the wrapped {@code
   * it}. If {@code eKind} is not EnumerateValues, {@code it} is expected to return [key, value]
   * pairs and {@code lambda} is only applied to the value part of each result. If {@code lambda}
   * returns Absent the result is discarded unless {@code eKind} is EnumerateAllKeys.
   */
  @Core.Private
  static final BaseType.Named TRANSFORMED_ITERATOR =
      Core.newBaseType("TransformedIterator", 3, LoopCore.ITERATOR);

  /**
   * {@code private compound JoinedCollection is Collection}
   *
   * <p>Elements are {@code c1}, {@code c2}.
   */
  @Core.Private
  static final BaseType.Named JOINED_COLLECTION =
      Core.newBaseType("JoinedCollection", 2, Core.COLLECTION);

  /**
   * {@code private compound JoinedMatrix is Matrix}
   *
   * <p>Elements are {@code c1}, {@code c2}.
   *
   * <p>Following the comments on {@link #TRANSFORMED_COLLECTION}, this would be a subtype of
   * JoinedCollection if such a thing were possible.
   */
  @Core.Private
  static final BaseType.Named JOINED_MATRIX = Core.newBaseType("JoinedMatrix", 2, Core.MATRIX);

  /**
   * {@code private compound JoinedIterator is Iterator}
   *
   * <p>Elements are {@code it}, {@code c2}, {@code eKind}.
   */
  @Core.Private
  static final BaseType.Named JOINED_ITERATOR =
      Core.newBaseType("JoinedIterator", 3, LoopCore.ITERATOR);

  /** {@code method filter(lambda) = Filter_(lambda) // v -> (lambda @ v) ? v : Absent} */
  @Core.Method("filter(Lambda)")
  static Value filter(TState tstate, @RC.In Value lambda) {
    return tstate.compound(FILTER, lambda);
  }

  /** {@code method at(Filter filter, x) = filter_ @ x ? x : Absent} */
  static class AtFilter extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(Filter, _)")
    static void begin(TState tstate, Value filter, @RC.In Value x) {
      tstate.startCall(at, filter.element(0), addRef(x)).saving(x);
    }

    @Continuation
    static Value afterAt(TState tstate, Value pass, @Saved Value x) throws BuiltinException {
      if (pass instanceof RValue) {
        return Condition.fromBoolean(pass).choose(x, Core.ABSENT);
      } else {
        return testBoolean(pass) ? addRef(x) : Core.ABSENT;
      }
    }
  }

  /** {@code default method withKeys(c) = c is Matrix ? WithKeysMatrix_(c) : WithKeys_(c)} */
  @Core.Method("withKeys(Collection) default")
  static Value withKeys(TState tstate, @RC.In Value c) {
    return c.isa(Core.MATRIX)
        .choose(() -> tstate.compound(WITH_KEYS_MATRIX, c), () -> tstate.compound(WITH_KEYS, c));
  }

  /**
   * <pre>
   * method at(WithKeys wk, key) {
   *   v = wk_ @ key
   *   return v is Absent ? Absent : [key, v]
   * }
   * </pre>
   */
  static class AtWithKeys extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(WithKeys, _)")
    static void begin(TState tstate, Value wk, @RC.In Value key) {
      tstate.startCall(at, wk.element(0), addRef(key)).saving(key);
    }

    @Continuation
    static Value afterAt(TState tstate, @RC.In Value v, @Saved @RC.In Value key) {
      return v.is(Core.ABSENT)
          .choose(
              () -> {
                tstate.dropValue(key);
                return Core.ABSENT;
              },
              () -> tstate.arrayValue(key, v));
    }
  }

  /**
   * <pre>
   * method element(WithKeysMatrix wk, Array key) {
   *   v = element(wk_, key)
   *   return v is Absent ? Absent : [key, v]
   * }
   * </pre>
   */
  static class ElementWithKeys extends BuiltinMethod {
    static final Caller element = new Caller("element:2", "afterElement");

    @Core.Method("element(WithKeysMatrix, Array)")
    static void begin(TState tstate, Value wk, @RC.In Value key) {
      tstate.startCall(element, wk.element(0), addRef(key)).saving(key);
    }

    @Continuation
    static Value afterElement(TState tstate, @RC.In Value v, @Saved @RC.In Value key) {
      return AtWithKeys.afterAt(tstate, v, key);
    }
  }

  /** {@code method keys(wk) (wk is WithKeys or wk is WithKeysMatrix) = keys(wk_)} */
  @Core.Method("keys(WithKeys|WithKeysMatrix)")
  static void keysWithKeys(TState tstate, Value wk, @Fn("keys:1") Caller keys) {
    tstate.startCall(keys, wk.element(0));
  }

  /**
   * <pre>
   * method iterator(wk, eKind) (wk is WithKeys or wk is WithKeysMatrix) {
   *   if eKind is EnumerateValues {
   *     return iterator(wk_, EnumerateWithKeys)
   *   }
   *   return WithKeysIterator_(iterator(wk_, eKind))
   * </pre>
   */
  static class IteratorWithKeys extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:2", "afterIterator");

    @Core.Method("iterator(WithKeys|WithKeysMatrix, EnumerationKind)")
    static void begin(TState tstate, Value tc, @RC.Singleton Value eKind) {
      Value eKind2 =
          eKind.is(LoopCore.ENUMERATE_VALUES).choose(LoopCore.ENUMERATE_WITH_KEYS, eKind);
      tstate.startCall(iterator, tc.element(0), eKind2).saving(eKind);
    }

    @Continuation
    static Value afterIterator(
        TState tstate, @RC.In Value innerIt, @Saved @RC.Singleton Value eKind) {
      return eKind
          .is(LoopCore.ENUMERATE_VALUES)
          .choose(() -> innerIt, () -> tstate.compound(WITH_KEYS_ITERATOR, innerIt));
    }
  }

  /**
   * <pre>
   * method next(WithKeysIterator it=) {
   *   result = next(it_=)
   *   if result is not Absent {
   *     [k, v] = result
   *     result = [k, result]
   *   }
   *   return result
   * }
   * </pre>
   */
  static class NextWithKeysIterator extends BuiltinMethod {
    static final Caller next = new Caller("next:1", "afterNext");

    @Core.Method("next(WithKeysIterator)")
    static void begin(TState tstate, Value it) {
      tstate.startCall(next, it.element(0));
    }

    @Continuation
    static void afterNext(TState tstate, @RC.In Value result, @RC.In Value innerIt)
        throws BuiltinException {
      Value withKey =
          result
              .is(Core.ABSENT)
              .chooseExcept(
                  () -> Core.ABSENT,
                  () -> {
                    Err.NOT_PAIR.unless(result.isArrayOfLength(2));
                    return tstate.arrayValue(result.element(0), result);
                  });
      tstate.setResults(withKey, tstate.compound(WITH_KEYS_ITERATOR, innerIt));
    }
  }

  /**
   * <pre>
   * method pipe(Lambda left, Lambda right) default {
   *   if left is not Collection {
   *     return TransformedLambda_({first: left, second: right})
   *   } else if left is TransformedCollection or left is TransformedMatrix {
   *     // Rather than nest TransformedCollection we compose the Lambdas.
   *     // There's no clear reason to prefer one or the other.
   *     right = left_.lambda | right
   *     left = left_.base
   *   }
   *   if left is Matrix {
   *     return TransformedMatrix_({base: left, lambda: right})
   *   } else {
   *     return TransformedCollection_({base: left, lambda: right})
   *   }
   * }
   * </pre>
   */
  @Core.Method("pipe(Lambda, Lambda) default")
  static Value pipeLambdaLambda(TState tstate, @RC.In Value left, @RC.In Value right) {
    return left.isa(Core.COLLECTION)
        .choose(
            () ->
                left.isa(TRANSFORMED_COLLECTION)
                    .or(left.isa(TRANSFORMED_MATRIX))
                    .choose(
                        () -> {
                          Value newLeft = left.element(0);
                          Value newRight =
                              tstate.compound(TRANSFORMED_LAMBDA, left.element(1), right);
                          tstate.dropValue(left);
                          return pipeCollectionLambda(tstate, newLeft, newRight);
                        },
                        () -> pipeCollectionLambda(tstate, left, right)),
            () -> tstate.compound(TRANSFORMED_LAMBDA, left, right));
  }

  @RC.Out
  private static Value pipeCollectionLambda(TState tstate, @RC.In Value left, @RC.In Value right) {
    return left.isa(Core.MATRIX)
        .choose(
            () -> tstate.compound(TRANSFORMED_MATRIX, left, right),
            () -> tstate.compound(TRANSFORMED_COLLECTION, left, right));
  }

  /**
   * <pre>
   * method at(TransformedLambda tLambda, x) {
   *   x = tLambda_.first @ x
   *   return x is Absent ? Absent : tLambda_.second @ x
   * }
   *
   * method at(tc, x) (tc is TransformedCollection) {
   *   x = tc_.base @ x
   *   return x is Absent ? Absent : tc_.lambda @ x
   * }
   * </pre>
   *
   * <p>Note that we use the same method for TransformedLambda and TransformedCollection; see
   * comment on TransformedCollection. Also note that this method is *not* used for
   * TransformedMatrix; that uses the default {@code at(Matrix, _)} method in MatrixCore to ensure
   * that ranges are handled properly.
   */
  static class AtTransformed extends BuiltinMethod {
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("at(TransformedLambda|TransformedCollection, _)")
    static void begin(TState tstate, Value tLambda, @RC.In Value x) {
      tstate.startCall(at, tLambda.element(0), x).saving(tLambda.element(1));
    }

    @Continuation
    static void afterAt(
        TState tstate, @RC.In Value x, @Saved Value second, @Fn("at:2") Caller at2) {
      x.is(Core.ABSENT)
          .test(
              () -> tstate.setResult(Core.ABSENT), () -> tstate.startCall(at2, addRef(second), x));
    }
  }

  /**
   * <pre>
   * method element(TransformedMatrix tm, Array x) {
   *   x = element(tm_.base, x)
   *   return x is Absent ? Absent : tm_.lambda @ x
   * }
   * </pre>
   */
  static class ElementTransformed extends BuiltinMethod {
    static final Caller element = new Caller("element:2", "afterElement");

    @Core.Method("element(TransformedMatrix, Array)")
    static void begin(TState tstate, Value tm, @RC.In Value x) {
      tstate.startCall(element, tm.element(0), x).saving(tm.element(1));
    }

    @Continuation
    static void afterElement(
        TState tstate, @RC.In Value x, @Saved Value lambda, @Fn("at:2") Caller at2) {
      AtTransformed.afterAt(tstate, x, lambda, at2);
    }
  }

  /**
   * <pre>
   * method keys(tc) (tc is TransformedCollection or tc is TransformedMatrix) = keys(tc_.base)
   * </pre>
   */
  @Core.Method("keys(TransformedCollection|TransformedMatrix)")
  static void keysTransformed(TState tstate, Value tc, @Fn("keys:1") Caller keys) {
    tstate.startCall(keys, tc.element(0));
  }

  /**
   * <pre>
   * method size(tc) (tc is TransformedCollection or tc is TransformedMatrix) = size(tc_.base)
   * </pre>
   */
  @Core.Method("size(TransformedCollection|TransformedMatrix)")
  static void sizeTransformed(TState tstate, Value tc, @Fn("size:1") Caller size) {
    tstate.startCall(size, tc.element(0));
  }

  /**
   * <pre>
   * method iterator(tc, eKind) (tc is TransformedCollection or tc is TransformedMatrix) =
   *     TransformedIterator_({it: iterator(tc_.base, eKind), eKind, lambda: tc_.lambda})
   * </pre>
   */
  static class IteratorTransformed extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:2", "afterIterator");

    @Core.Method("iterator(TransformedCollection|TransformedMatrix, EnumerationKind)")
    static void begin(TState tstate, Value tc, @RC.Singleton Value eKind) {
      tstate.startCall(iterator, tc.element(0), eKind).saving(eKind, tc.element(1));
    }

    @Continuation
    static Value afterIterator(
        TState tstate,
        @RC.In Value innerIt,
        @Saved @RC.Singleton Value eKind,
        @RC.In Value innerLambda) {
      return tstate.compound(TRANSFORMED_ITERATOR, innerIt, eKind, innerLambda);
    }
  }

  /**
   * <pre>
   * method next(TransformedIterator it=) {
   *   for sequential it {
   *     item = next(it_.it=)
   *     if item is Absent {
   *       break { return Absent }
   *     }
   *     if it_.eKind is not EnumerateValues {
   *       [key, item] = item
   *     }
   *     transformed = item is Absent ? Absent : it_.lambda @ item
   *     if transformed is not Absent or it_.eKind is EnumerateAllKeys
   *       break {
   *         return it_.eKind is EnumerateValues ? transformed : [key, transformed]
   *       }
   *     }
   *   }
   * }
   * </pre>
   */
  static class NextTransformedIterator extends BuiltinMethod {
    static final Caller next = new Caller("next:1", "afterNext");
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("next(TransformedIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
      Value innerIt = it.element(0);
      it = it.replaceElement(tstate, 0, Core.TO_BE_SET);
      tstate.jump("loop", innerIt, it);
    }

    @LoopContinuation
    static void loop(TState tstate, @RC.In Value innerIt, @RC.In Value it) {
      tstate.startCall(next, innerIt).saving(it);
    }

    @Continuation(order = 2)
    static void afterNext(
        TState tstate, @RC.In Value item, @RC.In Value innerIt, @Saved @RC.In Value it)
        throws BuiltinException {
      item.is(Core.ABSENT)
          .testExcept(
              () -> tstate.setResults(Core.ABSENT, it.replaceElement(tstate, 0, innerIt)),
              () -> {
                Value eKind = it.peekElement(1);
                eKind
                    .is(LoopCore.ENUMERATE_VALUES)
                    .testExcept(
                        () -> {
                          Value lambda = it.element(2);
                          // We won't use this value, but we have to put something there.
                          Value key = Core.UNDEF;
                          tstate.startCall(at, lambda, item).saving(key, innerIt, it);
                        },
                        () -> {
                          Err.NOT_PAIR.unless(item.isArrayOfLength(2));
                          Value key = item.element(0);
                          Value value = item.element(1);
                          tstate.dropValue(item);
                          value
                              .is(Core.ABSENT)
                              .test(
                                  () -> tstate.jump("afterAt", Core.ABSENT, key, innerIt, it),
                                  () -> {
                                    Value lambda = it.element(2);
                                    tstate.startCall(at, lambda, value).saving(key, innerIt, it);
                                  });
                        });
              });
    }

    @Continuation(order = 3)
    static void afterAt(
        TState tstate,
        @RC.In Value transformed,
        @Saved Value key,
        @RC.In Value innerIt,
        @RC.In Value it) {
      Value eKind = it.peekElement(1);
      transformed
          .is(Core.ABSENT)
          .and(eKind.is(LoopCore.ENUMERATE_ALL_KEYS).not())
          .test(
              () -> tstate.jump("loop", innerIt, it),
              () -> {
                Value result =
                    eKind
                        .is(LoopCore.ENUMERATE_VALUES)
                        .choose(
                            () -> transformed, () -> tstate.arrayValue(addRef(key), transformed));
                tstate.setResults(result, it.replaceElement(tstate, 0, innerIt));
              });
    }
  }

  /**
   * <pre>
   * method at(JoinedCollection c, key) {
   *   x1 = c_.c1 @ key
   *   x2 = c_.c2 @ key
   *   return (x1 is Absent and x2 is Absent) ? Absent : [x1, x2]
   * }
   * </pre>
   */
  static class AtJoinedCollection extends BuiltinMethod {
    static final Caller atC1 = new Caller("at:2", "afterAtC1");
    static final Caller atC2 = new Caller("at:2", "afterAtC2");

    @Core.Method("at(JoinedCollection, _)")
    static void begin(TState tstate, Value c, @RC.In Value key) {
      tstate.startCall(atC1, c.element(0), addRef(key)).saving(c.element(1), key);
    }

    @Continuation
    static void afterAtC1(
        TState tstate, @RC.In Value x1, @Saved @RC.In Value c2, @RC.In Value key) {
      tstate.startCall(atC2, c2, key).saving(x1);
    }

    @Continuation(order = 2)
    static Value afterAtC2(TState tstate, @RC.In Value x2, @Saved @RC.In Value x1) {
      return x1.is(Core.ABSENT)
          .and(x2.is(Core.ABSENT))
          .choose(() -> Core.ABSENT, () -> tstate.arrayValue(x1, x2));
    }
  }

  /**
   * <pre>
   * method element(JoinedMatrixc, key) {
   *   x1 = element(c_.c1, key)
   *   x2 = element(c_.c2, key)
   *   return (x1 is Absent and x2 is Absent) ? Absent : [x1, x2]
   * }
   * </pre>
   */
  static class ElementJoinedMatrix extends BuiltinMethod {
    static final Caller elementC1 = new Caller("element:2", "afterElementC1");
    static final Caller elementC2 = new Caller("element:2", "afterElementC2");

    @Core.Method("element(JoinedMatrix, _)")
    static void begin(TState tstate, Value c, @RC.In Value key) {
      tstate.startCall(elementC1, c.element(0), addRef(key)).saving(c.element(1), key);
    }

    @Continuation
    static void afterElementC1(
        TState tstate, @RC.In Value x1, @Saved @RC.In Value c2, @RC.In Value key) {
      tstate.startCall(elementC2, c2, key).saving(x1);
    }

    @Continuation(order = 2)
    static Value afterElementC2(TState tstate, @RC.In Value x2, @Saved @RC.In Value x1) {
      return x1.is(Core.ABSENT)
          .and(x2.is(Core.ABSENT))
          .choose(() -> Core.ABSENT, () -> tstate.arrayValue(x1, x2));
    }
  }

  /**
   * <pre>
   * method iterator(c, eKind) (c is JoinedCollection or c is JoinedMatrix) {
   *   return JoinedIterator_({it: iterator(c_.c1, EnumerateAllKeys), c2: c_.c2, eKind})
   * }
   * </pre>
   */
  static class IteratorJoinedCollection extends BuiltinMethod {
    static final Caller iterator = new Caller("iterator:2", "afterIterator");

    @Core.Method("iterator(JoinedCollection|JoinedMatrix, EnumerationKind)")
    static void begin(TState tstate, Value c, @RC.Singleton Value eKind) {
      tstate
          .startCall(iterator, c.element(0), LoopCore.ENUMERATE_ALL_KEYS)
          .saving(c.element(1), eKind);
    }

    @Continuation
    static Value afterIterator(
        TState tstate, @RC.In Value it, @Saved @RC.In Value c2, @RC.Singleton Value eKind) {
      return tstate.compound(JOINED_ITERATOR, it, c2, eKind);
    }
  }

  /**
   * <pre>
   * method next(JoinedIterator it=) {
   *   for sequential it {
   *     x1 = next(it_.it=)
   *     if x1 is Absent {
   *       break { return Absent }
   *     }
   *     [key, x1] = x1
   *     x2 = it_.c2 @ key
   *     if x1 is Absent and x2 is Absent {
   *       if it_.eKind is not EnumerateAllKeys {
   *         continue
   *       }
   *       x = Absent
   *     } else {
   *       x = [x1, x2]
   *     }
   *     return it_.eKind is EnumerateValues ? x : [key, x]
   *   }
   * }
   * </pre>
   */
  static class NextJoinedIterator extends BuiltinMethod {
    static final Caller next = new Caller("next:1", "afterNext");
    static final Caller at = new Caller("at:2", "afterAt");

    @Core.Method("next(JoinedIterator)")
    static void begin(TState tstate, @RC.In Value it) {
      // See docs/ref_counts.md#the-startupdate-idiom-for-compound-values
      Value innerIt = it.element(0);
      it = it.replaceElement(tstate, 0, Core.TO_BE_SET);
      tstate.jump("loop", innerIt, it);
    }

    @LoopContinuation
    static void loop(TState tstate, @RC.In Value innerIt, @RC.In Value it) {
      tstate.startCall(next, innerIt).saving(it);
    }

    @Continuation(order = 2)
    static void afterNext(TState tstate, Value x1, @RC.In Value innerIt, @Saved @RC.In Value it)
        throws BuiltinException {
      x1.is(Core.ABSENT)
          .testExcept(
              () -> tstate.setResults(Core.ABSENT, it.replaceElement(tstate, 0, innerIt)),
              () -> {
                Err.NOT_PAIR.unless(x1.isArrayOfLength(2));
                Value key = x1.element(0);
                Value innerX1 = x1.element(1);
                Value c2 = it.element(1);
                tstate.startCall(at, c2, addRef(key)).saving(innerIt, it, key, innerX1);
              });
    }

    @Continuation(order = 3)
    static void afterAt(
        TState tstate,
        @RC.In Value x2,
        @Saved @RC.In Value innerIt,
        @RC.In Value it,
        Value key,
        @RC.In Value x1) {
      Value eKind = it.peekElement(2);
      x1.is(Core.ABSENT)
          .and(x2.is(Core.ABSENT))
          .test(
              () ->
                  eKind
                      .is(LoopCore.ENUMERATE_ALL_KEYS)
                      .test(
                          () -> {
                            Value result = tstate.arrayValue(addRef(key), Core.ABSENT);
                            tstate.setResults(result, it.replaceElement(tstate, 0, innerIt));
                          },
                          () -> tstate.jump("loop", innerIt, it)),
              () -> {
                Value result1 = tstate.arrayValue(x1, x2);
                Value result2 =
                    eKind
                        .is(LoopCore.ENUMERATE_VALUES)
                        .choose(() -> result1, () -> tstate.arrayValue(addRef(key), result1));
                tstate.setResults(result2, it.replaceElement(tstate, 0, innerIt));
              });
    }
  }

  private CollectionCore() {}
}

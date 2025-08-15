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

package org.retrolang;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The Vm class is just a namespace for a bunch of interfaces; see virtual_machine.md for an
 * explanation of how they are used.
 */
public class Vm {

  // Just a namespace for the contained interfaces.
  private Vm() {}

  /**
   * A VirtualMachine instance is the starting point for all virtual machine operations; it provides
   * access to existing and new Modules, to simple numeric and string constant Exprs, and to the
   * core Compounds for constructing arrays and structs.
   */
  public interface VirtualMachine {

    /**
     * Returns the Core language module, which includes
     *
     * <ul>
     *   <li>standard singletons: False, True, None, Absent, ...
     *   <li>standard types: Number, Boolean, Array, String, Range, Lambda, ...
     *   <li>standard functions: at:2, size:1, sum:0, add:2, lessThan:2, ...
     * </ul>
     */
    Module core();

    /**
     * Creates a new module, into which user-defined code can be loaded. name may be null; anonymous
     * modules are used for one-time execution of a block of instructions.
     */
    ModuleBuilder newModule(String name);

    /** Returns an Expr for a String value. */
    Expr asExpr(String s);

    /** Returns an Expr for an Integer value. */
    Expr asExpr(int i);

    /** Returns an Expr for a Number value. */
    Expr asExpr(double d);

    /** Returns an Expr for a Number value. */
    default Expr asExpr(Number n) {
      if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
        return asExpr(n.intValue());
      } else {
        return asExpr(n.doubleValue());
      }
    }

    /** Returns a Compound representing an array with the given length. */
    Compound arrayOfSize(int size);

    /** Returns a Compound representing a struct with the given keys. The keys must be sorted. */
    Compound structWithKeys(String... keys);

    /**
     * Returns a ResourceTracker that sums the memory allocations and deallocations for one or more
     * computations.
     *
     * @param limit the maximum number of live bytes that may be allocated by this ResourceTracker
     * @param maxTraces the maximum number of execution stacks to save for each trace instruction
     * @param debug if true, enables additional logging and consistency checks
     */
    ResourceTracker newResourceTracker(long limit, int maxTraces, boolean debug);
  }

  /**
   * A Module provides access to the types and functions defined in a previously-compiled Retrospect
   * module.
   */
  public interface Module {
    /**
     * Returns the singleton in this module with the given name, or null if there is no such
     * singleton.
     */
    Singleton lookupSingleton(String name);

    /**
     * Returns the singleton, compound, or union type in this module with the given name, or null if
     * there is no such type.
     */
    Type lookupType(String name);

    /**
     * Returns the function in this module with the given name and numArgs, or null if there is no
     * such function.
     */
    Function lookupFunction(String name, int numArgs);
  }

  /**
   * An Access determines to what extent a newly-defined type or function will be accessible outside
   * the module in which it is being defined.
   */
  public enum Access {
    /** A PRIVATE type or function may not be referred to from other modules. */
    PRIVATE,

    /**
     * A VISIBLE type or function may be referred to from other modules, but may not be extended by
     * them.
     */
    VISIBLE,

    /**
     * An OPEN type or function may be referred to from other modules, and they may add their own
     * subtypes of it (for union types) or methods (for functions). Singletons and Compounds may not
     * be OPEN (since they are not extensible).
     */
    OPEN
  }

  /**
   * A ModuleBuilder provides the context for creating new types and functions, and for adding
   * methods to previously-defined functions.
   */
  public interface ModuleBuilder {

    /**
     * Returns a new singleton with the given super types, each of which must be a union type
     * defined in this module or an open union type defined in another module.
     *
     * <p>{@code access} must be PRIVATE or VISIBLE; if it is VISIBLE there must not already be a
     * non-PRIVATE singleton, compound, or union type in this module with the given name.
     */
    Singleton newSingleton(String name, Access access, Type... supers);

    /**
     * Returns a new compound type with the given super types, each of which must be a union type
     * defined in this module or an open union type defined in another module.
     *
     * <p>If {@code elementNames} is null the compound will have a single element; these are used to
     * implement Retrospect compound types. If {@code elementNames} is non-null the size of the
     * compound is determined by the length of {@code elementNames}, which must be greater than
     * zero; these are used by compiler to implement implicit compound types such as those of
     * lambdas, loop bodies, and loop states.
     *
     * <p>{@code access} must be PRIVATE or VISIBLE; if it is VISIBLE there must not already be a
     * non-PRIVATE singleton, compound, or union type in this module with the given name.
     */
    Compound newCompoundType(String name, String[] elementNames, Access access, Type... supers);

    /**
     * Returns a new union type with the given super types, each of which must be a union type
     * defined in this module or an open union type defined in another module.
     *
     * <p>If {@code access} is VISIBLE or OPEN there must not already be a non-PRIVATE singleton,
     * compound, or union type in this module with the given name.
     *
     * <p>If {@code access} is OPEN, other modules may declare singletons, compounds, or unions to
     * be subtytpes of this type.
     */
    Type newUnionType(String name, Access access, Type... supers);

    /**
     * Returns a new union type containing the given types, which may be unions, singletons, or
     * compounds, from this module or another (as long as they are at least VISIBLE). None of the
     * subtypes may be Core.Integer.
     *
     * <p>If {@code access} is VISIBLE or OPEN there must not already be a non-PRIVATE singleton,
     * compound, or union type in this module with the given name.
     *
     * <p>If {@code access} is OPEN, other modules may declare singletons, compounds, or unions to
     * be subtytpes of this type.
     */
    Type newTypeFromUnion(String name, Access access, Type... subtypes);

    /**
     * Returns a new function with the given {@code name} and {@code numArgs}. {@code argIsInout}
     * will be evaluated for each argument index in {@code 0..(numArgs-1)}. If {@code hasResult} is
     * true the function returns a result in addition to any inout args.
     *
     * <p>If {@code access} is VISIBLE or OPEN there must not already be a non-PRIVATE function in
     * this module with the given name and numArgs.
     *
     * <p>If {@code access} is OPEN, other modules may declare methods for this function.
     */
    Function newFunction(
        String name, int numArgs, IntPredicate argIsInout, boolean hasResult, Access access);

    /**
     * Returns a new InstructionBlock with the given {@code numArgs} and {@code numResults} ({@code
     * numResults} should include any inout arguments). {@code source} can be null, a file name, or
     * any other identifier that is useful to show in stack dumps.
     *
     * <p>After creating an InstructionBlock and emitting an appropriate sequence of instructions to
     * it, the caller is expected to call {@link InstructionBlock#done} and then either add the
     * InstructionBlock as a method of an existing function ({@link InstructionBlock#addMethod}) or
     * just execute it given values for its args ({@link InstructionBlock#applyToArgs}).
     */
    InstructionBlock newInstructionBlock(int numArgs, int numResults, Object source);

    /**
     * Implements the "_" suffix operator.
     *
     * <p>Returns a function of one argument, which must be an instance of one of this Module's
     * compounds (with null {@code elementNames}, and hence a single element). The function's result
     * is the value that was passed to compound.make() to construct the argument.
     */
    Function unCompound();

    /**
     * Implements the "_" suffix operator when used on the left-hand side of an "=" (analogous to
     * the {@code startUpdate:2} function).
     *
     * <p>Returns a function of one argument, which must be an instance of one of this Module's
     * compounds (with null {@code elementNames}, and hence a single element). The function has two
     * results: the value that was passed to compound.make() to construct the argument, and a lambda
     * that will make() a new value of that type.
     */
    Function updateCompound();

    /**
     * Indicates that this ModuleBuilder is complete.
     *
     * <p>Before calling {@code build()}, you may not
     *
     * <ul>
     *   <li>use it to create any Values (e.g. by calling {@link Singleton#asValue} on the result of
     *       {@link #newSingleton} or {@link Compound#make(ResourceTracker,Value...)} on the result
     *       of {@link #newCompoundType}); or
     *   <li>use a Singleton, CompoundType, Type, or Function created by this ModuleBuilder in the
     *       InstructionBlock of any other ModuleBuilder.
     * </ul>
     *
     * <p>After calling {@code build()}, you may not
     *
     * <ul>
     *   <li>call any of the {@code new*} methods except {@link #newInstructionBlock}; or
     *   <li>call {@link InstructionBlock#addMethod} on any InstructionBlock created by this
     *       ModuleBuilder.
     * </ul>
     */
    void build();
  }

  /**
   * Exprs are used to specify the operands of many instructions.
   *
   * <p>Expr is a superclass that includes {@link Local}s, the constant expressions returned by
   * {@link VirtualMachine#asExpr}, {@link Singleton}s, and the compound expressions returned by
   * {@link Compound#make}. Constant expressions (those that do not contain Locals, directly or
   * indirectly) represent a single Value; the value of a non-constant expression depends on the
   * values of the Locals.
   */
  public interface Expr {}

  /**
   * A Value represents a Retrospect language value.
   *
   * <p>Values may be obtained
   *
   * <ul>
   *   <li>by calling {@link Singleton#asValue};
   *   <li>by calling one of the {@link ResourceTracker#asValue} methods;
   *   <li>by calling {@link Compound#make} (passing a ResourceTracker); or
   *   <li>by executing an InstructionBlock with {@link InstructionBlock#applyToArgs}.
   * </ul>
   *
   * <p>Values may be reference-counted, and must be handled carefully to ensure that the associated
   * memory is properly tracked. When a Value is returned from any method other than {@link
   * Singleton#asValue} the caller must do one of three things:
   *
   * <ul>
   *   <li>call {@link #close}, and then never use it again;
   *   <li>pass it as an argument once in a subsequent call to {@link Compound#make} or {@link
   *       InstructionBlock#applyToArgs}, and then never use it again; or
   *   <li>call {@link #keep}, pass it any number of times to {@link Compound#make} or {@link
   *       InstructionBlock#applyToArgs}, and then call {@link #close} and never use it again.
   * </ul>
   *
   * <p>(Since Values are AutoCloseable, a try-with-resources is often the most convenient way to
   * ensure that {@link #close} is called if you're using the first or third option.)
   */
  public interface Value extends AutoCloseable {
    /**
     * Must be called at least once on each Value unless
     *
     * <ul>
     *   <li>the Value was returned by {@link Singleton#asValue}, or
     *   <li>the Value is passed as argument to {@link Compound#make} or {@link
     *       InstructionBlock#applyToArgs}, without a preceding call to {@link #keep}.
     * </ul>
     *
     * <p>Has no effect if called more than once, or if called on a Value returned by {@link
     * Singleton#asValue}.
     */
    @Override
    void close();

    /**
     * Disables the automatic close done by {@link Compound#make} or {@link
     * InstructionBlock#applyToArgs}; a call to {@code keep()} must eventually be followed by a call
     * to close() on this Value.
     *
     * <p>Has no effect if called more than once.
     */
    Value keep();
  }

  /** A Type represents a Retrospect language type. */
  public interface Type {
    /**
     * Returns a MethodPredicate which is true if the specified argument is ({@code positive}=true)
     * or is not ({@code positive}=false) a value of this type. Not permitted if this is the type
     * Core.Integer.
     */
    MethodPredicate argType(int argIndex, boolean positive);

    /**
     * Returns a lambda value with a single argument, that returns True if the value is of this type
     * and False otherwise.
     */
    Expr testLambda();
  }

  /**
   * A Singleton represents a Retrospect language singleton, which can be used as either a value or
   * a type.
   */
  public interface Singleton extends Expr {
    /** Returns the Type corresponding to this Singleton. */
    Type asType();

    /**
     * Returns a Value corresponding to this Singleton.
     *
     * <p>The returned Value's close() and keep() methods do nothing.
     */
    Value asValue();
  }

  /** A Function represents a Retrospect language function. */
  public interface Function {
    /** Returns the number of arguments that must be passed when calling this function. */
    int numArgs();

    /**
     * Returns true if the specified argument is inout, i.e. if each call to the function will
     * return a new value for that argument.
     */
    boolean argIsInout(int i);

    /**
     * Returns the total number of results of this function.
     *
     * <ul>
     *   <li>If this Function was declared in Retrospect as a procedure, {@code numResults()} will
     *       be the number of inout arguments.
     *   <li>If this Function was declared in Retrospect as a function, {@code numResults()} will be
     *       one more than the number of inout arguments.
     *   <li>If this Function was created by some other means, {@code numResults()} is constrained
     *       only to be greater than or equal to the number of inout arguments.
     * </ul>
     */
    int numResults();

    /**
     * Returns a lambda value that calls this function.
     *
     * <p>Returns null if this function does not correspond to a lambda, either because it has inout
     * arguments (i.e. {@code argIsInout(i)} is true for any {@code i}) or because {@code
     * numResults()} is not 1.
     *
     * <p>If {@code numArgs()} is zero, the lambda ignores its argument. If {@code numArgs()} is
     * one, the lambda argument will be used as the function argument. If {@code numArgs()} is more
     * than one, the lambda argument must be an array of length {@code numArgs()}.
     */
    Expr asLambdaExpr();
  }

  /**
   * A Compound may represent a Retrospect language compound type (which always has a single,
   * unnamed element), or a compound created implicitly by the compiler (which has a fixed number of
   * named elements). The number of elements is referred to as the size of the Compound.
   *
   * <p>For simplicity arrayOfSize(0) and structWithKeys() return Compounds with size 0; the values
   * constructed by those Compounds are singletons.
   */
  public interface Compound {
    /**
     * Returns the type corresponding to this Compound. Only valid if this Compound was returned
     * from {@link ModuleBuilder#newCompoundType}.
     *
     * <p>Throws an exception if this Compound was returned from {@link VirtualMachine#arrayOfSize}
     * or {@link VirtualMachine#structWithKeys}.
     */
    Type asType();

    /**
     * Returns a Function with one (non-inout) arg and {@code n} results, where {@code n} is the
     * size of this Compound. When the returned Function is called with a value of this Compound, it
     * will return its elements; when called with any other value it will error.
     */
    Function extract();

    /**
     * Returns an Expr for an instance of this Compound with the given elements; the number of
     * elements must match the size of this Compound.
     */
    Expr make(Expr... elements);

    /**
     * Returns a value of this Compound with the given elements; the number of elements must match
     * the size of this Compound.
     *
     * <p>The Values passed as elements must have been allocated by the given ResourceTracker, or
     * have been returned by {@link Singleton#asValue}.
     */
    Value make(ResourceTracker allocator, Value... elements);

    /**
     * Returns a lambda value that returns instances of this Compound.
     *
     * <p>If this Compound's size is one, the lambda's argument will be used as the element.
     * Otherwise the lambda argument must be an array of elements, with length matching this
     * Compound's size.
     */
    Expr asLambdaExpr();
  }

  /**
   * A MethodPredicate is used to restrict the arguments to which a method is applicable.
   *
   * <p>A simple MethodPredicate (constraining the type of one argument) is created by calling
   * {@link Type#argType}; MethodPredicates can be combined into more complex MethodPredicates with
   * {@link #and} and {@link #or}.
   */
  public interface MethodPredicate {
    /** Returns a MethodPredicate that is true if either this or {@code other} is true. */
    MethodPredicate or(MethodPredicate other);

    /** Returns a MethodPredicate that is true if both this and {@code other} are true. */
    MethodPredicate and(MethodPredicate other);
  }

  /**
   * An InstructionBlock is used to construct the sequence of instructions that defines a method, a
   * lambda value, or a loop body, or that specifies a computation to be performed.
   *
   * <p>The sequence of operations is
   *
   * <ul>
   *   <li>Create a new InstructionBlock with {@link ModuleBuilder#newInstructionBlock}.
   *   <li>Call {@link #addArg} once for each argument (in order).
   *   <li>Call {@link #newLocal}, {@link #newTarget}, and {@code emit*} as needed to define the
   *       desired behavior; optionally include calls to {@link #setLineNumber} to provide more
   *       useful information in stack dumps.
   *   <li>Call {@link #done}.
   *   <li>Either call {@link #addMethod} once, or call {@link #applyToArgs} one or more times.
   * </ul>
   */
  public interface InstructionBlock {
    /**
     * Must be called once for each of this InstructionBlock's arguments, before calling any other
     * methods. If {@code name} is null the next argument is unused, and the call returns null;
     * otherwise it returns a new Local that will be initialized with the value of the next
     * argument.
     */
    @CanIgnoreReturnValue
    Local addArg(String name);

    /**
     * Returns a new, uninitialized Local. A runtime error will be thrown if this local is used
     * before it is set.
     */
    Local newLocal(String name);

    /**
     * Returns a new BranchTarget, which can be used as the target of zero or more branch
     * instructions. If it is used as a target it must be defined with {@link #defineTarget} before
     * calling {@link #done}. Once defined a BranchTarget cannot be used by following instructions,
     * i.e. only forward branches are allowed.
     */
    BranchTarget newTarget();

    /**
     * Returns true unless the most recently emitted instruction is a terminal (i.e. an
     * unconditional branch, continue, return, or error), and it has not been followed by a {@link
     * #defineTarget} for a BranchTarget used in at least one branch instruction.
     *
     * <p>If {@code nextInstructionIsReachable()} would return false all {@code emit*} methods are
     * no-ops.
     */
    boolean nextInstructionIsReachable();

    /**
     * Any subsequently emitted instructions will be labelled with the given line number until the
     * next call to {@code setLineNumber}.
     */
    void setLineNumber(int lineNum, int charPositionInLine);

    /**
     * Previously-emitted branches to this target will continue execution at the next instruction
     * emitted. Each BranchTarget used as the target of a branch instruction must be defined exactly
     * once, after all branches that target it have been emitted.
     */
    void defineTarget(BranchTarget target);

    /** Emits an instruction that sets the given Local to the value of the given Expr. */
    void emitSet(Local output, Expr input);

    /**
     * Emits an instruction that calls the given Function with the values of the given Exprs as its
     * arguments; the number of inputs must match {@code fn.numArgs()}. {@code outputs.length} must
     * match {@code fn.numResults()}, and the output Locals will be set to the function results when
     * it returns. Elements of {@code outputs} may be null, in which case the corresponding function
     * result is discarded.
     */
    void emitCall(Local[] outputs, Function fn, Expr... inputs);

    /**
     * Emits an instruction that calls the given Function with the values of the given Exprs as its
     * arguments; the number of inputs must match {@code fn.numArgs()}. The Function must return a
     * single result, which will be stored in the given Local (or discarded if output is null).
     */
    default void emitCall(Local output, Function fn, Expr... inputs) {
      emitCall(new Local[] {output}, fn, inputs);
    }

    /**
     * Emits an instruction that returns from this InstructionBlock; the number of inputs must match
     * the value of {@code numResults} passed when creating this InstructionBlock.
     */
    void emitReturn(Expr... input);

    /**
     * Emits an instruction that terminates execution of the current computation. If {@code cause}
     * is non-null it will be included in the stack dump. The stack dump will include the values of
     * the given Locals, as well as all Locals that would be live if execution continued at the next
     * instruction.
     */
    void emitError(String cause, Local... include);

    /**
     * Emits an instruction that saves a snapshot of the current state of execution and then
     * continues execution. The snapshot will include the values of the given Locals, as well as all
     * live Locals.
     */
    void emitTrace(String message, Local... include);

    /** Emits an instruction that continues execution after the given BranchTarget. */
    void emitBranch(BranchTarget target);

    /**
     * Emits an instruction that tests the value of the given Local and
     *
     * <ul>
     *   <li>errors if its value is neither True nor False;
     *   <li>continues execution after the given BranchTarget if its value is True ({@code
     *       branchIfTrue}=true) or False ({@code branchIfTrue}=false);
     *   <li>continues execution with the next instruction otherwise.
     * </ul>
     */
    void emitConditionalBranch(BranchTarget target, Local input, boolean branchIfTrue);

    /**
     * Emits an instruction that tests the value of the given Local and continues execution after
     * the given BranchTarget if the value is ({@code branchIfContains}=true) or is not ({@code
     * branchIfContains}=false) of the given Type. If the branch is not taken, execution continues
     * with the next instruction.
     */
    void emitTypeCheckBranch(BranchTarget target, Local input, Type type, boolean branchIfContains);

    /**
     * Must be called once on each InstructionBlock after the desired instructions have been emitted
     * and after all used BranchTargets have been defined. May only be called when {@link
     * #nextInstructionIsReachable} would return false.
     */
    void done();

    /**
     * Defines a new method for the given Function, which must either be OPEN or have been created
     * in the same module as this InstructionBlock. If {@code predicate} is non-null it restricts
     * the arguments to which this method will be applied; if it is null the method is applicable to
     * all arguments.
     *
     * <p>If {@code isDefault} is true, this method is only applicable if no preferred (non-default)
     * method is applicable.
     *
     * <p>The values passed for {@code numArgs} and {@code numResults} when creating this
     * InstructionBlock must match the given Function.
     */
    void addMethod(Function fn, MethodPredicate predicate, boolean isDefault);

    /**
     * Executes this InstructionBlock with the given arguments; returns its result or throws a
     * RuntimeError.
     *
     * <p>The number of args given must match the value passed for {@code numArgs} when creating
     * this InstructionBlock. This InstructionBlock must have been created with {@code
     * numResults}=1.
     */
    Value applyToArgs(ResourceTracker tracker, Value... args) throws RuntimeError;
  }

  /**
   * A RuntimeError is thrown by {@link InstructionBlock#applyToArgs} if an error occurs while
   * executing the instructions. The caller should catch the RuntimeError and {@link #close} it when
   * done, to ensure that reference counts are properly updated.
   */
  public abstract static class RuntimeError extends Exception implements AutoCloseable {
    protected RuntimeError(String msg, Throwable cause) {
      super(msg, cause);
    }

    /** Must be called at least once on each RuntimeError. Once closed, stack() will return null. */
    @Override
    public abstract void close();

    /**
     * Returns a sequence of Strings describing the execution state when the error occurred.
     *
     * <ul>
     *   <li>The first string describes the instruction where the error occurred;
     *   <li>the second string describes the call instruction for the method containing the
     *       instruction where the error occurred;
     *   <li>... and so on up to ...
     *   <li>the final string describes an instruction in the InstructionBlock on which {@link
     *       InstructionBlock#applyToArgs} was called;
     * </ul>
     */
    public abstract List<String> stack();
  }

  /**
   * A Local is used when constructing an InstructionBlock to represent the value of a Retrospect
   * variable or an intermediate expression.
   */
  public interface Local extends Expr {}

  /**
   * A BranchTarget is used when constructing an InstructionBlock to represent a not-yet-emitted
   * instruction that may be branched to.
   */
  public interface BranchTarget {}

  /**
   * A ResourceTracker totals the memory allocations and deallocations for one or more computations,
   * and interrupts a computation if the total memory use exceeds a specified limit.
   */
  public interface ResourceTracker {

    /** Returns a Value representing the given string. */
    Value asValue(String s);

    /** Returns a Value representing the given number. */
    Value asValue(int i);

    /** Returns a Value representing the given number. */
    Value asValue(double d);

    /** Returns a Value representing the given number. */
    default Value asValue(Number n) {
      if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
        return asValue(n.intValue());
      } else {
        return asValue(n.doubleValue());
      }
    }

    /** Returns true if all Values allocated by this ResourceTracker have been released. */
    boolean allReleased();

    /**
     * Returns a chronologically-ordered description of any trace statements that have been executed
     * by computations using this ResourceTracker since the last call to {@code takeTraces()}.
     */
    String takeTraces();
  }
}

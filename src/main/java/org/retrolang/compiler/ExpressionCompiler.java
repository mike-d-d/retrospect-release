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

package org.retrolang.compiler;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.Nullable;
import org.retrolang.Vm;
import org.retrolang.Vm.BranchTarget;
import org.retrolang.Vm.Compound;
import org.retrolang.Vm.Expr;
import org.retrolang.Vm.InstructionBlock;
import org.retrolang.Vm.Local;
import org.retrolang.Vm.VirtualMachine;
import org.retrolang.compiler.RetrospectParser.ArgContext;
import org.retrolang.compiler.RetrospectParser.ArrayElementContext;
import org.retrolang.compiler.RetrospectParser.ArrayExpressionContext;
import org.retrolang.compiler.RetrospectParser.ConjExpressionContext;
import org.retrolang.compiler.RetrospectParser.ExpressionContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallContext;
import org.retrolang.compiler.RetrospectParser.FunctionCallExpressionContext;
import org.retrolang.compiler.RetrospectParser.FunctionNameContext;
import org.retrolang.compiler.RetrospectParser.HashExpressionContext;
import org.retrolang.compiler.RetrospectParser.IdRefExpressionContext;
import org.retrolang.compiler.RetrospectParser.IndexContext;
import org.retrolang.compiler.RetrospectParser.IndexExpressionContext;
import org.retrolang.compiler.RetrospectParser.LambdaExpressionContext;
import org.retrolang.compiler.RetrospectParser.LowerIdContext;
import org.retrolang.compiler.RetrospectParser.NumberExpressionContext;
import org.retrolang.compiler.RetrospectParser.OpExpressionContext;
import org.retrolang.compiler.RetrospectParser.ParamAnnotationContext;
import org.retrolang.compiler.RetrospectParser.ParenExpressionContext;
import org.retrolang.compiler.RetrospectParser.StringExpressionContext;
import org.retrolang.compiler.RetrospectParser.StructElementContext;
import org.retrolang.compiler.RetrospectParser.StructExpressionContext;
import org.retrolang.compiler.RetrospectParser.TernaryExpressionContext;
import org.retrolang.compiler.RetrospectParser.TypeCheckExpressionContext;
import org.retrolang.compiler.RetrospectParser.TypeNameContext;
import org.retrolang.compiler.RetrospectParser.TypeNameExpressionContext;
import org.retrolang.util.Bits;
import org.retrolang.util.Pair;
import org.retrolang.util.StringUtil;

/**
 * Emits instructions to compute the value of a Retrospect expression.
 *
 * <p>Instances of the base class are used to implement BlockCompiler.compile(); visiting an
 * expression returns an Expr with its value, after emitting instructions as necessary. For example,
 * visiting "{@code x}" will just return the Local corresponding to "{@code x}", while visiting
 * "{@code x+1}" will emit code to call {@code add(x, 1)} and store the result in a temp, then
 * return the temp.
 */
class ExpressionCompiler extends VisitorBase<Expr> {
  final BlockCompiler blockCompiler;

  ExpressionCompiler(BlockCompiler blockCompiler) {
    this.blockCompiler = blockCompiler;
  }

  private VirtualMachine vm() {
    return blockCompiler.symbols.vm;
  }

  private Vm.Module vmCore() {
    return blockCompiler.symbols.vmCore;
  }

  private Expr makeArray(Expr... elements) {
    return vm().arrayOfSize(elements.length).make(elements);
  }

  /** If we need to store the result in a Local, use this one. */
  Local outputLocal() {
    return blockCompiler.newTmp();
  }

  /**
   * To compile e.g. "{@code a != 0}", we emit a call to "{@code equal(a, 0)}" and then call {@code
   * booleanNegate()} on the result. The default implementation calls "{@code not()}" on the result,
   * but Tester overrides this to just invert the branch.
   */
  Local booleanNegate(Local x) {
    // We can do the negate in place, since our argument is always the result of
    // an immediately preceding call instruction.
    blockCompiler.ib.emitCall(x, blockCompiler.symbols.vmNot, x);
    return x;
  }

  /** Applies the given IntFunction to each int in 0 .. n-1 and returns the results as an array. */
  private Expr[] toArray(IntFunction<Expr> intFunc, int n) {
    return IntStream.range(0, n).mapToObj(intFunc).toArray(Expr[]::new);
  }

  /** Compiles a function call, given a function to compile each arg. */
  Local compileFnCall(Vm.Function fn, IntFunction<Expr> args) {
    int tmpState = blockCompiler.saveTmpState();
    Expr[] compiledArgs = toArray(args, fn.numArgs());
    blockCompiler.resetTmps(tmpState);
    return fnCall(fn, compiledArgs);
  }

  /**
   * Emits a function call, given an Expr for each arg. Returns an Expr for the result, or null if
   * the function has no outputs.
   */
  @Nullable Local fnCall(Vm.Function fn, Expr... args) {
    assert fn.numArgs() == args.length;
    Local[] outputs;
    if (fn.numResults() == 1) {
      outputs = new Local[] {outputLocal()};
    } else {
      assert fn.numResults() == 0;
      outputs = new Local[0];
    }
    blockCompiler.ib.emitCall(outputs, fn, args);
    return (outputs.length == 1) ? outputs[0] : null;
  }

  @Override
  public Expr visitNumberExpression(NumberExpressionContext ctx) {
    return compileNumber(ctx, false);
  }

  /** Converts a numeric literal to a constant Expr. */
  private Expr compileNumber(NumberExpressionContext ctx, boolean negate) {
    String s = ctx.getText();
    if (s.chars().noneMatch(i -> i == '.' || i == 'e' || i == 'E')) {
      // It looks like an integer, so try to parse it as one.
      if (s.equals("0")) {
        return vm().asExpr(0);
      } else if (s.startsWith("0")) {
        // Maybe they're expecting this to mean octal?
        throw Compiler.error(ctx.start, "Integer constant may not start with '0'");
      } else if (negate && s.equals("2147483648")) {
        // -2147483648 is a valid integer, but 2147483648 isn't, so we have to handle
        // it specially
        return vm().asExpr(Integer.MIN_VALUE);
      }
      try {
        int i = Integer.parseInt(s);
        return vm().asExpr(negate ? -i : i);
      } catch (NumberFormatException e) {
        // If it's too big to represent as an integer, fall through and parse it as a double.
      }
    }
    double d;
    try {
      d = Double.parseDouble(s);
    } catch (NumberFormatException e) {
      // Not sure if this can happen?
      throw error("Can't parse number");
    }
    return vm().asExpr(negate ? -d : d);
  }

  @Override
  public Expr visitStringExpression(StringExpressionContext ctx) {
    return vm().asExpr(StringUtil.unescape(ctx.getText()));
  }

  @Override
  public Expr visitArrayExpression(ArrayExpressionContext ctx) {
    List<ArrayElementContext> entries = ctx.arrayElement();
    return compileExpressions(entries, BlockCompiler.checkDistributed(entries));
  }

  @Override
  public Expr visitStructExpression(StructExpressionContext ctx) {
    List<StructElementContext> entries = ctx.structElement();
    int n = (entries == null) ? 0 : entries.size();
    if (n == 0) {
      return vm().structWithKeys().make();
    }
    // Sort the entries by key.
    List<Pair<String, StructElementContext>> pairs = new ArrayList<>(n);
    for (StructElementContext entry : entries) {
      pairs.add(new Pair<>(Compiler.getKey(entry), entry));
    }
    pairs.sort(Comparator.comparing(p -> p.x));
    Compound dict = vm().structWithKeys(pairs.stream().map(p -> p.x).toArray(String[]::new));
    // Are any of the subexpressions preceded by "^" ?
    Bits distribute = Bits.fromPredicate(n - 1, i -> pairs.get(i).y.dist != null);
    // An IntFunction to get the value of the i'th element.
    IntFunction<Expr> elements = i -> compileStructElement(pairs.get(i).y);
    if (distribute.isEmpty()) {
      return dict.make(toArray(elements, n));
    } else {
      return compileDistributed(dict.asLambdaExpr(), distribute, elements, n);
    }
  }

  private Expr compileStructElement(StructElementContext element) {
    ExpressionContext expr = element.expression();
    if (expr != null) {
      return blockCompiler.compile(expr);
    } else {
      return element.lowerId().entry.local;
    }
  }

  @Override
  public Expr visitOpExpression(OpExpressionContext ctx) {
    Token op = ctx.op;
    boolean lDistribute = (ctx.ldist != null);
    boolean rDistribute = (ctx.rdist != null);
    int opType = op.getType();
    // Range construction ("..") always calls a binary function, even if one of the arguments is
    // omitted, so "-" and "not" are the only two unary operations we need to handle.
    if (ctx.left == null && (opType == TokenType.MINUS || opType == TokenType.KEYWORD_NOT)) {
      if (opType == TokenType.MINUS && ctx.right instanceof NumberExpressionContext) {
        if (rDistribute) {
          throw Compiler.cannotDistribute(ctx.start);
        }
        // negative numeric constant (e.g. "-3") ends up here
        return compileNumber((NumberExpressionContext) ctx.right, true);
      } else if (opType == TokenType.KEYWORD_NOT && !rDistribute) {
        // If it's e.g. "not (x and y)" we're going to use booleanOutput() for the subexpression
        // anyway, so we might as well use it for the whole expression and skip the call to not().
        ExpressionContext right = ctx.right;
        while (right instanceof ParenExpressionContext) {
          right = ((ParenExpressionContext) right).expression();
        }
        if (right instanceof ConjExpressionContext) {
          return booleanOutput(ctx);
        }
      }
      int tmpState = blockCompiler.saveTmpState();
      Expr right = blockCompiler.compile(ctx.right);
      blockCompiler.resetTmps(tmpState);
      Vm.Function fn =
          (opType == TokenType.MINUS)
              ? blockCompiler.symbols.vmCore.lookupFunction("negative", 1)
              : blockCompiler.symbols.vmNot;
      if (rDistribute) {
        // e.g. "not ^x" is equivalent to "x | -> not #"
        return fnCall(blockCompiler.symbols.vmPipe, right, fn.asLambdaExpr());
      } else {
        return fnCall(fn, right);
      }
    }
    // Most operators are directly compiled as a call to the corresponding function, but a few
    // swap the arguments and/or invert the function result.
    String fnName;
    boolean invert;
    int leftArgNum;
    if (opType == TokenType.NOT_EQUALS) {
      fnName = "equal";
      invert = true;
      leftArgNum = 0;
    } else if (opType == TokenType.GREATER_THAN) {
      fnName = "lessThan";
      invert = false;
      leftArgNum = 1;
    } else if (opType == TokenType.GREATER_THAN_OR_EQUALS) {
      fnName = "lessThan";
      invert = true;
      leftArgNum = 0;
    } else if (opType == TokenType.LESS_THAN_OR_EQUALS) {
      fnName = "lessThan";
      invert = true;
      leftArgNum = 1;
    } else {
      fnName = Symbols.FUNCTION_NAME_FROM_BINOP_TOKEN.get(opType);
      assert fnName != null;
      invert = false;
      leftArgNum = 0;
    }
    Vm.Function fn = vmCore().lookupFunction(fnName, 2);
    IntFunction<Expr> args =
        i -> {
          ExpressionContext expr = (i == leftArgNum) ? ctx.left : ctx.right;
          return (expr == null) ? blockCompiler.symbols.vmNone : blockCompiler.compile(expr);
        };
    Bits distribute = Bits.fromPredicate(1, i -> (i == leftArgNum) ? lDistribute : rDistribute);
    if (distribute.isEmpty()) {
      Local result = compileFnCall(fn, args);
      return invert ? booleanNegate(result) : result;
    } else {
      Local result = compileDistributed(fn.asLambdaExpr(), distribute, args, 2);
      if (invert) {
        // e.g. "x ^!= 3" will compile to "x | -> # == 3 | -> not #"
        // We could instead add a builtin like
        //    function lambdaNot(lambda) = -> not (lambda @ #)
        // and distribute the result of that instead, but I don't think there would be any
        // performance benefit to doing so.
        blockCompiler.ib.emitCall(
            result,
            blockCompiler.symbols.vmPipe,
            result,
            blockCompiler.symbols.vmNot.asLambdaExpr());
      }
      return result;
    }
  }

  @Override
  public Expr visitTernaryExpression(TernaryExpressionContext ctx) {
    Local tmp = blockCompiler.newTmp();
    new Setter(blockCompiler, tmp).compileTernary(ctx);
    return tmp;
  }

  @Override
  public Expr visitConjExpression(ConjExpressionContext ctx) {
    return booleanOutput(ctx);
  }

  @Override
  public Expr visitTypeCheckExpression(TypeCheckExpressionContext ctx) {
    if (ctx.dist == null) {
      return booleanOutput(ctx);
    }
    // A distributed type check (e.g. "a ^is Number") pipes the argument through the type's
    // testLambda (and then through not() if the test was inverted; see the comment at the end
    // of visitOpExpression().
    Expr testLambda = blockCompiler.symbols.getType(ctx.typeName()).testLambda();
    int tmpState = blockCompiler.saveTmpState();
    Expr expr = blockCompiler.compile(ctx.expression());
    blockCompiler.resetTmps(tmpState);
    Local result = fnCall(blockCompiler.symbols.vmPipe, expr, testLambda);
    if (ctx.negation != null) {
      blockCompiler.ib.emitCall(
          result, blockCompiler.symbols.vmPipe, result, blockCompiler.symbols.vmNot.asLambdaExpr());
    }
    return result;
  }

  /**
   * Compiles an expression that will always return a boolean by treating it as "if EXPR { output =
   * True } else { output = False }".
   *
   * <p>Used when EXPR is an "and" or "or" expression, or a (non-distributed) type check.
   */
  Local booleanOutput(ExpressionContext ctx) {
    // Tester overrides all the paths that would lead here.
    assert !(this instanceof Tester);
    InstructionBlock ib = blockCompiler.ib;
    BranchTarget isFalse = ib.newTarget();
    BranchTarget done = ib.newTarget();
    blockCompiler.compileTest(isFalse, false, ctx);
    Local result = outputLocal();
    ib.emitSet(result, blockCompiler.symbols.vmTrue);
    ib.emitBranch(done);
    ib.defineTarget(isFalse);
    ib.emitSet(result, blockCompiler.symbols.vmFalse);
    ib.defineTarget(done);
    return result;
  }

  @Override
  public Expr visitIdRefExpression(IdRefExpressionContext idCtx) {
    // Needs to handle variable names and function names with no args (e.g. "sum").
    FunctionNameContext ctx = idCtx.functionName();
    if (ctx.module == null) {
      // If it's a variable reference, PassTwo will already have filled in the Scope.Entry.
      Scope.Entry entry = ctx.lowerId().entry;
      if (entry != null) {
        return entry.local;
      }
    }
    Vm.Function fn = blockCompiler.symbols.getFunction(ctx, 0);
    if (fn.numResults() != 1) {
      throw error("'%s' does not return a result", ctx.getText());
    }
    return fnCall(fn);
  }

  @Override
  public Expr visitTypeNameExpression(TypeNameExpressionContext ctx) {
    TypeNameContext typeName = ctx.typeName();
    ExpressionContext expr = ctx.expression();
    if (expr == null) {
      // An isolated type name must be a singleton.
      return blockCompiler.symbols.getSingleton(typeName);
    }
    // Otherwise it's "Foo_(x)", where Foo must be a compound type declared in this module.
    return blockCompiler.symbols.getOwnCompound(typeName).make(blockCompiler.compile(expr));
  }

  @Override
  public Expr visitLambdaExpression(LambdaExpressionContext ctx) {
    // PassTwo did most of the work; all we need to do now is create an instance of the lambda's
    // closure type.
    Scope scope = ctx.lambdaArrow().blockCompiler.scope;
    return scope.getClosure(null);
  }

  @Override
  public Expr visitHashExpression(HashExpressionContext ctx) {
    Local result = blockCompiler.scope.getHash();
    assert result != null;
    return result;
  }

  @Override
  public Expr visitFunctionCallExpression(FunctionCallExpressionContext ctx) {
    return compileFunctionCall(ctx.functionCall(), true);
  }

  void compileFunctionCallStatement(FunctionCallContext ctx) {
    visitWithCurrentNode(ctx, node -> compileFunctionCall(ctx, false));
  }

  private @Nullable Expr compileFunctionCall(FunctionCallContext ctx, boolean asExpression) {
    // Scan the args to figure out numArgs (counting each "<<" arg as two values), inOut, and
    // distribute.
    int numArgs = 0;
    Bits inOut = Bits.EMPTY;
    Bits distribute = Bits.EMPTY;
    List<ArgContext> args = ctx.arg();
    for (ArgContext arg : args) {
      ParamAnnotationContext annotation = arg.paramAnnotation();
      if (annotation != null) {
        if (Symbols.isCollector(annotation)) {
          ++numArgs;
        }
        inOut = inOut.set(numArgs);
      } else if (arg.dist != null) {
        distribute = distribute.set(numArgs);
      }
      ++numArgs;
    }
    // Now that we know numArgs we can lookup the function and make sure that the inout args match.
    FunctionNameContext fnName = ctx.functionName();
    Vm.Function fn = blockCompiler.symbols.getFunction(fnName, numArgs);
    for (int i = 0; i < numArgs; i++) {
      Symbols.checkInOutParameter(fn, i, inOut.test(i), ctx);
    }
    // Check that we're not trying to use a function as a procedure or vice versa.
    int numResults = fn.numResults() - inOut.count();
    if (numResults != (asExpression ? 1 : 0)) {
      String name = fnName.getText();
      if (numResults == 0) {
        throw error("'%s' does not return a result", name);
      } else if (numResults == 1) {
        throw error("'%s' returns a result", name);
      } else {
        // The VM supports functions that return multiple values without using inouts, but the
        // language doesn't support calling them directly.
        throw error("'%s' returns %s results", name, numResults);
      }
    }
    if (inOut.isEmpty()) {
      IntFunction<Expr> compiledArgs = i -> blockCompiler.compile(args.get(i).expression());
      if (distribute.isEmpty()) {
        return compileFnCall(fn, compiledArgs);
      } else {
        Expr lambda = fn.asLambdaExpr();
        if (lambda == null) {
          // I don't think this can happen?
          throw error("Cannot distribute '%s'", fnName.getText());
        }
        return compileDistributed(lambda, distribute, compiledArgs, numArgs);
      }
    } else if (!distribute.isEmpty()) {
      throw error("Cannot distribute a function with inout parameters");
    }
    // inout args make things more complicated, especially when they're combined with indexing
    Expr[] argValues = new Expr[numArgs];
    Local[] results = new Local[fn.numResults()];
    int nextArg = 0;
    int nextResult = 0;
    if (asExpression) {
      results[nextResult++] = outputLocal();
    }
    int initialTmpState = blockCompiler.saveTmpState();
    // Indexed inout args require one or more post-processing steps.
    List<Runnable> after = new ArrayList<>();
    for (ArgContext arg : args) {
      ExpressionContext expr = arg.expression();
      ParamAnnotationContext annotation = arg.paramAnnotation();
      if (annotation == null) {
        argValues[nextArg++] = blockCompiler.compile(expr);
        continue;
      }
      Local inOutArg;
      if (expr instanceof IndexExpressionContext indexExpr) {
        if (Symbols.isCollector(annotation)) {
          throw error("Collector argument cannot be indexed");
        }
        inOutArg = blockCompiler.newTmp();
        after.add(blockCompiler.compileIndexedInout(indexExpr, inOutArg));
      } else {
        LowerIdContext id = Compiler.asLowerId(expr);
        if (Symbols.isCollector(annotation)) {
          String roName = Compiler.roName(id.start.getText());
          argValues[nextArg++] = blockCompiler.scope.getExistingEntry(roName).local;
        }
        inOutArg = id.entry.local;
      }
      argValues[nextArg++] = inOutArg;
      results[nextResult++] = inOutArg;
    }
    assert nextArg == argValues.length && nextResult == results.length;
    blockCompiler.ib.emitCall(results, fn, argValues);
    Lists.reverse(after).forEach(Runnable::run);
    blockCompiler.resetTmps(initialTmpState);
    return asExpression ? results[0] : null;
  }

  /**
   * Compiles a call to a function that is distributed over one or more arguments.
   *
   * @param lambda a lambda for the function to distribute, or null to just construct an array from
   *     the arguments
   * @param distribute indicates which of the arguments are to be distributed; should be non-empty
   * @param args an IntFunction to compile the nth argument
   * @param n the number of arguments to the lambda
   */
  private Local compileDistributed(Expr lambda, Bits distribute, IntFunction<Expr> args, int n) {
    assert distribute.max() < n;
    int nDistribute = distribute.count();
    if (nDistribute > 2) {
      throw error("Cannot distribute over more than two parameters");
    }
    // Knowing that we're constructing an array allows us to optimize "[^a, ^b]" (it can just be
    // a join, instead of a join piped into "[x, y] -> [x, y]").
    boolean toArray = (lambda == null);
    if (toArray) {
      lambda = vm().arrayOfSize(n).asLambdaExpr();
    }
    // Unless all the arguments are distributed we need to curry the lambda; for example,
    // to compile "f(a, b, ^c, ^d, e)" we'll emit
    //    join(c, d) | curryLambda(fLambda, [-1, -2, 1, 2, -3], [a, b, e])
    //
    // See http://docs/library_reference.md?cl=head#currylambdalambda-args-values for
    // the full explanation of curryLambda; the short version is that
    // curryLambda(lambda, args, values), where
    //   lambda is a Lambda that accepts an array of length n as an argument,
    //   args is an array of n ints
    //   values is an array
    // returns a Lambda such that
    //   curryLambda(lambda, args, values) @ x
    // is equivalent to
    //   lambda @ (newArg(^args, values, x) | save)
    // where newArg is
    //   function newArg(arg, values, x) {
    //     if i == 0 { return x }
    //     else if i > 0 { return x[i] }
    //     else { return values[-i] }
    //  }

    // If we're going to call curry, curryArgs will contain the elements of the second ("args")
    // array (the values array will be constructed later).
    Expr[] curryArgs = (n == nDistribute) ? null : new Expr[n];
    int tmpState = blockCompiler.saveTmpState();
    int d1 = distribute.min();
    Expr pipeFrom = args.apply(d1);
    if (nDistribute == 1) {
      if (curryArgs != null) {
        curryArgs[d1] = vm().asExpr(0);
      }
    } else {
      assert nDistribute == 2;
      int d2 = distribute.max();
      if (curryArgs != null) {
        curryArgs[d1] = vm().asExpr(1);
        curryArgs[d2] = vm().asExpr(2);
      }
      Expr pipeFrom2 = args.apply(d2);
      blockCompiler.resetTmps(tmpState);
      if (toArray && curryArgs == null) {
        return fnCall(vmCore().lookupFunction("join", 2), pipeFrom, pipeFrom2);
      }
      Local joined = blockCompiler.newTmp();
      blockCompiler.ib.emitCall(joined, vmCore().lookupFunction("join", 2), pipeFrom, pipeFrom2);
      pipeFrom = joined;
    }
    if (curryArgs != null) {
      Expr[] values = new Expr[n - nDistribute];
      int nextValue = 0;
      for (int i = 0; i < n; i++) {
        if (!distribute.test(i)) {
          values[nextValue++] = args.apply(i);
          curryArgs[i] = vm().asExpr(-nextValue);
        }
      }
      Local curried = blockCompiler.newTmp();
      Vm.Function curry = blockCompiler.symbols.vmCore.lookupFunction("curryLambda", 3);
      blockCompiler.ib.emitCall(curried, curry, lambda, makeArray(curryArgs), makeArray(values));
      lambda = curried;
    }
    blockCompiler.resetTmps(tmpState);
    return fnCall(blockCompiler.symbols.vmPipe, pipeFrom, lambda);
  }

  /**
   * Compiles an array expression; {@code distributed} must have been returned by a previous call to
   * {@link BlockCompiler#checkDistributed} on the given expressions.
   */
  Expr compileExpressions(List<ArrayElementContext> exprs, Bits distributed) {
    int n = (exprs == null) ? 0 : exprs.size();
    if (n == 0) {
      assert distributed.isEmpty();
      return vm().arrayOfSize(0).make();
    }
    // An IntFunction to get the value of the i'th entry.
    IntFunction<Expr> compiledEntries = i -> blockCompiler.compile(exprs.get(i).expression());
    if (distributed.isEmpty()) {
      return makeArray(toArray(compiledEntries, n));
    } else {
      return compileDistributed(null, distributed, compiledEntries, n);
    }
  }

  @Override
  public Expr visitIndexExpression(IndexExpressionContext ctx) {
    ExpressionContext baseExpr = ctx.expression();
    IndexContext indexExpr = ctx.index();
    Bits indexDistributed = BlockCompiler.checkDistributed(indexExpr);
    boolean distributeBase = (ctx.dist != null);
    if (indexDistributed == null) {
      // The index is "_"
      Vm.Function uncompound = blockCompiler.symbols.module.unCompound();
      int tmpState = blockCompiler.saveTmpState();
      Expr base = blockCompiler.compile(baseExpr);
      blockCompiler.resetTmps(tmpState);
      if (!distributeBase) {
        return fnCall(uncompound, base);
      } else {
        return fnCall(blockCompiler.symbols.vmPipe, base, uncompound.asLambdaExpr());
      }
    }
    // "a.x", "a[...]", and "a @ x" all turn into a (possibly distributed) call to at()
    IntFunction<Expr> atArgs =
        i ->
            (i == 0)
                ? blockCompiler.compile(baseExpr)
                : blockCompiler.compileIndex(indexExpr, indexDistributed);
    Bits distributeAt = Bits.fromBooleans(distributeBase, !indexDistributed.isEmpty());
    if (distributeAt.isEmpty()) {
      return compileFnCall(blockCompiler.symbols.vmAt, atArgs);
    } else {
      return compileDistributed(blockCompiler.symbols.vmAt.asLambdaExpr(), distributeAt, atArgs, 2);
    }
  }

  /**
   * Instances of Setter are used to implement {@link BlockCompiler#compileSet}; visiting an
   * expression will either return an Expr with its value or store that Expr into the specified
   * Local and return the destination (knowing where the result should end up enables often allows
   * us to eliminate extra moving of values to and from temps).
   */
  static class Setter extends ExpressionCompiler {
    final Local lhs;

    /**
     * Creates a Setter that will store the value of the visited expression into the given Local.
     */
    Setter(BlockCompiler blockCompiler, Local lhs) {
      super(blockCompiler);
      this.lhs = lhs;
    }

    /** Emits instructions to store the value of {@code expr} into this Setter's destination. */
    void compileSet(ExpressionContext expr) {
      // visit() may allocate temps, but once we've stored its result they're no longer needed.
      int tmpState = blockCompiler.saveTmpState();
      Expr result = visit(expr);
      if (result != lhs) {
        blockCompiler.ib.emitSet(lhs, result);
      }
      blockCompiler.resetTmps(tmpState);
    }

    @Override
    Local outputLocal() {
      return lhs;
    }

    @Override
    public Expr visitTernaryExpression(TernaryExpressionContext ctx) {
      compileTernary(ctx);
      return lhs;
    }

    void compileTernary(TernaryExpressionContext ctx) {
      InstructionBlock ib = blockCompiler.ib;
      BranchTarget done = ib.newTarget();
      BranchTarget isFalse = ib.newTarget();
      if (ctx.ifTrue != null) {
        blockCompiler.compileTest(isFalse, false, ctx.cond);
        compileSet(ctx.ifTrue);
      } else {
        int tmpState = blockCompiler.saveTmpState();
        Expr result = blockCompiler.compile(ctx.cond);
        blockCompiler.resetTmps(tmpState);
        if (!(result instanceof Local local)) {
          throw error("Not a sensible use of ?:");
        }
        ib.emitTypeCheckBranch(isFalse, local, blockCompiler.symbols.vmAbsent.asType(), true);
        blockCompiler.ib.emitSet(lhs, result);
      }
      ib.emitBranch(done);
      ib.defineTarget(isFalse);
      compileSet(ctx.ifFalse);
      ib.defineTarget(done);
    }
  }

  /**
   * Instances of Tester are used to implement {@link BlockCompiler#compileTest}; visiting an
   * expression will either return an Expr with its value or emit a conditional branch to the
   * specified BranchTarget and return null.
   */
  static class Tester extends ExpressionCompiler {
    final BranchTarget branchTarget;
    final boolean branchIfTrue;

    /**
     * Creates a Tester that will emit a conditional branch to the specified target, to be taken if
     * the visited expression is True or False.
     */
    Tester(BlockCompiler blockCompiler, BranchTarget branchTarget, boolean branchIfTrue) {
      super(blockCompiler);
      this.branchTarget = branchTarget;
      this.branchIfTrue = branchIfTrue;
    }

    /**
     * Emits an instruction to branch to this Tester's target based on the value of {@code result}.
     * Note that this uses the {@code ifTrue} parameter in place of the {@code branchIfTrue} that
     * was passed to the constructor (callers pass either {@code branchIfTrue} or {@code
     * !branchIfTrue}).
     */
    void emitBranch(Local result, boolean ifTrue) {
      blockCompiler.ib.emitConditionalBranch(branchTarget, result, ifTrue);
    }

    /** Emits instructions to conditionally branch based on the value of {@code expr}. */
    void compileTest(ExpressionContext expr) {
      int tmpState = blockCompiler.saveTmpState();
      Expr result = visit(expr);
      blockCompiler.resetTmps(tmpState);
      // visit() returns null if the branch has already been emitted.
      if (result != null) {
        compileTest(result, expr.start);
      }
    }

    /** Emits instructions to conditionally branch based on the value of {@code expr}. */
    void compileTest(Expr expr, Token token) {
      if (expr instanceof Local local) {
        emitBranch(local, branchIfTrue);
      } else {
        // If expr is True or False, we either emit an unconditional branch or
        // emit nothing.
        // If expr is anything else (i.e. another singleton, a number, or a compound)
        // it can't possibly be True or False, so error.
        Expr alwaysBranch =
            branchIfTrue ? blockCompiler.symbols.vmTrue : blockCompiler.symbols.vmFalse;
        Expr neverBranch =
            branchIfTrue ? blockCompiler.symbols.vmFalse : blockCompiler.symbols.vmTrue;
        if (expr.equals(alwaysBranch)) {
          blockCompiler.ib.emitBranch(branchTarget);
        } else if (!expr.equals(neverBranch)) {
          // You'll end up here if you try to compile something like "3 ? x : y"
          throw Compiler.error(token, "Not a sensible condition to test");
        }
      }
    }

    @Override
    @Nullable Local booleanNegate(Local x) {
      emitBranch(x, !branchIfTrue);
      return null;
    }

    /**
     * To compile "{@code not}" in a test context we don't need to call the {@code not()} function,
     * we can just invert the sense of the branch.
     */
    @Override
    public @Nullable Expr visitOpExpression(OpExpressionContext ctx) {
      if (ctx.op.getType() == TokenType.KEYWORD_NOT && ctx.rdist == null) {
        blockCompiler.compileTest(branchTarget, !branchIfTrue, ctx.right);
        return null;
      }
      // Note that ExpressionCompiler.visitOpExpression() uses blockCompiler.compileExpression()
      // to recursively visit subexpressions, so they won't be handled by this Tester.
      return super.visitOpExpression(ctx);
    }

    /**
     * To compile "{@code ?:}" in a test context we don't need a variable to store the result in, we
     * can just compile each alternative with the same Tester.
     */
    @Override
    public @Nullable Expr visitTernaryExpression(TernaryExpressionContext ctx) {
      InstructionBlock ib = blockCompiler.ib;
      BranchTarget isFalse = ib.newTarget();
      BranchTarget done = ib.newTarget();
      if (ctx.ifTrue == null) {
        // "x ?: y" in a test context: if x evaluates to Absent, use y, otherwise
        // x should be True or False.
        int tmpState = blockCompiler.saveTmpState();
        Expr result = blockCompiler.compile(ctx.cond);
        blockCompiler.resetTmps(tmpState);
        if (!result.equals(blockCompiler.symbols.vmAbsent)) {
          if (result instanceof Local local) {
            blockCompiler.ib.emitTypeCheckBranch(
                isFalse, local, blockCompiler.symbols.vmAbsent.asType(), true);
          }
          compileTest(result, ctx.start);
          ib.emitBranch(done);
        }
      } else {
        blockCompiler.compileTest(isFalse, false, ctx.cond);
        compileTest(ctx.ifTrue);
        ib.emitBranch(done);
      }
      ib.defineTarget(isFalse);
      compileTest(ctx.ifFalse);
      ib.defineTarget(done);
      return null;
    }

    @Override
    public @Nullable Expr visitConjExpression(ConjExpressionContext ctx) {
      // and, or
      int keyword = ctx.op.getType();
      boolean isAnd = (keyword == TokenType.KEYWORD_AND);
      assert isAnd || (keyword == TokenType.KEYWORD_OR);
      if (isAnd != branchIfTrue) {
        // or, nand -- branch if either subexpression says branch
        compileTest(ctx.left);
        compileTest(ctx.right);
      } else {
        // and, nor -- only try the second subexpression if the first one succeeds
        InstructionBlock ib = blockCompiler.ib;
        BranchTarget done = ib.newTarget();
        blockCompiler.compileTest(done, !branchIfTrue, ctx.left);
        compileTest(ctx.right);
        ib.defineTarget(done);
      }
      return null;
    }

    @Override
    public @Nullable Expr visitTypeCheckExpression(TypeCheckExpressionContext ctx) {
      Vm.Type type = blockCompiler.symbols.getType(ctx.typeName());
      boolean isNegated = (ctx.negation != null);
      int tmpState = blockCompiler.saveTmpState();
      Expr expr = blockCompiler.compile(ctx.expression());
      blockCompiler.resetTmps(tmpState);
      if (expr instanceof Local local) {
        blockCompiler.ib.emitTypeCheckBranch(branchTarget, local, type, isNegated != branchIfTrue);
      } else {
        // e.g. "if [x, y] is Boolean { .. }"
        // We could implement this (with a little pain), but would anyone ever want it?
        throw error("Why a constant type test?");
      }
      return null;
    }
  }
}

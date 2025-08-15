# CodeBuilder

The `code` package defines a framework for constructing, optimizing, and loading
new Java MethodHandles defined by JVM instructions. It builds on the low-level
API provided by [ASM](https://asm.ow2.io), which is used to specify sequences of
JVM instructions. This doc assumes basic familiarity with the JVM, and most use
of `code` will require some familiarity with the ASM
[MethodVisitor](https://asm.ow2.io/javadoc/org/objectweb/asm/MethodVisitor.html)
API.

The core classes are:

*   `CodeValue`: represents a JVM value stored or manipulated during execution.
    One of `Const` (a constant), `Register` (an intermediate value stored for
    later use), or `Op.Result` (the result of applying an operation to the
    values represented by a sequence of other `CodeValue`s).

*   `Op`: an operation that takes a fixed number of `CodeValue`s and optionally
    returns a `CodeValue`. Some Ops correspond to simple JVM instructions (e.g.
    `Op.ADD_INTS` is implemented by the IADD instruction), while others are
    implemented by method calls.

*   `Block`: represents a contiguous sequence of JVM instructions. Blocks are
    linked together into a graph representing the complete method to be loaded.
    There are three subclass of Block:

    *   `Terminal`: No outlinks. Currently the only Terminal is Return, although
        Throw would be another Terminal if it were implemented.

    *   `NonTerminal`: A single outlink, “next”, to the Block that is executed
        after this one. The most common is `SetBlock`, which sets a Register to
        the value of a CodeValue.

    *   `Split`: Two outlinks, “next” and “alternate”; which outlink is taken
        depends on execution of the block. Most Split Blocks are subclasses of
        TestBlock, which chooses based on the value(s) of one or two CodeValues
        (e.g. an `IsEq` block follows the next link if the two values are the
        same, and the alternate link otherwise). There is also
        `SetBlock.WithCatch`, which is like a regular SetBlock but takes its
        alternate link if evaluation of the CodeValue throws a specified
        exception.

    The `code` package provides enough block types to construct basic programs,
    but the Block subclasses are designed to be extensible so that new block
    types can be added easily.

*   `CodeBuilder`: Used to assemble a graph of Blocks and then convert them into
    a callable MethodHandle. CodeBuilder maintains the intermediate state while
    the graph is being assembled, and then optimizes and loads it.

*   `FutureBlock`: used to hold a set of not-yet-attached outlinks while the
    block graph is being constructed. CodeBuilder maintains a `next` FutureBlock
    as Blocks are added, enabling a simple sequence of NonTerminals to be
    constructed by just adding them in order, but to build more complex graphs
    the client must manipulate FutureBlocks directly.

*   `Loop`: Blocks cannot be linked arbitrarily: in general Blocks must form an
    acyclic graph. Loops provide the exception to this rule, enabling a
    controlled form of circularity in the block graph. (This constraint enables
    the analysis and optimization of block graphs to be simpler and more
    efficient.)

*   `ValueInfo`: represents what the CodeBuilder is able to infer about the
    value of a CodeValue at some point during execution. Possibilities include
    `ANY` (no information), `IntRange` (value is in a range of integers), and
    `Const` (value is known exactly).

## An Example

As an example of how these pieces fit together, this section shows how to use
them to build a method that determines the length of a
[Collatz sequence](https://en.wikipedia.org/wiki/Collatz_conjecture). (This is
taken from CodeBuilderTest; the full code can be seen there.). The algorithm can
be written in a Retrospect-like pseudocode as

```
int collatz(int n) {
  int count = 0
  for sequential n, count {
    if n == 1 {
      return count
    }
    n = (n % 2 == 0) ? n / 2 : 3 * n + 1
    count += 1
  }
}
```

##### Initialize the CodeBuilder & construct a Register for each argument

```
CodeBuilder cb = new CodeBuilder();
Register n = cb.newArg(int.class);
```

##### Add Registers and Blocks to the CodeBuilder

```
Register count = cb.newRegister(int.class);
new SetBlock(count, CodeValue.ZERO).addTo(cb);
```

That’s a pretty straightforward translation of `int count = 0`.

##### Add Loops

```
Loop loop = cb.startLoop(n, count);
```

Note that Loops must identify which registers will be updated on the each loop
iteration; all other registers must either be unchanged within the loop or used
only as temporary storage within a single iteration.

##### Add Conditionals

```
FutureBlock else1 = new FutureBlock();
new TestBlock.IsEq(OpCodeType.INT, n, CodeValue.of(1))
    .setBranch(false, else1)
    .addTo(cb);
new ReturnBlock(count).addTo(cb);
cb.setNext(else1);
```

FutureBlocks serve as placeholders for not-yet-emitted Blocks. Here we create an
IsEq TestBlock comparing `n` with the constant `1`, branching to `else1` if the
test is false. That’s followed by a ReturnBlock (which will be executed if the
TestBlock didn’t branch, i.e. if `n == 1`). Finally we use `setNext()` so that
the next Block added after this will be the one that the TestBlock branches to.

The conditional assignment to `n` also uses a TestBlock:

```
FutureBlock else2 = new FutureBlock();
new TestBlock.IsEq(OpCodeType.INT, Op.MOD_INTS.result(n, CodeValue.of(2)), CodeValue.of(0))
    .setBranch(false, else2)
    .addTo(cb);
new SetBlock(n, Op.DIV_INTS.result(n, CodeValue.of(2))).addTo(cb);
FutureBlock done2 = cb.swapNext(else2);
new SetBlock(n, Op.ADD_INTS.result(Op.MULTIPLY_INTS.result(CodeValue.of(3), n), CodeValue.of(1))).addTo(cb);
cb.mergeNext(done2);
```

Note that this time we use `swapNext()` instead of `setNext()` after the “then”
branch of the TestBlock, which returns a new FutureBlock that will follow the
preceding block (i.e. the `SetBlock(n, Op.DIV_INTS(...))`). (In the previous
example the preceding block was a ReturnBlock, so there was nothing following
it.). After adding the “else” branch we use `mergeNext()` to combine the `done2`
path with the current execution path.

##### Close the Loop and load the completed code

```
loop.complete();

MethodHandle mh = cb.load("collatz", "test", int.class, lookup);
int result27 = (int) mh.invoke(27);
assertThat(result27).isEqualTo(111);
```

The call to `load()` verifies that the Block graph is complete (i.e. that there
are no dangling FutureBlocks), does some simple optimizations, and then loads
the resulting code as a new MethodHandle.

## Examining the results

CodeBuilderTest captures the CodeBuilder's output at three stages, providing a
view into its process.

##### After optimization

The first stage is the completed block graph, after optimizations have been
applied (although in this case there are no applicable optimizations). Since
arbitrary graph structures can be painful to read, CodeBuilder has logic to
render the graph as a sequence; in this case it produces:

```
 1: i1 ← 0;
=2: test i0 == 1; T:→ 8
 3: test iMod(i0, 2) == 0; F:→ 5
 4: i0 ← iDiv(i0, 2); → 6
-5: i0 ← iAdd(iMul(3, i0), 1);
-6: i1 ← iAdd(i1, 1);
 7: back0 → 2 with i0, i1
-8: return i1
```

Each line represents one of the blocks in the graph, and they are numbered
sequentially. The number doesn’t necessarily correspond to the order in which
blocks were added (e.g. the ReturnBlock has been moved to the end). Non-terminal
blocks that link to the next block in sequence (such as block 1 in this listing)
have no explicit link, but blocks (such as block 4) that link to somewhere else
are followed by an explicit representation of the link (in this case, `→ 6` to
indicate that execution continues with block 6). TestBlocks have two outlinks
(`T` and `F`), but if one of them goes to the following block it will be elided
(block 2 elides its ifFalse link to the following block, while block 3 elides
its ifTrue link).

Some other things to note:

*   Registers are referred to by a type marker (`i` for int, `d` for double, `x`
    for pointer, etc.) followed by a unique index (these are assigned
    sequentially, in the order of calls to `newArg()` or `newRegister()`). In
    this example all register are ints.

*   The BackRef block (number 7) inserted at the end of the loop has a somewhat
    cryptic syntax; it includes the loop number (in this case, 0) and the
    registers updated by the loop (in this case, `i0` and `i1`).

*   At this stage the blocks will be ordered so that BackRefs are the only
    blocks that link to an earlier block in the sequence; all other outlinks
    will be to a higher-numbered block.

*   Any block that is the target of a link from a later block (i.e. a Backref)
    will be marked with a `=` prefix; any block that is the target of an
    explicit link from an earlier block will be marked with a `-`.

##### Before emitting

The second stage is immediately before emitting bytecodes; again we print the
block graph.

```
 1: I1 ← 0;
=2: test I0 == 1; T:→ 7
 3: test iMod(I0, 2) == 0; F:→ 6
 4: I0 ← iDiv(I0, 2);
=5: I1 ← iAdd(I1, 1); → 2
-6: I0 ← iAdd(iMul(3, I0), 1); → 5
-7: return I1
```

In this example the differences from the previous stage are minor:

*   Registers identifiers are using upper case prefixes (in this case, `I`
    instead of `i`). This indicates that the following index is no longer the
    register number, but is the index in the Java local array (which is the way
    bytecodes refer to local variables). The distinction here is invisible,
    since registers have been assigned Java local indices that match their
    register number, but in more complex examples they will often differ.

*   The BackRef block has been removed, and the forward-linking-only constraint
    has been relaxed. This also led to a slightly different ordering of blocks.

This final version of the block graph will be used as the basis for emitting
bytecodes.

##### Bytecodes

The final stage is the emitted bytecodes. This output is produced by ASM, and
understanding it requires some familiarity with the JVM (the
[full listing of JVM instructions](https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-6.html#jvms-6.5)
may be useful).

```
L0
 LINENUMBER 1 L0
 ICONST_0
 ISTORE 1
L1
 LINENUMBER 2 L1
FRAME APPEND [I]
 ILOAD 0
 ICONST_1
 IF_ICMPEQ L2
L3
 LINENUMBER 3 L3
 ILOAD 0
 ICONST_2
 IREM
 IFNE L4
L5
 LINENUMBER 4 L5
 ILOAD 0
 ICONST_2
 IDIV
 ISTORE 0
L6
 LINENUMBER 5 L6
FRAME SAME
 ILOAD 1
 ICONST_1
 IADD
 ISTORE 1
 GOTO L1
L4
 LINENUMBER 6 L4
FRAME SAME
 ICONST_3
 ILOAD 0
 IMUL
 ICONST_1
 IADD
 ISTORE 0
 GOTO L6
L2
 LINENUMBER 7 L2
FRAME FULL [T I] []
 ILOAD 1
 IRETURN
```

The “label” lines (`L0`, `L1`, etc.) aren’t actually bytecodes — they’re points
in the bytecode sequence that are referenced by one or more bytecodes. Their
numbering is chosen by ASM and is arbitrary.

Each block from stage 2 is translated into a sequence of one or more bytecodes;
for example, the first block (`I1 ← 0`) is translated to

```
ICONST_0
ISTORE 1
```

which pushes the integer constant 0 on the stack, and then pops it and stores it
in the local variable with index 1.

These instructions are preceded by `LINENUMBER 1 L0`, which adds an annotation
indicating that the bytecodes beginning at `L0` correspond to Java source line
number 1. Of course these bytecodes don’t correspond to any Java source code,
but we’ve repurposed these annotations to relate the bytecodes to the final
block numbering (stage two). If the emitted code (or code it calls) throws an
exception, the stack trace will include these “line numbers”, which together
with the final block listing provides some hope for debugging.

The other annotations that are required by the JVM are “stack map frames”; these
appear in this example as `FRAME APPEND [I]`, `FRAME SAME`, and `FRAME FULL [T
I] []`. Frame annotations are required before any bytecode that is the target of
a branch (or exception handler), and tell the verifier the types of the stack
contents and the Java locals at that point in execution. The full details are in
the
[ASM docs](https://asm.ow2.io/javadoc/org/objectweb/asm/MethodVisitor.html#visitFrame\(int,int,java.lang.Object%5B%5D,int,java.lang.Object%5B%5D\))
and the JVM specification, but these annotations can be safely ignored while
reading the code.

## The ValueInfo interface and CodeValue simplification

Many of the optimizations that CodeBuilder can perform are dependent on its
ability to make inferences about the possible values of an expression during the
program's execution. As a simple example, consider this sequence of blocks that
sets i1 to 1 if i0 is negative, and 0 otherwise:

```
 1: test i0 < 0; F:→ 3
 2: i1 ← 1; → 4
-3: i1 ← 0
-4: ...
```

CodeBuilder can infer that when execution reaches block 4, i1 is either 0 or 1.

The results of these inferences are represented by the interface `ValueInfo`. A
ValueInfo can be thought of as representing the set of all values the expression
could have, with `ANY` representing all possible values of the expression's
type. The `code` package provides some basic implementations of ValueInfo (such
as `IntRange`, `SmallIntSet`, and `Const`), but additional ValueInfo
implementations can be defined as needed.

In order to represent what is known about the values of each register, some of
the methods on CodeValue take an `IntFunction<ValueInfo>` as an argument — the
IntFunction is called with the register's index. The SmallIntMap class is
usually used to implement these IntFunctions, but supporting the more general
functional interface provides additional flexibility.

The two core operations defined on CodeValue are

```
CodeValue simplify(IntFunction<ValueInfo> registers);
ValueInfo info(IntFunction<ValueInfo> registers);
```

The first returns a potentially simpler CodeValue that is known to be equal to
`this` (given the register info); if no simplification can be done it just
returns `this`. The second returns a ValueInfo that includes all possible values
of the CodeValue; if nothing is known it returns `ANY`.

Register also implements ValueInfo; if the ValueInfo of one register (r1) is
another register (r2), it means that the two registers are known to have the
same value (and hence the ValueInfo of r2 also applies to r1). In this case the
ValueInfo of r2 is not permitted to be a register, so it is never necessary to
look up more than two ValueInfos and they may not form a cycle.

## Propagating register info and Loops

Propagation of register info through sequential code is relatively
straightforward: given the possible values of registers before each block, we
can infer their possible values after the block executes. In the case of a
TestBlock we infer two sets of register infos, one for each outlink, and they
may be different; for example, when analyzing a block that tests `x == 3`, on
the “true” outlink we know the value of `x` while on the “false” outlink we may
know nothing more (e.g. if it was `ANY` going in it will still be `ANY`) or we
may be able to improve it (if it was `IntRange(0, 3)` going in it will be be
`IntRange(0, 2)` if the test fails).

This simple approach is insufficient in the presence of loops, since the
possible values of the loop registers at the beginning of the loop depend not
just on their values before the loop starts, but also the values that may be
assigned in the loop body — which we can’t analyze without knowing the possible
values at the beginning of the loop.

The simple approach implemented by CodeBuilder is

*   When first encountering the loop body, assume that all loop registers may
    have any value and analyze the body accordingly.
*   When the loop body is complete, see what we have been able to infer about
    the values of the loop registers immediately before branching back; if it is
    stronger than our previous assumption (i.e. `ANY` if this is the first
    iteration), repeat the analysis with the stronger initial assumptions.
*   Repeat until we stop gaining information.

Usually this process terminates after just one or two iterations, but its
simplicity leads to two problems.

The first problem is that it may be overly conservative. For example, if a
register is not modified at all in the body of the loop we know that the info
from before the loop started is valid throughout, but this approach cannot infer
that; since it starts the iteration assuming that the register may have any
value, that will still be true at the end of the loop.

We fix that particular case by detecting when a loop does not modify one of its
loop registers, and simply removing that register from the loop; then it is no
longer treated specially and the before-loop info is propagated throughout. But
there are many similar cases that cannot be fixed that way — for example, simply
swapping two of the loop registers means that they will both continue to be
`ANY`, when a smarter algorithm could infer that they would each have the union
of the infos they started with.

The second problem is that although each iteration strengthens the information
we have about the register values, it may not converge in a reasonable number of
steps. For example, consider the block graph that results from a simple loop
equivalent to

```
for (int i = 0; i < 10; i++) { ... }
```

The blocks would be something like

```
 1: i ← 0;
=2: test i < 10; F:→ 9
   ...
 7: i ← iAdd(i, 1);
 8: back0 → 2 with i, ...
-9: ...
```

On the first iteration, `i` is `ANY` before the test and `IntRange(MIN_VALUE,
9)` if the test succeeds (`MIN_VALUE` is the smallest possible int32 value, i.e.
-2^31, so that IntRange represents all int32 values less than 10). After the
iAdd its value is `IntRange(MIN_VALUE+1, 10)`, and since that’s an improvement
over `ANY` we will repeat the loop body analysis.

On the second iteration, the successful test narrows `i` to
`IntRange(MIN_VALUE+1, 9)` and the iAdd updates it to `IntRange(MIN_VALUE+2,
10)`, which is a *slight* improvement over what it had before...

This would eventually converge after iterating 2^31 times (at that point the
`IntRange(1, 10)` from the back reference is unioned with the `0` from the loop
entry to get the same `IntRange(0, 10)` that we had on the previous iteration,
so we stop). But 2^31 iterations will take much longer than we’re willing to
spend, and the additional information is unlikely to be useful anyway.

A more sophisticated approach could reach that conclusion much more quickly, but
since I have not yet encountered examples where the additional complexity paid
off in better bytecodes, CodeBuilder instead implements something much simpler:
ValueInfo classes (such as IntRange) that have long sequences of strictly
stronger ValueInfos must implement the optional method `isCloseEnoughTo()`. Loop
analysis may terminate even if a register’s info is not the same the previous
iteration, as long as `isCloseEnoughTo()` returns true. For example, IntRange
considers two different ranges to be “close enough” if

*   both have at least 8 elements, and
*   both contain the same subset of [-8, 8].

Those (admittedly very arbitrary) criteria are intended to ensure that iteration
will always converge after a relatively small number of iterations (16 * the
number of loop registers in the worst case) and that when we stop iterating it
is unlikely that more iterations would have enabled us to discover additional
optimizations.

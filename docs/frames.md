# Representing Values with Frames & Frame Evolution

[TOC]

## Representing variables in generated code

One of the primary decisions the Retrospect VM must make before it is able to
generate byte code for a fragment of Retrospect source is how it will represent
the values of variables.

For example, consider a program that looks like this:

```
...
f = x -> a * x + b
...
z = f @ x0
```

i.e. it assigns a simple linear function to the variable `f` (capturing the
values of variables `a` and `b`), and then later applies that lambda to the
value `x0`.

The Retrospect compiler will introduce a new basetype for the lambda expression
(it's unnamed, but I'll refer to it here as `Lambda31`) with two elements (`a`
and `b`); the instructions emitted for the first line of code will create a
value of that basetype using the current values of `a` and `b` as its elements.
The compiler will also define a method for the `at(lambda, arg)` function (aka
`@`) that if written as Retrospect source code would look like

```
method at(Lambda31 lambda, x) = lambda_.a * x + lambda_.b
```

(i.e. it extracts `a` and `b` from the Lambda31 value and computes the value of
the original expression).

When first executing these instructions the VM will represent `f` using a
generic `CompoundValue` instance that refers to the basetype (`Lambda31`) and
the values of `a` and `b` (which will in turn be represented by instances of
`NumValue`, assuming that they're numbers), but if this turns out to be
frequently-executed code we'll want to choose a more efficient representation.

Suppose that during its initial executions of this code the VM observes that the
value of `f` is always an instance of `Lambda31`, and that the embedded `a` and
`b` values are always 64-bit floats. When generating Java byte codes it could
then choose to represent `f` using two Java locals of type double (call them
`f_a` and `f_b`, although they don't actually have meaningful names), so the
generated code (decompiled to Java) would just be

```
double f_a, f_b;
...
f_a = a;
f_b = b;
...
z = f_a * x0 + f_b;
```

## Increasing complexity

For simple programs the approach described above works well, but what happens as
the program gets more complex?

For example, what if `a`, `b`, and `x0` are pairs of numbers? (Retrospect
defines `*` and `+` to operate element-wise over equal-length arrays.) The VM
can use the same approach, generating code equivalent to

```
double f_a1, f_a2, f_b1, f_b2;
...
f_a1 = a1;
f_a2 = a2;
f_b1 = b1;
f_b2 = b2;
...
z1 = f_a1 * x01 + f_b1;
z2 = f_a2 * x02 + f_b2;
```

... but clearly this approach won't scale well if the arrays get longer.

Another way the code might get more complex is if other control paths can assign
other lambda expressions to `f`, e.g.

```
if ... {
  f = x -> a * x + b
} else {
  f = x -> x * x
}
...
z = f @ x0
```

This would cause the compiler to create a new singleton basetype (call it
`Lambda32`; it can be a singleton because the lambda expression has no captured
variables) with its own `at` method. If we again assume that `a` and `b` can be
represented by doubles, the generated code would look something like

```
int f_tag; // 0 for Lambda31, 1 for Lambda32
double f_a, f_b;
...
if (...) {
  f_tag = 0;
  f_a = a;
  f_b = b;
} else {
  f_tag = 1;
}
...
if (f_tag == 0) {
  z = f_a * x0 + f_b;
} else {
  z = x0 * x0;
}
```

Again, this approach is plausible up to a point but has scaling limitations. We
can't generate byte codes that use an unbounded number of Java locals so we need
another option when the complexity increases.

## Introducing Frames

A frame is a Java object that the VM uses to represent a value. Like a
CompoundValue, each frame instance has a basetype and elements; unlike a
CompoundValue the representation of a frame's elements is optimized based on
observed execution.

Going back to the first example above, the VM could instead represent `f` by
using a frame class with two double fields (which I'll refer to here as
`Frame2d`). The VM doesn't create such classes on the fly; instead it has a set
of generic frame classes of varying sizes and chooses the most appropriate. The
generated code would look something like

```
Frame f;
...
f = new Frame2d();
f.layout = ...  // FrameLayout with template Lambda31(d0, d1)
f.d0 = a;
f.d1 = b;
...
z = f.d0 * x0 + f.d1;
```

Since `Frame2d` is a generic class that may be used to represent many different
types of values, it uses a template to record both the basetype (in this case
Lambda31) and how that basetype's elements are represented (in this case, as two
doubles). The template is part of the *FrameLayout* that each frame links to
(FrameLayout can be thought of as the Retrospect VM's version of a
[vtable](https://en.wikipedia.org/wiki/Virtual_method_table)).

Although frames implement the generic Value interface, byte codes are usually
emitted assuming a specific FrameLayout (e.g. in the example above we emitted
`f.d0 = a` rather than a call to `Value.replaceElement()`). Of course generated
code must take appropriate precautions to ensure that frames it encounters have
the expected layout, and be prepared to safely fallback to the interpreter if
they don't, but in many cases (including the example above) many or all of those
checks can be elided by simple control-flow analysis.

Disclaimer: a few simplifications were made for this particular example; the
truth is that

*   The VM wouldn't usually introduce a frame with such a simple template; its
    current threshold is closer to eight fields.

*   There isn't actually a `Frame2d` subclass. There are currently 11 frame
    subclasses of varying sizes, and if we were laying out a frame with template
    `Lambda31(d0, d1)` we would use `Frame5i2x`. That subclass has 5 int fields
    and 2 Object fields; we would use stupid Java tricks to alias a pair of the
    int fields to store each of the doubles, and the remaining int field and
    Object fields would be unused.

*   Because RecordLayout uses templates where the NumVar index is the byte
    offset of the field, and double fields need to be aligned, and the first int
    field isn't double aligned, the template for this layout would actually be
    `Lambda31(d4, d12)`.

## FrameLayout Evolution

The Retrospect language doesn't ask users to specify FrameLayouts, so the VM has
to infer them based on the program's execution history so far. Sometimes it may
determine that a previously-chosen layout is insufficient for the values it
needs to represent; for example, if in initial executions of the code above `a`
and `b` were doubles (so we chose the `Frame2d` layout described above), but in
some subsequent executions they were pairs of doubles, the VM would switch to a
layout with template `Lambda31(b0⸨0:d4; 1:[d4, d12]⸩, b1⸨0:d20; 1:[d20, d28]⸩)`
(where each element is a tagged union of a double or a pair of doubles —
requiring a frame class with at least 9 int fields).

Generating byte codes to handle multiple layouts for each variable doesn't scale
well, so instead we impose a constraint: at any point in program execution, each
variable has at most one preferred FrameLayout for each sortOrder (which means
one per basetype, except that there is at most one FrameLayout for all array
basetypes). We determine this layout by a process of *FrameLayout evolution*:
each time we need to represent a value that isn't supported by the current
layout, we choose a new more inclusive layout and mark the previous layout as
having evolved to the new one.

Instructions that allocate new frames keep track of which layout they used last;
next time they are executed they check to see if that layout has evolved, and if
so use the newer layout. In this way layout evolution acts as a communication
channel from the instruction where a problem is detected ("oops; this could have
been done more efficiently if the value had been allocated with layout *x*")
back to the instruction that made the layout decision in the first place. Over
time the expectation is that the VM will converge on a set of layouts that is
efficient for the program being executed.

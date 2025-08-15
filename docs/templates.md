# Templates in the Retrospect VM

[TOC]

A *template* is a structure describing how to construct a Retrospect value from
a sequence of inputs, each of which is either a numeric value (uint8, int32, or
float64) or an object of type Value.

Some motivating examples (details to follow; we’ll write templates using a text
notation but they are actually structured objects):

*   `[i0, i1]`: a template with two int32 inputs (`i0` and `i1`); the template’s
    value is a 2-element array containing those integers

*   `[U32(i0), U32(i1)]`: another template with two int32 inputs representing a
    2-element array, but this time each integer is interpreted as an unsigned
    (U32) value (U32 is a size 1 base type; `U32(i0)` is a value of that base
    type whose element is provided by `i0`)

*   `{x:d0, y:d1}`: a template with two float64 inputs, whose value is a
    2-element struct with keys "x" and "y" (the structure of this template is
    actually `Struct(["x", "y"], [d0, d1])`

*   `b0⸨0:False, 1:True⸩`: this template is a
    [tagged union](https://en.wikipedia.org/wiki/Tagged_union). A uint8 input
    (`b0`) must be either zero or one; if it is zero, the template’s value is
    `False`; if it is one, the template’s value is `True` (in other words, this
    is the standard representation for Boolean values).

*   `b0⸨0:[d1, d2], 1:None⸩`: a more interesting tagged union — this template’s
    value is either a 2-element float64 array (`[d1, d2]`) or `None`.

Templates are instances of the interface Template. An interface (rather than an
abstract class) is used only to work around limitations of the Java inheritance
hierarchy, not because we expect to support additional implementations of
Template in the future.

### Structure

There are 4 types of templates:

*   **constants**: e.g. `None`, `3.14`, `"abc"`. Any Retrospect value can be
    used as a constant template, but the value must be uncounted (since
    templates are uncounted).

*   **variables**: These come in 4 types, each with a distinct prefix letter
    (uint8 (`b`), int32 (`i`), float64 (`d`), and Value (`x`)). Each variable
    has an associated index, whose interpretation depends on the use of the
    template. Value-typed variables (those with an `x` prefix, also called
    "reference variables" or just "refvars") also have an associated base type
    to which their values are restricted (e.g. `x0:String`).

*   **compounds**: A compound template has a compositional baseType (with
    size >= 1) plus a template for each element of the compound (e.g. `U32(i0)`,
    `Range(i0, i1)`)

*   **unions**: A union template has two or more templates for the choices, plus
    an optional variable (of type `b` or `i`, depending on the number of
    choices) for the tag (e.g. `b0⸨0:i1, 1:Range(0, i1)⸩` is a union with tag
    `b0` and two choices: `i1` and `Range(0, i1)`).

### Uses

Templates are used in 3 ways:

*   To create more compact implementations of compound Values.

*   To create more compact implementations of arrays of Values.

*   When translating instructions to byte codes, to describe how the value of a
    (Retrospect) local is implemented with (Java) local variables.

The first use uses a new Frame class which combines a template with a sequence
of bytes and a sequence of Values. The variable indices for numeric variables
are offsets into the byte sequence, so e.g. to represent a lot of x/y/z structs
with double values we could use Frames with room for 24 bytes and the template
`{x:d0, y:d8, z:d16}`. If instead each x/y/z was an integer or `None` we might
use the template `{x:b0⸨i4, None⸩, y:b1⸨i8, None⸩, z:b2⸨i12, None⸩}` with Frames
that have room for 16 bytes (note that in these templates, offsets for int32
variables are always multiples of 4 and offsets of float64 variables are
multiples of 8 to match alignment constraints).

The second use combines a template with a sequence of parallel Java arrays, one
for each variable. For example, a (Retrospect) array where each value was an
x/y/z double struct would use the template `{x:d0, y:d1, z:d2}` and 3 arrays of
bytes, each with length at least 8 times the length of the Retrospect array.
Reading or writing a single element of the Retrospect array will access one
double (8 bytes) from each of the Java arrays (these arrays will be read and
written as if they were `double[]`, but using byte arrays everywhere simplifies
the code). If each struct element was an integer or `None` we could use a
template `{x:b0⸨i1, None⸩, y:b2⸨i3, None⸩, z:b4⸨i5, None⸩}` combined with 6
arrays (arrays 0, 2, and 4 would have length at least as long as the Retrospect
array; arrays 1, 3, and 5 would have at least 4 times that length).

(We currently also use Frames for this second kind of value, since that turns
out to be simplest; such Frames always have exactly 4 bytes of numeric data (for
their length) and a varying number of Java array pointers.)

The details of the third use will be elaborated when byte code generation is
implemented.

### Union restrictions and BaseType orders

In order to help bound the complexity of code that deals with templates we place
some restrictions on the choices in a union:

*   no choice in a union can be another union;

*   no two choices in a union can have the same base type or different base
    types with the same sortOrder, and;

*   the order in which choices appear in a union is determined by their base
    types.

Thus for example these unions are invalid:

*   `b0⸨0:i1, 1:d1⸩` : two choices with base type Number
*   `b0⸨0:{x:i1}, 1:{y:i1}⸩` : two choices with base type Struct
*   `b0⸨0:None, 1:i1⸩` (choice with base type `None` must follow choice with
    base type `Number`)

The required ordering of unions is fixed by assigning a (long) sortOrder to each
base type, and requiring that a union's choices be in strictly ascending
sortOrder.

While most base types are assigned distinct sortOrders, array base types are
treated specially. There are distinct base types for each array length (i.e.
`[]`, `[3]`, and `[5, 6]` each have different base types), but all array base
types have the same sort order; as a result, a union can contain at most one of
them. For a template to represent arrays of varying lengths it must use a new
(non-compositional) base type “varray” (“variable length array”), which also has
the same sortOrder as the fixed-length array base types.

### FrameLayouts

Each non-compositional Retrospect base type (such as String) has an associated
Java subclass of Value, and a refvar of that base type will always be bound to
an instance of that subclass.

Compositional base types do not have their own subclasses of Value.
CompoundValue can be used to represent values of any compositional base type,
but its lack of constraints would make efficient byte code generation difficult.
Instead refvars with a compositional base type must also specify a
*FrameLayout*, and the refvar can only be bound to Values that use that
FrameLayout.

There are two FrameLayout subclasses, RecordLayout and VArrayLayout,
corresponding to the first two cases in the "Uses" section above:

*   A RecordLayout specifies a compound template, and each Value using that
    layout is a Frame that will be interpreted using that template.

*   A VArrayLayout specifies a template for the array elements, and each Value
    using that layout is a Frame with an array length plus an appropriately
    typed and sized Java array for each of the template's variables.

RecordLayouts are written as `*` followed by the compound template;
VArrayLayouts are written as `*[]` followed by the element template. For
example,

*   a refvar with a RecordLayout representing a 3-element array of doubles would
    be written `x0:*[d0, d8, d16]`; and
*   a refvar with a VArrayLayout representing a variable-length array of doubles
    would be written `x0:*[]d0`.

Values of the first refvar would be Frames with at least 24 bytes to store the 3
doubles; values of the second refvar would be Frames with at least 4 bytes to
store the length and one pointer to store the byte[] containing the doubles.

### Untagged unions

In some cases it is not necessary to allocate a tag for the union. If

*   each of the union's choices are either a refvar or a singleton,
*   at least of the choices is a refvar, and
*   all of the refvar choices have the same index

then the type of that refvar is used to discriminate the union, and a singleton
choice is represented by storing that singleton in the refvar.

For example, the template `⸨x0:*[]d0, None, x0:String⸩` can represent a
variable-length array of doubles, or `None`, or a String. It uses only a single
refvar (`x0`) and its value is the value of that refvar, which must be one of
those three choices.

### TemplateBuilders and VarAllocators

Templates are immutable. New templates are constructed by using instances of
TemplateBuilder. The most common pattern is:

*   Start with an empty TemplateBuilder.

*   Add one or more values that the final template must be able to represent to
    the TemplateBuilder.

*   Construct a Template from the TemplateBuilder.

For example, starting with an empty TemplateBuilder and adding `3` and `4` to
it, then building a template would produce the template `b0` (the simplest
template that can represent both those values). If we also added `None` to that
builder we would get `b0⸨0:b1, 1:None⸩`.

Another example: starting with an empty builder and adding the ranges `0..1` and
`1..` (aka `Range(0, 1)` and `Range(1, None)` would produce the template
`Range(b0, b1⸨0:1, 1:None⸩)` (i.e. a Range whose lower bound is specified by a
uint8 variable, and whose upper bound is either `1` or `None` depending on the
value of a second uint8 variable).

A final example: starting with an empty builder and adding `[1]` and `[4, 5000]`
would produce the template `x0:*[]i0`, i.e. a refvar whose FrameLayout is a
variable-length array of integers.

Building a template actually requires a second argument: a *VarAllocator* that
sets the policy for how variable indices are assigned. For example, given a
TemplateBuilder representing a (fixed-length) array of two doubles, we would
build the template `[d0, d8]` using the VarAllocator for RecordLayouts (where
indices are interpreted as offsets into a sequence of bytes), but `[d0, d1]`
using the VarAllocator for ArrayLayouts (where indices specify which Java array
to use).

VarAllocators also affect how var indices may be reused in unions. For example,
consider a TemplateBuilder with two choices:

*   a two-element array whose first element is specified by an int32 variable
    and whose second element is determined by a float64 variable, or

*   a Range whose upper and lower bounds are specified by int32 variables.

The template built for a RecordLayout would be `b0⸨0:[i4, d8], 1:Range(i4,
i8)⸩`. Note that the two choices in the union use overlapping bytes for their
representations.

The template built for an ArrayLayout, on the other hand, would be `b0⸨0:[i1,
d2], 1:Range(i1, i3)⸩`, i.e. the varray would be implemented using

*   a `byte[]` (`b0`) for the tags;

*   a `byte[]` used as an `int[]` (`i1`) for either the first element of the
    array or the lower bound of the range, depending on the value of the
    corresponding tag;

*   a `byte[]` used as a `double[]` (`d2`) for the second element of the array
    if the corresponding tag is 0, or unused otherwise; and

*   another `byte[]` used as an `int[]` (`i3`) for the upper bound of the range
    if the corresponding tag is 1, or unused otherwise.

(While it would be possible to save some space by storing the upper bounds in
the same arrays as the doubles, doing so would add some complexity; we can
revisit that decision later.)

Instead of starting with an empty TemplateBuilder, any existing Template can be
coerced to a TemplateBuilder with `toBuilder()`. Adding values to an existing
Template and then building will return the original Template if possible (i.e.
if it already included all the values added, and the VarAllocator used to build
assigns the same indices to each variable).

### Template Weights

In order to bound the number of Java locals our generated code will use, we may
need to bound the number of variables in a template. We do this by defining the
*total weight* of a Template as the number of variables in it (with some
adjustment for unions, where separate choices may reuse the same variables).
TemplateBuilders track their current total weight, and if it gets too high they
provide a method (`insertFrameLayout()`) that can identify a suitable part of
the template to be replaced with a newly-created FrameLayout.

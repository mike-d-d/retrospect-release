# Harmonization by ValueMemos

The VM uses ValueMemos to record the structure of values that are produced at a
particular stage of the computation:

*   A ValueMemo at each call site records the values returned by that call.
*   A ValueMemo records the argument values passed to most MethodMemos, and the
    values returned from them. (This is skipped for the simplest built-in
    methods, to reduce the amount of bookkeeping; see `fixedMemo` in
    `VmMethod.java` for details.)
*   A ValueMemo records the values passed to each continuation of a built-in
    method.
*   When multiple code paths in an InstructionBlock converge at a BranchTarget,
    a ValueMemo records the values of locals that might have been set
    differently on the different paths.

The primary purpose of keeping all these records is to support code generation:
ValueMemos provide the information needed to choose an efficient representation
for each intermediate value when generating code.

A secondary effect of the ValueMemos is *harmonization*: each ValueMemo chooses
a single representation for each baseType it sees, and if it sees arrays it
chooses a single representation for all arrays. This requirement keeps the
generated code from getting too complex, but means that ValueMemos may modify
the representation of values in addition to recording them.

For example, if the first array value recorded by a ValueMemo is a fixed-length
array of 2 elements, it will record that arrays are represented as fixed-length
pairs. If it subsequently encounters an array of 3 elements, it will switch to
using variable-length arrays for all array values. Once it has made this switch,
each fixed-length array that it encounters will be converted to an equivalent
variable-length array.

As another example, if it encounters a Frame with a baseType MyType, any
subsequent value with the same baseType will be converted to a Frame with the
same layout. If the value is already a Frame with type MyType but a different
layout, the two layouts will be merged and the Frame will be updated to use the
merged layout.

# Original mixed string and bit-vector formulas

`KNoodlerHybridSolver` accepts ordinary KSMT Boolean, string, integer and native
bit-vector assertions with any of `KNoodlerBackend.Z3`, `CVC5` and `YICES`.
Original bit-vectors keep their declared widths, modular arithmetic, signed and
unsigned comparisons, and exact model values. This works with either generated
`INT` or `BV` realization encoding; the generated encoding target does not change
input bit-vector semantics.

```kotlin
val bits = ctx.mkConst("bits", ctx.mkBvSort(8u))
val text = ctx.mkConst("text", ctx.stringSort)
KNoodlerHybridSolver(ctx, KNoodlerBackend.YICES).use { solver ->
    solver.assert(ctx.mkEq(ctx.mkStringLen(text), ctx.mkBv2IntExpr(bits, false)))
    solver.assert(ctx.mkEq(bits, ctx.mkBv(3, 8u)))
    solver.assert(ctx.mkEq(text, ctx.mkStringLiteral("abc")))
    check(solver.check() == KSolverStatus.SAT)
}
```

The accepted BV fragment includes constants of arbitrary positive widths;
bitwise operations and reductions; modular arithmetic including signed and
unsigned division, remainders and signed modulo; signed and unsigned comparisons;
concatenation, extraction, extension and repetition; shifts and rotations; the
KSMT overflow/underflow predicates; and signed or unsigned `mkBv2IntExpr`.
Boolean connectives, equality, distinctness and ITEs can connect those operations
to supported string equations, regex membership and mathematical integer lengths.
The signed conversion subtracts `2^width` for a set sign bit; the unsigned
conversion returns a nonnegative integer. String lengths and original integer
arithmetic remain mathematical integers, including after these conversions.

The current KSMT expression API has no integer-to-BV expression; this wrapper does
not invent a conversion API. Arrays, quantifiers, uninterpreted functions,
floating-point and real arithmetic remain unsupported, as do string operations
outside the existing equation/concatenation/length/ground-regex fragment.
`checkNoodlerHybridSupport` checks this syntax without creating a backend;
`checkNoodlerYicesSupport` remains its compatibility name. Backend resource
limitations can still produce UNKNOWN. Unsupported input is reported, never
dropped from the asserted formula.

Translation visits and reconstructs native BV applications with their original
KSMT declarations and translated operands. Variable rotations are lowered exactly
to unsigned remainder, shifts and bitwise OR because cvc5 does not expose a native
variable-rotation operation; indexed rotations remain native. BV-to-integer conversion operands may
therefore depend on the Boolean/string abstraction without losing sharing.
Returned detached models contain original declarations only, with BV sorts and
values preserved exactly. Before returning SAT the wrapper evaluates every
original assertion, including the mixed Boolean dependencies and conversion
expressions, against the complete reconstructed assignment. The existing core
model evaluator supplies native BV evaluation; ground regex evaluation is added
by the hybrid model.

`KNoodlerMixedBitVectorTest` runs the same control, mixed conversion, overflow,
width, signedness, operator-family, scope and assumption checks against all three
backends and both generated encoding targets.

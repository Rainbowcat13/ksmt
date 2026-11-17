# Hybrid configuration

The shared hybrid algorithm accepts ordinary KSMT string assertions. Select its backend in
`KNoodlerHybridSolver`'s constructor and select the generated encoding independently:

```kotlin
KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3).use { solver ->
    solver.configure {
        encodingTarget = NoodlerEncodingTarget.BV
    }
    solver.assert(ctx.mkEq(ctx.mkStringLen(x), ctx.mkIntNum(3)))
    solver.check()
}
```

`INT` remains the default. `BV` encodes character values and DFA state identifiers during exact
fixed-length realization. Characters cover all values from 0 through `0x2FFFF`; states use their
own sufficient width. String lengths, input mathematical integers, and Boolean/length search
remain unbounded integers. Encoding selection does not reinterpret original BV formulas or
convert unrestricted integer arithmetic into wrapping arithmetic. Exhausted candidate resources
return `UNKNOWN`; failure at a finite length bound does not establish global `UNSAT`.

Call `configure` before the first check. Assertions can be buffered before configuration. Options
are copied when configured and applied to each fresh backend context. Hybrid options use the
`noodler.` namespace, including `setStringParameter("noodler.encodingTarget", "BV")` for generic
configuration/worker serialization. Other parameter names go to the selected backend using the
ordinary KSMT configuration methods; unsupported options are rejected by that backend.

`KNoodlerYicesSolver(ctx)` and `KNoodlerYicesSolverConfiguration` remain source compatibility
entry points for Yices. Existing `setYicesOption` and `yices.` parameter names remain supported
for Yices and explicitly fail when applied to another backend. The native-string preprocessing
solver `KNoodlerSolver` is a distinct algorithm; backend selection never silently selects it.

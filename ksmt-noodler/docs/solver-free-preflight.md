# Solver-free UNSAT preflight

`KNoodlerHybridSolver` buffers ordinary assertions, tracked assertions and scopes.
Before constructing a backend, each check tries sound proofs from necessary
conjuncts: opposite Boolean literals, empty/intersected regular languages,
incompatible word equations, and certified common-period words whose lengths
must agree but which are required to differ. It never treats an atom inside an
arbitrary disjunction or implication as an unconditional fact.

```kotlin
val factory = KNoodlerBackendFactory { error("Backend should not be needed") }
KNoodlerHybridSolver(ctx, factory).use { solver ->
    val b = ctx.mkConst("b", ctx.boolSort)
    solver.assert(b)
    solver.assert(ctx.mkNot(b))
    check(solver.check() == KSolverStatus.UNSAT)
    val stats = requireNotNull(solver.lastCheckStatistics)
    check(stats.backendConstructions == 0)
    check(stats.backendAssertions == 0L)
    check(stats.backendChecks == 0)
}
```

`backendConstructions` counts factory invocations, `backendAssertions` counts
individual generated assertion expressions submitted to either backend (a batch
counts its elements), and `backendChecks` counts check/check-with-assumptions
calls. The counters include Boolean/length search and character realization.
`preflightReason` identifies a completed solver-free proof.

The proof is optional and bounded by a small share of the check's preprocessing
allowance. Failed certificates and exhausted proof resources produce no learned
constraint and solving continues normally. A cancelled or expired check returns
UNKNOWN. No resource failure establishes UNSAT. Current assumptions participate
only in the current check; no proof or learned constraint survives a check or
pop. UNSAT cores retain the wrapper's conservative tracked-assertion/assumption
contract.

This guarantee applies to the universal hybrid API and its Yices adapter.
`KNoodlerSolver` is the distinct native-string preprocessing API whose caller
supplies an already constructed backend.

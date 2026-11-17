# Shared hybrid solver

`KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3)` accepts ordinary KSMT Boolean
assertions containing the supported string fragment. `CVC5` and `YICES` select
the same hybrid algorithm with different arithmetic backends. The default is
Yices. `KNoodlerYicesSolver(ctx)` is a compatibility entry point for that default.
`KNoodlerSolver` remains the separate native-string preprocessing strategy.

```kotlin
KContext().use { ctx ->
    KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3).use { solver ->
        val x = ctx.mkConst("x", ctx.stringSort)
        solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("hello")))
        check(solver.check() == KSolverStatus.SAT)
        println(solver.model().eval(x))
    }
}
```

Configure before the first check, including a check that returns UNKNOWN or
finishes without native solving. Backend-specific options are applied to both
native roles. An unsupported option is rejected explicitly by that backend;
legacy `setYicesOption` requires Yices. The encoding target is independent of
backend choice; see [encoding configuration](hybrid-configuration.md).

The wrapper buffers assertions and scopes. Each check creates independent search
and character-realization solver instances and closes them before returning,
including when a check fails. Both use the caller's KContext for expressions, but
their native contexts and assertion stacks are separate. Learned clauses do not
survive checks. Models are detached and contain original input declarations,
without generated length, character, automaton-state or Boolean-proxy symbols.
An UNSAT core conservatively contains the active tracked assertions and current
assumptions; it is valid relative to the untracked assertions, but is not minimal.

An injected `KNoodlerBackendFactory` must return a fresh, exclusively owned
`KSolver` on every call and support the generated Boolean/integer/BV fragment.
The wrapper owns returned solvers; it never closes the caller's KContext. A
caller-owned mutable solver is unsuitable as a factory result. The built-in
backend choices implement this factory contract. Backend instances are used
sequentially on the checking thread; concurrent mutation of a KContext remains
subject to ordinary KSMT context rules. `interrupt` cancels current preprocessing
and interrupts the active native check. Close after the checking thread returns.

The algorithm is sound but incomplete: fixed-candidate failure only excludes
that candidate; bounded search or resource exhaustion returns UNKNOWN unless
the unbounded abstraction itself proves UNSAT. SAT requires reconstruction and
validation against every original assertion and assumption.

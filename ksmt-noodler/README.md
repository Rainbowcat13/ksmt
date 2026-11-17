# KSMT Noodler hybrid solver

This module is our Kotlin implementation of automata-based string reasoning. It
is separate from the official Z3-Noodler solver.

`KNoodlerHybridSolver` runs one shared Boolean/length-search and fixed-length
character-realization algorithm with Z3, cvc5 or Yices. Assert ordinary KSMT
formulas; the wrapper translates supported string operations before invoking its
backends. `KNoodlerYicesSolver(ctx)` remains the Yices compatibility entry point.
`KNoodlerSolver(ctx, nativeStringSolver)` remains the distinct native-string
preprocessing strategy.

```kotlin
KContext().use { ctx ->
    KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3).use { solver ->
        solver.configure { encodingTarget = NoodlerEncodingTarget.BV }
        val text = ctx.mkConst("text", ctx.stringSort)
        solver.assert(ctx.mkEq(text, ctx.mkStringLiteral("hello")))
        check(solver.check() == KSolverStatus.SAT)
        solver.model().use { println(it.eval(text)) }
    }
}
```

Configure before the first check. Backend selection is independent of generated
encoding: `INT` is the default; `BV` uses unsigned 18-bit character values and
sufficient widths for automaton states. String lengths and mathematical integer
arithmetic remain unbounded. Original native BV input is supported independently
of that encoding setting and retains its widths and modular semantics.

The supported fragment includes Boolean structure, string variables/literals,
concatenation, string ITE, equality/disequality, ground regex membership, integer
length constraints and the documented native BV operations/conversions. SAT
requires a complete assignment validated against the original assertions.
Resource exhaustion, unsupported syntax and inconclusive bounded search never
establish UNSAT. The algorithm is incomplete for unrestricted word equations
with lengths.

The wrapper buffers scopes and assertions. Selected necessary-conjunct
contradictions can finish in preflight with zero backend constructions,
assertions or checks. Search and realization otherwise use separately owned
backend instances. Models expose original declarations and hide generated
symbols. See the following references for the exact contracts:

- [Public hybrid API and lifecycle](docs/hybrid-api.md).
- [Configuration and migration](docs/hybrid-configuration.md).
- [Internal finite-domain BV encoding](docs/bit-vector-encoding.md).
- [Original mixed string/BV fragment](docs/mixed-bitvectors.md).
- [Solver-free UNSAT guarantees and counters](docs/solver-free-preflight.md).
- [Existing KSMT portfolio integration](docs/hybrid-portfolio.md).
- [Runnable library examples](../examples/README.md).

Build and test with JDK 17 from the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:test --console=plain
```

Executable benchmark orchestration, analysis code, raw new measurements and logs
live in [the sibling experiments workspace](../../ksmt-noodler-experiments/README.md),
at `C:\Users\danii\ksmt-noodler-experiments`. Its composite Gradle build consumes
the current solver checkout. The solver repository no longer provides benchmark
launch tasks, diagnostic runners or Python experiment scripts.

[Migration details](docs/benchmark-migration.md) list the removed and preserved
components and the hash manifests. Existing local corpora, correctness fixtures,
historical measurements and provenance remain intact. The
[historical evidence archive](benchmarks/results/README.md) predates the shared
hybrid implementation; its measurements are not results for the final build.

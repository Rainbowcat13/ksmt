# Noodler and Yices

`KNoodlerYicesSolver` combines regular-language reasoning with two independent Yices contexts. One context searches Boolean choices and integer lengths. The other realizes a proposed choice using integer character positions and automaton transitions. Repeated occurrences of a variable share the same characters.

The existing `KNoodlerSolver(ctx, baseSolver)` wrappers for Z3 and cvc5 remain available. The Yices combination is a separate solver: it does not forward string expressions to Yices and does not fall back to Z3 or cvc5.

## Use

```kotlin
import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import kotlin.time.Duration.Companion.seconds

KContext().use { ctx ->
    with(ctx) {
        val x = mkConst("x", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.configure {
                maxIdleRounds = 2
                maxCharacterPositions = 100_000
                maxEncodingConstraints = 1_000_000
                maxPreprocessingUnits = 1_000_000
                maxAutomataStates = 100_000
                maxAutomataStorage = 1_000_000
                optionalPreprocessing = false
                smallFirstSearch = false
                adaptiveLengthProjection = true
            }
            solver.assert(mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))))
            solver.assert(mkEq(mkStringLen(x), mkIntNum(4)))

            when (solver.check(10.seconds)) {
                KSolverStatus.SAT -> solver.model().use { println(it.eval(x)) } // "abab"
                KSolverStatus.UNSAT -> println("unsatisfiable")
                KSolverStatus.UNKNOWN -> println(solver.reasonOfUnknown())
            }
            println(solver.lastCheckStatistics)
        }
    }
}
```

Configure the solver before its first check. A check uses one overall timeout across search, automata processing, and candidate realization. `interrupt()` cancels an active check.

## Supported formulas and results

The supported fragment is quantifier-free Boolean formulas over string variables, literals, concatenation, string-valued `ite`, string equality/disequality, ground regex membership, and linear integer arithmetic including string lengths. Boolean structure and negative regex membership are preserved. The syntax boundary requires literal multiplication coefficients and nonzero literal divisors, plus literal operands for `str.to_re` and `re.range`; the context may simplify constant expressions into these forms.

Regex reasoning derives accepted-length constraints, including periodic lengths such as the even lengths of `(ab)*`. Candidate realization checks characters jointly; equal lengths alone never prove string equality. Proven infeasible combinations of atom choices and lengths produce guarded constraints for the next search round.

The default `adaptiveLengthProjection` first extracts shortest/longest accepted lengths and modular residues from the useful automaton graph. It then attempts exact periodic projection within 256 reachable subsets; exceeding this allowance retains both the graph constraints and the already certified finite prefix of accepted lengths, allowing Yices to reject impossible short candidates before character realization. A bounded check-local cache reuses projections. Setting it to `false` restores exhaustive length projection for comparison. Common prefixes/suffixes in positive word equations are cancelled before regular propagation. See the [length-projection experiment](docs/length-projection.md) for examples, controls, and measurements.

Regex compilation batches concatenation, union, and repetition into a shared epsilon-NFA before determinization; complement, intersection, and difference remain exact compilation boundaries. Temporary barrier automata are released after their last consumer. Exact candidate encoding prunes transitions by position and membership polarity, including known literal segments. Check statistics expose batched determinizations, retained intermediate automata, unpruned/emitted transition counts, and charged encoding units. See the [compiler and positional-pruning experiment](docs/automata-encoding.md) for measured coverage changes, counter definitions, and explanations of the proposed worklist and conflict-core improvements.

- **SAT** includes a joint model validated against the original assertions.
- **UNSAT** follows from sound constraints in the unbounded search problem.
- **UNKNOWN** reports unsupported operations, a timeout, interruption, exhausted resources, or the configured number of idle rounds. There is no completeness guarantee for unrestricted word equations with lengths.

Stable regular-language branches reuse their published consequences across different length candidates. A full sweep that changes no language marks that check-local branch as stable; later candidates can skip the same refinement and projection work. `refinementSweeps` and `refinementSkips` expose this behavior. See the [full comparison run](docs/combined-comparison.md) for its regression test, live benchmark status, and final table locations.

`maxIdleRounds` defaults to 2. An idle round has no fresh justified constraint or proven refinement. `maxCharacterPositions` defaults to 100,000 and limits the size of one realization query. `maxEncodingConstraints` defaults to 1,000,000 and limits materialized character equalities and automaton transition cases. `maxPreprocessingUnits` defaults to 1,000,000 and budgets syntax expansion, code-point copies, and refinement work. Exceeding a resource limit returns UNKNOWN; it never removes lengths from the search problem or proves UNSAT.

Substring, replacement, conversions, symbolic regexes, nonlinear arithmetic, and quantifiers are outside this fragment. `lastCheckStatistics` reports rounds, learned constraints, realization checks, phase timings, and the termination reason.

`maxAutomataStates` and `maxAutomataStorage` configure the hybrid's automata, including derived operations and model validation. Storage is an accounting allowance for entries/work, not bytes of RAM. These settings do not alter the Z3/cvc5 wrappers' defaults. The experimental `optionalPreprocessing` policy attempts exact realization before optional length/refinement analysis and caps optional work separately within the overall preprocessing budget. The `smallFirstSearch` policy tries temporary total-length bounds `0, 1, 2, 4, ...`; only an unbounded contradiction establishes UNSAT. Both policies default to `false`. See the [policy and cap ablation](docs/ablation-run.md) for configuration, the experiment matrix, and results.

## Unicode and solver state

String semantics use code points in the SMT-LIB range `0..0x2FFFF`, including NUL. Kotlin text is accepted by `mkStringLiteral(String)`. To distinguish explicit surrogate code points from a supplementary character, use the integer-array overload:

```kotlin
val supplementary = ctx.mkStringLiteral(intArrayOf(0x1F600)) // length 1
val surrogateSequence = ctx.mkStringLiteral(intArrayOf(0xD83D, 0xDE00)) // length 2
```

Use `KStringLiteralExpr.codePoints` for exact values. Its `.value` property is a compatibility text projection and throws if UTF-16 would merge distinct surrogate characters. SMT-LIB printing and serialization preserve exact code points.

`push`, `pop`, `assertAndTrack`, and `checkWithAssumptions` follow the `KSolver` interface. Learned constraints are local to a check. Models expose original declarations and hide generated symbols. Assertion-stack changes invalidate subsequent `solver.model()` access. Both Noodler integrations already return detached snapshots, so previously retrieved models remain usable across solver changes or closure while their `KContext` remains open. Hybrid unsat cores are conservative and may contain all original tracked assertions and assumptions from that check; legacy `KNoodlerSolver` does not support unsat cores.

## Tests and benchmarks

Build this repository with JDK 17. Its Kotlin 1.7 compiler has existing compatibility problems with newer JDK APIs. On this Windows workspace:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:test
.\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers
```

The comparison runs five explicit backends: `z3`, `cvc5`, `noodler-z3`, `noodler-cvc5`, and `noodler-yices`. Each sample/backend/trial starts a fresh JVM, sequentially. The defaults are one trial, 30 seconds for startup plus parsing, 10 seconds shared by assertion and solving, and 10 seconds for independent witness validation. The parent also enforces phase deadlines and drains stdout/stderr continuously.

The source defaults to the local `ksmt-test/testData/string-data` corpus. Every selected `.smt2` file is hashed into the manifest; the shared hybrid syntax checker records unsupported formulas in `exclusions.csv` instead of silently filtering them out. Standalone Yices and Bitwuzla do not support the string fragment and are not comparison backends.

SAT witnesses fix **every original Boolean, integer, and string constant** before checking the original formula with another solver: cvc5 validates Z3-based backends, and Z3 validates the others. All backends share the Z3 SMT-LIB frontend, and validation reuses the parsed KSMT assertions; parsing itself is not independently checked. Raw solver status is retained separately from invalid/unverified witnesses, native UNKNOWN, external timeout, unsupported operations, parsing errors, and worker crashes. An external solve timeout records `NOT_RETURNED` when the backend never returned a status. Different satisfying assignments are not disagreements.

Each invocation creates `build/reports/noodler-benchmarks/<run-id>/` containing `manifest.csv`, `exclusions.csv`, `metadata.properties`, `raw.csv`, `summary.md`, per-attempt logs, and immutable checkpoint records. Timings separate JVM startup, parsing, setup, assertion/check, validation, and total wall time. Legacy preprocessing skip flags and reasons are included. Metadata identifies the exact runtime binaries, source hashes, JVM, OS, Git state, and configuration. Existing corpus files and historical results are preserved.

For a bounded smoke run or an interrupted full run:

```powershell
.\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers -PnoodlerBenchmarkMaxJobs=25
.\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers '-PnoodlerBenchmarkResume=C:\path\to\run-id' -PnoodlerBenchmarkMaxJobs=500
```

`MaxJobs` and `MaxWallMs` limit only the current invocation; resume processes the remaining jobs without repeating completed attempts. These invocation limits are checked between attempts, so an active worker can finish after `MaxWallMs`. Resume requires identical binaries, source contents, solver selection, repetitions, and timeouts. A process interruption before checkpoint commit may retry that unfinished attempt, retaining its previous log. CSVs and summaries can be reconstructed from committed records.

All Gradle options use the `noodlerBenchmark` prefix:

| Suffix | Purpose / default |
|---|---|
| `Source`, `Output` | Corpus directory; parent report directory |
| `Resume` | Existing run directory |
| `Solvers` | Comma-separated IDs; all five |
| `SolveMs`, `StartupParseMs`, `ValidationMs` | 10000, 30000, 10000 |
| `Repeats` | Trials per sample/backend; 1 |
| `Prefix`, `Offset`, `Limit` | Deterministic selection from sorted source paths |
| `MaxJobs`, `MaxWallMs` | Stop after completed jobs or elapsed invocation budget |
| `Variant` | `baseline`, individual `states/storage/encoding/preprocessing/positions-2x/4x`, `optional`, `small-first`, `combined` |
| `SourceList` | UTF-8 file of exact relative corpus paths, one per line; cannot combine with offset/limit |
| `MaxAutomataStates`, `MaxAutomataStorage` | Hybrid automata state/storage overrides |
| `MaxEncodingConstraints`, `MaxPreprocessingUnits`, `MaxCharacterPositions`, `MaxIdleRounds` | Hybrid resource/progress overrides |
| `OptionalPreprocessing`, `SmallFirstSearch` | Explicit `true`/`false` policy overrides |
| `AdaptiveLengthProjection` | Graph constraints with bounded exact projection (`true`); exhaustive exact projection (`false`) |

The direct entry point is `io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt`: `run` accepts corresponding kebab-case flags (for example `--solve-ms`), and `summarize --resume PATH` refreshes a report without rerunning solvers.

To repeat the best configuration from the policy/cap matrix and export a white-background PNG table, see [the repeat and table commands](docs/ablation-run.md#repeat-the-best-measured-variant-and-save-the-table). The exporter also writes SVG, CSV, and Markdown copies and audits completeness before publishing a final table.

`runNoodlerQfSimpleTests` and `launchNoodlerQfSimpleTests` remain compatibility aliases, using the prepared corpus and the new isolated harness. Preparation reuses an existing manifest and preserves historical files. The old `run-single --backend z3|cvc5|yices` diagnostic remains available. The historical `NoodlerStringBenchmarksBasedTest` recorder is disabled because its thread timeout cannot terminate native solver work. Its saved timings are not comparative performance evidence; use the subprocess task above.

See [the architecture overview and review](docs/overview-and-review.md) for implementation details, known limits, and the assessment of historical results. One trial establishes coverage and detects regressions; it does not establish a statistical speed ranking.

### Comparing a run with an explicitly recorded rerun

If a host interruption invalidates a measurement, preserve the original run and rerun the affected case across all five backends with identical binaries and budgets. The standalone Python 3.9+ analyzer verifies complete groups and comparable configurations before substituting only explicitly named cases:

```powershell
python ksmt-noodler/scripts/analyze_benchmark.py --primary C:\path\to\primary-run --supplemental C:\path\to\rerun --replace-case QF_S/family/case.smt2 --replacement-reason "Recorded host interruption" --output C:\path\to\new-analysis
```

The separate output contains `comparison.csv`, `analysis.md`, and `provenance.json`. Source run IDs and hashes identify every replacement; original inputs remain unchanged. The report includes family coverage, correctness checks, paired timings, coverage gains and losses, and resource-limit reasons. Publication fails on incomplete or incomparable inputs. Run `python ksmt-noodler/scripts/analyze_benchmark.py --self-test` for its synthetic validation checks. See [the full-corpus run record](docs/benchmark-run.md) for this workspace's measurements.

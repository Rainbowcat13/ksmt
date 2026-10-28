# Noodler integration and correctness review

`ksmt-noodler` provides regular-language propagation for string equations and combines it with native SMT solvers. The integrations have two different responsibilities:

| Integration | Regular-language reasoning | Final decision and model |
| --- | --- | --- |
| `KNoodlerSolver(ctx, KZ3Solver(ctx))` | One sound Unicode automata refinement sweep; strengthened languages become regex constraints | Z3 checks the original formula plus refinements and supplies the joint model |
| `KNoodlerSolver(ctx, KCvc5Solver(ctx))` | The same refinement engine | cvc5 checks the original formula plus refinements and supplies the joint model |
| `KNoodlerYicesSolver(ctx)` | Unicode automata refine Boolean/string/length candidates | One Yices context searches Boolean choices and lengths; another checks exact characters at a proposed length vector |

The Yices combination never sends strings to native Yices. Repeated occurrences of a variable share character variables, and infeasible realizations block only their complete Boolean/length candidate. A reported SAT witness is checked against the original assertions. Unsupported operations, exhausted budgets, interruption, or timeout return UNKNOWN. This remains an incomplete procedure for unrestricted word equations with lengths.

`maxPreprocessingUnits` defaults to 1,000,000 and bounds cumulative syntax, expansion, and validation work within one check. It is separate from character-position and character-encoding limits. These are resource limits, not bounds on the mathematical string domain; exhausting one cannot establish UNSAT.

String values use exact code points in `0..0x2FFFF`, including NUL and explicit surrogate code points. The surrounding work also added exact string-value construction, simplification, Z3/cvc5 conversion, and runner serialization. Use `KStringLiteralExpr.codePoints` when preserving the distinction between supplementary characters and separate surrogate characters matters.

## Correctness findings and repairs

The review reproduced the first five failures below against the previous compiled implementation. Targeted regressions now cover the repairs; passing an unrelated benchmark does not establish that a defect is fixed.

| Finding | Reproduction / consequence | Repair |
| --- | --- | --- |
| Legacy Kleene star reused an accepting start incorrectly | `(a* b)*` accepted `"a"`; its complement could reject a satisfiable formula | Give the independently usable NFA closure a fresh accepting start; use the symbolic Unicode compiler for solver preprocessing |
| Legacy segmentation discarded cyclic paths | `x ∈ (ab)*`, `y ∈ {ab}`, `t = xy`, `x = y` was rejected despite `x = y = ab, t = abab` | Replace simple-path segmentation with full boundary reachability and quotient propagation |
| An inferred finite alphabet was used as the SMT string universe | `x ∈ re.allchar`, `x ∈ {Ā}` was rejected; even unconstrained variables without Unicode literals could be restricted incorrectly | Share full-domain interval automata and key variables by declaration identity |
| Legacy wrapper discarded the backend model | For `i = 1`, checking returned SAT but the wrapper model evaluated `i` to zero and the assertion to false | Return the backend's complete joint model, detached before temporary scopes are removed |
| Recursive hybrid translation overflowed the stack | A 10,000-level concatenation threw `StackOverflowError` | Iterative traversal, shared term DAGs, and preprocessing limits checked before expansion |
| Timeout and interruption excluded legacy preprocessing | The backend received a fresh full timeout after potentially unbounded automata work | One check-wide deadline and cancellation signal, with the remaining time passed to the backend |
| Custom-expression model wrapper omitted `close()` | The example did not implement the current `KModel` interface | Delegate model closure; independently test closure and detachment |
| Custom-expression cores exposed rewritten assertions | A custom tracked assertion returned as its ordinary Boolean rewrite | Maintain scoped origin mappings and per-check reverse multimaps, including assumptions and rewrite collisions |

The direct `Noodler` core is a propagator, not a source of joint models. Its compatibility `solve()` result means either a proven contradiction or that propagation did not find one. Query a `KSolver` after SAT to obtain a model. The deprecated `Noodler.model()` now throws an unsupported-feature exception. This changes the direct-core API: the former string-to-NFA diagnostic maps are replaced by `inputRegularConstraints` and `derivedRegularConstraints`, declaration-to-regex snapshots populated by `propagate()` or `solve()`. The legacy alphabet argument must not narrow SMT-LIB semantics.

Z3/cvc5 wrappers use one propagation sweep before the backend check. They do not seek an unlimited local fixed point: equations such as `x = xy`, with `y ∈ a+`, can repeatedly increase inferred minimum lengths without reaching an empty language. Unsupported or resource-limited preprocessing may be skipped while the original backend formula remains intact. Deadline expiration and cancellation are reported as UNKNOWN.

Preprocessing cooperatively observes cancellation. Prompt cancellation inside a native check still depends on the backend: the current cvc5 adapter's `interrupt()` is a no-op. The benchmark subprocess watchdog is therefore required to enforce a hard deadline even when native code cannot be interrupted.

## Verification

The completed checks passed 157 `ksmt-noodler` tests, 19 core string/native-loader tests, six native conversion/serialization tests, and four custom-expression example tests (186 JVM tests total). This includes subprocess watchdog, recovery, report classification, and independent model-validation regressions. The standalone comparison analyzer also passes 13 synthetic checks covering complete groups, comparable configurations, explicit replacements, Unicode metadata, and publication after successful writes. The `ksmt-test` test sources also compile. The module's Detekt task still fails on 38 existing findings in legacy automata, compatibility runners, and the deprecated analyzer; the new benchmark and hybrid files have no findings. `git diff --check` passes.

The former `NoodlerStringBenchmarksBasedTest` recorder is disabled because cancelling a JVM thread cannot stop an unresponsive native solver. Its supported replacement is `:ksmt-noodler:benchmarkNoodlerSolvers`, which terminates the worker process on timeout.

## How to read the benchmark results

The comparison uses plain Z3, plain cvc5, both Noodler wrappers, and the Yices combination. Corpus eligibility is determined by supported syntax, not by which cases a solver happens to solve. Source paths and hashes, exclusions, solver configuration, and environment details accompany each run.

By default each eligible case is scheduled once per backend with a 10-second assertion/checking limit. Workers are isolated uniformly in fresh JVM processes. Startup/parsing and witness verification have separate limits and timings. A crashed worker, an unsupported operation, an ordinary UNKNOWN, and an external timeout are different outcomes.

For SAT, the harness fixes every original Boolean, integer, and string variable to the returned model value and checks the original assertions with a reference backend. Every backend uses the common Z3 SMT-LIB frontend, and validation reuses its KSMT assertions; this checks the witness independently of the solving backend, but does not independently validate parsing or source translation. A different valid witness is acceptable. An inconclusive verification is not a validated SAT result. Agreement on UNSAT is corroboration, not a checked proof.

Compare coverage by benchmark family before comparing speed. Use paired timings on cases both solvers solve, and inspect invalid witnesses and decisive disagreements before interpreting raw solved counts. This is a single-trial baseline with a fixed solver order; it does not estimate timing variability or remove order-dependent effects from host load and caches. The raw rows and per-worker logs are the source of truth for summaries.

`solve_ms` measures the public assertion/check calls. Legacy wrapper checks include the model snapshot needed before their temporary scope is popped; the hybrid check includes its own witness validation. Independent cross-solver validation is measured separately for every backend. The hybrid `preprocess_ms` diagnostic measures refinement/length propagation and excludes initial abstraction/regex compilation, so it is not directly comparable to the legacy wrapper's total preprocessing field.

### Completed comparison

The [completed benchmark report](benchmark-run.md) covers all 3,248 local sources, of which 752 match the supported syntax. After remeasuring three cases interrupted by host sleep, Noodler+Z3 solved 417, Noodler+cvc5 391, plain Z3 317, plain cvc5 303, and Noodler+Yices 277. All 663 SAT answers passed independent validation; no decisive disagreements or invalid witnesses were observed.

The wrappers improve coverage, with net gains of 100 and 88 cases over their respective base solvers. They usually add time on cases the base solver already solves. The Yices combination contributes 12 UNSAT claims beyond all four alternatives but is frequently limited by automata and encoding budgets. All 200 eligible negated-predicate cases remain inconclusive. These are single-trial results in two QF_S families, not evidence of general completeness or a statistical speedup. The report links the raw data, matched timings, exclusions, and replacement provenance.

### Historical results

The preserved `qf_simple_tests/results.csv` covers 577 cases from two QF_S families and predates the Yices integration:

| Backend | SAT | UNSAT | Inconclusive | Reported solved |
| --- | ---: | ---: | ---: | ---: |
| Z3 | 128 | 47 | 402 | 175 |
| Noodler + Z3 | 65 | 203 | 309 | 268 |
| Noodler + cvc5 | 47 | 221 | 309 | 268 |

All historical decisive answers came from the 377 `hornstr-equiv` cases. The other 200 `negated-predicates` cases were inconclusive for every recorded backend: Z3 reported UNKNOWN and both wrappers reported TIMEOUT. Each result maps uniquely through the historical manifest's copied filename to its full source path, but that manifest has no content hashes establishing the source version at measurement time.

No direct SAT/UNSAT contradictions appear among decisive pairs. However, those runs used unequal timing boundaries and inconsistent witness validation, and the legacy implementation had the defects described above. The apparent coverage increase is therefore not an established correctness or performance improvement. The old `status_match=false` counts mostly include inconclusive outcomes; differing witnesses also do not demonstrate a bug. Historical files are retained separately from corrected measurements.

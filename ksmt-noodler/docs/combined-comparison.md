# Full comparison with all current improvements

The full five-backend benchmark is complete: **752 formulas, 3,760 fresh solver attempts**. Measurement ran on 2026-10-27 from 17:26 to 22:11 UTC. The committed [PNG table](../benchmarks/results/full-defaults/comparison.png), [table CSV](../benchmarks/results/full-defaults/comparison.csv), and [detailed summary](../benchmarks/results/full-defaults/summary.md) contain only this fresh run. The [evidence archive](../benchmarks/results/README.md) describes the preserved files and their checksums.

## Results

| Backend | Validated SAT | UNSAT | Solved / 752 | UNKNOWN | Timeout |
|---|---:|---:|---:|---:|---:|
| Z3 | 173 | 145 | 318 | 0 | 434 |
| cvc5 | 120 | 184 | 304 | 0 | 448 |
| Noodler + Z3 | 178 | 351 | **529** | 0 | 223 |
| Noodler + cvc5 | 134 | 355 | 489 | 0 | 263 |
| Noodler + Yices | 149 | 361 | **510** | 240 | 2 |

All **754 SAT answers** passed independent witness validation. Every case has exactly one immutable result per backend: 3,760 unique keys and records, with no missing/duplicate attempts, syntax exclusions within this selection, worker failures, invalid/unverified witnesses, or decisive disagreements. The largest attempt wall time was 11,097.873 ms, below the audit's host-interruption threshold. UNSAT answers are solver claims without proof certificates; the common SMT-LIB frontend is not independently validated.

Noodler + Z3 has the highest individual coverage. Noodler + Yices solves 192 more cases than plain Z3 and 206 more than plain cvc5, equivalent to 60.4% and 67.8% more solved cases in this run. The union of the two plain solvers covers 365 cases. The hybrid adds 169 beyond that union, while missing 24 cases that at least one plain solver solves. It also solves eight cases outside the union of the other four backends. All five together cover 543 cases.

These are coverage measurements, not a general speed ranking. On the 294 cases both plain Z3 and the hybrid solved, the hybrid's solve time was a median 116.265 ms higher. On the 299 cases shared with cvc5, the median difference was +84.628 ms. One fixed-order trial cannot establish timing stability. This combined run also does not isolate the effect of the small stabilized-branch cache from the preceding compiler, length-projection, and character-encoding improvements.

All 200 `negated-predicates` cases remain unsolved: Z3, cvc5, and their Noodler wrappers time out on all 200; Noodler + Yices returns UNKNOWN on all 200. The 552 `hornstr-equiv` cases account for every decisive result. The hybrid's remaining UNKNOWN outcomes comprise 200 preprocessing allowances exhausted during encoding, 37 character-encoding allowances, two automata-storage allowances during compilation, and one automata-state allowance during refinement. Its two timeouts occur in `hornstr-equiv`.

The stabilized-branch optimization recorded **835 refinement sweeps and 3,991 skipped refinement passes** across the 752 hybrid attempts. These counters count work performed or avoided, not independently attributable coverage gains.

For historical context only, the [previous full comparison](benchmark-run.md) reported 277 hybrid results. The fresh hybrid run has 235 newly decisive cases and two previously decisive cases now inconclusive, for a net increase to 510; there are no old-versus-new decisive answer conflicts. Source hashes match, but the builds and measurement dates differ, so this is not a controlled ablation of any one optimization. No historical rows were substituted into the fresh table.

## Latest small improvement

The simplest safe addition was to reuse a stabilized regular-language branch. A branch is identified by its complete Boolean atom assignment. Its equations and initial membership languages stay fixed, and proposed numeric lengths do not restrict those languages.

After a complete refinement sweep changes no language and all derived length consequences have been asserted, subsequent candidates in the same branch can skip refinement and length projection. Learned character conflicts still reject individual infeasible candidates. The cache belongs to one check and is rebuilt for the next check, including checks with assumptions.

This is a small version of the third proposal, rather than a full dependency queue. The fourth proposal would require grouping and tracking the complete polarity-dependent character encodings, as well as keeping all length dependencies in a core-derived blocker.

The new regression enumerates nine infeasible length vectors of commuting unary strings: one regular-language sweep, eight skipped passes. A following check with an assumption has fresh state and enumerates three vectors: one sweep, two skipped passes. All 226 Kotlin tests passed. Detekt retains its 38 existing findings; the changed hybrid code has no new findings.

The benchmark records `refinement_sweeps` and `refinement_skips` alongside the existing regex, length-projection, and character-encoding statistics.

## Scope and controls

The current 3,248-file corpus was rechecked against the previous complete source manifest: file inventory and every SHA256 matched. The comparison uses all 752 formulas in the common supported syntax, including 552 `hornstr-equiv` and 200 `negated-predicates` cases. The other 2,496 formulas were previously excluded for unsupported syntax; the supported syntax has not changed. Selection does not depend on whether a solver succeeds.

All 752 cases are run on `z3`, `cvc5`, `noodler-z3`, `noodler-cvc5`, and `noodler-yices`: 3,760 fresh sequential process attempts, one trial per backend. Assertion/check has a 10-second deadline, startup/parsing 30 seconds, and independent witness validation 10 seconds. The hybrid uses its default caps and all implemented optimizations; `adaptiveLengthProjection=true`, `optionalPreprocessing=false`, and `smallFirstSearch=false`.

Every runtime classpath entry was copied and hash-verified in a separate snapshot. Later builds do not change this run's binaries. SAT witnesses are checked by another solver. The final table exporter audits completeness, comparison consistency, witness validation, decisive disagreements, and timing flags before publishing PNG, SVG, CSV, and Markdown files.

## Run records and final artifacts

Portable copies are committed under `ksmt-noodler/benchmarks/results/full-defaults/`:

- [Raw rows](../benchmarks/results/full-defaults/raw.csv), [source manifest](../benchmarks/results/full-defaults/manifest.csv), [metadata](../benchmarks/results/full-defaults/metadata.properties), and [source list](../benchmarks/results/full-defaults/source-list.txt) preserve all measured attempts and their configuration.
- [Source audit](../benchmarks/results/full-defaults/source-audit.json) and [runtime inventory](../benchmarks/results/full-defaults/runtime-manifest.json) preserve selection and binary provenance.
- The [archive index](../benchmarks/results/archive-index.json) records original file locations, byte sizes, and SHA256 values. Binary snapshots, per-attempt checkpoint files, and worker logs are not committed. Re-running the strict exporter requires those original logs; see the [archive scope](../benchmarks/results/README.md#archive-scope-and-reproduction).

The original measurement root below is **local-only**, under the ignored build directory. Its links remain useful in the original workspace but are not portable Git artifacts:

`build/reports/noodler-combined-comparison/full-20261027T172513Z/`

- Local-only [final status](../../build/reports/noodler-combined-comparison/full-20261027T172513Z/status.json) records `complete`, all 3,760 attempts, and the original exported artifact paths.
- The local-only [coordinator log](../../build/reports/noodler-combined-comparison/full-20261027T172513Z/launcher.log) records each completed attempt.
- The local-only [complete run directory](../../build/reports/noodler-combined-comparison/full-20261027T172513Z/runs/20261027T172635Z-0654d06e/) contains the original immutable records and worker logs.

The pipeline completed its audit and published `comparison.png`, `comparison.svg`, `comparison.csv`, and `comparison.md` in the run root. Runtime SHA256 is `836bc18bf33ed98e4e94530946b43d6a030a57ff4467b431615fb60a5048252d`; the selected manifest SHA256 is `3e77a8be3377c6cd3f0e4540d3124db4f7ee9d2c0fd25589c07ab14206c4bcd2`. Effective hybrid settings SHA256 is `b3fa5b832ccda92ae2dfd31c658b43394b0e99505bfb0b1a66edf1f905b9b5f5`.

Final publication exposed an exporter-format bug: immutable non-SAT records legitimately omit the `validator` field. The exporter was corrected to require a validator for SAT witnesses while permitting its absence for non-SAT outcomes, with regression tests. Resuming the completed pipeline reran the audit/export only; no solver attempt was repeated and no raw result was changed.

The helper temporarily prevented automatic system sleep while active, allowing the display to turn off. Its execution-state request was cleared on exit. It ran without a visible console window.

In the original workspace only, an interrupted process can restart the same saved pipeline; it resumes completed benchmark checkpoints and verifies the original binaries, sources, settings, and environment. The helper and binary snapshot used by this command are local build artifacts:

```powershell
build/noodler-report-venv/Scripts/python.exe build/reports/noodler-combined-comparison/full-20261027T172513Z/run-comparison.py
```

An active pipeline holds a lock and rejects a duplicate launch.

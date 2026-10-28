# Noodler/Yices policy and resource ablation

Workspace run: [live analysis](../../build/reports/noodler-ablation/20260920-policy-ablation/analysis.md), [derived comparison CSV](../../build/reports/noodler-ablation/20260920-policy-ablation/comparison.csv), [progress and audit status](../../build/reports/noodler-ablation/20260920-policy-ablation/status.json), and [suite configuration](../../build/reports/noodler-ablation/20260920-policy-ablation/suite.json). The report explicitly identifies whether the matrix is complete.

The experiment compares **14 variants** on the **114 historical SAT misses**, then reruns the matrix on **all 752 eligible cases**. All **3,248** corpus files are source-hash checked; the **2,496** unsupported-syntax exclusions from the reference inventory remain explicit. The diagnostic selection is biased toward historical hybrid failures and must not be used as an overall coverage estimate.

The suite produces **1,596 diagnostic attempts** and **10,528 full-set attempts**. Each attempt uses a fresh sequential JVM, 10 seconds shared by assertion/check, 30 seconds for startup/parsing, and 10 seconds for independent Z3 witness validation. Baseline is rerun with the same compiled binaries as the variants; historical timings are not substituted. One fixed-order trial measures coverage and catches regressions but does not establish stable speed rankings.

## Changes and variants

| Variant | Change relative to baseline |
|---|---|
| `baseline` | Original default caps; both experimental policies disabled |
| `states-2x`, `states-4x` | Automaton state cap: 200,000 / 400,000 |
| `storage-2x`, `storage-4x` | Automata storage/work allowance: 2,000,000 / 4,000,000 |
| `encoding-2x`, `encoding-4x` | Per-candidate encoding cap: 2,000,000 / 4,000,000 |
| `preprocessing-2x`, `preprocessing-4x` | Cumulative preprocessing allowance: 2,000,000 / 4,000,000 |
| `positions-2x`, `positions-4x` | Character-position cap: 200,000 / 400,000 |
| `optional` | Exact witness attempt before best-effort length/refinement analysis |
| `small-first` | Temporary total-length bounds 0, 1, 2, 4, ... |
| `combined` | Both policies enabled, with baseline caps |

Only the named cap changes in an individual-cap variant. `maxIdleRounds` stays at 2 throughout; it counts consecutive rounds without progress, not total candidates. Automata storage counts allocation entries/work rather than bytes of memory. The hybrid's caps now propagate through regex compilation, derived operations, refinement, and model validation; wrapper defaults remain unchanged.

Optional preprocessing uses a child allowance of one quarter of the remaining preprocessing budget when optional work first becomes necessary. Child work also consumes the parent budget, preserving an essential remainder. Resource-failed optional preparation is skipped without forgetting already justified lemmas. Global timeouts and cancellation still propagate. Essential regex compilation and exact checking of every original atom remain mandatory.

Small-first search uses internal assumption guards for the sum of string lengths. Exhausting a bound widens it or checks the unbounded abstraction. Only an assumption-free contradiction can produce final UNSAT. Permanent candidate blockers require an exact realization UNSAT result; resource exhaustion never supplies an infeasibility lemma. Reaching the operational character cap remains UNKNOWN.

Statistics now distinguish compilation, accepted-length analysis, branch construction, refinement, character encoding, native realization, and validation. They include optional skips, attempted bounds, candidate total length, encoding estimate, and actual native realization starts. `realizationChecks` includes encoding attempts and should not be read as the number of native solver calls.

Validation before measurement: **183 Noodler JVM tests passed**, including 26 new cap, policy, and harness regressions; **11 ablation audit tests** and **13 existing comparison-audit tests** passed. An additional report-only regression checks that the repeated diagnostic cases in the two stages remain separate. Detekt still reports the 38 pre-existing findings in legacy automaton/runner code; the new production files and policy changes introduce no remaining findings in the checked report.

## Launch or modify

For a single variant through Gradle:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers `
  -PnoodlerBenchmarkSolvers=noodler-yices `
  -PnoodlerBenchmarkVariant=combined `
  -PnoodlerBenchmarkLimit=10
```

Variant presets and effective settings are defined in [BenchmarkVariant.kt](../src/main/kotlin/io/ksmt/solver/noodler/benchmark/BenchmarkVariant.kt). The policies are in [KNoodlerYicesSolver.kt](../src/main/kotlin/io/ksmt/solver/noodler/KNoodlerYicesSolver.kt), and their public configuration is in [KNoodlerYicesSolverConfiguration.kt](../src/main/kotlin/io/ksmt/solver/noodler/KNoodlerYicesSolverConfiguration.kt). Every effective setting is recorded in metadata and immutable attempt records; changing settings or binaries requires a new run.

For the complete matrix, first build/test and write the resolved runtime classpath:

```powershell
.\gradlew.bat :ksmt-noodler:test :ksmt-noodler:writeNoodlerBenchmarkClasspath
python -m unittest discover -s ksmt-noodler/scripts -p test_ablation.py
```

Prepare a new suite. This snapshots the runtime so subsequent local builds cannot change an active experiment:

```powershell
python ksmt-noodler/scripts/run_ablation.py prepare `
  --source ksmt-test/testData/string-data/37c6cd5d98edb46057f30cef35ba7c7250fe0161 `
  --reference build/reports/noodler-benchmarks/20260920-curated-comparison/comparison.csv `
  --manifest build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/manifest.csv `
  --classpath-file ksmt-noodler/build/noodler-benchmark-classpath.txt `
  --java 'C:\Program Files\Java\jdk-17\bin\java.exe' `
  --output build/reports/noodler-ablation/NEW-SUITE

python ksmt-noodler/scripts/run_ablation.py run --suite build/reports/noodler-ablation/NEW-SUITE
```

The same `run --suite` command resumes completed checkpoints. The controller finishes every diagnostic variant before starting the full set. It stops for witness failures, decisive disagreements, or suspicious wall-time outliers, preserving the raw evidence for investigation. It never silently retries and substitutes an unfavorable result.

For a confirmed host interruption, `scripts/curate_ablation.py` reruns exactly the affected stage/variant/source with the frozen runtime and unchanged budgets. It only accepts an original wall time above all three budgets plus ten seconds. Supply `--suite`, `--stage`, `--variant`, `--sample`, and a concrete `--reason`. The original run stays intact; the singleton rerun is under `supplemental/`, and `curations.json` records both checkpoint hashes. The analyzer validates the replacement independently and exposes it in the derived comparison, report, and status. Resume the ordinary suite command after every outstanding audit flag has been resolved. This mechanism is not for replacing ordinary timeouts or choosing a more favorable answer.

During this workspace's sweep, Windows Modern Standby interrupted `QF_S/20250411-negated-predicates/diseq-1-3-6-106.smt2` in `storage-2x` when the lid closed. The corresponding System event evidence is recorded in the suite's `incidents/20260920-lid-sleep.json`. Its original wall time was 1,002,023.7975 ms; the explicit singleton replacement took 10,525.3398 ms. Both outcomes were TIMEOUT, so coverage counts did not change. The original checkpoint and pending next-attempt log remain intact; the sweep resumed from its checkpoints.

## Artifacts and interpretation

`analysis.md` is refreshed during execution and explicitly marks partial results. `comparison.csv` links every derived row to its source run, immutable record, and log. `status.json` reports completed/expected attempts and audit flags. Each run under `runs/<stage>/<variant>/<run-id>/` retains its own `metadata.properties`, `manifest.csv`, `source-list.txt`, `raw.csv`, `summary.md`, records, and logs. `suite.json` and `runtime/manifest.json` record the reference hashes, exact selections, runtime snapshot, and budgets.

The report shows validated SAT, claimed UNSAT, UNKNOWN, timeout, gains/losses against the fresh baseline, and paired median timing differences on cases with the same decisive result. A smaller UNKNOWN count is useful only if those cases become validated SAT or sound UNSAT rather than timeouts. No performance conclusion should be drawn from unfinished rows.

The fresh diagnostic baseline found 11 validated SAT cases among the 114 historical misses (10 previously hit the encoding cap and one timed out). Both policies were disabled and the caps stayed at their original values. These are not optimization gains. The new and historical builds differ; expression ordering also uses identity hashes, so changes in allocation can influence native search order even when the high-level policy is unchanged. That is a plausible source of variation, not a demonstrated causal explanation. All ablation deltas use the fresh baseline from the same frozen runtime.

Refresh an existing report without invoking solvers:

```powershell
python ksmt-noodler/scripts/analyze_ablation.py --suite build/reports/noodler-ablation/NEW-SUITE
```

## Repeat the best measured variant and save the table

`scripts/run_best_comparison.py` waits for the completed, audited full matrix before choosing a variant. It maximizes solved cases (validated SAT plus claimed UNSAT), then validated SAT. Ties prefer smaller cap multipliers, fewer experimental policies, and finally the declared variant order. Timing does not break ties. This selects the best measured configuration in this experiment, not an optimum over untested configurations.

The follow-up job is under `build/reports/noodler-best-comparison/20260920-best-repeat/`. Its `status.json` distinguishes waiting, running, stopped, and complete. `selection.json` records the ranking and the hash of the copied selection evidence. The new comparison reruns all five backends sequentially on all 3,248 source files, including explicit exclusions; it does not reuse historical result rows.

Create the plotting environment once, using the pinned packages tested with Python 3.12:

```powershell
python -m venv build/noodler-report-venv
build/noodler-report-venv/Scripts/python.exe -m pip install -r ksmt-noodler/scripts/requirements-benchmark-table.txt
```

```powershell
python ksmt-noodler/scripts/run_best_comparison.py `
  --ablation-suite build/reports/noodler-ablation/20260920-policy-ablation `
  --output build/reports/noodler-best-comparison/20260920-best-repeat `
  --render-python build/noodler-report-venv/Scripts/python.exe
```

The same command resumes the job after interruption. Do not launch a second controller while its lock is held. The rendering environment is separate from the benchmark JVMs; it uses Matplotlib for an exact numeric table. On successful completion, `benchmark-table.png` is the white-background image, `benchmark-table.svg` is the vector copy, and `.csv` and `.md` contain the table values and context. Raw records and worker logs remain under `runs/`.

To render an already completed run again:

```powershell
build/noodler-report-venv/Scripts/python.exe ksmt-noodler/scripts/render_benchmark_table.py `
  --run build/reports/noodler-best-comparison/20260920-best-repeat/runs/RUN-ID `
  --output-prefix build/reports/noodler-best-comparison/20260920-best-repeat/benchmark-table `
  --overwrite
```

The exporter rejects incomplete or inconsistent final results. Its explicit `--allow-partial` option produces a visibly labeled preview; such a preview is not a final benchmark comparison. One repeat can expose coverage variation, but cannot establish a reliable speed ranking or eliminate selection bias from choosing a winner on this same corpus.

The audit/recovery/export tooling has 69 passing synthetic tests: 26 ablation-audit, 17 winner-selection/resume, 9 curation-helper, and 17 table-export tests. These use isolated fixtures and do not launch native solvers. The export layout was also visually checked on synthetic data; the actual final image must be inspected after the new comparison finishes.

# Automaton length-projection experiment

The hybrid already encoded exact ultimately periodic accepted lengths before this change. The issue is the cost of discovering that representation: forgetting transition characters turns a DFA into a unary NFA, and repeatedly computing reachable subsets can have a much larger period than the original graph.

`adaptiveLengthProjection=true` uses graph bounds and length congruences, then attempts exact projection within its allowance. A fallback retains necessary conditions and the already certified finite prefix of accepted lengths. Below the prefix cutoff, Yices permits only lengths actually observed as accepting; lengths at or above the cutoff remain available subject to the other necessary conditions. Exact character realization checks remaining candidates. `false` runs the previous exact subset-orbit projection. Resource exhaustion remains inconclusive in either mode. This flag is independent of `optionalPreprocessing`, `smallFirstSearch`, and the resource caps.

The focused corpus under [benchmarks/length-projection](../benchmarks/length-projection) contains 12 crafted formulas with declared expected answers: parity, nonzero modular offsets, multiple residues, finite length holes and upper bounds, shortest paths, negative membership, epsilon, Boolean guards, concatenated lengths, and a large unary subset orbit. In the two large-orbit cases, five disjoint alphabet branches accept lengths `1 mod 11`, `1 mod 13`, `1 mod 17`, `1 mod 19`, or `1 mod 23`; their combined subset orbit has period 1,062,347 although the original character automaton is small. These cases target the algorithm directly and do not estimate application coverage.

Run both modes with the same binaries, budgets, and solver selection:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:test :ksmt-noodler:writeNoodlerBenchmarkClasspath
foreach ($adaptive in @('false', 'true')) {
    .\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers `
        -PnoodlerBenchmarkSource=ksmt-noodler/benchmarks/length-projection `
        -PnoodlerBenchmarkSolvers=noodler-yices `
        -PnoodlerBenchmarkRepeats=3 `
        "-PnoodlerBenchmarkAdaptiveLengthProjection=$adaptive" `
        "-PnoodlerBenchmarkOutput=build/reports/noodler-length-projection/final-synthetic-$adaptive"
}
```

The harness independently validates every SAT witness with Z3 and compares decisive answers to the declared statuses. The flag is recorded in metadata, immutable records, `raw.csv`, and the settings fingerprint. `length_projection_fallbacks` and `length_projection_cache_hits` expose fallback and cache activity. Resume rejects a changed flag. The 14 existing policy/cap presets retain their meanings and use the enabled default unless explicitly overridden.

[length-projection-sources.txt](length-projection-sources.txt) selects 12 existing source files for a second experiment: two historical compilation resource failures, four refinement failures, two validated SAT controls, two UNSAT controls, and two unary disequality cases. The selection uses the saved full-set baseline in the partial `20260920-policy-ablation` suite. It deliberately targets failures and is not representative of the full corpus.

```powershell
foreach ($adaptive in @('false', 'true')) {
    .\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers `
        -PnoodlerBenchmarkSource=ksmt-test/testData/string-data/37c6cd5d98edb46057f30cef35ba7c7250fe0161 `
        -PnoodlerBenchmarkSourceList=ksmt-noodler/docs/length-projection-sources.txt `
        -PnoodlerBenchmarkSolvers=noodler-yices `
        "-PnoodlerBenchmarkAdaptiveLengthProjection=$adaptive" `
        "-PnoodlerBenchmarkOutput=build/reports/noodler-length-projection/final-corpus-$adaptive"
}
```

Historical results locate the largest remaining limit: 243 of the fresh baseline's 270 UNKNOWN attempts failed in regex compilation, before length projection. Length-projection improvements alone therefore cannot remove most historical UNKNOWN results. The incomplete policy/cap matrix must not be presented as a completed ranking; its complete full-set rows show a baseline of 287 solved cases and storage-4x with 337, while other variants remain unfinished.

Each command creates a new run directory. Preserve old runs and compare fresh measurements from the same build. One trial can demonstrate a changed outcome; speed claims require repeated matched measurements.

## Final measurement on 2026-10-27

All four runs completed without syntax exclusions: 36/36 attempts per synthetic mode (12 cases, three trials) and 12/12 per corpus mode (one trial). They used the same compiled runtime, SHA256 `c8214dc61f0a67c939e4b90a06aa42dbf1b81a5c9cdf5d73fceff4f2b5ce9974`, identical source manifests within each pair, unchanged default caps, and budgets of 10 seconds for assertion/check, 30 seconds for startup/parsing, and 10 seconds for independent validation. Only `adaptiveLengthProjection` differed within each pair. Cancellation checkpoints and the prefix-language refinement optimization were present in both modes, so this comparison measures the length-projection policy rather than their effect. These final runs supersede the intermediate build's measurements retained in the same report directory.

| Selection | Adaptive projection | Validated SAT | UNSAT | UNKNOWN | Timeout |
|---|---|---:|---:|---:|---:|
| Synthetic, 12 cases × 3 trials | `false` | 12 | 18 | 6 | 0 |
| Synthetic, 12 cases × 3 trials | `true` | 15 | 21 | 0 | 0 |
| Selected corpus, 12 cases | `false` | 2 | 2 | 6 | 2 |
| Selected corpus, 12 cases | `true` | 2 | 2 | 6 | 2 |

The table counts attempts; every synthetic case had the same outcome across all three trials. Thus the adaptive policy increased synthetic coverage from 10/12 to 12/12 cases in every trial. The two large-orbit cases previously exhausted the automata storage allowance during `seed-lengths`. With the adaptive policy, both recorded two bounded-analysis fallbacks and reached a decisive result. Solve times below are per-case medians over the three trials:

| Case | Previous projection | Adaptive projection |
|---|---|---|
| `09-large-orbit-sat.smt2` | UNKNOWN, 3,096.543 ms | Validated SAT, 707.765 ms |
| `10-large-orbit-unsat.smt2` | UNKNOWN, 3,348.751 ms | UNSAT, 118.351 ms |

For the UNSAT large-orbit case, the preserved prefix excludes length 2 directly in Yices. All three adaptive trials recorded `realization_checks=0` and `native_realization_checks=0`: no character encoding or native realization query was needed. The SAT case used one realization query in each trial.

All other synthetic outcomes were unchanged. On the ten shared decisive cases, take each mode's three-trial median per case, subtract previous from adaptive, then take the median across cases: +3.9035 ms. The selected corpus had no outcome or failure-phase changes; its four shared decisive cases had a single-trial median difference of −11.8395 ms. These measurements demonstrate recovery from expensive length projection in the crafted cases, with no measured coverage gain in the selected application cases. They do not establish a general speed improvement. Every SAT witness passed independent Z3 validation; there were no invalid/unverified witnesses, known-status conflicts, or decisive disagreements.

Reports and immutable records remain under:

- Synthetic previous projection: [summary](../../build/reports/noodler-length-projection/final-synthetic-false/20261027T142807Z-961e8d44/summary.md), [raw results](../../build/reports/noodler-length-projection/final-synthetic-false/20261027T142807Z-961e8d44/raw.csv).
- Synthetic adaptive projection: [summary](../../build/reports/noodler-length-projection/final-synthetic-true/20261027T142850Z-a6824311/summary.md), [raw results](../../build/reports/noodler-length-projection/final-synthetic-true/20261027T142850Z-a6824311/raw.csv).
- Corpus previous projection: [summary](../../build/reports/noodler-length-projection/final-corpus-false/20261027T142916Z-542ddd3b/summary.md), [raw results](../../build/reports/noodler-length-projection/final-corpus-false/20261027T142916Z-542ddd3b/raw.csv).
- Corpus adaptive projection: [summary](../../build/reports/noodler-length-projection/final-corpus-true/20261027T142953Z-9e64d1a3/summary.md), [raw results](../../build/reports/noodler-length-projection/final-corpus-true/20261027T142953Z-9e64d1a3/raw.csv).

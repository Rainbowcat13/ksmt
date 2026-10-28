# Noodler + Yices with fourfold resource allowances

The full-corpus rerun is complete for **Noodler + Yices only**, with all five public resource allowances multiplied by four: **752/752 attempts**, 22:31–23:08 UTC on 2026-10-27 (01:31–02:08 Moscow time on 2026-10-28). The complete pipeline took approximately **36 minutes 47 seconds**. The committed [final comparison PNG](../benchmarks/results/fourfold-caps/comparison.png), [CSV](../benchmarks/results/fourfold-caps/comparison.csv), and [paired case data](../benchmarks/results/fourfold-caps/paired-cases.csv) contain the measured results. The [evidence archive](../benchmarks/results/README.md) describes the preserved files and their checksums.

## Settings

| Resource allowance | Previous run | New run |
|---|---:|---:|
| Automaton states | 100,000 | 400,000 |
| Automaton storage/work entries | 1,000,000 | 4,000,000 |
| Primitive character-encoding units | 1,000,000 | 4,000,000 |
| Preprocessing/work units | 1,000,000 | 4,000,000 |
| Materialized character positions | 100,000 | 400,000 |

Assertion/check remains **10 seconds** per formula. Startup/parsing remains 30 seconds and independent witness validation 10 seconds. Each of the same 752 eligible formulas receives one fresh-process attempt.

The policies remain `adaptiveLengthProjection=true`, `optionalPreprocessing=false`, `smallFirstSearch=false`, and `maxIdleRounds=2`. This experiment overrides resource configuration through the existing runner; it does not modify source code or default settings. Compiled heuristic thresholds, including bounded exact length projection and optional positional-planning allowances, are unchanged.

The variant identifier remains `baseline` because there is no aggregate fourfold preset; the explicit effective resource values and their configuration fingerprint distinguish this experiment from the default-cap run. Captions and before/after tables identify the new row as fourfold resources.

## Baseline and comparison

The reference is the hybrid row of the [completed five-backend comparison](combined-comparison.md):

| Configuration | Validated SAT | UNSAT | Solved / 752 | UNKNOWN | Timeout |
|---|---:|---:|---:|---:|---:|
| Default resource caps | 149 | 361 | 510 | 240 | 2 |
| Fourfold resource caps | 149 | 363 | 512 | 124 | 116 |

All 510 previously decisive cases retained the same result. Two previous UNKNOWN cases became UNSAT, 114 became TIMEOUT, and 124 remained UNKNOWN. The two previous timeouts remained timeouts. Thus the larger allowances produced **two extra solved cases and no coverage losses**, with most of the reduction in UNKNOWN caused by later timeout rather than successful solving.

The gains are both in `QF_S/20250411-hornstr-equiv/`:

| Case | Default caps | Fourfold caps |
|---|---|---|
| `coffee-can_lstar_non_incre_equiv_bad_0_3.smt2` | UNKNOWN: automata storage/work allowance during compilation | UNSAT, 638.222 ms |
| `coffee-can_lstar_non_incre_equiv_init_0_3.smt2` | UNKNOWN: automata storage/work allowance during compilation | UNSAT, 559.142 ms |

All 149 SAT witnesses in the new run passed independent Z3 validation. The audit found no worker failures, invalid/unverified witnesses, duplicate or missing attempts, or decisive disagreements against any of the five baseline backends. UNSAT results remain solver claims without proof certificates.

The remaining 124 UNKNOWN results all occur during encoding: 100 exhaust the four-million-unit preprocessing/work allowance, and 24 exhaust the four-million-unit character-encoding allowance. The `negated-predicates` family still has no solved case: its former 200 UNKNOWN results split into 100 UNKNOWN and 100 TIMEOUT. In `hornstr-equiv`, there are 24 UNKNOWN and 16 TIMEOUT; the latter include the two original timeouts.

The summed worker wall time increased from **19.960 to 36.606 minutes**; summed solve time increased from **13.845 to 30.497 minutes**. The completed pipeline elapsed time includes coordination and export, so it differs slightly from summed worker time. On the same 510 cases solved in both configurations, the median per-case solve-time change was +2.261 ms. This single-trial comparison shows that the extra cost predominantly comes from longer inconclusive searches; it does not establish a general speed ranking or isolate which of the five allowance increases matters most.

## Provenance

The new run uses the **exact previously frozen runtime**, without rebuilding: runtime SHA256 `836bc18bf33ed98e4e94530946b43d6a030a57ff4467b431615fb60a5048252d`. The launch preparation reverified all 275 runtime files and all 752 selected source hashes. Classpath entries continue to refer to the separate immutable snapshot of the previous comparison.

The selected source-list SHA256 is `e40006a1c3f9dec3638e1aa4a76f0a2fd1b10077bd986b90182f5489bd426921`; the selected-manifest SHA256 is `3e77a8be3377c6cd3f0e4540d3124db4f7ee9d2c0fd25589c07ab14206c4bcd2`. Both matched in the completed comparison, as did every case hash, classpath, JDK/OS field, timeout, and unchanged policy. The effective hybrid-settings fingerprint is `31b94bdcc5007dc01d48225bb372a9b3ebdbe36e979cd4e8a45995df588dfbe8`; it intentionally differs from the default-cap settings because the five allowances changed.

The previous complete run and every immutable attempt record are preserved. New hybrid results belong to a separate run; they are not substituted into the old five-backend raw dataset.

## Results and run records

Portable copies are committed under `ksmt-noodler/benchmarks/results/fourfold-caps/`:

- [Detailed summary](../benchmarks/results/fourfold-caps/summary.md), [raw rows](../benchmarks/results/fourfold-caps/raw.csv), [source manifest](../benchmarks/results/fourfold-caps/manifest.csv), [metadata](../benchmarks/results/fourfold-caps/metadata.properties), and [source list](../benchmarks/results/fourfold-caps/source-list.txt) preserve the complete new measurements.
- [Effective experiment settings](../benchmarks/results/fourfold-caps/experiment.json), [comparison details](../benchmarks/results/fourfold-caps/comparison.json), [paired case data](../benchmarks/results/fourfold-caps/paired-cases.csv), and the [standalone aggregate](../benchmarks/results/fourfold-caps/fourfold-result.csv) preserve the controls and outcome changes.
- The [baseline raw rows](../benchmarks/results/full-defaults/raw.csv) contain the default-cap measurements used for pairing. The [archive index](../benchmarks/results/archive-index.json) records the exact-byte copies and their SHA256 values.

Binary snapshots, worker logs, immutable checkpoint files, and machine-specific launch files are not committed. Re-running the strict exporter requires the original logs; see the [archive scope](../benchmarks/results/README.md#archive-scope-and-reproduction).

The original experiment root below is **local-only**, under the ignored build directory. Its links are available in the original workspace rather than in a fresh Git checkout:

`build/reports/noodler-resource-caps-4x/fourfold-20261027T222944Z/`

- Local-only [final status](../../build/reports/noodler-resource-caps-4x/fourfold-20261027T222944Z/status.json) records `complete`, all 752 attempts, and original exported result paths.
- The local-only [coordinator log](../../build/reports/noodler-resource-caps-4x/fourfold-20261027T222944Z/launcher.log) and [complete run directory](../../build/reports/noodler-resource-caps-4x/fourfold-20261027T222944Z/runs/20261027T223146Z-9babb927/) retain each original immutable record and worker log.
- The local-only [frozen classpath](../../build/reports/noodler-resource-caps-4x/fourfold-20261027T222944Z/classpath.txt) identifies the original binary snapshot.

The pipeline audited the complete new run, independently validated SAT witnesses, source/runtime identity, effective settings, and any decisive conflicts against all five baseline backends. It generated standalone results and the default-versus-fourfold comparison in PNG/SVG/CSV/Markdown/JSON as applicable. The portable archive retains the primary PNG, aggregate CSVs, comparison JSON, paired case data, and raw measurements; the larger generated Markdown and SVG exports remain local-only. The original five-backend table and raw results remain unchanged.

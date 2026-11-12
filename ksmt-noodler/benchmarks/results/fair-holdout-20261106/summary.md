# Noodler solver comparison

Run `20261106T134247Z-f2ee353d`; schema 1; created 2026-11-06T13:42:47.870154100Z.
Hybrid variant: `baseline`; settings fingerprint: `fc7b4b54c24fd063d0447a99bd498060561e3ba751a306541820adb6227eb2bb`.
Adaptive automaton length projection: `true`.
Bounded automaton witness search: `true`.
Exact source selection: `1200` paths; list SHA256 `9c41e3c0be9729910bf2fbbcde63904ecad80f717ec051ce73fbff3fb0c59ed4`. Snapshot: `source-list.txt`.
Corpus: **1200 files**, **0 syntax exclusions**, **2400/2400 attempts completed for non-excluded samples**. Repetitions: 1.
Syntax eligibility inspected: **1200/1200 files**. Parse failures and startup timeouts leave eligibility unclassified.
Tables count recorded attempts, including repetitions and failures before any later syntax exclusion.

Every attempt uses a fresh sequential JVM. Assertion/check budget: 10000 ms; startup/parsing budget: 30000 ms; independent witness validation: 10000 ms.
SAT below means the complete original-variable assignment passed an independent solver check. UNSAT is a solver claim; the harness does not generate proofs. UNKNOWN/timeouts are inconclusive.

| Solver | Validated SAT | UNSAT | UNKNOWN | Solve timeout | Invalid witness | Unverified witness | Other failures | Solved total ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| noodler-z3 | 857 | 274 | 0 | 69 | 0 | 0 | 0 | 258402.970 |
| noodler-yices | 782 | 309 | 99 | 10 | 0 | 0 | 0 | 237650.505 |

Solved totals describe different case sets and are not a speed ranking. See the matched cases below.

## Correctness checks

Samples with decisive SAT/UNSAT disagreements across any trial/backend: **0**. Known-status conflicting attempts: **0**. Invalid witnesses: **0**.
Different witnesses are valid alternatives. A validation timeout/unsupported operation is unverified, not invalid.


## Matched solved cases

Pairs include only cases/trials where both solvers returned the same decisive outcome, SAT was independently validated, and both solve timings were recorded.

| Left | Right | Matched cases | Left solve ms | Right solve ms | Left faster | Ties |
|---|---|---:|---:|---:|---:|---:|
| noodler-z3 | noodler-yices | 1042 | 184873.096 | 220234.272 | 940 | 0 |

## Family coverage

| Family | Manifest files | Solver | Completed attempts | Validated SAT / UNSAT |
|---|---:|---|---:|---:|
| QF_S/2019-Jiang | 150 | noodler-z3 | 150 | 46 / 96 |
| QF_S/2019-Jiang | 150 | noodler-yices | 150 | 41 / 96 |
| QF_S/2020-sygus-qgen | 300 | noodler-z3 | 300 | 300 / 0 |
| QF_S/2020-sygus-qgen | 300 | noodler-yices | 300 | 300 / 0 |
| QF_S/20230329-woorpje-lu | 150 | noodler-z3 | 150 | 94 / 33 |
| QF_S/20230329-woorpje-lu | 150 | noodler-yices | 150 | 72 / 32 |
| QF_SLIA/2015-Norn | 300 | noodler-z3 | 300 | 202 / 61 |
| QF_SLIA/2015-Norn | 300 | noodler-yices | 300 | 202 / 98 |
| QF_SLIA/2019-Jiang | 150 | noodler-z3 | 150 | 86 / 64 |
| QF_SLIA/2019-Jiang | 150 | noodler-yices | 150 | 82 / 64 |
| QF_SLIA/20230329-woorpje-lu | 150 | noodler-z3 | 150 | 129 / 20 |
| QF_SLIA/20230329-woorpje-lu | 150 | noodler-yices | 150 | 85 / 19 |

## Preprocessing and provenance

- noodler-z3: preprocessing skipped on 425 recorded attempts.
- noodler-yices: preprocessing skipped on 0 recorded attempts.

The [manifest](manifest.csv) contains all selected source hashes and labels. [Exclusions](exclusions.csv) explain unsupported syntax. [Raw rows](raw.csv) preserve raw solver status, validation outcome, separate timings, and legacy preprocessing flags. [Metadata](metadata.properties) records the binary fingerprint, Git state, JVM, OS, options, and classpath.
Immutable `records/*.properties` checkpoints are the recovery source; CSVs and this summary are derived views. Interrupted/uncommitted attempts retain their logs and may be retried on resume. Completed attempts are never repeated.
One trial is a coverage/regression measurement, not a statistical performance conclusion.

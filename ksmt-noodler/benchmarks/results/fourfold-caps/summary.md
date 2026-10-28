# Noodler solver comparison

Run `20261027T223146Z-9babb927`; schema 1; created 2026-10-27T22:31:46.903586500Z.
Hybrid variant: `baseline`; settings fingerprint: `31b94bdcc5007dc01d48225bb372a9b3ebdbe36e979cd4e8a45995df588dfbe8`.
Adaptive automaton length projection: `true`.
Exact source selection: `752` paths; list SHA256 `e40006a1c3f9dec3638e1aa4a76f0a2fd1b10077bd986b90182f5489bd426921`. Snapshot: `source-list.txt`.
Corpus: **752 files**, **0 syntax exclusions**, **752/752 attempts completed for non-excluded samples**. Repetitions: 1.
Syntax eligibility inspected: **752/752 files**. Parse failures and startup timeouts leave eligibility unclassified.
Tables count recorded attempts, including repetitions and failures before any later syntax exclusion.

Every attempt uses a fresh sequential JVM. Assertion/check budget: 10000 ms; startup/parsing budget: 30000 ms; independent witness validation: 10000 ms.
SAT below means the complete original-variable assignment passed an independent solver check. UNSAT is a solver claim; the harness does not generate proofs. UNKNOWN/timeouts are inconclusive.

| Solver | Validated SAT | UNSAT | UNKNOWN | Solve timeout | Invalid witness | Unverified witness | Other failures | Solved total ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| noodler-yices | 149 | 363 | 124 | 116 | 0 | 0 | 0 | 75290.219 |

Solved totals describe different case sets and are not a speed ranking. See the matched cases below.

## Correctness checks

Samples with decisive SAT/UNSAT disagreements across any trial/backend: **0**. Known-status conflicting attempts: **0**. Invalid witnesses: **0**.
Different witnesses are valid alternatives. A validation timeout/unsupported operation is unverified, not invalid.


## Matched solved cases

Pairs include only cases/trials where both solvers returned the same decisive outcome, SAT was independently validated, and both solve timings were recorded.

| Left | Right | Matched cases | Left solve ms | Right solve ms | Left faster | Ties |
|---|---|---:|---:|---:|---:|---:|

## Family coverage

| Family | Manifest files | Solver | Completed attempts | Validated SAT / UNSAT |
|---|---:|---|---:|---:|
| QF_S/20250411-hornstr-equiv | 552 | noodler-yices | 552 | 149 / 363 |
| QF_S/20250411-negated-predicates | 200 | noodler-yices | 200 | 0 / 0 |

## Preprocessing and provenance

- noodler-yices: preprocessing skipped on 0 recorded attempts.

The [manifest](manifest.csv) contains all selected source hashes and labels. [Exclusions](exclusions.csv) explain unsupported syntax. [Raw rows](raw.csv) preserve raw solver status, validation outcome, separate timings, and legacy preprocessing flags. [Metadata](metadata.properties) records the binary fingerprint, Git state, JVM, OS, options, and classpath.
Immutable `records/*.properties` checkpoints are the recovery source; CSVs and this summary are derived views. Interrupted/uncommitted attempts retain their logs and may be retried on resume. Completed attempts are never repeated.
One trial is a coverage/regression measurement, not a statistical performance conclusion.

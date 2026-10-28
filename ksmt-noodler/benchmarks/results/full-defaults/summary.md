# Noodler solver comparison

Run `20261027T172635Z-0654d06e`; schema 1; created 2026-10-27T17:26:35.603131800Z.
Hybrid variant: `baseline`; settings fingerprint: `b3fa5b832ccda92ae2dfd31c658b43394b0e99505bfb0b1a66edf1f905b9b5f5`.
Adaptive automaton length projection: `true`.
Exact source selection: `752` paths; list SHA256 `e40006a1c3f9dec3638e1aa4a76f0a2fd1b10077bd986b90182f5489bd426921`. Snapshot: `source-list.txt`.
Corpus: **752 files**, **0 syntax exclusions**, **3760/3760 attempts completed for non-excluded samples**. Repetitions: 1.
Syntax eligibility inspected: **752/752 files**. Parse failures and startup timeouts leave eligibility unclassified.
Tables count recorded attempts, including repetitions and failures before any later syntax exclusion.

Every attempt uses a fresh sequential JVM. Assertion/check budget: 10000 ms; startup/parsing budget: 30000 ms; independent witness validation: 10000 ms.
SAT below means the complete original-variable assignment passed an independent solver check. UNSAT is a solver claim; the harness does not generate proofs. UNKNOWN/timeouts are inconclusive.

| Solver | Validated SAT | UNSAT | UNKNOWN | Solve timeout | Invalid witness | Unverified witness | Other failures | Solved total ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| z3 | 173 | 145 | 0 | 434 | 0 | 0 | 0 | 42084.967 |
| cvc5 | 120 | 184 | 0 | 448 | 0 | 0 | 0 | 129716.777 |
| noodler-z3 | 178 | 351 | 0 | 223 | 0 | 0 | 0 | 82611.194 |
| noodler-cvc5 | 134 | 355 | 0 | 263 | 0 | 0 | 0 | 105383.746 |
| noodler-yices | 149 | 361 | 240 | 2 | 0 | 0 | 0 | 72438.954 |

Solved totals describe different case sets and are not a speed ranking. See the matched cases below.

## Correctness checks

Samples with decisive SAT/UNSAT disagreements across any trial/backend: **0**. Known-status conflicting attempts: **0**. Invalid witnesses: **0**.
Different witnesses are valid alternatives. A validation timeout/unsupported operation is unverified, not invalid.


## Matched solved cases

Pairs include only cases/trials where both solvers returned the same decisive outcome, SAT was independently validated, and both solve timings were recorded.

| Left | Right | Matched cases | Left solve ms | Right solve ms | Left faster | Ties |
|---|---|---:|---:|---:|---:|---:|
| z3 | cvc5 | 257 | 10995.861 | 55234.829 | 252 | 0 |
| z3 | noodler-z3 | 316 | 42055.630 | 38845.226 | 281 | 0 |
| z3 | noodler-cvc5 | 278 | 16698.669 | 69407.166 | 252 | 0 |
| z3 | noodler-yices | 294 | 10962.057 | 43687.667 | 283 | 0 |
| cvc5 | noodler-z3 | 301 | 129339.747 | 19625.731 | 188 | 0 |
| cvc5 | noodler-cvc5 | 304 | 129716.777 | 59875.391 | 233 | 0 |
| cvc5 | noodler-yices | 299 | 129378.938 | 41322.493 | 234 | 0 |
| noodler-z3 | noodler-cvc5 | 483 | 30511.242 | 83655.782 | 468 | 0 |
| noodler-z3 | noodler-yices | 496 | 31543.747 | 70608.736 | 483 | 0 |
| noodler-cvc5 | noodler-yices | 475 | 95244.798 | 65826.200 | 442 | 0 |

## Family coverage

| Family | Manifest files | Solver | Completed attempts | Validated SAT / UNSAT |
|---|---:|---|---:|---:|
| QF_S/20250411-hornstr-equiv | 552 | z3 | 552 | 173 / 145 |
| QF_S/20250411-hornstr-equiv | 552 | cvc5 | 552 | 120 / 184 |
| QF_S/20250411-hornstr-equiv | 552 | noodler-z3 | 552 | 178 / 351 |
| QF_S/20250411-hornstr-equiv | 552 | noodler-cvc5 | 552 | 134 / 355 |
| QF_S/20250411-hornstr-equiv | 552 | noodler-yices | 552 | 149 / 361 |
| QF_S/20250411-negated-predicates | 200 | z3 | 200 | 0 / 0 |
| QF_S/20250411-negated-predicates | 200 | cvc5 | 200 | 0 / 0 |
| QF_S/20250411-negated-predicates | 200 | noodler-z3 | 200 | 0 / 0 |
| QF_S/20250411-negated-predicates | 200 | noodler-cvc5 | 200 | 0 / 0 |
| QF_S/20250411-negated-predicates | 200 | noodler-yices | 200 | 0 / 0 |

## Preprocessing and provenance

- noodler-z3: preprocessing skipped on 12 recorded attempts.
- noodler-cvc5: preprocessing skipped on 7 recorded attempts.
- noodler-yices: preprocessing skipped on 0 recorded attempts.

The [manifest](manifest.csv) contains all selected source hashes and labels. [Exclusions](exclusions.csv) explain unsupported syntax. [Raw rows](raw.csv) preserve raw solver status, validation outcome, separate timings, and legacy preprocessing flags. [Metadata](metadata.properties) records the binary fingerprint, Git state, JVM, OS, options, and classpath.
Immutable `records/*.properties` checkpoints are the recovery source; CSVs and this summary are derived views. Interrupted/uncommitted attempts retain their logs and may be retried on resume. Completed attempts are never repeated.
One trial is a coverage/regression measurement, not a statistical performance conclusion.

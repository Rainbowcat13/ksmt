# Shared periodic-length rule: new-family holdout

With the same periodic-word certificate enabled in both combinations, **Z3 + Noodler solved 1,131/1,200 cases and Yices + Noodler solved 1,091/1,200**. This held-out run does not support an overall coverage advantage for Yices. The algorithms and selection were frozen before the first solver result; no algorithm changes were made in response to these measurements.

## Implementation

The existing `PeriodicWordEquality` certificate is now also used by the shared Noodler propagator and therefore by the Z3 and cvc5 wrappers. If every participating language is proved to be contained in powers of the same nonempty word, equality of the concatenated terms is equivalent to equality of their lengths. A string disequality then implies a length disequality. The wrapper publishes only consequences of necessary conjuncts; the Yices combination retains guards for its selected Boolean branch. The benchmark flag `periodicLengthReasoning` now controls the rule in both combinations.

All 263 module tests passed, including nine tests for the shared implementation. The pre-existing Detekt findings remain unchanged (38 findings); no new finding was added by these changes.

## Data and selection

The source is the official [SMT-LIB 2025.08.04 release](https://zenodo.org/records/16740866), QF_S and QF_SLIA. Six complete candidate families contained 5,283 distinct files, with no exact SHA256 overlap against any of the 3,248 files in the previous local corpus. The actual parser and support checker accepted 4,960 candidates and excluded 323 for unsupported syntax. No solver answers were used during this check or selection.

The **1,200-case sample**, rather than the entire official library, was selected by the predeclared quotas and SHA256 ranking of `fair-holdout-20261106-v1` plus each relative path. It contains 300 cases per origin (Jiang, SyGuS, Woorpje, Norn), 600 per logic, and six logic/family strata. Exact-byte novelty does not establish semantic independence of generated instances. The syntax filter limits conclusions to the common supported fragment.

| Family | Cases | Z3 + Noodler solved | Yices + Noodler solved |
|---|---:|---:|---:|
| QF_S/2019-Jiang | 150 | 142 | 137 |
| QF_S/2020-sygus-qgen | 300 | 300 | 300 |
| QF_S/20230329-woorpje-lu | 150 | 127 | 104 |
| QF_SLIA/2015-Norn | 300 | 263 | 300 |
| QF_SLIA/2019-Jiang | 150 | 150 | 146 |
| QF_SLIA/20230329-woorpje-lu | 150 | 149 | 104 |

## Measured results

| Combination | SAT | UNSAT | UNKNOWN | TIMEOUT | Solved |
|---|---:|---:|---:|---:|---:|
| Z3 + Noodler | 857 | 274 | 0 | 69 | 1,131 |
| Yices + Noodler | 782 | 309 | 99 | 10 | 1,091 |

Both solved 1,042 cases; 89 were solved only by Z3 + Noodler, 49 only by Yices + Noodler, and 20 by neither. There were no invalid or unverified SAT models, known-status conflicts, or conflicting decisive answers. All 857 SAT models from Z3 + Noodler were checked by native cvc5; all 782 from Yices + Noodler were checked by native Z3. UNSAT answers have no proof certificates. Only 52 inputs have a known SAT label; the other 1,148 carry UNKNOWN labels, so absence of label conflicts is a limited check.

Yices returned 95 UNKNOWN results during refinement: 48 exceeded the automata storage/work cap and 47 the automaton-state cap. The remaining four UNKNOWN results exceeded preprocessing (two), character-encoding (one), or character-position (one) budgets. All 79 timeouts across both combinations were classified at the SOLVE deadline. These resource failures remain in the denominator.

The Yices instrumentation recorded 522 periodic-length lemmas on three Jiang/QF_S cases, which ended in two UNKNOWN results and one TIMEOUT. No successfully solved case recorded such a lemma. The wrapper does not expose this counter, so its missing values must not be interpreted as zero. This run shows that the rule applies outside the old corpus, but gives no observed coverage benefit attributable to it. In particular, the Norn advantage must not be attributed to this rule. There is no rule-off ablation on this holdout.

## Procedure and limits

Run `20261106T134247Z-f2ee353d`: 2,400 attempts in 2,937.547 seconds. Each case/backend pair used a fresh JVM from the same hash-verified frozen runtime, interleaved in the fixed order Z3 then Yices. The solve budget was 10 seconds, startup/parsing 30 seconds, and independent model validation 10 seconds. There was one trial per pair. Both combinations used the common certificate; their string-solving architectures and internal resource accounting remain different.

Yices settings were unchanged: automata states 100,000; automata storage, encoding constraints and preprocessing work 1,000,000 each; character positions 100,000; membership-pruning work 250,000; idle rounds 2; adaptive length projection and automata witness search enabled; optional preprocessing and small-first search disabled.

On the 1,042 mutually solved cases, Yices used 220.234 seconds of aggregate assertion/check time and Z3 184.873 seconds. Yices was faster on 102 pairs, Z3 on 940; the median Yices-minus-Z3 time was +110.202 ms. These are descriptive measurements from one fixed-order run, not a statistical speed ranking. They exclude startup/parsing and independent validation.

## Evidence and reproduction

- [Aggregate results](comparison.csv), [family/origin results](families.csv), [paired cases](paired.csv), and [full audited report](comparison.json).
- [Raw measurements](raw.csv), [manifest](manifest.csv), [effective settings](metadata.properties), and [completed experiment](experiment.json).
- [Frozen selection plan](selection-plan.json), [selected inputs](holdout-sources.txt), [selection hashes](holdout-selection.csv), [all candidate hashes](candidate-manifest.csv), and [syntax eligibility](eligibility.csv).
- [Independent selection audit](independent-selection-audit.json), [runtime audit](independent-runtime-audit.json), [runtime file hashes](runtime-manifest.json), and [final checkpoint audit](independent-final-audit.json).

Every copied artifact is recorded in [the archive index](../archive-index.json) with its original path, byte count and SHA256. The original runtime, immutable attempt checkpoints, logs and external benchmark files remain under `build/experiments/fair-holdout-20261106` and `build/benchmarks/new-families-20261106`; they are not embedded in this archive. Re-running the strict checkpoint audit requires those originals. No dummy logs or substituted rows were used.

To make a fresh measurement after extracting the official archives, use the benchmark runner's `run` command with `--source <candidate-root> --source-list <holdout-sources.txt> --solvers noodler-z3,noodler-yices --repeats 1 --solve-ms 10000 --startup-parse-ms 30000 --validation-ms 10000 --periodic-length-reasoning true --automata-witness-search true --max-membership-pruning-units 250000 --output <new-output>`. Record the new build and runtime hashes separately: it is a new experiment, not a replacement for these rows.

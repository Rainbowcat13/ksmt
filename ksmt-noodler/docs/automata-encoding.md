# Batched regex compilation and positional transition pruning

This experiment compares the saved runtime before these two changes with the newly built runtime at identical solver settings and resource caps. It measures the combined change; separate attribution requires an additional ablation.

The regex compiler change targets repeated NFA-to-DFA conversions inside concatenation, union, and repetition. Complement and Boolean language operations remain exact boundaries. The character encoder change targets the old expansion of every automaton edge at every character position: forward reachability and backward reachability from the appropriate final states can exclude impossible transitions before constructing Yices formulas. Negative membership must use rejecting final states.

The existing [length-projection policy](length-projection.md) stays enabled in both runtimes. All comparisons use `noodler-yices`, a fresh sequential JVM per attempt, 10 seconds shared by assertion/check, 30 seconds for startup/parsing, and 10 seconds for independent Z3 witness validation. Resource exhaustion is recorded as inconclusive. A change in failure phase is useful diagnostic evidence, but only a decisive result counts as solved.

## Focused inputs

[benchmarks/automata-encoding](../benchmarks/automata-encoding) contains eight formulas with explicit expected answers:

| File | Expected | Purpose |
|---|---|---|
| `01-long-concat-sat.smt2` | SAT | 160 concatenated two-character regex leaves, accepted length 320 |
| `02-long-concat-length-unsat.smt2` | UNSAT | Same compiler workload with impossible length 319 |
| `03-repeat-loop-sat.smt2` | SAT | Bounded repetition of a union, with a feasible intermediate repetition count |
| `04-complement-intersection-unsat.smt2` | UNSAT | Exact complement/intersection boundary around bounded repetition |
| `05-position-positive-sat.smt2` | SAT | Eight distinct literal branches of length 121; fixed length makes only a small frontier feasible per position |
| `06-position-negative-sat.smt2` | SAT | Same automaton under negative membership; rejects incorrect pruning from accepting states |
| `07-literal-unicode-sat.smt2` | SAT | Known literal segments containing NUL, supplementary code points, and the maximum string code point |
| `08-literal-unicode-negative-unsat.smt2` | UNSAT | Negative membership with a fixed word that belongs to the language |

The positive/negative positional pair is constructed to expose the old encoding cost: the DFA has roughly a thousand states, but any run of the fixed length passes through only a small set of states at each position. Actual emitted constraints and budget outcomes must be measured; this construction alone does not prove a performance gain.

[sources.txt](../benchmarks/automata-encoding/sources.txt) selects 20 existing corpus files. Selection was made from the complete historical `all-eligible/baseline/20260920T142904Z-bb10c698` report: eight compilation UNKNOWN cases across different model names, four character-encoding UNKNOWN cases, four refinement UNKNOWN cases, two validated SAT controls, and two UNSAT controls. The source files were checked to exist. The selection deliberately overrepresents historical failures and is not an application coverage estimate.

## Run the comparison

Build this repository with JDK 17. The pre-change module runtime was snapshotted before source edits in:

`build/experiments/automata-encoding-20261027T160355Z/baseline-classpath.txt`

Its copied module entries remain separate from the mutable build outputs. Other module and dependency paths are shared and must remain unchanged during this experiment. Existing reports are preserved.

Run the baseline before rebuilding, or use its saved classpath afterward:

```powershell
$java = 'C:\Program Files\Java\jdk-17\bin\java.exe'
$baselineClasspath = (Get-Content -Raw build/experiments/automata-encoding-20261027T160355Z/baseline-classpath.txt).Trim()
& $java -Xss8m -cp $baselineClasspath io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt run `
    --source ksmt-noodler/benchmarks/automata-encoding `
    --solvers noodler-yices --repeats 1 `
    --output build/reports/noodler-automata-encoding/baseline-synthetic
& $java -Xss8m -cp $baselineClasspath io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt run `
    --source ksmt-test/testData/string-data/37c6cd5d98edb46057f30cef35ba7c7250fe0161 `
    --source-list ksmt-noodler/benchmarks/automata-encoding/sources.txt `
    --solvers noodler-yices `
    --output build/reports/noodler-automata-encoding/baseline-corpus
```

After the implementation is ready:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:test :ksmt-noodler:writeNoodlerBenchmarkClasspath
$finalClasspath = (Get-Content -Raw ksmt-noodler/build/noodler-benchmark-classpath.txt).Trim()
& $java -Xss8m -cp $finalClasspath io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt run `
    --source ksmt-noodler/benchmarks/automata-encoding `
    --solvers noodler-yices --repeats 1 `
    --output build/reports/noodler-automata-encoding/final-synthetic
& $java -Xss8m -cp $finalClasspath io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt run `
    --source ksmt-test/testData/string-data/37c6cd5d98edb46057f30cef35ba7c7250fe0161 `
    --source-list ksmt-noodler/benchmarks/automata-encoding/sources.txt `
    --solvers noodler-yices `
    --output build/reports/noodler-automata-encoding/final-corpus
```

Check pairwise manifests, effective settings, environment, attempt completeness, expected statuses, witness validation, and failure phases. Runtime fingerprints are expected to differ because this compares two builds. A single trial demonstrates outcome changes and catches regressions; repeated matched trials are needed for speed claims.

## The two follow-up ideas

These ideas explain the proposed third and fourth improvements. They are not implemented by this change.

### Revisit only equations affected by a language change

Suppose the string equations are `x ++ u = v` and `a ++ b = c`. If a membership constraint narrows the possible strings for `x`, the first equation may narrow `u` or `v`; the second equation cannot learn anything from that particular change.

The current refiner sweeps every equation when it is invoked. A dependency index would map each variable to the equations where it occurs. Changed variables enqueue only those equations. If processing one equation narrows another variable, its dependent equations are enqueued in turn. Processing stops when the queue is empty or its permitted work is exhausted. Partial propagation remains a sound necessary condition.

The same dependency versions can invalidate cached prefix/suffix languages only when an input variable changes. This avoids rebuilding every prefix and suffix on each visit. It also avoids paying for a complete sweep merely because exact character realization rejected another length candidate while the regular languages stayed unchanged.

A separate related opportunity is the backward pass of `factorBetween`: unlike the ordinary reachable-pair DFA product, that pass currently allocates the full state-pair product. Constructing only reachable and relevant pairs would reduce its storage on sparse products.

### Learn conflicts without unrelated atom choices and lengths

Consider a candidate where `|x|=1`, `|y|=1`, and `|z|=100`, with the atoms `x=y` and `xy in {"ab","ba"}` selected true. The character check is inconsistent: equal one-character words cannot concatenate to either `ab` or `ba`. Constraints concerning `z` do not cause that conflict.

The current blocker excludes the complete selected atom cube together with the complete length vector. A new candidate with `|z|=101` can therefore repeat the same failed `x,y` check. An unsatisfiable core could justify the stronger blocker

```text
not (x_equals_y AND xy_in_ab_or_ba AND len_x = 1 AND len_y = 1)
```

This blocks the conflict for every value of `len_z` and unrelated atom choices.

The lengths for `x` and `y` must remain: the exact encoder materializes arrays at concrete lengths, so an atom core from that query does not prove the same result for every possible length. A safe initial implementation retains the lengths of every variable on which the core's encoded atom groups depend. Every group's transition definitions and final predicate must be guarded/tracked consistently, or justified as unconditional total definitions; positional pruning can make those definitions depend on the selected membership polarity. Sharing encoding fragments requires extending the dependency set accordingly.

This is a more involved proof obligation than simply retrieving a native unsatisfiable core. Its benefit would be fewer repeated character checks across numerically different candidates.

## Measurements on 2026-10-27

Validation before measurement: 225 Kotlin tests passed. Detekt reported the same 38 existing findings in legacy code, with no remaining findings in the changed hybrid production files.

Both comparisons completed their entire selections without syntax exclusions or duplicate/missing attempts. Within each pair, manifests, source hashes, solver, effective settings, JDK/OS, caps, and phase budgets matched. The settings fingerprint was `b3fa5b832ccda92ae2dfd31c658b43394b0e99505bfb0b1a66edf1f905b9b5f5`. The synthetic manifest hash was `bc74fce7dc23daa411f19c106e381d05466385941ac0208689175cb2a0b1e1f7`; the selected-corpus manifest hash was `05050ba81e6dfec30528e3a58cfd7e826644737e5cc0410165d87f2ade04403d`.

This is a saved-baseline-build versus new-build comparison, not a same-binary policy ablation. The recorded runtime fingerprints were:

- Saved baseline: `c7f4247ce0b493f3fcef3b32aa414f58289f5ea6ae2fd02b2a4192eacedbbb15`.
- New compiler and encoder: `a49434dfd294a350a6d176c5d32c4b8272620ec8ad94627da1eac3e6351aec0d`.

| Selection | Runtime | Validated SAT | UNSAT | UNKNOWN | Timeout | Solved |
|---|---|---:|---:|---:|---:|---:|
| Synthetic, 8 cases | Saved baseline | 1 | 3 | 3 | 1 | 4/8 |
| Synthetic, 8 cases | New build | 5 | 3 | 0 | 0 | 8/8 |
| Selected corpus, 20 cases | Saved baseline | 3 | 2 | 14 | 1 | 5/20 |
| Selected corpus, 20 cases | New build | 13 | 4 | 3 | 0 | 17/20 |
| Previous length-projection fixtures, 12 cases | New build | 5 | 7 | 0 | 0 | 12/12 |

The new build gained four synthetic and twelve selected-corpus results, with no losses or decisive disagreements against the saved baseline. All SAT witnesses passed independent Z3 validation. Every synthetic and length-regression answer matched its declared status. The selected corpus does not declare expected statuses; these measurements do not independently certify its new UNSAT answers.

All eight selected baseline compilation failures and all three selected baseline refinement failures became decisive. The baseline timeout also became SAT. The remaining three UNKNOWN cases are `eqdist_lstar_non_incre_equiv_trans_1_10.smt2`, `eqdist_lstar_non_incre_equiv_trans_1_15.smt2`, and `eqdist_lstar_non_incre_equiv_trans_1_23.smt2`; all now report the character-encoding allowance in the `encoding` phase.

The repeated-union fixture `03-repeat-loop-sat.smt2` changed from a 10,000 ms solve timeout to validated SAT in 159.461 ms. `01-long-concat-sat.smt2` changed from encoding-limit UNKNOWN to validated SAT in 197.835 ms. Positive and negative positional cases changed from encoding-limit UNKNOWN to validated SAT in 232.361 ms and 269.799 ms.

The new encoder's counters expose the reduction in generated membership transition cases:

| Synthetic case | Unpruned edge-position pairs | Emitted edge-position pairs | Charged encoding units |
|---|---:|---:|---:|
| Long concatenation | 307,840 | 320 | 2,240 |
| Bounded repetition of a union | 28,288 | 128 | 704 |
| Positive membership, 8 branches | 349,932 | 968 | 4,235 |
| Negative membership, 8 branches | 349,932 | 3,002 | 12,371 |
| Known Unicode literal segments | 12,408 | 80 | 388 |

For the positive eight-branch case, the unpruned encoding estimate was 1,400,092 units, exceeding the unchanged 1,000,000 cap; the pruned encoding charged 4,235. For the negative case, the same estimate fell to 12,371 charged units. Each used one batched regex determinization and had one intermediate DFA retained at its compiler peak.

`membership_transitions_considered` is the full DFA edge count multiplied by word positions, accumulated across encoding attempts; it is an unpruned reference size, not the planner's number of inspected edges. `membership_transitions_encoded` counts emitted transition cases. `last_candidate_encoding_constraints` counts conservative primitive-constraint allowance charges for the latest candidate; it is not an exact SMT node or byte count. `last_candidate_encoding_estimate` remains the full unpruned upper estimate. `regex_determinizations`, `regex_peak_intermediate_automata`, and `regex_cached_roots` describe the check's main regex compiler; peak intermediates counts automata rather than their states or bytes.

This targeted, single-trial experiment demonstrates coverage gains at unchanged caps. It does not estimate full-corpus coverage or establish a general speed ranking. The combined comparison does not isolate how much of each gain comes from compilation versus positional pruning.

Reports and immutable attempt records:

- Saved baseline synthetic: [summary](../../build/reports/noodler-automata-encoding/baseline-synthetic/20261027T160702Z-249d764c/summary.md), [raw](../../build/reports/noodler-automata-encoding/baseline-synthetic/20261027T160702Z-249d764c/raw.csv).
- New synthetic: [summary](../../build/reports/noodler-automata-encoding/after-synthetic/20261027T161326Z-bd58efe1/summary.md), [raw](../../build/reports/noodler-automata-encoding/after-synthetic/20261027T161326Z-bd58efe1/raw.csv).
- Saved baseline corpus selection: [summary](../../build/reports/noodler-automata-encoding/baseline-corpus/20261027T160719Z-b098691a/summary.md), [raw](../../build/reports/noodler-automata-encoding/baseline-corpus/20261027T160719Z-b098691a/raw.csv).
- New corpus selection: [summary](../../build/reports/noodler-automata-encoding/after-corpus/20261027T161333Z-feca9b81/summary.md), [raw](../../build/reports/noodler-automata-encoding/after-corpus/20261027T161333Z-feca9b81/raw.csv).
- Length regression: [summary](../../build/reports/noodler-automata-encoding/length-regression/20261027T161349Z-a2c64dc8/summary.md), [raw](../../build/reports/noodler-automata-encoding/length-regression/20261027T161349Z-a2c64dc8/raw.csv).

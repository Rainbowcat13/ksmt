# Completed corpus comparison

The comparison is complete. Start with the [detailed analysis](../../build/reports/noodler-benchmarks/20260920-curated-comparison/analysis.md) and [comparison CSV](../../build/reports/noodler-benchmarks/20260920-curated-comparison/comparison.csv). The [provenance file](../../build/reports/noodler-benchmarks/20260920-curated-comparison/provenance.json) identifies every input and replacement.

All **3,248** local source files were inspected and hashed. **752** fit the common supported syntax: 552 `hornstr-equiv` and 200 `negated-predicates` cases. The other **2,496** were explicitly excluded for unsupported syntax, including replacement, containment, index-of, and unsupported equality operands. Eligibility was determined by syntax, not solver success. Every eligible case has one completed attempt from each of five backends: **3,760 comparative attempts**.

## Results

| Backend | Validated SAT | UNSAT | Solved / 752 | UNKNOWN | Timeout |
|---|---:|---:|---:|---:|---:|
| Z3 | 172 | 145 | 317 | 0 | 435 |
| cvc5 | 120 | 183 | 303 | 0 | 449 |
| Noodler + Z3 | 181 | 236 | **417** | 0 | 335 |
| Noodler + cvc5 | 123 | 268 | **391** | 0 | 361 |
| Noodler + Yices | 67 | 210 | **277** | 286 | 189 |

All **663 SAT answers** passed independent witness checks. There were **zero invalid or unverified witnesses, decisive disagreements, or remaining worker failures** in the curated comparison. UNSAT answers are solver claims without proof certificates. The independent validator shares the Z3 SMT-LIB frontend, so parsing is not independently validated. All cases with known status labels were inconclusive; the label-conflict check therefore provides no additional confirmation of decisive answers here.

## Assessment

**The wrappers show a useful coverage improvement on this corpus.** Noodler+Z3 gained 104 cases and lost four relative to Z3: a net gain of 100, or **31.5% more solved cases**. Noodler+cvc5 gained 88 with no losses, or **29.0% more solved cases**. Noodler+Z3 has the highest individual coverage.

**This is not a general speed improvement.** On 313 cases both Z3 and its wrapper solved with the same answer, the wrapper added a median **120.5 ms**; plain Z3 was faster on 297. For the 303 cvc5 pairs, the median added time was **107.2 ms**, and plain cvc5 was faster on 270. A few large improvements make the cvc5 wrapper's paired aggregate slightly faster despite its higher median. Timing comparisons use identical solved case sets; totals over different solved sets are not a ranking.

**The Yices combination is a promising complementary solver, with substantial remaining limits.** It reported UNSAT on **92 cases beyond the union of the plain solvers**, including **12 beyond all four alternatives**, but its overall coverage was lower. Its 286 UNKNOWN results comprise 246 automata storage/work limits, four automata state limits, 19 character-encoding limits, and 17 preprocessing limits. These exits preserve soundness by remaining inconclusive; they do not prove UNSAT.

**The negated-predicate family remains unsolved.** All five backends were inconclusive on all 200 eligible cases. For example, `diseq-1-3-5-0.smt2` requires `XYZXX != XYXZX` while `X,Y,Z` all belong to `a*`. Both words must equal `a^(3|X|+|Y|+|Z|)`, so the formula is UNSAT. The hybrid can reject individual length candidates, but currently lacks the general equality argument that excludes every length vector at once. That explanation follows from the source and implementation; the watchdog-killed worker did not provide a trace of its rounds. This illustrates the documented incompleteness of the combination.

The four Noodler+Z3 losses are `muzzle_sat_non_incre_equiv_trans_0_{5,12,13,14}.smt2`. Plain Z3 returned UNSAT in about 10–16 ms. For cases 13 and 14, saved statistics show 463/826 ms of preprocessing followed by about 9.54/9.17 seconds in the backend after a resource-limit fallback with zero generated lemmas. The other two were killed before phase statistics were reported. Thus preprocessing alone does not explain the losses. The wrapper also introduces an incremental scope; this is a plausible influence on Z3 search, not a demonstrated root cause from these logs.

The evidence supports better coverage in these two families, not a broad performance or completeness claim. This is one trial in fixed solver order, on Windows 11 with JDK 17.0.8 and an Intel i7-11800H (8 cores / 16 threads). Repeated runs would be needed to estimate timing variability. The excluded families require additional supported operations before they can inform this comparison.

## Measurement and provenance

Every attempt used a fresh sequential JVM with a **10-second assertion/check limit**, a separate **30-second startup/parsing limit**, and a separate **10-second witness-validation limit**. The public check timing includes internal hybrid validation and legacy model detachment; external witness validation is timed separately.

The [primary run](../../build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/summary.md) finished with all groups complete. Its [raw results](../../build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/raw.csv), [manifest](../../build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/manifest.csv), and [exclusions](../../build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/exclusions.csv) are preserved. All source paths and content hashes were rechecked against the local corpus.

Host sleep/hibernation interrupted four measurements across three cases. All five backends were remeasured for each affected case in [supplemental run `20260920T115059Z-234ef838`](../../build/reports/noodler-benchmarks/20260920T115059Z-234ef838/summary.md). The curated comparison substitutes those **15 rows**, preserving both raw datasets and recording each replacement. No other case was dropped or substituted. See the [environment record](../../build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96/environment-events.md).

Both runs use runtime SHA256 `a3afd1fe9a7ea19c78a6c770da86949366a5347d548bbd295eba83714ee3a7b1`, identical classpaths, Java/OS metadata, solver order, repetitions, and phase budgets. The supplemental source tree contains exact copies of only the three affected files. There are no residual wall-time outliers above the combined budgets plus cleanup allowance.

## Reproduce

From the repository root:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :ksmt-noodler:benchmarkNoodlerSolvers
```

Regenerate the curated report in a new output directory:

```powershell
python ksmt-noodler/scripts/analyze_benchmark.py --primary build/reports/noodler-benchmarks/20260918T220735Z-1fadbe96 --supplemental build/reports/noodler-benchmarks/20260920T115059Z-234ef838 --replace-case QF_S/20250411-hornstr-equiv/dining-cryptographers_sat_non_incre_equiv_trans_59_0.smt2 --replace-case QF_S/20250411-negated-predicates/diseq-1-3-5-109.smt2 --replace-case QF_S/20250411-negated-predicates/diseq-1-3-5-11.smt2 --replacement-reason "Recorded Windows sleep/hibernation; all five backends remeasured for each affected case" --output build/reports/noodler-benchmarks/new-curated-comparison
```

The analyzer refuses incomplete groups, mismatched binaries/budgets/source hashes, or an existing output directory. See the [implementation overview and fixed bugs](overview-and-review.md) and [benchmark options](../README.md).

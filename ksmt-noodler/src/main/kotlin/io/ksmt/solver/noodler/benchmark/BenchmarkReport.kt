package io.ksmt.solver.noodler.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

internal val benchmarkColumns = listOf(
    "key", "sample_id", "sample", "source_sha256", "family", "logic", "expected_status", "solver", "trial",
    "eligible", "outcome", "raw_status", "validation_status", "validator", "timeout_phase",
    "startup_ms", "parse_ms", "setup_ms", "solve_ms", "validation_ms", "wall_ms",
    "assertion_count", "witness_bindings", "witness_sha256", "preprocess_skipped", "preprocess_skip_reason",
    "preprocess_ms", "base_check_ms", "generated_constraints", "fresh_constraints", "hybrid_rounds",
    "learned_constraints", "realization_checks", "worker_exit", "details", "solver_statistics", "log", "completed_utc",
    "variant", "hybrid_configuration_sha256", "max_automata_states", "max_automata_storage",
    "max_encoding_constraints", "max_preprocessing_units", "max_character_positions", "max_idle_rounds",
    "optional_preprocessing", "small_first_search", "adaptive_length_projection", "failure_phase",
    "optional_preprocessing_skips",
    "last_optional_skip_reason", "bounded_search_bounds", "native_realization_checks",
    "last_candidate_total_length", "last_candidate_encoding_estimate", "phase_times_ms",
    "length_projection_fallbacks", "length_projection_cache_hits",
    "last_candidate_encoding_constraints", "membership_transitions_considered", "membership_transitions_encoded",
    "membership_pruning_fallbacks",
    "regex_determinizations", "regex_peak_intermediate_automata", "regex_cached_roots",
    "refinement_sweeps", "refinement_skips",
)
private val exclusionColumns = listOf("sample_id", "sample", "source_sha256", "reason")
private val nanoTimes = setOf("startup_ms", "parse_ms", "setup_ms", "solve_ms", "validation_ms", "wall_ms")
private val decisiveOutcomes = setOf("SAT", "UNSAT")
private val categorizedOutcomes = decisiveOutcomes + setOf(
    "UNKNOWN", "TIMEOUT", "INVALID_WITNESS", "UNVERIFIED_WITNESS"
)
private const val NANOS_PER_MILLISECOND = 1_000_000.0
private const val MAX_CORRECTNESS_DETAILS = 30

internal fun formatMillis(nanoseconds: String?): String = nanoseconds?.toLongOrNull()?.let {
    String.format(Locale.ROOT, "%.3f", it / NANOS_PER_MILLISECOND)
}.orEmpty()

private fun recordCsv(record: Map<String, String>): String = BenchmarkCsv.row(benchmarkColumns.map {
    if (it in nanoTimes) formatMillis(record[it.removeSuffix("_ms") + "_ns"]) else record[it].orEmpty()
})

internal fun restoreAggregateCsv(
    directory: Path,
    records: List<Map<String, String>>,
    exclusions: Map<String, Map<String, String>>,
) {
    // Committed per-attempt records recover a partial/missing CSV tail after a parent crash.
    val raw = BenchmarkCsv.row(benchmarkColumns) + records.joinToString("", transform = ::recordCsv)
    val excluded = BenchmarkCsv.row(exclusionColumns) + exclusions.values.sortedBy { it["sample"] }
        .joinToString("") { row -> BenchmarkCsv.row(exclusionColumns.map { row[it].orEmpty() }) }
    restoreCsv(directory.resolve("raw.csv"), raw)
    restoreCsv(directory.resolve("exclusions.csv"), excluded)
}

private fun restoreCsv(path: Path, contents: String) {
    if (!Files.exists(path) || Files.readString(path) != contents) Files.writeString(path, contents)
}

internal fun appendRecordCsv(path: Path, row: Map<String, String>) {
    Files.writeString(path, recordCsv(row), StandardOpenOption.APPEND)
}

internal fun appendExclusionCsv(path: Path, row: Map<String, String>) {
    Files.writeString(path, BenchmarkCsv.row(exclusionColumns.map { row[it].orEmpty() }), StandardOpenOption.APPEND)
}

/** Repetition cannot change satisfiability; compare decisive outcomes across every trial and backend. */
internal fun decisiveDisagreements(records: List<Map<String, String>>): List<List<Map<String, String>>> = records
    .filter { it["outcome"] in decisiveOutcomes }
    .groupBy { it.getValue("sample_id") }.values
    .filter { group -> group.map { it["outcome"] }.toSet().size > 1 }

internal fun writeBenchmarkSummary(
    directory: Path,
    metadata: Map<String, String>,
    samples: List<BenchmarkSample>,
    records: List<Map<String, String>>,
    exclusions: Map<String, Map<String, String>>,
) {
    val solvers = metadata.getValue("solvers").split(',')
    val text = buildString {
        appendOverview(directory, metadata, samples, records, exclusions)
        appendSolverTotals(solvers, records)
        appendCorrectnessChecks(records)
        appendMatchedCases(solvers, records)
        appendFamilyCoverage(solvers, samples, records)
        appendProvenance(solvers, records)
    }
    Files.writeString(directory.resolve("summary.md"), text)
}

private fun StringBuilder.appendOverview(
    directory: Path,
    metadata: Map<String, String>,
    samples: List<BenchmarkSample>,
    records: List<Map<String, String>>,
    exclusions: Map<String, Map<String, String>>,
) {
    val repeats = metadata.getValue("repeats").toInt()
    val solverCount = metadata.getValue("solvers").split(',').size
    val attempted = records.count { it["outcome"] != "EXCLUDED" && it["sample_id"] !in exclusions }
    val expected = (samples.size - exclusions.size).toLong() * solverCount * repeats
    val classified = records.filter { it["eligible"] == "true" || it["eligible"] == "false" }
        .mapNotNull { it["sample_id"] }.toSet() + exclusions.keys
    appendLine("# Noodler solver comparison")
    appendLine()
    appendLine("Run `${directory.fileName}`; schema ${metadata["schema"]}; created ${metadata["created_utc"]}.")
    appendLine("Hybrid variant: `${metadata["variant"] ?: "baseline"}`; settings fingerprint: " +
        "`${metadata["hybrid-configuration-sha256"].orEmpty()}`.")
    metadata["adaptive-length-projection"]?.let {
        appendLine("Adaptive automaton length projection: `$it`.")
    }
    metadata["source-list"]?.takeIf { it.isNotEmpty() }?.let {
        appendLine("Exact source selection: `${metadata["source-list-count"]}` paths; " +
            "list SHA256 `${metadata["source-list-sha256"]}`. Snapshot: `source-list.txt`.")
    }
    appendLine("Corpus: **${samples.size} files**, **${exclusions.size} syntax exclusions**, " +
        "**$attempted/$expected attempts completed for non-excluded samples**. Repetitions: $repeats.")
    appendLine("Syntax eligibility inspected: **${classified.size}/${samples.size} files**. " +
        "Parse failures and startup timeouts leave eligibility unclassified.")
    appendLine("Tables count recorded attempts, including repetitions and failures before any later syntax exclusion.")
    appendLine()
    appendLine("Every attempt uses a fresh sequential JVM. Assertion/check budget: ${metadata["solve-ms"]} ms; " +
        "startup/parsing budget: ${metadata["startup-parse-ms"]} ms; " +
        "independent witness validation: ${metadata["validation-ms"]} ms.")
    appendLine("SAT below means the complete original-variable assignment passed an independent solver check. " +
        "UNSAT is a solver claim; the harness does not generate proofs. UNKNOWN/timeouts are inconclusive.")
    appendLine()
}

private fun StringBuilder.appendSolverTotals(solvers: List<String>, records: List<Map<String, String>>) {
    appendLine("| Solver | Validated SAT | UNSAT | UNKNOWN | Solve timeout | " +
        "Invalid witness | Unverified witness | Other failures | Solved total ms |")
    appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for (solver in solvers) {
        val rows = records.filter { it["solver"] == solver && it["outcome"] != "EXCLUDED" }
        val counts = rows.groupingBy { it["outcome"] }.eachCount()
        val other = rows.count { it["outcome"] !in categorizedOutcomes }
        val solved = rows.filter { it["outcome"] in decisiveOutcomes }
        val times = solved.mapNotNull(::solveNanos)
        val total = if (times.size == solved.size) formatMillis(times.sum().toString()) else "unavailable"
        appendLine("| $solver | ${counts["SAT"] ?: 0} | ${counts["UNSAT"] ?: 0} | ${counts["UNKNOWN"] ?: 0} | " +
            "${counts["TIMEOUT"] ?: 0} | ${counts["INVALID_WITNESS"] ?: 0} | " +
            "${counts["UNVERIFIED_WITNESS"] ?: 0} | $other | $total |")
    }
    appendLine()
    appendLine("Solved totals describe different case sets and are not a speed ranking. See the matched cases below.")
    appendLine()
}

private fun StringBuilder.appendCorrectnessChecks(records: List<Map<String, String>>) {
    val disagreements = decisiveDisagreements(records)
    val labelConflicts = records.filter { row ->
        row["outcome"] in decisiveOutcomes && row["expected_status"] in decisiveOutcomes &&
            row["outcome"] != row["expected_status"]
    }
    val invalid = records.filter { it["outcome"] == "INVALID_WITNESS" }
    appendLine("## Correctness checks")
    appendLine()
    appendLine("Samples with decisive SAT/UNSAT disagreements across any trial/backend: **${disagreements.size}**. " +
        "Known-status conflicting attempts: **${labelConflicts.size}**. Invalid witnesses: **${invalid.size}**.")
    appendLine("Different witnesses are valid alternatives. " +
        "A validation timeout/unsupported operation is unverified, not invalid.")
    appendLine()
    val details = (disagreements.flatten() + labelConflicts + invalid).distinctBy { it["key"] }
    for (row in details.take(MAX_CORRECTNESS_DETAILS)) {
        appendLine("- `${row["sample"]}`: ${row["solver"]}, trial=${row["trial"]}, " +
            "raw=${row["raw_status"]}, outcome=${row["outcome"]}; [log](${row["log"]}).")
    }
    if (details.size > MAX_CORRECTNESS_DETAILS) {
        appendLine()
        appendLine("Showing the first $MAX_CORRECTNESS_DETAILS affected attempts; see raw.csv for all records.")
    }
    appendLine()
}

private fun StringBuilder.appendMatchedCases(solvers: List<String>, records: List<Map<String, String>>) {
    appendLine("## Matched solved cases")
    appendLine()
    appendLine("Pairs include only cases/trials where both solvers returned the same decisive outcome, " +
        "SAT was independently validated, and both solve timings were recorded.")
    appendLine()
    appendLine("| Left | Right | Matched cases | Left solve ms | Right solve ms | Left faster | Ties |")
    appendLine("|---|---|---:|---:|---:|---:|---:|")
    for (leftIndex in solvers.indices) {
        for (rightIndex in leftIndex + 1 until solvers.size) {
            val left = solvers[leftIndex]
            val right = solvers[rightIndex]
            val times = matchedTimings(left, right, records)
            val leftTotal = formatMillis(times.sumOf { it.first }.toString())
            val rightTotal = formatMillis(times.sumOf { it.second }.toString())
            appendLine("| $left | $right | ${times.size} | $leftTotal | $rightTotal | " +
                "${times.count { it.first < it.second }} | ${times.count { it.first == it.second }} |")
        }
    }
    appendLine()
}

private fun matchedTimings(
    left: String, right: String, records: List<Map<String, String>>,
): List<Pair<Long, Long>> {
    val lhs = records.filter { it["solver"] == left }.associateBy { it["sample_id"] to it["trial"] }
    val rhs = records.filter { it["solver"] == right }.associateBy { it["sample_id"] to it["trial"] }
    return lhs.keys.intersect(rhs.keys).mapNotNull { key ->
        val one = lhs.getValue(key)
        val two = rhs.getValue(key)
        if (one["outcome"] !in decisiveOutcomes || one["outcome"] != two["outcome"]) return@mapNotNull null
        val leftTime = solveNanos(one) ?: return@mapNotNull null
        val rightTime = solveNanos(two) ?: return@mapNotNull null
        leftTime to rightTime
    }
}

private fun solveNanos(record: Map<String, String>): Long? =
    record["solve_ns"]?.toLongOrNull()?.takeIf { it >= 0 }

private fun StringBuilder.appendFamilyCoverage(
    solvers: List<String>, samples: List<BenchmarkSample>, records: List<Map<String, String>>,
) {
    appendLine("## Family coverage")
    appendLine()
    appendLine("| Family | Manifest files | Solver | Completed attempts | Validated SAT / UNSAT |")
    appendLine("|---|---:|---|---:|---:|")
    for ((family, familySamples) in samples.groupBy { it.family }.toSortedMap()) {
        for (solver in solvers) {
            val rows = records.filter {
                it["family"] == family && it["solver"] == solver && it["outcome"] != "EXCLUDED"
            }
            appendLine("| $family | ${familySamples.size} | $solver | ${rows.size} | " +
                "${rows.count { it["outcome"] == "SAT" }} / ${rows.count { it["outcome"] == "UNSAT" }} |")
        }
    }
    appendLine()
}

private fun StringBuilder.appendProvenance(solvers: List<String>, records: List<Map<String, String>>) {
    appendLine("## Preprocessing and provenance")
    appendLine()
    solvers.filter { it.startsWith("noodler-") }.forEach { solver ->
        val rows = records.filter { it["solver"] == solver }
        val skipped = rows.count { it["preprocess_skipped"] == "true" }
        appendLine("- $solver: preprocessing skipped on $skipped recorded attempts.")
    }
    appendLine()
    appendLine("The [manifest](manifest.csv) contains all selected source hashes and labels. " +
        "[Exclusions](exclusions.csv) explain unsupported syntax. " +
        "[Raw rows](raw.csv) preserve raw solver status, validation outcome, separate timings, " +
        "and legacy preprocessing flags. [Metadata](metadata.properties) records the binary fingerprint, " +
        "Git state, JVM, OS, options, and classpath.")
    appendLine("Immutable `records/*.properties` checkpoints are the recovery source; " +
        "CSVs and this summary are derived views. Interrupted/uncommitted attempts retain their logs " +
        "and may be retried on resume. Completed attempts are never repeated.")
    appendLine("One trial is a coverage/regression measurement, not a statistical performance conclusion.")
}

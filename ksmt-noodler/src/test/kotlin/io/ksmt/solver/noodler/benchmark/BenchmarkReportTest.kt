package io.ksmt.solver.noodler.benchmark

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkReportTest {
    @TempDir
    lateinit var temporary: Path

    private val sample = BenchmarkSample("family/sample.smt2", "hash", 1, "QF_S", "unknown")
    private val metadata = mapOf(
        "solvers" to "z3,cvc5", "repeats" to "2", "schema" to "1", "created_utc" to "2026-09-19",
        "solve-ms" to "1000", "startup-parse-ms" to "1000", "validation-ms" to "1000",
    )

    @Test
    fun disagreementsIncludeDifferentRepetitionsAndTheSameBackend() {
        val sat = row("z3", "SAT", "1")
        assertEquals(1, decisiveDisagreements(listOf(sat, row("cvc5", "UNSAT", "2"))).size)
        assertEquals(1, decisiveDisagreements(listOf(sat, row("z3", "UNSAT", "2"))).size)
        assertTrue(decisiveDisagreements(listOf(sat, row("cvc5", "UNKNOWN", "2"))).isEmpty())
    }

    @Test
    fun missingSolveTimesDoNotBecomeZeroTimeMatches() {
        val rows = listOf(row("z3", "SAT", "1") + ("solve_ns" to "1000000"), row("cvc5", "SAT", "1"))
        writeBenchmarkSummary(temporary, metadata, listOf(sample), rows, emptyMap())
        val summary = Files.readString(temporary.resolve("summary.md"))

        assertTrue(summary.contains("| z3 | cvc5 | 0 | 0.000 | 0.000 | 0 | 0 |"))
        assertTrue(summary.contains("| cvc5 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | unavailable |"))
    }

    @Test
    fun matchedTimesRequireTheSameSampleAndTrial() {
        val rows = listOf(
            row("z3", "SAT", "1") + ("solve_ns" to "1000000"),
            row("cvc5", "SAT", "2") + ("solve_ns" to "2000000"),
            row("z3", "UNSAT", "3") + ("solve_ns" to "3000000"),
            row("cvc5", "UNSAT", "3") + ("solve_ns" to "4000000"),
        )
        writeBenchmarkSummary(temporary, metadata, listOf(sample), rows, emptyMap())
        val summary = Files.readString(temporary.resolve("summary.md"))

        assertTrue(summary.contains("| z3 | cvc5 | 1 | 3.000 | 4.000 | 1 | 0 |"))
    }

    @Test
    fun earlierParseFailuresDoNotInflateExcludedSampleCompletion() {
        val rows = listOf(row("z3", "STARTUP_PARSE_TIMEOUT", "1"))
        val exclusions = mapOf(sample.id to mapOf("sample_id" to sample.id, "reason" to "unsupported"))
        writeBenchmarkSummary(temporary, metadata, listOf(sample), rows, exclusions)
        val summary = Files.readString(temporary.resolve("summary.md"))

        assertTrue(summary.contains("**0/0 attempts completed for non-excluded samples**"))
        assertTrue(summary.contains("Syntax eligibility inspected: **1/1 files**"))
        assertTrue(summary.contains("| z3 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0.000 |"))
    }

    private fun row(solver: String, outcome: String, trial: String): Map<String, String> = mapOf(
        "key" to "$solver-$trial", "sample_id" to sample.id, "sample" to sample.relativePath,
        "family" to sample.family, "solver" to solver, "outcome" to outcome, "raw_status" to outcome,
        "trial" to trial, "log" to "worker.log",
    )
}

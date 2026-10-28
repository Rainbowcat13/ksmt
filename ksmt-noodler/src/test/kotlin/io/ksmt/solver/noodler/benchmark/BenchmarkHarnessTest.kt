package io.ksmt.solver.noodler.benchmark

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BenchmarkHarnessTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun testProtocolDoesNotConfuseSolverLogsOrEmbeddedDelimiters() {
        val protocol = BenchmarkProtocol("fixture")
        val fields = mapOf("message" to "line\nquoted\"\t=\u0000\uD83D\uDE00", "key" to "value")
        assertEquals("RESULT" to fields, protocol.decode(protocol.encode("RESULT", fields)))
        assertNull(protocol.decode("arbitrary solver log"))
        assertNull(protocol.decode(BenchmarkProtocol("another-worker").encode("RESULT", fields)))
        assertNull(protocol.decode("KSMT_BENCHMARK:fixture:RESULT\tmalformed"))
    }

    @Test
    fun testCsvRoundTripsMultilineUnicodeAndCarriageReturns() {
        val fields = listOf("plain", "a,b", "a\"b", "line\nnext", "\r", "\u0000", "\uD83D\uDE00", "")
        assertEquals(listOf(fields, listOf("next")), BenchmarkCsv.parse(BenchmarkCsv.row(fields) + BenchmarkCsv.row(listOf("next"))))
        assertEquals(listOf(listOf("a", "b")), BenchmarkCsv.parse("a,b\r\n"))
        assertFailsWith<IllegalArgumentException> { BenchmarkCsv.parse("\"unfinished") }
    }

    @Test
    fun testVerboseWorkerIsDrainedWhileItRuns() {
        val result = fixture("verbose", BenchmarkLimits(10_000, 10_000, 10_000))
        assertEquals("SAT", result["outcome"])
        assertEquals("0", result["worker_exit"])
        assertTrue(Files.size(temporary.resolve("verbose.log")) > 1_000_000)
    }

    @Test
    fun testHungSolverIsKilledWithoutInventingARawSolverStatus() {
        val result = fixture("solve-hang", BenchmarkLimits(100, 10_000, 1_000))
        assertEquals("TIMEOUT", result["outcome"])
        assertEquals("SOLVE", result["timeout_phase"])
        assertEquals("NOT_RETURNED", result["raw_status"])
    }

    @Test
    fun testHungParserUsesTheSeparateStartupBudget() {
        val result = fixture("parse-hang", BenchmarkLimits(10_000, 250, 10_000))
        assertEquals("STARTUP_PARSE_TIMEOUT", result["outcome"])
        assertEquals("NOT_RUN", result["raw_status"])
    }

    @Test
    fun testHungValidationKeepsRawSatButDoesNotCountItAsValidated() {
        val result = fixture("validation-hang", BenchmarkLimits(1_000, 10_000, 100))
        assertEquals("UNVERIFIED_WITNESS", result["outcome"])
        assertEquals("SAT", result["raw_status"])
        assertEquals("TIMEOUT", result["validation_status"])
    }

    @Test
    fun testCrashedWorkerIsNotReportedAsUnknownOrUnsat() {
        val result = fixture("crash", BenchmarkLimits(1_000, 10_000, 1_000))
        assertEquals("CRASH", result["outcome"])
        assertEquals("7", result["worker_exit"])
        assertEquals("NOT_RUN", result["raw_status"])
    }

    @Test
    fun testLateReportedResultCannotEvadeTheWatchdogPoll() {
        val result = fixture("late-result", BenchmarkLimits(100, 10_000, 1_000))
        assertEquals("TIMEOUT", result["outcome"])
        assertEquals("SAT", result["raw_status"])
        assertEquals("SOLVE", result["timeout_phase"])
    }

    @Test
    fun testIndependentValidationFixesEveryOriginalSort() {
        KContext().use { ctx -> with(ctx) {
            val x = mkConst("x", stringSort)
            val n = mkConst("n", intSort)
            val b = mkConst("b", boolSort)
            val assertions = listOf(mkEq(x, mkStringLiteral("ab")), mkEq(n, mkStringLen(x)), b)
            KZ3Solver(this).use { solver ->
                solver.assert(assertions)
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                solver.model().use { model ->
                    val bindings = assertNotNull(exactBindings(this, assertions, model, BenchmarkDeadline(10_000)))
                    assertEquals(3, bindings.size)
                    assertEquals(KSolverStatus.SAT, validateBindings(this, assertions, bindings, BenchmarkBackend.CVC5, BenchmarkDeadline(10_000)))
                }
            }
            val good = listOf(mkEq(x, mkStringLiteral("ab")), mkEq(n, mkIntNum(2)), b)
            for (bad in listOf(mkEq(x, mkStringLiteral("ba")), mkEq(n, mkIntNum(3)), mkNot(b))) {
                assertEquals(KSolverStatus.UNSAT,
                    validateBindings(this, assertions, good + bad, BenchmarkBackend.CVC5, BenchmarkDeadline(10_000)))
            }
        } }
    }

    @Test
    fun testDisagreementSummaryIgnoresInconclusiveAndUnverifiedResults() {
        fun row(solver: String, outcome: String, raw: String = outcome) =
            mapOf("sample_id" to "sample", "trial" to "1", "solver" to solver, "outcome" to outcome, "raw_status" to raw)
        assertTrue(decisiveDisagreements(listOf(row("z3", "UNKNOWN"), row("cvc5", "UNSAT"))).isEmpty())
        assertTrue(decisiveDisagreements(listOf(row("z3", "UNVERIFIED_WITNESS", "SAT"), row("cvc5", "UNSAT"))).isEmpty())
        assertEquals(1, decisiveDisagreements(listOf(row("z3", "SAT"), row("cvc5", "UNSAT"))).size)
    }

    @Test
    fun testCheckpointRecoveryRepairsAPartialCsvWithoutRepeatingRecords() {
        Files.createDirectories(temporary.resolve("records"))
        Files.createDirectories(temporary.resolve("exclusions"))
        val row = mapOf("key" to "attempt", "sample_id" to "sample", "solver" to "z3", "trial" to "1",
            "outcome" to "SAT", "raw_status" to "SAT", "completed_utc" to "2026-09-19T00:00:00Z")
        writeProperties(temporary.resolve("records/attempt.properties"), row)
        Files.writeString(temporary.resolve("raw.csv"), "\"partial")
        restoreAggregateCsv(temporary, readRecords(temporary), readExclusions(temporary))
        val restored = Files.readString(temporary.resolve("raw.csv"))
        restoreAggregateCsv(temporary, readRecords(temporary), readExclusions(temporary))
        assertEquals(restored, Files.readString(temporary.resolve("raw.csv")))
        assertEquals(2, BenchmarkCsv.parse(restored).size)
        assertEquals(listOf(row), readRecords(temporary))
    }

    @Test
    fun testResumeSkipsCommittedAttemptsAndPreservesSourceResults() {
        val source = Files.createDirectory(temporary.resolve("source"))
        val output = Files.createDirectory(temporary.resolve("output"))
        Files.writeString(source.resolve("sample.smt2"),
            "(set-logic QF_S)\n(declare-fun x () String)\n(assert (= x \"ab\"))\n(check-sat)\n")
        Files.writeString(source.resolve("results.csv"), "historical data")
        runNoodlerBenchmarks(listOf("run", "--source", source.toString(), "--output", output.toString(),
            "--solvers", "z3", "--max-jobs", "1"))
        val run = Files.list(output).use { it.findFirst().orElseThrow() }
        val before = readRecords(run)
        assertEquals(1, before.size)
        assertEquals("SAT", before.single()["outcome"])
        val logsBefore = Files.list(run.resolve("logs")).use { it.count() }
        runNoodlerBenchmarks(listOf("run", "--resume", run.toString(), "--max-jobs", "2"))
        assertEquals(before, readRecords(run))
        assertEquals(logsBefore, Files.list(run.resolve("logs")).use { it.count() })
        assertEquals("historical data", Files.readString(source.resolve("results.csv")))
        assertFalse(Files.exists(source.resolve("manifest.csv")))
        assertFailsWith<IllegalArgumentException> {
            runNoodlerBenchmarks(listOf("run", "--resume", run.toString(), "--solve-ms", "5"))
        }
    }

    private fun fixture(mode: String, limits: BenchmarkLimits): Map<String, String> {
        val token = "fixture-$mode"
        return BenchmarkProcess(limits).run(
            benchmarkJavaCommand("io.ksmt.solver.noodler.benchmark.BenchmarkProcessFixtureKt", listOf(token, mode)),
            token, temporary.resolve("$mode.log"), temporary,
        )
    }
}

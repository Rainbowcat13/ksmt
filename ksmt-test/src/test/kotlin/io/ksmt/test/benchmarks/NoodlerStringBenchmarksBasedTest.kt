package io.ksmt.test.benchmarks

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.noodler.KNoodlerSolver
import io.ksmt.solver.z3.KZ3SMTLibParser
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.KExprTheoryRequirement
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds

/**
 * Legacy compatibility smoke recorder, not a performance comparison.
 * Thread cancellation cannot isolate an unresponsive native backend. Use the
 * :ksmt-noodler:benchmarkNoodlerSolvers subprocess harness for measured comparisons.
 */
@Disabled("Unsafe native thread timeout; use :ksmt-noodler:benchmarkNoodlerSolvers")
class NoodlerStringBenchmarksBasedTest {

    @Execution(ExecutionMode.SAME_THREAD)
    @ParameterizedTest(name = "{0}")
    @MethodSource("noodlerTestData")
    fun testSolver(name: String, samplePath: Path) {
        assumeTrue(name != NO_TEST_DATA_NAME, "No string-data benchmarks found")

        System.err.println("NOODLER START $name")
        val result = runSample(samplePath)

        statusCounters.computeIfAbsent(result.status) { AtomicInteger() }.incrementAndGet()
        synchronized(reportLock) {
            reportPath.appendText(
                "${csv(name)},${csv(result.backend)},${csv(result.status)},${result.elapsedMs},${csv(result.details ?: "")}\n"
            )
        }

        val detailsSuffix = result.details?.let { " -- $it" } ?: ""
        System.err.println("NOODLER END $name: ${result.status} [${result.backend}] in ${result.elapsedMs}ms$detailsSuffix")
    }

    private fun runSample(samplePath: Path): SampleResult {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "noodler-string-sample").apply { isDaemon = true }
        }

        return try {
            val future = executor.submit<SampleResult> { runSampleImpl(samplePath) }
            future.get(SAMPLE_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            SampleResult("unknown", "TIMEOUT", SAMPLE_TIMEOUT.inWholeMilliseconds, "sample timeout")
        } catch (t: Throwable) {
            SampleResult("unknown", "ERROR", -1, describeThrowable(t))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private fun runSampleImpl(samplePath: Path): SampleResult = try {
        with(KContext()) {
            val assertions = KZ3SMTLibParser(this).parse(samplePath)
            val theoryRequirement = KExprTheoryRequirement(this).also { requirement ->
                assertions.forEach(requirement::apply)
            }
            val preferredBackend = chooseBackend(samplePath)

            try {
                solveSample(this, assertions, theoryRequirement, preferredBackend)
            } catch (t: Throwable) {
                if (preferredBackend.backend.name == z3Backend.name && isZ3UnsupportedFeature(t)) {
                    val fallbackReason = "z3 unsupported, retry with cvc5: ${describeThrowable(t)}"
                    solveSample(
                        this,
                        assertions,
                        theoryRequirement,
                        BackendSelection(cvc5Backend, fallbackReason)
                    )
                } else {
                    throw t
                }
            }
        }
    } catch (t: Throwable) {
        SampleResult("unknown", "ERROR", -1, describeThrowable(t))
    }

    private fun solveSample(
        ctx: KContext,
        assertions: List<KExpr<KBoolSort>>,
        theoryRequirement: KExprTheoryRequirement,
        selection: BackendSelection,
    ): SampleResult =
        KNoodlerSolver(ctx, selection.backend.factory(ctx)).use { solver ->
            solver.configure {
                optimizeForTheories(theoryRequirement.usedTheories, theoryRequirement.hasQuantifiers)
            }

            assertions.forEach(solver::assert)

            var status = KSolverStatus.UNKNOWN
            val elapsedMs = measureTimeMillis {
                status = solver.check(CHECK_TIMEOUT)
            }

            val details = buildList {
                selection.reason?.let(::add)
                solver.lastCheckStatistics?.let { stats ->
                    if (stats.noodlerSkipped) {
                        add("noodler(skipped=${stats.noodlerSkipReason ?: "unsupported"}, alpha=${stats.alphabetSize})")
                    } else {
                        add(
                            "noodler(alpha=${stats.alphabetSize}, invocations=${stats.noodlerInvocations}, " +
                                "ms=${stats.noodlerTimeMs}, generated=${stats.generatedConstraints}, " +
                                "fresh=${stats.freshConstraints})"
                        )
                    }
                    add("base(ms=${stats.baseSolverCheckTimeMs})")
                }
                if (status == KSolverStatus.UNKNOWN) {
                    add("reason=${solver.reasonOfUnknown()}")
                }
            }.takeIf { it.isNotEmpty() }?.joinToString("; ")

            SampleResult(selection.backend.name, status.name, elapsedMs, details)
        }

    private fun chooseBackend(samplePath: Path): BackendSelection {
        val sample = Files.readString(samplePath)
        val unsupportedFeatures = unsupportedZ3StringTokens
            .filter { (token, _) -> sample.contains(token) }
            .map { (_, featureName) -> featureName }

        return if (unsupportedFeatures.isEmpty()) {
            BackendSelection(z3Backend)
        } else {
            BackendSelection(
                cvc5Backend,
                "requires cvc5: ${unsupportedFeatures.joinToString()}"
            )
        }
    }

    companion object {
        private val reportPath = Paths.get("build/reports/noodler-string-results.csv")
        private val reportLock = Any()
        private val statusCounters = ConcurrentHashMap<String, AtomicInteger>()
        private val CHECK_TIMEOUT = (System.getenv("noodlerCheckTimeoutSeconds")?.toLongOrNull() ?: 10L).seconds
        private val SAMPLE_TIMEOUT = (System.getenv("noodlerSampleTimeoutSeconds")?.toLongOrNull() ?: 20L).seconds
        private const val NO_TEST_DATA_NAME = "__NO__TEST__DATA__"
        private val z3Backend = BaseSolverBackend("z3", ::KZ3Solver)
        private val cvc5Backend = BaseSolverBackend("cvc5", ::KCvc5Solver)
        private val unsupportedZ3StringTokens = listOf(
            "str.indexof_re" to "KStringIndexOfRegexExpr",
            "str.replace_all" to "KStringReplaceAllExpr",
            "str.replace_re_all" to "KStringReplaceAllWithRegexExpr",
            "str.replace_re" to "KStringReplaceWithRegexExpr",
            "str.to_lower" to "KStringToLowerExpr",
            "str.to_upper" to "KStringToUpperExpr",
            "str.rev" to "KStringReverseExpr",
        )

        private val testDataChunkSize = System.getenv("benchmarkChunkMaxSize")?.toIntOrNull() ?: Int.MAX_VALUE
        private val testDataChunk = System.getenv("benchmarkChunk")?.toIntOrNull() ?: 0
        private val benchmarkPathPrefix = System.getenv("benchmarkPathPrefix")?.takeIf { it.isNotBlank() }

        @BeforeAll
        @JvmStatic
        fun prepareReport() {
            reportPath.parent?.createDirectories()
            reportPath.writeText("name,backend,status,elapsed_ms,details\n")
            val filterSuffix = benchmarkPathPrefix?.let { ", prefix=$it" } ?: ""
            val timeoutSuffix = ", checkTimeout=${CHECK_TIMEOUT.inWholeSeconds}s, sampleTimeout=${SAMPLE_TIMEOUT.inWholeSeconds}s"
            System.err.println(
                "NOODLER CONFIG: chunk=$testDataChunk, chunkSize=$testDataChunkSize$filterSuffix$timeoutSuffix"
            )
        }

        @AfterAll
        @JvmStatic
        fun printSummary() {
            val summary = statusCounters.entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.key}=${it.value.get()}" }
            System.err.println("NOODLER SUMMARY: $summary")
            System.err.println("NOODLER REPORT: $reportPath")
        }

        @JvmStatic
        fun noodlerTestData(): List<Arguments> {
            val root = stringDataLocation()
            val testData = Files.walk(root).use { paths ->
                val byRelativeName = Comparator.comparing<Path, String> {
                    root.relativize(it).toString().replace('\\', '/')
                }

                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".smt2") }
                    .sorted(byRelativeName)
                    .filter { path ->
                        benchmarkPathPrefix?.let { prefix ->
                            root.relativize(path).toString().replace('\\', '/').startsWith(prefix)
                        } ?: true
                    }
                    .skip(testDataChunk.toLong() * testDataChunkSize)
                    .limit(testDataChunkSize.toLong())
                    .map { path ->
                        val name = root.relativize(path).toString().replace('\\', '/')
                        Arguments.of(name, path)
                    }
                    .toList()
            }

            return if (testData.isEmpty()) {
                listOf(Arguments.of(NO_TEST_DATA_NAME, Paths.get(".")))
            } else {
                testData
            }
        }

        private fun stringDataLocation(): Path {
            val cwd = Paths.get("").toAbsolutePath().normalize()
            val base = listOf(
                cwd.resolve("testData").resolve("string-data"),
                cwd.resolve("ksmt-test").resolve("testData").resolve("string-data")
            ).firstOrNull { Files.isDirectory(it) } ?: error("No string-data directory found from $cwd")

            if (Files.isDirectory(base.resolve("QF_S")) || Files.isDirectory(base.resolve("QF_SLIA"))) {
                return base
            }

            return Files.list(base).use { revisions ->
                revisions
                    .filter { Files.isDirectory(it) }
                    .sorted()
                    .findFirst()
                    .orElseThrow { error("No string-data revision directory found in $base") }
            }
        }

        private fun csv(value: String): String {
            val escaped = value.replace("\"", "\"\"")
            return "\"$escaped\""
        }

        private fun describeThrowable(throwable: Throwable): String =
            if (throwable is StackOverflowError) {
                val stackPrefix = throwable.stackTrace
                    .take(8)
                    .joinToString(" <- ") { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                if (stackPrefix.isBlank()) "StackOverflowError" else "StackOverflowError <- $stackPrefix"
            } else {
                generateSequence(throwable) { current ->
                when (current) {
                    is ExceptionInInitializerError -> current.exception ?: current.cause
                    else -> current.cause
                }
            }
                .take(5)
                .map { current ->
                    val name = current::class.simpleName ?: current.javaClass.name
                    val message = current.message?.takeIf { it.isNotBlank() }
                    if (message == null) name else "$name: $message"
                }
                .distinct()
                .joinToString(" <- ")
                .ifBlank { "unknown error" }
            }

        private fun isZ3UnsupportedFeature(throwable: Throwable): Boolean =
            generateSequence(throwable) { current ->
                when (current) {
                    is ExceptionInInitializerError -> current.exception ?: current.cause
                    else -> current.cause
                }
            }.filterIsInstance<KSolverUnsupportedFeatureException>()
                .any { it.message?.contains("not supported in z3") == true }
    }

    private data class BaseSolverBackend(
        val name: String,
        val factory: (KContext) -> KSolver<out KSolverConfiguration>,
    )

    private data class BackendSelection(
        val backend: BaseSolverBackend,
        val reason: String? = null,
    )

    private data class SampleResult(
        val backend: String,
        val status: String,
        val elapsedMs: Long,
        val details: String?,
    )
}

package io.ksmt.solver.noodler.core

import io.ksmt.KContext
import io.ksmt.expr.KConst
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.KNoodlerSolver
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import io.ksmt.solver.noodler.automaton.ALL_CHAR_ALPHABET
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.solver.z3.KZ3SMTLibParser
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KStringSort
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import java.util.Comparator
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun main(args: Array<String>) {
    val config = RunnerConfig.fromArgs(args)
    val runner = NoodlerQfSimpleRunner(
        repoRoot = Paths.get("").toAbsolutePath().normalize(),
        from = config.from,
        limit = config.limit,
        timeoutMs = config.timeoutMs,
    )

    when (config.command) {
        RunnerCommand.PREPARE -> runner.prepare()
        RunnerCommand.RUN -> runner.run()
        RunnerCommand.LAUNCH -> runner.launch(config.rebuild)
        RunnerCommand.RUN_SINGLE -> runner.runSingle(
            samplePath = config.sample ?: error("Missing sample path"),
            backend = config.backend,
        )
    }
}

private class NoodlerQfSimpleRunner(
    private val repoRoot: Path,
    private val from: Int,
    private val limit: Int?,
    private val timeoutMs: Long?,
) {
    private val sourceRoot = locateStringDataRoot()
    private val targetRoot = repoRoot.resolve("ksmt-noodler").resolve("qf_simple_tests")
    private val manifestPath = targetRoot.resolve("manifest.csv")

    fun prepare() {
        if (Files.isRegularFile(manifestPath)) {
            println("Reusing existing prepared corpus at $targetRoot; historical data and results are preserved")
            return
        }
        val candidates = collectFragmentSafeQfSamples()
            .sortedWith(compareBy<SampleDescriptor>({ it.variableCount }, { it.relativePath }))

        recreateDirectory(targetRoot)

        manifestPath.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.appendLine("order,variable_count,statement_count,source_relative_path,copied_name")

            candidates.forEachIndexed { index, sample ->
                val order = index + 1
                val copiedName = "%04d__v%03d__%s".format(order, sample.variableCount, sample.sourcePath.name)
                val targetPath = targetRoot.resolve(copiedName)
                if (Files.exists(targetPath)) {
                    require(Files.mismatch(sample.sourcePath, targetPath) == -1L) {
                        "Prepared file differs from source; preserving existing file: $targetPath"
                    }
                } else {
                    Files.copy(sample.sourcePath, targetPath)
                }
                writer.appendLine(
                    listOf(
                        order.toString(),
                        sample.variableCount.toString(),
                        sample.statementCount.toString(),
                        csv(sample.relativePath),
                        csv(copiedName),
                    ).joinToString(",")
                )
            }
        }

        println(
            "Prepared ${candidates.size} fragment-safe QF samples with extractable Noodler statements in " +
                targetRoot.invariantSeparatorsPathString
        )
    }

    fun launch(rebuild: Boolean) {
        if (rebuild || !targetRoot.isDirectory() || !Files.isRegularFile(manifestPath)) {
            prepare()
        }
        run()
    }

    fun run() {
        val arguments = mutableListOf(
            "run", "--source", targetRoot.toString(), "--offset", from.toString(),
            "--solve-ms", (timeoutMs ?: 10_000L).toString(),
            "--output", repoRoot.resolve("build/reports/noodler-benchmarks").toString(),
        )
        limit?.let { arguments += listOf("--limit", it.toString()) }
        io.ksmt.solver.noodler.benchmark.runNoodlerBenchmarks(arguments)
    }

    fun runSingle(samplePath: String, backend: KNoodlerBackend) {
        val result = try {
            runKNoodler(Paths.get(samplePath), backend, timeoutMs = null)
        } catch (ex: Throwable) {
            NoodlerResult(
                status = "ERROR",
                elapsedMs = 0L,
                statementCount = 0,
                generatedConstraints = 0,
                freshConstraints = 0,
                modelAssignments = emptyMap(),
                details = ex::class.simpleName + (ex.message?.let { ": $it" } ?: ""),
            )
        }

        println(
            listOf(
                result.status,
                result.elapsedMs.toString(),
                result.statementCount.toString(),
                result.generatedConstraints.toString(),
                result.freshConstraints.toString(),
                serializeStringAssignments(result.modelAssignments),
                result.details.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '),
            ).joinToString("\t")
        )
    }

    private fun collectFragmentSafeQfSamples(): List<SampleDescriptor> {
        val byRelativeName = Comparator.comparing<Path, String> {
            sourceRoot.relativize(it).toString().replace('\\', '/')
        }

        val files = Files.walk(sourceRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".smt2") }
                .sorted(byRelativeName)
                .toList()
        }

        return files.mapNotNull { path ->
            val relativeName = sourceRoot.relativize(path).toString().replace('\\', '/')
            if (!relativeName.startsWith("QF_")) {
                return@mapNotNull null
            }

            with(KContext()) {
                val assertions = KZ3SMTLibParser(this).parse(path)
                val analysis = assertions.analyzeForNoodler(ALL_CHAR_ALPHABET)
                if (analysis.skipNoodler) {
                    return@with null
                }

                val statements = extractNoodlerStatements(assertions)
                if (statements.isEmpty()) {
                    return@with null
                }
                SampleDescriptor(
                    sourcePath = path,
                    relativePath = relativeName,
                    variableCount = countStringVariables(assertions),
                    statementCount = statements.size,
                )
            }
        }
    }

    private fun runKNoodler(sample: Path, backend: KNoodlerBackend, timeoutMs: Long?): NoodlerResult = with(KContext()) {
        var statementCount = 0
        try {
            val assertions = KZ3SMTLibParser(this).parse(sample)
            val analysis = assertions.analyzeForNoodler(ALL_CHAR_ALPHABET)
            statementCount = if (analysis.skipNoodler) {
                0
            } else {
                extractNoodlerStatements(assertions).size
            }
            val stringVariables = collectStringVariables(assertions)

            backend.factory(this).use { solver ->
                lateinit var status: KSolverStatus
                val elapsedMs = measureTimeMillis {
                    assertions.forEach(solver::assert)
                    status = solver.check(timeoutMs?.milliseconds ?: Duration.INFINITE)
                }

                val statusDetails = if (status == KSolverStatus.UNKNOWN) {
                    solver.reasonOfUnknown()
                } else {
                    ""
                }
                val modelExtraction = if (status == KSolverStatus.SAT) {
                    solver.model().use { model ->
                        if (backend == KNoodlerBackend.YICES) {
                            assertions.forEach { assertion ->
                                check(model.eval(assertion, isComplete = true) == trueExpr) {
                                    "Hybrid model does not satisfy original assertion: $assertion"
                                }
                            }
                        }
                        extractStringAssignments(model, stringVariables)
                    }
                } else {
                    ModelExtraction.EMPTY
                }
                val checkStatistics = (solver as? KNoodlerSolver)?.lastCheckStatistics
                val hybridStatistics = (solver as? KNoodlerYicesSolver)?.lastCheckStatistics

                return@with NoodlerResult(
                    status = status.name,
                    elapsedMs = elapsedMs,
                    statementCount = statementCount,
                    generatedConstraints = checkStatistics?.generatedConstraints ?: hybridStatistics?.learnedConstraints ?: 0,
                    freshConstraints = checkStatistics?.freshConstraints ?: hybridStatistics?.learnedConstraints ?: 0,
                    modelAssignments = modelExtraction.assignments,
                    details = joinDetails(
                        checkStatistics?.let(::formatNoodlerCheckStatistics).orEmpty(),
                        hybridStatistics?.toString().orEmpty(),
                        if (backend == KNoodlerBackend.YICES && status == KSolverStatus.SAT) "witness=validated" else "",
                        statusDetails,
                        modelExtraction.details,
                    ),
                )
            }
        } catch (ex: KSolverUnsupportedFeatureException) {
            return@with NoodlerResult(
                status = "UNSUPPORTED",
                elapsedMs = 0L,
                statementCount = statementCount,
                generatedConstraints = 0,
                freshConstraints = 0,
                modelAssignments = emptyMap(),
                details = ex::class.simpleName + (ex.message?.let { ": $it" } ?: ""),
            )
        } catch (ex: Throwable) {
            return@with NoodlerResult(
                status = "ERROR",
                elapsedMs = 0L,
                statementCount = statementCount,
                generatedConstraints = 0,
                freshConstraints = 0,
                modelAssignments = emptyMap(),
                details = ex::class.simpleName + (ex.message?.let { ": $it" } ?: ""),
            )
        }
    }

    private fun countStringVariables(assertions: List<KExpr<KBoolSort>>): Int {
        return collectStringVariables(assertions).size
    }

    private fun collectStringVariables(assertions: List<KExpr<KBoolSort>>): Map<String, KConst<*>> {
        val variables = linkedMapOf<String, KConst<*>>()
        val pending = ArrayDeque<KExpr<*>>()
        val visited = linkedSetOf<KExpr<*>>()
        pending.addAll(assertions)

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!visited.add(current)) {
                continue
            }

            if (current is KConst<*> && current.sort is KStringSort) {
                variables.putIfAbsent(current.decl.name, current)
            }

            val app = current as? io.ksmt.expr.KApp<*, *> ?: continue
            pending.addAll(app.args)
        }

        return variables
    }

    private fun locateStringDataRoot(): Path {
        val base = repoRoot.resolve("ksmt-test").resolve("testData").resolve("string-data")
        require(base.isDirectory()) {
            "No string-data directory found at ${base.invariantSeparatorsPathString}"
        }

        if (base.resolve("QF_S").isDirectory() || base.resolve("QF_SLIA").isDirectory()) {
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

    private fun recreateDirectory(path: Path) {
        // Preparing a compatibility corpus must not delete saved benchmark results.
        path.createDirectories()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun formatNoodlerCheckStatistics(stats: KNoodlerSolver.CheckStatistics): String {
        val noodlerDetails = if (stats.noodlerSkipped) {
            "noodler(skipped=${stats.noodlerSkipReason ?: "unsupported"}, alpha=${stats.alphabetSize})"
        } else {
            "noodler(alpha=${stats.alphabetSize}, invocations=${stats.noodlerInvocations}, " +
                "ms=${stats.noodlerTimeMs}, generated=${stats.generatedConstraints}, " +
                "fresh=${stats.freshConstraints})"
        }
        return "$noodlerDetails; base(ms=${stats.baseSolverCheckTimeMs})"
    }

    private data class SampleDescriptor(
        val sourcePath: Path,
        val relativePath: String,
        val variableCount: Int,
        val statementCount: Int,
    )

    private data class NoodlerResult(
        val status: String,
        val elapsedMs: Long,
        val statementCount: Int,
        val generatedConstraints: Int,
        val freshConstraints: Int,
        val modelAssignments: Map<String, String>,
        val details: String,
    )

}

internal enum class KNoodlerBackend(
    val cliName: String,
    val displayName: String,
    val factory: (KContext) -> KSolver<out KSolverConfiguration>,
) {
    Z3("z3", "KNoodler(Z3)", { KNoodlerSolver(it, KZ3Solver(it)) }),
    CVC5("cvc5", "KNoodler(cvc5)", { KNoodlerSolver(it, KCvc5Solver(it)) }),
    YICES("yices", "KNoodlerYices", ::KNoodlerYicesSolver);

    companion object {
        fun fromCliName(value: String?): KNoodlerBackend {
            return values().firstOrNull { it.cliName.equals(value, ignoreCase = true) }
                ?: error("Unknown backend '$value', expected one of: ${values().joinToString { it.cliName }}")
        }
    }
}

internal enum class StringModelComparisonStatus {
    SKIPPED,
    NOODLER_MODEL_EMPTY,
    REFERENCE_MODEL_EMPTY,
    NO_OVERLAP,
    MATCH,
    DIFFERENT_WITNESS,
}

internal data class StringModelComparison(
    val status: StringModelComparisonStatus,
    val details: String,
)

private data class ModelExtraction(
    val assignments: Map<String, String>,
    val details: String,
) {
    companion object {
        val EMPTY = ModelExtraction(emptyMap(), "")
    }
}

internal fun compareStringModels(
    noodlerStatus: String,
    noodlerModel: Map<String, String>,
    referenceSolverName: String,
    referenceStatus: String,
    referenceModel: Map<String, String>,
): StringModelComparison {
    if (noodlerStatus != "SAT") {
        return StringModelComparison(
            status = StringModelComparisonStatus.SKIPPED,
            details = "comparison skipped because Noodler status=$noodlerStatus",
        )
    }
    if (referenceStatus != "SAT") {
        return StringModelComparison(
            status = StringModelComparisonStatus.SKIPPED,
            details = "comparison skipped because $referenceSolverName status=$referenceStatus",
        )
    }
    if (noodlerModel.isEmpty()) {
        return StringModelComparison(
            status = StringModelComparisonStatus.NOODLER_MODEL_EMPTY,
            details = "Noodler did not produce string assignments",
        )
    }
    if (referenceModel.isEmpty()) {
        return StringModelComparison(
            status = StringModelComparisonStatus.REFERENCE_MODEL_EMPTY,
            details = "$referenceSolverName did not produce string assignments",
        )
    }

    val overlap = noodlerModel.keys.intersect(referenceModel.keys).sorted()
    if (overlap.isEmpty()) {
        return StringModelComparison(
            status = StringModelComparisonStatus.NO_OVERLAP,
            details = "no shared string variables between models",
        )
    }

    val mismatches = overlap.filter { variable ->
        noodlerModel.getValue(variable) != referenceModel.getValue(variable)
    }
    if (mismatches.isEmpty()) {
        return StringModelComparison(
            status = StringModelComparisonStatus.MATCH,
            details = "overlap=${overlap.size}; vars=${overlap.joinToString(";")}",
        )
    }

    val mismatchPreview = mismatches.take(5).joinToString("; ") { variable ->
        "$variable(noodler=${renderModelValue(noodlerModel.getValue(variable))}," +
            " reference=${renderModelValue(referenceModel.getValue(variable))})"
    }
    val suffix = if (mismatches.size > 5) "; +${mismatches.size - 5} more" else ""
    return StringModelComparison(
        status = StringModelComparisonStatus.DIFFERENT_WITNESS,
        details = "overlap=${overlap.size}; mismatches=$mismatchPreview$suffix",
    )
}

internal fun serializeStringAssignments(assignments: Map<String, String>): String {
    if (assignments.isEmpty()) {
        return ""
    }

    return assignments.entries
        .sortedBy { it.key }
        .joinToString(";") { (name, value) ->
            "${encodeAssignmentField(name)}=${encodeAssignmentField(value)}"
        }
}

internal fun deserializeStringAssignments(serialized: String): Map<String, String> {
    if (serialized.isBlank()) {
        return emptyMap()
    }

    return serialized.split(';')
        .filter { it.isNotEmpty() }
        .associate { entry ->
            val separator = entry.indexOf('=')
            require(separator >= 0) { "Malformed serialized assignment entry: '$entry'" }
            val name = decodeAssignmentField(entry.substring(0, separator))
            val value = decodeAssignmentField(entry.substring(separator + 1))
            name to value
        }
}

@Suppress("UNCHECKED_CAST")
private fun extractStringAssignments(
    model: KModel,
    variablesByName: Map<String, KConst<*>>,
): ModelExtraction {
    val assignments = linkedMapOf<String, String>()
    val issues = mutableListOf<String>()

    for ((name, variable) in variablesByName) {
        val value = try {
            model.eval(variable as KExpr<KStringSort>)
        } catch (error: Throwable) {
            issues += "$name=${error::class.simpleName}${error.message?.let { ": $it" } ?: ""}"
            continue
        }

        val literal = value as? KStringLiteralExpr
        if (literal == null) {
            issues += "$name=non-literal:${value::class.simpleName}"
            continue
        }

        // Canonical SMT-LIB text preserves NUL and explicit surrogate code points in subprocess output.
        assignments[name] = literal.toString()
    }

    return ModelExtraction(
        assignments = assignments,
        details = issues.joinToString("; "),
    )
}

private fun joinDetails(vararg details: String): String =
    details.filter { it.isNotBlank() }.joinToString("; ")

private fun renderModelValue(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

private fun encodeAssignmentField(value: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeAssignmentField(value: String): String =
    String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

private enum class RunnerCommand {
    PREPARE,
    RUN,
    LAUNCH,
    RUN_SINGLE,
}

private data class RunnerConfig(
    val command: RunnerCommand,
    val from: Int,
    val limit: Int?,
    val timeoutMs: Long?,
    val sample: String?,
    val backend: KNoodlerBackend,
    val rebuild: Boolean,
) {
    companion object {
        fun fromArgs(args: Array<String>): RunnerConfig {
            require(args.isNotEmpty()) {
                "Expected command: prepare | run | launch [--from N] [--limit N] [--timeout-ms N] [--rebuild] | " +
                    "run-single --sample PATH [--backend z3|cvc5|yices]"
            }

            val command = when (args.first().lowercase()) {
                "prepare" -> RunnerCommand.PREPARE
                "run" -> RunnerCommand.RUN
                "launch" -> RunnerCommand.LAUNCH
                "run-single" -> RunnerCommand.RUN_SINGLE
                else -> error("Unknown command '${args.first()}'")
            }

            var from = 0
            var limit: Int? = null
            var timeoutMs: Long? = 10_000L
            var sample: String? = null
            var backend = KNoodlerBackend.Z3
            var rebuild = false
            var index = 1

            while (index < args.size) {
                when (val argument = args[index]) {
                    "--from" -> {
                        from = args.getOrNull(index + 1)?.toIntOrNull()
                            ?: error("Missing integer value for --from")
                        index += 2
                    }

                    "--limit" -> {
                        limit = args.getOrNull(index + 1)?.toIntOrNull()
                            ?: error("Missing integer value for --limit")
                        index += 2
                    }

                    "--timeout-ms" -> {
                        timeoutMs = args.getOrNull(index + 1)?.toLongOrNull()
                            ?: error("Missing integer value for --timeout-ms")
                        index += 2
                    }

                    "--sample" -> {
                        sample = args.getOrNull(index + 1)
                            ?: error("Missing sample path for --sample")
                        index += 2
                    }

                    "--backend" -> {
                        backend = KNoodlerBackend.fromCliName(args.getOrNull(index + 1))
                        index += 2
                    }

                    "--rebuild" -> {
                        rebuild = true
                        index += 1
                    }

                    else -> error("Unknown argument '$argument'")
                }
            }

            return RunnerConfig(command, from, limit, timeoutMs, sample, backend, rebuild)
        }
    }
}

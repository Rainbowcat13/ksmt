@file:Suppress("UNCHECKED_CAST")

package io.ksmt.solver.noodler.benchmark

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KApp
import io.ksmt.expr.KConst
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFalse
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KTrue
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.KNoodlerSolver
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import io.ksmt.solver.noodler.checkNoodlerYicesSupport
import io.ksmt.solver.z3.KZ3SMTLibParser
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KSort
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

// One sequential transaction deliberately preserves phase boundaries and reports arbitrary backend failures.
@Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth", "TooGenericExceptionCaught", "PrintStackTrace")
internal fun runBenchmarkWorker(options: Map<String, String>) {
    val protocol = BenchmarkProtocol(options.getValue("token"))
    fun emit(kind: String, values: Map<String, String>) {
        println(protocol.encode(kind, values))
        System.out.flush()
    }
    val variant = BenchmarkVariant.resolve(options)
    val result = linkedMapOf("raw_status" to "NOT_RUN", "validation_status" to "NOT_APPLICABLE",
        "variant" to variant.id, "hybrid_configuration_sha256" to variant.settings.sha256)
    val backend = BenchmarkBackend.parse(options.getValue("solver"))
    val limits = BenchmarkLimits(
        options.getValue("solve-ms").toLong(), options.getValue("startup-parse-ms").toLong(),
        options.getValue("validation-ms").toLong(),
    )
    emit("PHASE", mapOf("phase" to "PARSE"))
    val parseStarted = System.nanoTime()
    var phase = "PARSE"
    try {
        KContext().use { ctx ->
            val sample = Paths.get(options.getValue("sample"))
            check(sha256File(sample) == options.getValue("sha256")) { "Source content changed after manifest creation" }
            val assertions = KZ3SMTLibParser(ctx).parse(sample)
            val unsupported = checkNoodlerYicesSupport(assertions)
            result["parse_ns"] = (System.nanoTime() - parseStarted).toString()
            result["eligible"] = (unsupported == null).toString()
            result["exclusion_reason"] = unsupported.orEmpty()
            result["assertion_count"] = assertions.size.toString()
            emit("ELIGIBILITY", result)
            if (unsupported != null) {
                result["outcome"] = "EXCLUDED"
                result["details"] = unsupported
                return@use
            }
            val setupStarted = System.nanoTime()
            backend.create(ctx, variant.settings).use { solver ->
                result["setup_ns"] = (System.nanoTime() - setupStarted).toString()
                phase = "SOLVE"
                result["raw_status"] = "NOT_RETURNED"
                emit("PHASE", mapOf("phase" to phase))
                val solveStarted = System.nanoTime()
                val deadline = BenchmarkDeadline(limits.solveMs)
                var status: KSolverStatus
                var reason = ""
                var solveTimedOut = false
                var nativeReturned = false
                try {
                    assertions.forEach { deadline.remaining(); solver.assert(it) }
                    status = solver.check(deadline.remaining())
                    nativeReturned = true
                    if (status == KSolverStatus.UNKNOWN) reason = solver.reasonOfUnknown()
                    if (deadline.expired()) { solveTimedOut = true; reason = "assertion/check deadline expired" }
                } catch (_: BenchmarkTimeout) {
                    status = KSolverStatus.UNKNOWN
                    solveTimedOut = true
                    reason = "assertion/check deadline expired"
                }
                result["solve_ns"] = (System.nanoTime() - solveStarted).toString()
                result["raw_status"] = if (nativeReturned) status.name else "NOT_RETURNED"
                result["details"] = reason
                result["outcome"] = if (solveTimedOut ||
                    (status == KSolverStatus.UNKNOWN && listOf("timeout", "timed out", "time limit")
                        .any { reason.contains(it, ignoreCase = true) })) {
                    "TIMEOUT"
                } else status.name
                if (result["outcome"] == "TIMEOUT") result["timeout_phase"] = "SOLVE"
                when (solver) {
                    is KNoodlerSolver -> solver.lastCheckStatistics?.let { stats ->
                        result["preprocess_skipped"] = stats.noodlerSkipped.toString()
                        result["preprocess_skip_reason"] = stats.noodlerSkipReason.orEmpty()
                        result["preprocess_ms"] = stats.noodlerTimeMs.toString()
                        result["base_check_ms"] = stats.baseSolverCheckTimeMs.toString()
                        result["generated_constraints"] = stats.generatedConstraints.toString()
                        result["fresh_constraints"] = stats.freshConstraints.toString()
                        result["solver_statistics"] = stats.toString()
                    }
                    is KNoodlerYicesSolver -> solver.lastCheckStatistics?.let { stats ->
                        result["preprocess_ms"] = stats.noodlerTimeMs.toString()
                        result["hybrid_rounds"] = stats.rounds.toString()
                        result["learned_constraints"] = stats.learnedConstraints.toString()
                        result["realization_checks"] = stats.realizationChecks.toString()
                        result["failure_phase"] = stats.failurePhase.orEmpty()
                        result["optional_preprocessing_skips"] = stats.optionalPreprocessingSkips.toString()
                        result["last_optional_skip_reason"] = stats.lastOptionalSkipReason.orEmpty()
                        result["bounded_search_bounds"] = stats.boundedSearchBounds.joinToString(",")
                        result["native_realization_checks"] = stats.nativeRealizationChecks.toString()
                        result["length_projection_fallbacks"] = stats.lengthProjectionFallbacks.toString()
                        result["length_projection_cache_hits"] = stats.lengthProjectionCacheHits.toString()
                        result["last_candidate_total_length"] = stats.lastCandidateTotalLength.orEmpty()
                        result["last_candidate_encoding_estimate"] =
                            stats.lastCandidateEncodingEstimate?.toString().orEmpty()
                        result["last_candidate_encoding_constraints"] =
                            stats.lastCandidateEncodingConstraints?.toString().orEmpty()
                        result["membership_transitions_considered"] = stats.membershipTransitionsConsidered.toString()
                        result["membership_transitions_encoded"] = stats.membershipTransitionsEncoded.toString()
                        result["membership_pruning_fallbacks"] = stats.membershipPruningFallbacks.toString()
                        result["regex_determinizations"] = stats.regexDeterminizations.toString()
                        result["regex_peak_intermediate_automata"] = stats.regexPeakIntermediateAutomata.toString()
                        result["regex_cached_roots"] = stats.regexCachedRoots.toString()
                        result["refinement_sweeps"] = stats.refinementSweeps.toString()
                        result["refinement_skips"] = stats.refinementSkips.toString()
                        result["phase_times_ms"] = stats.phaseTimesMs.entries.sortedBy { it.key }
                            .joinToString(";") { "${it.key}=${it.value}" }
                        result["solver_statistics"] = stats.toString()
                    }
                }
                emit("SOLVED", result)
                if (status == KSolverStatus.SAT && !solveTimedOut) {
                    phase = "VALIDATE"
                    emit("PHASE", mapOf("phase" to phase))
                    val validationStarted = System.nanoTime()
                    val validationDeadline = BenchmarkDeadline(limits.validationMs)
                    result["validator"] = backend.validator.id
                    try {
                        solver.model().use { model ->
                            val bindings = exactBindings(ctx, assertions, model, validationDeadline)
                            if (bindings == null) {
                                result["validation_status"] = "MISSING_VALUES"
                                result["outcome"] = "UNVERIFIED_WITNESS"
                                result["details"] =
                                    "Original Bool/Int/String constants lack complete literal assignments"
                            } else {
                                result["witness_bindings"] = bindings.size.toString()
                                val witness = bindings.joinToString("\n") { "(assert $it)" }
                                result["witness_sha256"] = sha256Text(witness)
                                emit("WITNESS", mapOf("bindings" to witness))
                                val validation = validateBindings(
                                    ctx, assertions, bindings, backend.validator, validationDeadline
                                )
                                result["validation_status"] = validation.name
                                result["outcome"] = when (validation) {
                                    KSolverStatus.SAT -> "SAT"
                                    KSolverStatus.UNSAT -> "INVALID_WITNESS"
                                    KSolverStatus.UNKNOWN -> "UNVERIFIED_WITNESS"
                                }
                                if (validation != KSolverStatus.SAT) {
                                    result["details"] =
                                        "Independent ${backend.validator.id} fixed-assignment check: $validation"
                                }
                            }
                        }
                    } catch (_: BenchmarkTimeout) {
                        result["validation_status"] = "TIMEOUT"
                        result["timeout_phase"] = "VALIDATE"
                        result["outcome"] = "UNVERIFIED_WITNESS"
                        result["details"] = "Independent witness validation deadline expired"
                    } catch (error: Exception) {
                        result["validation_status"] =
                            if (error is KSolverUnsupportedFeatureException) "UNSUPPORTED" else "ERROR"
                        result["outcome"] = "UNVERIFIED_WITNESS"
                        result["details"] = "${error.javaClass.simpleName}: ${error.message}"
                    } finally {
                        result["validation_ns"] = (System.nanoTime() - validationStarted).toString()
                    }
                }
            }
        }
    } catch (error: Throwable) {
        result["outcome"] = when {
            error is KSolverUnsupportedFeatureException -> "UNSUPPORTED"
            phase == "PARSE" -> "PARSE_ERROR"
            else -> "ERROR"
        }
        result["details"] = "${error.javaClass.simpleName}: ${error.message}"
        error.printStackTrace(System.err)
    }
    emit("RESULT", result)
}

internal fun exactBindings(
    ctx: KContext,
    assertions: List<KExpr<KBoolSort>>,
    model: KModel,
    deadline: BenchmarkDeadline,
): List<KExpr<KBoolSort>>? {
    val declarations = linkedSetOf<KDecl<*>>()
    val visited = hashSetOf<KExpr<*>>()
    val pending = ArrayDeque<KExpr<*>>()
    pending.addAll(assertions)
    while (pending.isNotEmpty()) {
        deadline.remaining()
        val expr = pending.removeLast()
        if (!visited.add(expr)) continue
        if (expr is KConst<*>) declarations += expr.decl
        if (expr is KApp<*, *>) pending.addAll(expr.args)
    }
    return declarations.map { declaration ->
        deadline.remaining()
        val variable = ctx.mkConstApp(declaration as KDecl<KSort>)
        val value: KExpr<*> = model.eval(variable, isComplete = true)
        if (!isLiteralAssignment(value)) return null
        ctx.mkEq(variable, value as KExpr<KSort>)
    }
}

private fun isLiteralAssignment(value: KExpr<*>): Boolean = when (value) {
    is KTrue, is KFalse, is KIntNumExpr, is KStringLiteralExpr -> true
    else -> false
}

internal fun validateBindings(
    ctx: KContext,
    assertions: List<KExpr<KBoolSort>>,
    bindings: List<KExpr<KBoolSort>>,
    validator: BenchmarkBackend,
    deadline: BenchmarkDeadline,
): KSolverStatus = validator.create(ctx).use { oracle ->
    (assertions + bindings).forEach { deadline.remaining(); oracle.assert(it) }
    oracle.check(deadline.remaining()).also { deadline.remaining() }
}

internal class BenchmarkTimeout : RuntimeException()

internal class BenchmarkDeadline(timeoutMs: Long) {
    private val started = System.nanoTime()
    private val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    fun expired(): Boolean = System.nanoTime() - started >= budget
    fun remaining(): Duration {
        val remaining = budget - (System.nanoTime() - started)
        if (remaining <= 0) throw BenchmarkTimeout()
        return remaining.nanoseconds
    }
}

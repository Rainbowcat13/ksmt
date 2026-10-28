package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.automaton.ALL_CHAR_ALPHABET
import io.ksmt.solver.noodler.core.Noodler
import io.ksmt.solver.noodler.core.extractNoodlerStatements
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.noodler.hybrid.HybridCheckControl
import io.ksmt.solver.noodler.hybrid.HybridIncomplete
import io.ksmt.solver.noodler.hybrid.MAX_STRING_CODE_POINT
import io.ksmt.sort.KBoolSort
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureTimeMillis
import kotlin.time.Duration

/** Adds one sound Unicode language propagation sweep to a string-capable backend. */
class KNoodlerSolver(
    private val ctx: KContext,
    private val baseSolver: KSolver<out KSolverConfiguration>,
    private val alphabet: List<Char> = ALL_CHAR_ALPHABET,
) : KSolver<KSolverConfiguration> {
    private val assertions = arrayListOf<KExpr<KBoolSort>>()
    private val scopeMarks = ArrayDeque<Int>()
    private val interrupted = AtomicBoolean()
    private val lifecycleLock = Any()
    @Volatile private var checking = false
    @Volatile private var closed = false
    private var lastStatus = KSolverStatus.UNKNOWN
    private var lastReason: String? = null
    private var lastModel: KModel? = null
    var lastCheckStatistics: CheckStatistics? = null
        private set

    @Suppress("UNCHECKED_CAST")
    override fun configure(configurator: KSolverConfiguration.() -> Unit) {
        ensureIdle()
        (baseSolver as KSolver<KSolverConfiguration>).configure(configurator)
        invalidate()
    }

    override fun assert(expr: KExpr<KBoolSort>) {
        ensureIdle()
        ctx.ensureContextMatch(expr)
        baseSolver.assert(expr)
        assertions += expr
        invalidate()
    }

    override fun assertAndTrack(expr: KExpr<KBoolSort>) {
        ensureIdle()
        ctx.ensureContextMatch(expr)
        baseSolver.assertAndTrack(expr)
        assertions += expr
        invalidate()
    }

    override fun push() {
        ensureIdle()
        baseSolver.push()
        scopeMarks.addLast(assertions.size)
        invalidate()
    }

    override fun pop(n: UInt) {
        ensureIdle()
        require(n <= scopeMarks.size.toUInt()) {
            "Can not pop $n scope levels because current scope level is ${scopeMarks.size}"
        }
        baseSolver.pop(n)
        repeat(n.toInt()) {
            val mark = scopeMarks.removeLast()
            assertions.subList(mark, assertions.size).clear()
        }
        invalidate()
    }

    override fun check(timeout: Duration): KSolverStatus = performCheck(emptyList(), timeout)

    override fun checkWithAssumptions(
        assumptions: List<KExpr<KBoolSort>>,
        timeout: Duration,
    ): KSolverStatus {
        ctx.ensureContextMatch(assumptions)
        return performCheck(assumptions, timeout)
    }

    // Backend exceptions have solver-specific types; invalidate the result and rethrow them unchanged.
    @Suppress("TooGenericExceptionCaught")
    private fun performCheck(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
        synchronized(lifecycleLock) {
            ensureIdle()
            interrupted.set(false)
            checking = true
            invalidate()
        }
        val control = HybridCheckControl(timeout, interrupted)
        val stats = MutableStatistics()
        var pushed = false
        try {
            control.checkpoint()
            val lemmas = prepareLemmas(assertions + assumptions, control, stats)
            control.checkpoint()
            baseSolver.push()
            pushed = true
            for (lemma in lemmas) {
                control.checkpoint()
                baseSolver.assert(lemma)
            }
            stats.baseTimeMs = measureTimeMillis {
                lastStatus = if (assumptions.isEmpty()) baseSolver.check(control.remaining()) else {
                    baseSolver.checkWithAssumptions(assumptions, control.remaining())
                }
            }
            control.checkpoint()
            when (lastStatus) {
                KSolverStatus.SAT -> {
                    lastModel = baseSolver.model().detach()
                    control.checkpoint()
                }
                KSolverStatus.UNKNOWN -> lastReason = baseSolver.reasonOfUnknown()
                KSolverStatus.UNSAT -> Unit
            }
        } catch (incomplete: HybridIncomplete) {
            lastStatus = KSolverStatus.UNKNOWN
            lastReason = incomplete.reason
            lastModel = null
        } catch (failure: Exception) {
            lastStatus = KSolverStatus.UNKNOWN
            lastReason = "check failed"
            lastModel = null
            throw failure
        } finally {
            try {
                if (pushed) baseSolver.pop()
            } finally {
                lastCheckStatistics = stats.snapshot()
                synchronized(lifecycleLock) { checking = false }
            }
        }
        return lastStatus
    }

    private fun prepareLemmas(
        original: List<KExpr<KBoolSort>>,
        control: HybridCheckControl,
        stats: MutableStatistics,
    ): List<KExpr<KBoolSort>> {
        var lemmas: List<KExpr<KBoolSort>> = emptyList()
        stats.noodlerTimeMs = measureTimeMillis {
            try {
                val statements = extractNoodlerStatements(original, control::checkpoint)
                if (statements.isEmpty()) {
                    stats.skipReason = "no supported conjuncts"
                } else {
                    stats.invocations = 1
                    val result = Noodler(statements, alphabet).propagate(control::checkpoint)
                    lemmas = if (result.contradiction) listOf(ctx.falseExpr) else result.constraints
                    stats.generated = lemmas.size
                    val originals = original.toHashSet()
                    lemmas = lemmas.distinct().filterNot { it in originals }
                    stats.fresh = lemmas.size
                }
            } catch (resource: AutomataLimitException) {
                control.checkpoint()
                stats.skipReason = resource.message ?: "automata resource limit"
            } catch (unsupported: KSolverUnsupportedFeatureException) {
                control.checkpoint()
                stats.skipReason = unsupported.message ?: "unsupported preprocessing"
            }
        }
        return lemmas
    }

    override fun model(): KModel {
        check(!closed) { "Solver is closed" }
        require(lastStatus == KSolverStatus.SAT) { "Model is only available after SAT, current status: $lastStatus" }
        return checkNotNull(lastModel)
    }

    override fun unsatCore(): List<KExpr<KBoolSort>> =
        throw KSolverUnsupportedFeatureException("Unsat core is not supported by KNoodlerSolver")

    override fun reasonOfUnknown(): String {
        check(!closed) { "Solver is closed" }
        require(lastStatus == KSolverStatus.UNKNOWN) { "Reason is only available after UNKNOWN" }
        return lastReason ?: "unknown"
    }

    override fun interrupt() {
        synchronized(lifecycleLock) {
            if (checking) {
                interrupted.set(true)
                baseSolver.interrupt()
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            check(!checking) { "Cannot close solver during a check; interrupt it first" }
            closed = true
            invalidate()
            baseSolver.close()
        }
    }

    private fun ensureIdle() {
        check(!closed) { "Solver is closed" }
        check(!checking) { "Solver check is already in progress" }
    }

    private fun invalidate() {
        lastStatus = KSolverStatus.UNKNOWN
        lastReason = null
        lastModel = null
        lastCheckStatistics = null
    }

    private class MutableStatistics {
        var invocations = 0
        var noodlerTimeMs = 0L
        var generated = 0
        var fresh = 0
        var baseTimeMs = 0L
        var skipReason: String? = null
        fun snapshot() = CheckStatistics(
            invocations, noodlerTimeMs, generated, fresh, baseTimeMs, MAX_STRING_CODE_POINT + 1,
            skipReason != null, skipReason
        )
    }

    data class CheckStatistics(
        val noodlerInvocations: Int,
        val noodlerTimeMs: Long,
        val generatedConstraints: Int,
        val freshConstraints: Int,
        val baseSolverCheckTimeMs: Long,
        /** Size of the SMT-LIB Unicode universe, independent of the compatibility alphabet hint. */
        val alphabetSize: Int,
        val noodlerSkipped: Boolean,
        val noodlerSkipReason: String?,
    )
}

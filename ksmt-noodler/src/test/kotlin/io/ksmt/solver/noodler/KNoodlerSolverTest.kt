package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.model.KModelImpl
import io.ksmt.solver.noodler.core.SolverInteractor
import io.ksmt.solver.noodler.hybrid.MAX_STRING_CODE_POINT
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KNoodlerSolverTest {
    @Test
    fun lemmasAreCheckLocalAndDoNotAccumulate(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val original = listOf(
            mkEq(x, y),
            mkStringInRegex(x, mkStringToRegex(mkStringLiteral("ab")))
        )
        val base = RecordingSolver(this)
        KNoodlerSolver(this, base, listOf('a')).use { solver ->
            solver.assert(original)
            repeat(2) {
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(original, base.assertions)
                assertEquals(original.size + 1, base.lastCheckAssertions.size)
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertEquals(1, stats.noodlerInvocations)
                assertEquals(1, stats.generatedConstraints)
                assertEquals(1, stats.freshConstraints)
                assertEquals(MAX_STRING_CODE_POINT + 1, stats.alphabetSize)
                assertFalse(stats.noodlerSkipped)
            }
        }
    }

    @Test
    fun unsupportedRegexFallsBackToOriginalBackendFormula(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val original = mkStringInRegexNoSimplify(x, mkStringToRegexNoSimplify(y))
        val base = RecordingSolver(this, KSolverStatus.UNKNOWN)
        KNoodlerSolver(this, base).use { solver ->
            solver.assert(original)
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertEquals(listOf(original), base.lastCheckAssertions)
            assertEquals("backend reason", solver.reasonOfUnknown())
            assertTrue(assertNotNull(solver.lastCheckStatistics).noodlerSkipped)
        }
    }

    @Test
    fun resourceLimitFallsBackWithoutPublishingPartialLemmas(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        var term: KExpr<io.ksmt.sort.KStringSort> = x
        repeat(20) { term = mkStringConcatNoSimplify(term, term) }
        val original = mkEqNoSimplify(term, x)
        val base = RecordingSolver(this)
        KNoodlerSolver(this, base).use { solver ->
            solver.assert(original)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(listOf(original), base.lastCheckAssertions)
            assertTrue(assertNotNull(solver.lastCheckStatistics).noodlerSkipped)
        }
    }

    @Test
    fun timeoutCoversPreprocessingAndBackendReceivesOnlyRemainingTime(): Unit = with(KContext()) {
        val base = RecordingSolver(this)
        KNoodlerSolver(this, base).use { solver ->
            assertEquals(KSolverStatus.UNKNOWN, solver.check(Duration.ZERO))
            assertEquals("timeout", solver.reasonOfUnknown())
            assertEquals(0, base.checkCalls)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertTrue(base.lastTimeout < 10.seconds)
            assertTrue(base.lastTimeout > Duration.ZERO)
        }
    }

    @Test
    fun interruptionWinsOverBackendSatAndDoesNotPoisonNextCheck(): Unit = with(KContext()) {
        val base = RecordingSolver(this)
        KNoodlerSolver(this, base).use { solver ->
            base.onCheck = solver::interrupt
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertEquals("interrupted", solver.reasonOfUnknown())
            assertFailsWith<IllegalArgumentException> { solver.model() }
            base.onCheck = null
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun failedModelCaptureInvalidatesStatusAndRestoresCheckScope(): Unit = with(KContext()) {
        val base = RecordingSolver(this)
        KNoodlerSolver(this, base).use { solver ->
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            base.failModel = true
            assertFailsWith<IllegalStateException> { solver.check(10.seconds) }
            assertFailsWith<IllegalArgumentException> { solver.model() }
            base.failModel = false
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun scopedAndAssumptionChecksKeepJointDetachedModel(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val i = mkConst("i", intSort)
        val b = mkConst("b", boolSort)
        val function = mkFuncDecl("f", intSort, listOf(intSort))
        val application = function.apply(listOf(mkIntNum(2)))
        KNoodlerSolver(this, KZ3Solver(this)).use { solver ->
            solver.assert(mkEq(i, mkIntNum(1)))
            solver.assert(b)
            solver.assert(mkEq(application, mkIntNum(7)))
            solver.push()
            solver.assert(mkEq(x, mkStringLiteral("a")))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            val captured = solver.model()
            assertEquals(mkIntNum(1), captured.eval(i))
            assertEquals(trueExpr, captured.eval(b))
            assertEquals(mkIntNum(7), captured.eval(application))
            assertEquals(mkStringLiteral("a"), captured.eval(x))
            solver.pop()
            assertFailsWith<IllegalArgumentException> { solver.model() }
            assertEquals(KSolverStatus.SAT,
                solver.checkWithAssumptions(listOf(mkEq(x, mkStringLiteral("b"))), 10.seconds))
            assertEquals(mkStringLiteral("b"), solver.model().eval(x))
            assertEquals(mkStringLiteral("a"), captured.eval(x))
            assertEquals(KSolverStatus.SAT,
                solver.checkWithAssumptions(listOf(mkEq(x, mkStringLiteral("c"))), 10.seconds))
            assertEquals(mkStringLiteral("c"), solver.model().eval(x))
            solver.assert(mkEq(i, mkIntNum(2)))
            assertFailsWith<IllegalArgumentException> { solver.model() }
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            assertFailsWith<IllegalArgumentException> { solver.model() }
        }
    }

    @Test
    fun backendDecidesCorrelationsAndOriginalUnsupportedAtoms(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        KNoodlerSolver(this, KZ3Solver(this)).use { solver ->
            solver.assert(mkEq(mkStringConcat(x, x), mkStringLiteral("a")))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        }
        val equation = mkEq(mkStringLen(x), mkIntNum(-1))
        SolverInteractor(listOf(equation), listOf('a'), KZ3Solver(this)).let { interactor ->
            interactor.solver.use { assertEquals(KSolverStatus.UNSAT, interactor.solve(solverTimeout = 10.seconds)) }
        }
    }

    @Test
    fun unboundedRefinementChainIsHandedToBackendAfterOneSweep(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        KNoodlerSolver(this, KZ3Solver(this)).use { solver ->
            solver.assert(mkEq(x, mkStringConcat(x, y)))
            solver.assert(mkStringInRegex(y, mkRegexCross(mkStringToRegex(mkStringLiteral("a")))))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            assertEquals(1, assertNotNull(solver.lastCheckStatistics).noodlerInvocations)
        }
    }

    @Test
    fun fullUniversePreservesConjugacyWitnessOutsideInferredAlphabet(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val z = mkConst("z", stringSort)
        val t = mkConst("t", stringSort)
        KNoodlerSolver(this, KZ3Solver(this), listOf('a')).use { solver ->
            solver.assert(mkEq(mkStringConcat(x, z), mkStringConcat(z, y)))
            solver.assert(mkNot(mkEq(x, y)))
            solver.assert(mkStringInRegex(t, mkStringToRegex(mkStringLiteral("a"))))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun unicodeAndCyclicSegmentationDoNotProduceFalseUnsat(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val t = mkConst("t", stringSort)
        KNoodlerSolver(this, KZ3Solver(this)).use { solver ->
            solver.assert(mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))))
            solver.assert(mkStringInRegex(y, mkStringToRegex(mkStringLiteral("ab"))))
            solver.assert(mkEq(t, mkStringConcat(x, y)))
            solver.assert(mkEq(x, y))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(mkStringLiteral("ab"), solver.model().eval(x))
            assertEquals(mkStringLiteral("abab"), solver.model().eval(t))
        }
        KNoodlerSolver(this, KZ3Solver(this), listOf('a')).use { solver ->
            solver.assert(mkStringInRegex(x, mkRegexAllChar()))
            solver.assert(mkStringInRegex(x, mkStringToRegex(mkStringLiteral(intArrayOf(256)))))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun scopeAndCloseInvalidateStatus(): Unit = with(KContext()) {
        val base = RecordingSolver(this)
        val solver = KNoodlerSolver(this, base)
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        solver.push()
        assertFailsWith<IllegalArgumentException> { solver.model() }
        solver.pop()
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        solver.close()
        assertFailsWith<IllegalStateException> { solver.model() }
        assertFailsWith<IllegalStateException> { solver.check(10.seconds) }
        solver.close()
    }

    private class RecordingSolver(
        private val ctx: KContext,
        private val status: KSolverStatus = KSolverStatus.SAT,
    ) : KSolver<KSolverConfiguration> {
        val assertions = arrayListOf<KExpr<KBoolSort>>()
        private val marks = ArrayDeque<Int>()
        var lastCheckAssertions: List<KExpr<KBoolSort>> = emptyList()
        var checkCalls = 0
        var lastTimeout: Duration = Duration.ZERO
        var onCheck: (() -> Unit)? = null
        var failModel = false
        override fun configure(configurator: KSolverConfiguration.() -> Unit) = Unit
        override fun assert(expr: KExpr<KBoolSort>) { assertions += expr }
        override fun assertAndTrack(expr: KExpr<KBoolSort>) = assert(expr)
        override fun push() { marks.addLast(assertions.size) }
        override fun pop(n: UInt) {
            repeat(n.toInt()) { assertions.subList(marks.removeLast(), assertions.size).clear() }
        }
        override fun check(timeout: Duration): KSolverStatus {
            lastCheckAssertions = assertions.toList()
            checkCalls++
            lastTimeout = timeout
            onCheck?.invoke()
            return status
        }
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration) = check(timeout)
        override fun model(): KModel {
            check(!failModel) { "model capture failed" }
            return KModelImpl(ctx, emptyMap(), emptyMap())
        }
        override fun unsatCore(): List<KExpr<KBoolSort>> = error("unused in tests")
        override fun reasonOfUnknown(): String = "backend reason"
        override fun interrupt() = Unit
        override fun close() = Unit
    }
}

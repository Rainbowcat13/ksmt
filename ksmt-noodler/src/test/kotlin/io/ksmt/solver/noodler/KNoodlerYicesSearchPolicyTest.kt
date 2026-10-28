package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.hybrid.HybridCheckControl
import io.ksmt.solver.noodler.hybrid.HybridIncomplete
import io.ksmt.solver.noodler.hybrid.PreprocessingBudget
import io.ksmt.solver.noodler.hybrid.PreprocessingLimitException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesSearchPolicyTest {
    @Test
    fun baselinePoliciesRemainDisabled(): Unit {
        val configuration = NoodlerYicesConfiguration()
        assertFalse(configuration.optionalPreprocessing)
        assertFalse(configuration.smallFirstSearch)
        assertEquals(2, configuration.maxIdleRounds)
        assertEquals(100_000, configuration.maxAutomataStates)
        assertEquals(1_000_000, configuration.maxAutomataStorage)
        configuration.setBoolParameter("noodler.optionalPreprocessing", true)
        configuration.setBoolParameter("noodler.smallFirstSearch", true)
        assertTrue(configuration.copy().optionalPreprocessing)
        assertTrue(configuration.copy().smallFirstSearch)
    }

    @Test
    fun exhaustedOptionalAllowancePreservesEssentialWork(): Unit {
        val parent = PreprocessingBudget(12)
        val optional = parent.limited(3)
        optional.reserve(3)
        assertFailsWith<PreprocessingLimitException> { optional.reserve() }
        assertEquals(9, parent.remaining)
        parent.reserve(9)
        assertEquals(0, parent.remaining)
    }

    @Test
    fun optionalAllowancePreservesInterrupts(): Unit {
        val interrupted = AtomicBoolean(false)
        val control = HybridCheckControl(Duration.INFINITE, interrupted)
        val optional = PreprocessingBudget(12, control::checkpoint).limited(3)
        interrupted.set(true)
        assertEquals("interrupted", assertFailsWith<HybridIncomplete> { optional.reserve() }.reason)
    }

    @Test
    fun boundedUnsatCanLeadToLargerSat(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure { smallFirstSearch = true }
            solver.assert(it.mkEq(x, it.mkStringLiteral("abc")))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                assertEquals(it.mkStringLiteral("abc"), model.eval(x))
                assertEquals(setOf(x.decl), model.declarations)
            }
            val stats = requireNotNull(solver.lastCheckStatistics)
            assertEquals(listOf(0, 1, 2, 4), stats.boundedSearchBounds)
            assertEquals("3", stats.lastCandidateTotalLength)
            assertEquals(1, stats.nativeRealizationChecks)
        }
    }

    @Test
    fun emptyAndPureArithmeticInputsHaveZeroTotalStringLength(): Unit = KContext().use {
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure { smallFirstSearch = true; optionalPreprocessing = true }
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals("0", requireNotNull(solver.lastCheckStatistics).lastCandidateTotalLength)
            val n = it.mkConst("n", it.intSort)
            solver.assert(it.mkEq(n, it.mkIntNum(7)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model -> assertEquals(it.mkIntNum(7), model.eval(n)) }
            solver.assert(it.mkNot(it.mkEq(n, it.mkIntNum(7))))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            assertEquals(0, requireNotNull(solver.lastCheckStatistics).nativeRealizationChecks)
        }
    }

    @Test
    fun exhaustedOperationalBoundIsUnknown(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure { smallFirstSearch = true; maxCharacterPositions = 2 }
            solver.assert(it.mkEq(it.mkStringLen(x), it.mkIntNum(3)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertEquals(listOf(0, 1, 2), requireNotNull(solver.lastCheckStatistics).boundedSearchBounds)
            assertFails { solver.unsatCore() }
        }
    }

    @Test
    fun optionalSeedFailureStillProducesExactWitness(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        val word = it.mkStringLiteral("aaaaaaaaaa")
        val assertion = it.mkStringInRegex(x, it.mkStringToRegex(word))
        // This literal DFA fits in 55 entries; its accepted-length subset enumeration does not.
        KNoodlerYicesSolver(it).use { baseline ->
            baseline.configure { maxAutomataStorage = 55; adaptiveLengthProjection = false }
            baseline.assert(assertion)
            assertEquals(KSolverStatus.UNKNOWN, baseline.check(10.seconds))
            assertEquals("seed-lengths", requireNotNull(baseline.lastCheckStatistics).failurePhase)
        }
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure {
                maxAutomataStorage = 55
                adaptiveLengthProjection = false
                optionalPreprocessing = true
                smallFirstSearch = true
            }
            solver.assert(assertion)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                assertEquals(word, model.eval(x))
                assertEquals(it.trueExpr, model.eval(assertion))
            }
            assertTrue(requireNotNull(solver.lastCheckStatistics).optionalPreprocessingSkips > 0)
        }
    }

    @Test
    fun encodingFailureDoesNotCreateAnInfeasibilityLemma(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure {
                optionalPreprocessing = true
                smallFirstSearch = true
                maxEncodingConstraints = 1
            }
            solver.assert(it.mkEq(it.mkStringLen(x), it.mkIntNum(1)))
            repeat(2) {
                assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
                val stats = requireNotNull(solver.lastCheckStatistics)
                assertEquals("encoding", stats.failurePhase)
                assertEquals(0, stats.learnedConstraints)
                assertEquals(0, stats.nativeRealizationChecks)
                assertEquals(2L, stats.lastCandidateEncodingEstimate)
                assertFails { solver.unsatCore() }
            }
        }
    }

    @Test
    fun policyGuardsDoNotEscapeUnsatCoreOrFollowingCheck(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        val tracked = it.mkEq(x, it.mkStringLiteral("a"))
        val assumption = it.mkEq(x, it.mkStringLiteral("b"))
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure { optionalPreprocessing = true; smallFirstSearch = true }
            solver.assertAndTrack(tracked)
            assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(assumption), 10.seconds))
            assertEquals(setOf(tracked, assumption), solver.unsatCore().toSet())
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model -> assertEquals(it.mkStringLiteral("a"), model.eval(x)) }
        }
    }

    @Test
    fun boundsCountCanonicalCodePoints(): Unit = KContext().use {
        val x = it.mkConst("x", it.stringSort)
        val points = intArrayOf(0xD83D, 0xDE00)
        KNoodlerYicesSolver(it).use { solver ->
            solver.configure { optionalPreprocessing = true; smallFirstSearch = true }
            solver.assert(it.mkEq(x, it.mkStringLiteral(points)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(listOf(0, 1, 2), requireNotNull(solver.lastCheckStatistics).boundedSearchBounds)
            solver.model().use { model ->
                assertContentEquals(points, (model.eval(x) as KStringLiteralExpr).codePoints)
            }
        }
    }
}

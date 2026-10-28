package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesLengthProjectionTest {
    @Test
    fun graphBoundsSolveWhereExactProjectionExhaustsStorage() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val word = mkStringLiteral("aaaaaaaaaa")
            for (wrongLength in listOf(false, true)) {
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.configure { maxAutomataStorage = 55 }
                    solver.assert(mkStringInRegex(x, mkStringToRegex(word)))
                    if (wrongLength) solver.assert(mkEq(mkStringLen(x), mkIntNum(9)))
                    assertEquals(if (wrongLength) KSolverStatus.UNSAT else KSolverStatus.SAT, solver.check(10.seconds))
                    val stats = assertNotNull(solver.lastCheckStatistics)
                    if (wrongLength) {
                        assertEquals(0, stats.realizationChecks)
                    } else {
                        assertEquals(word, solver.model().eval(x))
                        assertTrue(stats.lengthProjectionCacheHits > 0)
                    }
                }
            }
        }
    }

    @Test
    fun graphCongruenceRejectsImpossibleLengthWithoutCharacterEncoding() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.configure { maxEncodingConstraints = 0 }
                val cycle = mkRegexStar(mkStringToRegex(mkStringLiteral("abcdef")))
                solver.assert(mkStringInRegex(mkStringConcat(mkStringLiteral("!"), x), cycle))
                solver.assert(mkEq(mkIntMod(mkStringLen(x), mkIntNum(3)), mkIntNum(0)))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                assertEquals(0, assertNotNull(solver.lastCheckStatistics).realizationChecks)
            }
        }
    }

    @Test
    fun negativeMembershipDoesNotUsePositiveLanguageLengths() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val even = mkRegexStar(mkRegexPower(2, mkRegexAllChar()))
            val membership = mkStringInRegex(x, even)
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(mkNot(membership))
                solver.assert(mkEq(mkStringLen(x), mkIntNum(3)))
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(falseExpr, solver.model().eval(membership))
                solver.push()
                solver.assert(mkEq(mkStringLen(x), mkIntNum(2)))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                solver.pop()
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            }
        }
    }

    @Test
    fun lengthPolicyConfigurationIsCopiedAndSettable() {
        val configuration = NoodlerYicesConfiguration()
        assertTrue(configuration.adaptiveLengthProjection)
        configuration.setBoolParameter("noodler.adaptiveLengthProjection", false)
        assertEquals(false, configuration.copy().adaptiveLengthProjection)
    }

    @Test
    fun cappedExactPrefixRejectsShortHolesWithoutCharacterEncoding() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            fun branch(character: String, period: Int) = mkRegexConcat(
                mkStringToRegex(mkStringLiteral(character)),
                mkRegexStar(mkStringToRegex(mkStringLiteral(character.repeat(period)))),
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.configure { maxEncodingConstraints = 0 }
                solver.assert(mkStringInRegex(x, mkRegexUnion(branch("a", 17), branch("b", 19))))
                solver.assert(mkEq(mkStringLen(x), mkIntNum(2)))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertTrue(stats.lengthProjectionFallbacks > 0)
                assertEquals(0, stats.realizationChecks)
            }
        }
    }
}

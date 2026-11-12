package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesPeriodicLengthTest {
    @Test
    fun commutingPowersExcludeDisequalityForAllLengthsWithoutCharacterEncoding() = KContext().use { ctx ->
        with(ctx) {
            for (period in listOf("a", "ab")) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val z = mkConst("z", stringSort)
                val language = mkRegexStar(mkStringToRegex(mkStringLiteral(period)))
                val left = mkStringConcat(x, mkStringConcat(y, mkStringConcat(z, mkStringConcat(x, x))))
                val right = mkStringConcat(x, mkStringConcat(y, mkStringConcat(x, mkStringConcat(z, x))))
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.configure { maxEncodingConstraints = 0; automataWitnessSearch = false }
                    solver.assert(listOf(
                        mkStringInRegex(x, language), mkStringInRegex(y, language), mkStringInRegex(z, language),
                        mkNot(mkEq(left, right)),
                    ))
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                    val stats = assertNotNull(solver.lastCheckStatistics)
                    assertTrue(stats.periodicLengthLemmas > 0)
                    assertEquals(0, stats.realizationChecks)
                    assertEquals(0, stats.nativeRealizationChecks)
                }
            }
        }
    }

    @Test
    fun differentLengthsOfCommonPowersRemainSatisfiable() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val assertions = listOf(
                mkStringInRegex(x, language), mkStringInRegex(y, language), mkNot(mkEq(x, y)),
                mkEq(mkStringLen(x), mkIntNum(2)), mkEq(mkStringLen(y), mkIntNum(4)),
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(assertions)
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertTrue(assertNotNull(solver.lastCheckStatistics).periodicLengthLemmas > 0)
                solver.model().use { model ->
                    KZ3Solver(ctx).use { validator ->
                        validator.assert(assertions)
                        validator.assert(mkEq(x, model.eval(x)))
                        validator.assert(mkEq(y, model.eval(y)))
                        assertEquals(KSolverStatus.SAT, validator.check(10.seconds))
                    }
                }
            }
        }
    }

    @Test
    fun equalLengthsOfDifferentPeriodsDoNotForceEquality() = KContext().use { ctx ->
        with(ctx) {
            for ((first, second) in listOf("a" to "b", "ab" to "ba")) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val assertions = listOf(
                    mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral(first)))),
                    mkStringInRegex(y, mkRegexStar(mkStringToRegex(mkStringLiteral(second)))),
                    mkEq(mkStringLen(x), mkIntNum(first.length)),
                    mkEq(mkStringLen(y), mkStringLen(x)), mkNot(mkEq(x, y)),
                )
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                    assertEquals(0, assertNotNull(solver.lastCheckStatistics).periodicLengthLemmas)
                    solver.model().use { model -> assertions.forEach { assertEquals(trueExpr, model.eval(it)) } }
                }
            }
        }
    }

    @Test
    fun sameRegularLanguageCanStillSupplyDifferentWordsOfEqualLength() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexUnion(
                mkStringToRegex(mkStringLiteral("ab")), mkStringToRegex(mkStringLiteral("ba")),
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(listOf(mkStringInRegex(x, language), mkStringInRegex(y, language), mkNot(mkEq(x, y))))
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(0, assertNotNull(solver.lastCheckStatistics).periodicLengthLemmas)
            }
        }
    }

    @Test
    fun certificatesAreGuardedByBooleanChoicesAndResetAcrossChecks() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val choose = mkConst("choose", boolSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
            val inequality = mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x)))
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(listOf(mkStringInRegex(x, language), mkStringInRegex(y, language),
                    mkOr(choose, inequality)))
                assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(mkNot(choose)), 10.seconds))
                assertTrue(assertNotNull(solver.lastCheckStatistics).periodicLengthLemmas > 0)
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                solver.model().use { assertEquals(trueExpr, it.eval(choose)) }
            }
        }
    }

    @Test
    fun poppedRegularRestrictionsDoNotConstrainFutureChecks() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val unequal = mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x)))
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(listOf(unequal, mkEq(mkStringLen(x), mkIntNum(1)),
                    mkEq(mkStringLen(y), mkIntNum(1))))
                solver.push()
                val language = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
                solver.assert(listOf(mkStringInRegex(x, language), mkStringInRegex(y, language)))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                solver.pop()
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(0, assertNotNull(solver.lastCheckStatistics).periodicLengthLemmas)
            }
        }
    }
}

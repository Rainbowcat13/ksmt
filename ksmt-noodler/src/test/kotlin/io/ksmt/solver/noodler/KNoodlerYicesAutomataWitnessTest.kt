package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesAutomataWitnessTest {
    @Test
    fun automataConstructAJointWitnessWithoutCharacterEncoding() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val word = mkStringLiteral(intArrayOf(0, 0x1f600, 0x2ffff))
            val assertions = listOf(
                mkStringInRegex(x, mkStringToRegex(word)),
                mkEq(y, mkStringConcat(x, x)),
                mkEq(mkStringLen(x), mkIntNum(3)),
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.configure { maxEncodingConstraints = 0 }
                solver.assert(assertions)
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertEquals(1, stats.automataWitnessSuccesses)
                assertEquals(0, stats.nativeRealizationChecks)
                solver.model().use { model ->
                    assertEquals(word, model.eval(x))
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
    fun invalidPathAssignmentFallsBackWithoutBlockingSatisfiableLengths() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val alphabet = mkRegexUnion(
                mkStringToRegex(mkStringLiteral("a")), mkStringToRegex(mkStringLiteral("b")),
            )
            val assertions = listOf(
                mkStringInRegex(x, alphabet), mkStringInRegex(y, alphabet), mkNot(mkEq(x, y)),
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(assertions)
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertEquals(1, stats.rounds)
                assertEquals(1, stats.automataWitnessFallbacks)
                assertEquals(1, stats.nativeRealizationChecks)
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
    fun nonemptyRefinedLanguagesDoNotProveJointSatisfiability() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(listOf(
                    mkStringInRegex(x, mkRegexUnion(
                        mkStringToRegex(mkStringLiteral("ab")), mkStringToRegex(mkStringLiteral("ba")),
                    )),
                    mkStringInRegex(y, mkRegexUnion(
                        mkStringToRegex(mkStringLiteral("a")), mkStringToRegex(mkStringLiteral("b")),
                    )),
                    mkEq(mkStringConcat(x, y), mkStringConcat(y, x)),
                ))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertEquals(0, stats.automataWitnessSuccesses)
                assertTrue(stats.automataWitnessFallbacks > 0)
                assertTrue(stats.nativeRealizationChecks > 0)
            }
        }
    }

    @Test
    fun switchRetainsOriginalCharacterRealization() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.configure { setBoolParameter("noodler.automataWitnessSearch", false) }
                solver.assert(mkEq(x, mkStringLiteral("ab")))
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                val stats = assertNotNull(solver.lastCheckStatistics)
                assertEquals(0, stats.automataWitnessAttempts)
                assertEquals(1, stats.nativeRealizationChecks)
            }
        }
    }
}

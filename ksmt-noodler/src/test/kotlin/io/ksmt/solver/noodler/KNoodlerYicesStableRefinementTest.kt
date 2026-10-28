package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesStableRefinementTest {
    @Test
    fun stableLanguagesAreReusedAcrossDifferentLengthCandidatesAndResetBetweenChecks() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val unary = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(listOf(
                        mkStringInRegex(x, unary), mkStringInRegex(y, unary),
                        mkArithLe(mkStringLen(x), mkIntNum(2)),
                        mkArithLe(mkStringLen(y), mkIntNum(2)),
                        mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x))),
                    ))
                    // All nine length vectors fail exact realization: unary words commute.
                    // Their regular domains are already stable before the first character check.
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                    val first = assertNotNull(solver.lastCheckStatistics)
                    assertEquals(9, first.realizationChecks)
                    assertEquals(1, first.refinementSweeps)
                    assertEquals(8, first.refinementSkips)

                    // A new check has fresh branch state; the assumption leaves three vectors.
                    assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(
                        listOf(mkEq(mkStringLen(x), mkIntNum(0))), 10.seconds,
                    ))
                    val second = assertNotNull(solver.lastCheckStatistics)
                    assertEquals(3, second.realizationChecks)
                    assertEquals(1, second.refinementSweeps)
                    assertEquals(2, second.refinementSkips)
                }
            }
        }
}

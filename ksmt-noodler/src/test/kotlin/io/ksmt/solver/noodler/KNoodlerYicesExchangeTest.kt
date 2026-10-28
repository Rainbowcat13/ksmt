package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesExchangeTest {
    @Test
    fun exactCharacterConflictIsLearnedByTheUnboundedSearch() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            // Independent language projections permit both a and b for both variables.
            // Their correlation is impossible: equal one-character strings cannot concatenate to ab or ba.
            val language = mkRegexUnion(
                mkStringToRegex(mkStringLiteral("ab")), mkStringToRegex(mkStringLiteral("ba"))
            )
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.assert(listOf(
                    mkEq(x, y),
                    mkEq(mkStringLen(x), mkIntNum(1)),
                    mkEq(mkStringLen(y), mkIntNum(1)),
                    mkStringInRegex(mkStringConcat(x, y), language),
                ))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                val statistics = assertNotNull(solver.lastCheckStatistics)
                assertTrue(statistics.realizationChecks > 0, "The exact character check must reject the correlation")
                assertTrue(statistics.rounds >= 2, "The learned conflict must be checked by the unbounded solver")
            }
        }
    }

    @Test
    fun encodingBudgetDoesNotTurnResourceExhaustionIntoUnsat() = KContext().use { ctx ->
        with(ctx) {
            KNoodlerYicesSolver(ctx).use { solver ->
                solver.configure { maxEncodingConstraints = 0 }
                solver.assert(mkEq(mkStringLen(mkConst("x", stringSort)), mkIntNum(1)))
                assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
                assertTrue(solver.reasonOfUnknown().contains("resource limit"))
            }
        }
    }

    @Test
    fun witnessesEvaluateWithContextSimplificationDisabled() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val assertions = listOf(
                    mkEq(mkStringLen(x), mkIntNum(1)),
                    mkStringInRegex(x, mkStringToRegex(mkStringLiteral(intArrayOf(0x1f600)))),
                    mkEq(mkStringConcat(x, x), mkStringLiteral(intArrayOf(0x1f600, 0x1f600))),
                )
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                    assertions.forEach { assertEquals(trueExpr, solver.model().eval(it)) }
                }
            }
        }
}

package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesCancellationTest {
    @Test
    fun sharedVariableBoundariesRejectConflictingMiddleWordsBeforeRealization() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val left = mkStringConcat(x, mkStringConcat(mkStringLiteral("ab"), y))
                val right = mkStringConcat(x, mkStringConcat(mkStringLiteral("ba"), y))
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(mkEq(left, right))
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                    assertEquals(0, assertNotNull(solver.lastCheckStatistics).realizationChecks)
                }
            }
        }

    @Test
    fun differentlySplitUnicodeLiteralsPreserveAValidatedWitness() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val left = mkStringConcat(x, mkStringConcat(mkStringLiteral(intArrayOf(0x1f600, 0)), y))
                val right = mkStringConcat(x, mkStringConcat(
                    mkStringLiteral(intArrayOf(0x1f600)), mkStringConcat(mkStringLiteral(intArrayOf(0)), y)
                ))
                val assertions = listOf(
                    mkEq(left, right),
                    mkEq(mkStringLen(x), mkIntNum(1)),
                    mkEq(mkStringLen(y), mkIntNum(1)),
                )
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                    solver.model().use { model ->
                        assertions.forEach { assertEquals(trueExpr, model.eval(it)) }
                    }
                }
            }
        }

    @Test
    fun aNegatedConflictingEquationRemainsSatisfiable() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val left = mkStringConcat(x, mkStringConcat(mkStringLiteral("ab"), y))
                val right = mkStringConcat(x, mkStringConcat(mkStringLiteral("ba"), y))
                val assertion = mkNot(mkEq(left, right))
                KNoodlerYicesSolver(ctx).use { solver ->
                    solver.assert(assertion)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                    solver.model().use { assertEquals(trueExpr, it.eval(assertion)) }
                }
            }
        }
}

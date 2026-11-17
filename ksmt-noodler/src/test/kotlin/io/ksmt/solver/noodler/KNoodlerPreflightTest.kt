package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KNoodlerPreflightTest {
    @Test
    fun contradictionsNeverConstructAssertOrCheckABackend() = KContext().use { ctx ->
        with(ctx) {
            val b = mkConst("b", boolSort)
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val a = mkStringToRegex(mkStringLiteral("a"))
            val abPowers = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val fixtures: List<List<KExpr<KBoolSort>>> = listOf(
                listOf(b, mkNot(b)),
                listOf(mkStringInRegex(x, mkRegexIntersection(a, mkStringToRegex(mkStringLiteral("b"))))),
                listOf(mkStringInRegex(x, a), mkStringInRegex(x, mkStringToRegex(mkStringLiteral("b")))),
                listOf(mkEq(x, mkStringLiteral("a")), mkEq(x, mkStringLiteral("b"))),
                listOf(mkNot(mkStringInRegex(x, mkRegexAll()))),
                listOf(mkStringInRegex(x, abPowers), mkStringInRegex(y, abPowers),
                    mkEq(mkStringLen(x), mkStringLen(y)), mkNot(mkEq(x, y))),
                listOf(mkStringInRegex(x, abPowers), mkStringInRegex(y, abPowers),
                    mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x)))),
            )
            fixtures.forEachIndexed { index, assertions ->
                var constructions = 0
                val throwingFactory = KNoodlerBackendFactory { constructions++; error("Backend must never be created") }
                KNoodlerHybridSolver(this, throwingFactory).use { solver ->
                    assertions.forEach(solver::assertAndTrack)
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds), "fixture=$index")
                    assertEquals(0, constructions, "fixture=$index")
                    val stats = assertNotNull(solver.lastCheckStatistics)
                    assertEquals(0, stats.backendConstructions)
                    assertEquals(0L, stats.backendAssertions)
                    assertEquals(0, stats.backendChecks)
                    assertNotNull(stats.preflightReason)
                    assertEquals(assertions.distinct(), solver.unsatCore())
                }
            }
        }
    }

    @Test
    fun disjunctionsAndFailedCertificatesDoNotBecomeUnconditionalFacts() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val choices = mkOrNoSimplify(listOf(mkEq(x, mkStringLiteral("a")), mkEq(x, mkStringLiteral("b"))))
            val fixtures = listOf(
                listOf(choices, mkEq(x, mkStringLiteral("b"))),
                listOf(mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))),
                    mkStringInRegex(y, mkRegexStar(mkStringToRegex(mkStringLiteral("ba")))),
                    mkEq(mkStringLen(x), mkIntNum(2)), mkEq(mkStringLen(y), mkIntNum(2)), mkNot(mkEq(x, y))),
            )
            for (backend in KNoodlerBackend.values()) for (assertions in fixtures) {
                KNoodlerHybridSolver(this, backend).use { solver ->
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds), backend.name)
                    val stats = assertNotNull(solver.lastCheckStatistics)
                    assertEquals(null, stats.preflightReason)
                    assertEquals(2, stats.backendConstructions)
                    assertTrue(stats.backendAssertions > 0)
                    assertTrue(stats.backendChecks > 0)
                    assertions.forEach { assertEquals(trueExpr, solver.model().eval(it)) }
                }
            }
        }
    }

    @Test
    fun exhaustedOptionalProofStillAllowsSatisfiableInput() = KContext().use { ctx ->
        KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3).use { solver ->
            solver.configure { maxPreprocessingUnits = 8_000 }
            repeat(500) { solver.assert(ctx.mkConst("b$it", ctx.boolSort)) }
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            val stats = assertNotNull(solver.lastCheckStatistics)
            assertNotNull(stats.preflightSkippedReason)
            assertEquals(null, stats.preflightReason)
            assertTrue(stats.backendChecks > 0)
        }
    }

    @Test
    fun assumptionsScopesAndTimeoutsDoNotLeakProofs() = KContext().use { ctx ->
        with(ctx) {
            var constructions = 0
            val factory = KNoodlerBackendFactory { constructions++; KNoodlerBackend.Z3.create(it) }
            val b = mkConst("b", boolSort)
            KNoodlerHybridSolver(this, factory).use { solver ->
                solver.assert(b)
                assertEquals(0, constructions)
                assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(mkNot(b)), 10.seconds))
                assertEquals(0, constructions)
                assertEquals(listOf(mkNot(b)), solver.unsatCore())
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(2, constructions)
                solver.push()
                solver.assertAndTrack(mkNot(b))
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                assertEquals(2, constructions)
                solver.pop()
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                assertEquals(4, constructions)
                solver.push()
                solver.assert(mkNot(b))
                assertEquals(KSolverStatus.UNKNOWN, solver.check(Duration.ZERO))
                assertEquals(4, constructions)
                assertEquals(0, assertNotNull(solver.lastCheckStatistics).backendChecks)
                solver.pop()
                assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            }
        }
    }
}

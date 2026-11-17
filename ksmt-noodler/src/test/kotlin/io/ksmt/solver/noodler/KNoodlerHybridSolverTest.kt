package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The same public assertion/model/stack contract is exercised against all three native backends. */
class KNoodlerHybridSolverTest {
    @Test
    fun ordinaryAssertionsAndDetachedModelsAcrossBackends() {
        for (backend in KNoodlerBackend.values()) KContext().use { ctx ->
            with(ctx) {
                val x = mkConst("original.x", stringSort)
                val b = mkConst("original.b", boolSort)
                val membership = mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab"))))
                val length = mkEq(mkStringLen(x), mkIntNum(4))
                KNoodlerHybridSolver(this, backend).use { solver ->
                    solver.assert(listOf(membership, length, b))
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds),
                        backend.name + ": " + solver.lastCheckStatistics?.terminationReason)
                    val model = solver.model()
                    assertEquals(mkStringLiteral("abab"), model.eval(x), backend.name)
                    assertEquals(trueExpr, model.eval(membership), backend.name)
                    assertTrue(model.declarations.all { it.name.startsWith("original.") }, backend.name)

                    solver.push()
                    val oddLength = mkEq(mkStringLen(x), mkIntNum(3))
                    solver.assertAndTrack(oddLength)
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds), backend.name)
                    assertEquals(listOf(oddLength), solver.unsatCore(), backend.name)
                    solver.pop()
                    assertEquals(KSolverStatus.UNSAT,
                        solver.checkWithAssumptions(listOf(mkNot(b)), 10.seconds), backend.name)
                    assertEquals(listOf(mkNot(b)), solver.unsatCore(), backend.name)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds),
                        backend.name + ": " + solver.lastCheckStatistics?.terminationReason)
                    // Earlier native contexts have closed; the returned model is still usable.
                    assertEquals(mkStringLiteral("abab"), model.eval(x), backend.name)
                    assertFailsWith<IllegalStateException> { solver.configure { maxIdleRounds = 3 } }
                }
            }
        }
    }

    @Test
    fun exactCharacterRealizationAcrossBackends() {
        for (backend in KNoodlerBackend.values()) KContext().use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val y = mkConst("y", stringSort)
                val assertions = listOf(
                    mkEq(mkStringLen(x), mkIntNum(1)),
                    mkEq(mkStringLen(y), mkIntNum(1)),
                    mkNot(mkEq(x, y)),
                )
                KNoodlerHybridSolver(this, backend).use { solver ->
                    solver.configure { automataWitnessSearch = false }
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds),
                        backend.name + ": " + solver.lastCheckStatistics?.terminationReason)
                    assertTrue(requireNotNull(solver.lastCheckStatistics).nativeRealizationChecks > 0)
                    assertions.forEach { assertEquals(trueExpr, solver.model().eval(it), backend.name) }
                }
            }
        }
    }

    @Test
    fun factoryContextsAreOwnedAndClosedPerCheck() = KContext().use { ctx ->
        var created = 0
        var closed = 0
        val factory = KNoodlerBackendFactory {
            created++
            ClosingSolver(KNoodlerBackend.Z3.create(it)) { closed++ }
        }
        KNoodlerHybridSolver(ctx, factory).use { solver ->
            solver.assert(ctx.mkEq(ctx.mkConst("n", ctx.intSort), ctx.mkIntNum(2)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(2, created)
            assertEquals(created, closed)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(4, created)
            assertEquals(created, closed)
        }
    }

    @Test
    fun singletonUnsimplifiedArithmeticAcrossBackends() {
        for (backend in KNoodlerBackend.values()) KContext().use { ctx ->
            with(ctx) {
                val n = mkConst("n", intSort)
                KNoodlerHybridSolver(this, backend).use { solver ->
                    val equalities = listOf(
                        mkEq(mkArithAddNoSimplify(listOf(n)), mkIntNum(2)),
                        mkEq(mkArithMulNoSimplify(listOf(n)), mkIntNum(2)),
                        mkEq(mkArithSubNoSimplify(listOf(n)), mkIntNum(2)),
                    )
                    solver.assert(mkAndNoSimplify(listOf(mkAndNoSimplify(emptyList()))))
                    solver.assert(equalities)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds),
                        backend.name + ": " + solver.lastCheckStatistics?.terminationReason)
                    equalities.forEach { assertEquals(trueExpr, solver.model().eval(it)) }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private class ClosingSolver(
        private val delegate: KSolver<*>, private val onClose: () -> Unit,
    ) : KSolver<KSolverConfiguration> by (delegate as KSolver<KSolverConfiguration>) {
        override fun close() {
            delegate.close()
            onClose()
        }
    }
}

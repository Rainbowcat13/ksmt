import io.ksmt.decl.KDecl
import io.ksmt.expr.KExpr
import io.ksmt.expr.KUninterpretedSortValue
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.model.KFuncInterp
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KSort
import io.ksmt.sort.KUninterpretedSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class CustomExpressionsTest {
    @Test
    fun originalTrackedExpressionAppearsInNativeCore(): Unit = CustomContext().use { ctx ->
        val p = ctx.mkConst("p", ctx.boolSort)
        val q = ctx.mkConst("q", ctx.boolSort)
        val original = ctx.mkCustomAnd(listOf(p, q))
        CustomSolver(KZ3Solver(ctx), CustomExprRewriter(ctx)).use { solver ->
            solver.assertAndTrack(original)
            solver.assert(ctx.mkNot(p))
            assertEquals(KSolverStatus.UNSAT, solver.check(5.seconds))
            assertSame(original, solver.unsatCore().single())
        }
    }

    @Test
    fun collisionsScopesAndAssumptionsRetainOnlyActiveOriginals(): Unit = CustomContext().use { ctx ->
        val p = ctx.mkConst("p", ctx.boolSort)
        val first = ctx.mkCustomAnd(listOf(p))
        val second = ctx.mkCustomAnd(listOf(p, ctx.trueExpr))
        val assumption = ctx.mkCustomAnd(listOf(p, ctx.trueExpr, ctx.trueExpr))
        val backend = RecordingSolver()
        CustomSolver(backend, CustomExprRewriter(ctx)).use { solver ->
            solver.assertAndTrack(first)
            solver.push()
            solver.assertAndTrack(second)
            backend.core = listOf(p)
            assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(assumption), 5.seconds))
            assertEquals(listOf(first, second, assumption), solver.unsatCore())

            solver.pop()
            assertFailsWith<IllegalStateException> { solver.unsatCore() }
            assertEquals(KSolverStatus.UNSAT, solver.check(5.seconds))
            assertEquals(listOf(first), solver.unsatCore())

            solver.push()
            solver.push()
            solver.assertAndTrack(second)
            solver.pop(2u)
            assertEquals(KSolverStatus.UNSAT, solver.check(5.seconds))
            solver.pop(0u)
            assertEquals(listOf(first), solver.unsatCore())
            assertFailsWith<IllegalArgumentException> { solver.pop(UInt.MAX_VALUE) }

            backend.status = KSolverStatus.SAT
            solver.check(5.seconds)
            assertFailsWith<IllegalStateException> { solver.unsatCore() }
            backend.status = KSolverStatus.UNSAT
            solver.check(5.seconds)
            backend.failCheck = true
            assertFailsWith<IllegalStateException> { solver.check(5.seconds) }
            assertFailsWith<IllegalStateException> { solver.unsatCore() }
        }
    }

    @Test
    fun failedBackendMutationDoesNotChangeOriginBookkeeping(): Unit = CustomContext().use { ctx ->
        val p = ctx.mkConst("p", ctx.boolSort)
        val original = ctx.mkCustomAnd(listOf(p))
        val backend = RecordingSolver()
        CustomSolver(backend, CustomExprRewriter(ctx)).use { solver ->
            solver.push()
            solver.assertAndTrack(original)
            backend.failPop = true
            assertFailsWith<IllegalStateException> { solver.pop() }
            backend.core = listOf(p)
            solver.check(5.seconds)
            assertSame(original, solver.unsatCore().single())
            backend.failPop = false
            solver.pop()
            solver.check(5.seconds)
            // An unmapped backend expression must never escape as a rewritten core.
            assertFailsWith<IllegalStateException> { solver.unsatCore() }
        }
    }

    @Test
    fun modelCloseAndDetachDelegateToWrappedModels(): Unit = CustomContext().use { ctx ->
        val original = ClosingModel()
        val detached = ClosingModel()
        original.detached = detached
        val model = CustomModel(original, CustomExprRewriter(ctx))
        val detachedWrapper = model.detach()
        assertEquals(1, original.detachCalls)
        detachedWrapper.close()
        assertEquals(1, detached.closeCalls)
        assertEquals(0, original.closeCalls)
        model.close()
        assertEquals(1, original.closeCalls)
    }

    private class RecordingSolver : KSolver<KSolverConfiguration> {
        var core = emptyList<KExpr<KBoolSort>>()
        var status = KSolverStatus.UNSAT
        var failCheck = false
        var failPop = false
        override fun configure(configurator: KSolverConfiguration.() -> Unit) = error("unused")
        override fun assert(expr: KExpr<KBoolSort>) = Unit
        override fun assertAndTrack(expr: KExpr<KBoolSort>) = Unit
        override fun push() = Unit
        override fun pop(n: UInt) { check(!failPop) { "backend pop failed" } }
        override fun check(timeout: Duration): KSolverStatus {
            check(!failCheck) { "backend check failed" }
            return status
        }
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration) = check(timeout)
        override fun model(): KModel = error("unused")
        override fun unsatCore() = core
        override fun reasonOfUnknown() = "test"
        override fun interrupt() = Unit
        override fun close() = Unit
    }

    private class ClosingModel : KModel {
        var closeCalls = 0
        var detachCalls = 0
        var detached: KModel = this
        override val declarations = emptySet<KDecl<*>>()
        override val uninterpretedSorts = emptySet<KUninterpretedSort>()
        override fun <T : KSort> eval(expr: KExpr<T>, isComplete: Boolean): KExpr<T> = error("unused")
        override fun <T : KSort> interpretation(decl: KDecl<T>): KFuncInterp<T>? = null
        override fun uninterpretedSortUniverse(sort: KUninterpretedSort): Set<KUninterpretedSortValue>? = null
        override fun detach(): KModel { detachCalls++; return detached }
        override fun close() { closeCalls++ }
    }
}

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerHybridCvc5Solver
import io.ksmt.solver.noodler.KNoodlerHybridSolverUniversalConfiguration
import io.ksmt.solver.noodler.KNoodlerHybridYicesSolver
import io.ksmt.solver.noodler.KNoodlerHybridZ3Solver
import io.ksmt.solver.portfolio.KPortfolioSolverManager
import kotlin.time.Duration.Companion.seconds

/** Run with the examples build's runNoodlerPortfolioExample task. */
fun main() {
    KPortfolioSolverManager(listOf(
        KNoodlerHybridZ3Solver::class,
        KNoodlerHybridCvc5Solver::class,
        KNoodlerHybridYicesSolver::class,
    )).use { manager ->
        manager.registerSolver(KNoodlerHybridZ3Solver::class, KNoodlerHybridSolverUniversalConfiguration::class)
        manager.registerSolver(KNoodlerHybridCvc5Solver::class, KNoodlerHybridSolverUniversalConfiguration::class)
        manager.registerSolver(KNoodlerHybridYicesSolver::class, KNoodlerHybridSolverUniversalConfiguration::class)
        KContext().use { ctx ->
            manager.createPortfolioSolver(ctx, listOf(
                { setStringParameter("noodler.encodingTarget", "INT") },
                { setStringParameter("noodler.encodingTarget", "BV") },
                { setStringParameter("noodler.encodingTarget", "BV") },
            )).use { solver ->
                val x = ctx.mkConst("x", ctx.stringSort)
                solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("hello")))
                check(solver.check(5.seconds) == KSolverStatus.SAT)
                println(solver.model().eval(x))
            }
        }
    }
}

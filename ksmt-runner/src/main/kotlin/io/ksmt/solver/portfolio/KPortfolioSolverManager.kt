package io.ksmt.solver.portfolio

import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.runner.KSolverRunner
import io.ksmt.solver.runner.KSolverRunnerManager
import io.ksmt.utils.uncheckedCast
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KPortfolioSolverManager(
    private val solvers: List<KClass<out KSolver<out KSolverConfiguration>>>,
    portfolioPoolSize: Int = DEFAULT_PORTFOLIO_POOL_SIZE,
    hardTimeout: Duration = DEFAULT_HARD_TIMEOUT,
    workerProcessIdleTimeout: Duration = DEFAULT_WORKER_PROCESS_IDLE_TIMEOUT
) : KSolverRunnerManager(
    workerPoolSize = portfolioPoolSize * solvers.size,
    hardTimeout = hardTimeout,
    workerProcessIdleTimeout = workerProcessIdleTimeout
) {
    init {
        require(solvers.isNotEmpty()) { "Empty solver portfolio" }
    }

    /** Per-entry options are serialized and replayed by each runner after worker recovery. */
    fun createPortfolioSolver(
        ctx: KContext,
        configurations: List<KSolverConfiguration.() -> Unit> = emptyList(),
    ): KPortfolioSolver {
        require(configurations.isEmpty() || configurations.size == solvers.size) {
            "Provide one configuration per portfolio entry, or none"
        }
        val solverInstances = arrayListOf<Pair<KClass<out KSolver<*>>, KSolverRunner<*>>>()
        try {
            solvers.forEachIndexed { index, solverClass ->
                val solverType: KClass<out KSolver<KSolverConfiguration>> = solverClass.uncheckedCast()
                val runner = createSolver(ctx, solverType)
                // Record ownership before configuring: a configuration callback may itself fail.
                solverInstances += solverClass to runner
                configurations.getOrNull(index)?.let { runner.configure(it) }
            }
            return KPortfolioSolver(solverInstances)
        } catch (failure: Throwable) {
            solverInstances.forEach { (_, runner) ->
                try {
                    runner.close()
                } catch (closingFailure: Throwable) {
                    failure.addSuppressed(closingFailure)
                }
            }
            throw failure
        }
    }

    companion object {
        const val DEFAULT_PORTFOLIO_POOL_SIZE = 1
        val DEFAULT_HARD_TIMEOUT = 10.seconds
        val DEFAULT_WORKER_PROCESS_IDLE_TIMEOUT = 100.seconds
    }
}

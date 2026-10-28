package io.ksmt.solver.noodler.core

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerSolver
import io.ksmt.sort.KBoolSort
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Compatibility facade; asserts the original formula and delegates joint solving to the backend. */
class SolverInteractor(
    statements: List<KExpr<*>>,
    val alphabet: List<Char>,
    val solver: KSolver<out KSolverConfiguration>,
) {
    private val combined = statements.firstOrNull()?.ctx?.let { ctx ->
        KNoodlerSolver(ctx, solver, alphabet).also { wrapper ->
            statements.forEach {
                require(it.sort is KBoolSort) { "Expected a Boolean assertion" }
                @Suppress("UNCHECKED_CAST")
                wrapper.assert(it as KExpr<KBoolSort>)
            }
        }
    }

    /** One sweep precedes the backend check; maxRounds is a compatibility hint. */
    fun solve(maxRounds: Int = 1, solverTimeout: Duration = 1.minutes): KSolverStatus {
        require(maxRounds > 0) { "maxRounds must be positive" }
        return combined?.check(solverTimeout) ?: solver.check(solverTimeout)
    }

    fun model(): KModel = combined?.model() ?: solver.model()
}

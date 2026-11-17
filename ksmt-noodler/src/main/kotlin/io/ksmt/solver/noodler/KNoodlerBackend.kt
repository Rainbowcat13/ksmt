package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.yices.KYicesSolver
import io.ksmt.solver.z3.KZ3Solver

/**
 * Creates a fresh, exclusively owned arithmetic/BV solver on every call. The hybrid wrapper closes
 * every returned solver, including on cancellation or failure. Do not return a caller-owned instance.
 * A compatible backend must support quantifier-free Boolean, integer and (when used) BV constraints.
 * Native search and realization instances are independent; the expression context remains caller-owned.
 */
fun interface KNoodlerBackendFactory {
    fun create(ctx: KContext): KSolver<*>
}

/** Built-in interchangeable backends for the same hybrid algorithm. */
enum class KNoodlerBackend : KNoodlerBackendFactory {
    Z3 {
        override fun create(ctx: KContext): KSolver<*> = KZ3Solver(ctx)
    },
    CVC5 {
        override fun create(ctx: KContext): KSolver<*> = KCvc5Solver(ctx)
    },
    YICES {
        override fun create(ctx: KContext): KSolver<*> = KYicesSolver(ctx)
    },
}

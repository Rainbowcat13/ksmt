import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerBackend
import io.ksmt.solver.noodler.KNoodlerBackendFactory
import io.ksmt.solver.noodler.KNoodlerHybridSolver
import io.ksmt.solver.noodler.NoodlerEncodingTarget
import kotlin.time.Duration.Companion.seconds

/** Choose Z3, CVC5 or YICES through -PnoodlerBackend in the examples build. */
fun main(args: Array<String>) {
    val backend = KNoodlerBackend.valueOf(args.firstOrNull() ?: "Z3")
    for (target in NoodlerEncodingTarget.values()) ordinaryStrings(backend, target)
    mixedStringAndBitVector(backend)
    solverFreeUnsat()
}

private fun ordinaryStrings(backend: KNoodlerBackend, target: NoodlerEncodingTarget) = KContext().use { ctx ->
    with(ctx) {
        val x = mkConst("x", stringSort)
        KNoodlerHybridSolver(this, backend).use { solver ->
            solver.configure {
                encodingTarget = target
                // Exercise exact character realization in this encoding example.
                automataWitnessSearch = false
            }
            solver.assert(mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))))
            solver.assert(mkEq(mkStringLen(x), mkIntNum(4)))
            check(solver.check(5.seconds) == KSolverStatus.SAT)
            println("$backend/$target: x = ${solver.model().eval(x)}")
        }
    }
}

private fun mixedStringAndBitVector(backend: KNoodlerBackend) = KContext().use { ctx ->
    with(ctx) {
        val x = mkConst("x", stringSort)
        val size = mkConst("size", mkBvSort(4u))
        KNoodlerHybridSolver(this, backend).use { solver ->
            solver.assert(mkEq(size, mkBv(3, 4u)))
            solver.assert(mkEq(mkStringLen(x), mkBv2IntExpr(size, isSigned = false)))
            solver.assert(mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("a")))))
            check(solver.check(5.seconds) == KSolverStatus.SAT)
            val model = solver.model()
            println("$backend/mixed: x = ${model.eval(x)}, size = ${model.eval(size)}")
        }
    }
}

private fun solverFreeUnsat() = KContext().use { ctx ->
    val forbiddenBackend = KNoodlerBackendFactory { error("The contradiction must finish before backend creation") }
    KNoodlerHybridSolver(ctx, forbiddenBackend).use { solver ->
        val x = ctx.mkConst("x", ctx.stringSort)
        solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("a")))
        solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("b")))
        check(solver.check(5.seconds) == KSolverStatus.UNSAT)
        val statistics = checkNotNull(solver.lastCheckStatistics)
        check(statistics.backendConstructions == 0)
        check(statistics.backendAssertions == 0L)
        check(statistics.backendChecks == 0)
        println("UNSAT: ${statistics.preflightReason}; backend constructions/assertions/checks = 0/0/0")
    }
}

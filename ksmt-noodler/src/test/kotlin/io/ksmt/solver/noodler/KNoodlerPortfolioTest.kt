package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverException
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.portfolio.KPortfolioSolverManager
import io.ksmt.solver.runner.KSolverRunnerManager
import io.ksmt.sort.KBoolSort
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KNoodlerPortfolioTest {
    private val backends = listOf(
        KNoodlerHybridZ3Solver::class, KNoodlerHybridCvc5Solver::class, KNoodlerHybridYicesSolver::class,
    )

    @Test
    fun backendWorkersSerializeBothEncodingTargets() {
        KSolverRunnerManager(workerPoolSize = 3).use { manager ->
            backends.forEach { manager.registerSolver(it, KNoodlerHybridSolverUniversalConfiguration::class) }
            for (backend in backends) for (target in NoodlerEncodingTarget.values()) KContext().use { ctx ->
                manager.createSolver(ctx, backend).use { solver ->
                    solver.configure { encodingTarget = target; automataWitnessSearch = false }
                    val x = ctx.mkConst("x", ctx.stringSort)
                    val y = ctx.mkConst("y", ctx.stringSort)
                    val original = listOf(
                        ctx.mkEq(ctx.mkStringLen(x), ctx.mkIntNum(1)),
                        ctx.mkEq(ctx.mkStringLen(y), ctx.mkIntNum(1)), ctx.mkNot(ctx.mkEq(x, y)),
                    )
                    solver.assert(original)
                    assertEquals(KSolverStatus.SAT, solver.check(5.seconds), "$backend/$target")
                    val model = solver.model()
                    original.forEach { assertEquals(ctx.trueExpr, model.eval(it), "$backend/$target") }
                    assertTrue(model.declarations.all { it == x.decl || it == y.decl })
                }
            }
        }
    }

    @Test
    fun unknownAndBackendFailureDoNotHideDecisiveWinner() {
        val entries = listOf(UnknownWorker::class, FailingWorker::class, DelayedZ3Worker::class)
        KPortfolioSolverManager(entries, hardTimeout = 10.seconds).use { manager ->
            entries.forEach { manager.registerSolver(it, KNoodlerHybridSolverUniversalConfiguration::class) }
            KContext().use { ctx ->
                manager.createPortfolioSolver(ctx, listOf(
                    {}, {}, { setStringParameter("noodler.encodingTarget", "BV") },
                )).use { solver ->
                    val x = ctx.mkConst("x", ctx.stringSort)
                    solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("abc")))
                    assertEquals(KSolverStatus.SAT, solver.check(5.seconds))
                    assertEquals(ctx.mkStringLiteral("abc"), solver.model().eval(x))
                    assertTrue(solver.solverPortfolioStats().first { it.solver == FailingWorker::class }.isActive.not())
                    solver.push()
                    val conflict = ctx.mkEq(x, ctx.mkStringLiteral("def"))
                    solver.assertAndTrack(conflict)
                    assertEquals(KSolverStatus.UNSAT, solver.check(5.seconds))
                    assertTrue(conflict in solver.unsatCore())
                    solver.pop()
                    assertEquals(KSolverStatus.UNSAT,
                        solver.checkWithAssumptions(listOf(conflict), 5.seconds))
                    assertTrue(conflict in solver.unsatCore())
                    assertEquals(KSolverStatus.SAT, solver.check(5.seconds))
                    assertEquals(ctx.mkStringLiteral("abc"), solver.model().eval(x))
                }
            }
        }
    }

    @Test
    fun duplicateBackendEntriesHaveIndependentConfigurations() {
        KPortfolioSolverManager(listOf(KNoodlerHybridZ3Solver::class, KNoodlerHybridZ3Solver::class)).use { manager ->
            manager.registerSolver(KNoodlerHybridZ3Solver::class, KNoodlerHybridSolverUniversalConfiguration::class)
            KContext().use { ctx ->
                manager.createPortfolioSolver(ctx, listOf(
                    { setStringParameter("noodler.encodingTarget", "INT"); setIntParameter("noodler.maxCharacterPositions", 0) },
                    { setStringParameter("noodler.encodingTarget", "BV"); setBoolParameter("noodler.automataWitnessSearch", false) },
                )).use { solver ->
                    val x = ctx.mkConst("x", ctx.stringSort)
                    val y = ctx.mkConst("y", ctx.stringSort)
                    val original = listOf(
                        ctx.mkEq(ctx.mkStringLen(x), ctx.mkIntNum(1)),
                        ctx.mkEq(ctx.mkStringLen(y), ctx.mkIntNum(1)), ctx.mkNot(ctx.mkEq(x, y)),
                    )
                    solver.assert(original)
                    assertEquals(KSolverStatus.SAT, solver.check(5.seconds))
                    original.forEach { assertEquals(ctx.trueExpr, solver.model().eval(it)) }
                    assertEquals(0, solver.solverPortfolioStats()[0].queriesBest)
                    assertEquals(1, solver.solverPortfolioStats()[1].queriesBest)
                }
            }
        }
    }

    @Test
    fun portfolioInterruptionBypassesPendingCheckAndRemainsUsable() {
        val marker = Files.createTempDirectory("ksmt-portfolio-interrupt").resolve("started")
        try {
            KPortfolioSolverManager(listOf(InterruptibleWorker::class), hardTimeout = 30.seconds).use { manager ->
                manager.registerSolver(InterruptibleWorker::class, KNoodlerHybridSolverUniversalConfiguration::class)
                KContext().use { ctx ->
                    manager.createPortfolioSolver(ctx, listOf({ setStringParameter("test.startedMarker", marker.toString()) }))
                        .use { solver ->
                            val executor = Executors.newSingleThreadExecutor()
                            try {
                                val query = executor.submit<KSolverStatus> { solver.check(20.seconds) }
                                val waitUntil = System.nanoTime() + 15.seconds.inWholeNanoseconds
                                while (!Files.exists(marker) && System.nanoTime() < waitUntil) Thread.sleep(5)
                                assertTrue(Files.exists(marker), "worker did not begin its check")
                                val interruptedAt = System.nanoTime()
                                solver.interrupt()
                                assertEquals(KSolverStatus.UNKNOWN, query.get(5, TimeUnit.SECONDS))
                                assertTrue(System.nanoTime() - interruptedAt < 5.seconds.inWholeNanoseconds,
                                    "interrupt waited for the original 20 second check")
                                assertEquals(KSolverStatus.UNKNOWN, solver.check(20.milliseconds))
                            } finally {
                                executor.shutdownNow()
                            }
                        }
                }
            }
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(marker.parent)
        }
    }

    @Test
    fun runnerRecoversAfterHardTimeoutAndReplaysAssertions() {
        val marker = Files.createTempDirectory("ksmt-runner-recovery").resolve("first-check")
        try {
            KSolverRunnerManager(hardTimeout = 1.seconds).use { manager ->
                manager.registerSolver(RecoveringWorker::class, KNoodlerHybridSolverUniversalConfiguration::class)
                KContext().use { ctx ->
                    manager.createSolver(ctx, RecoveringWorker::class).use { solver ->
                        solver.configure { setStringParameter("test.firstCheck", marker.toString()) }
                        val x = ctx.mkConst("x", ctx.stringSort)
                        solver.assert(ctx.mkEq(x, ctx.mkStringLiteral("restored")))
                        assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
                        assertTrue(Files.exists(marker), "worker did not execute its first check")
                        assertEquals(KSolverStatus.SAT, solver.check(5.seconds))
                        assertEquals(ctx.mkStringLiteral("restored"), solver.model().eval(x))
                    }
                }
            }
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(marker.parent)
        }
    }

    class RecoveringWorker(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3) {
        private var marker: Path? = null
        override fun configure(configurator: KNoodlerHybridSolverConfiguration.() -> Unit) {
            val values = object : KNoodlerHybridSolverConfiguration by NoodlerYicesConfiguration() {
                override fun setStringParameter(param: String, value: String) {
                    require(param == "test.firstCheck")
                    marker = Path.of(value)
                }
            }
            values.configurator()
        }
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
            marker?.let {
                if (!Files.exists(it)) {
                    Files.writeString(it, "first check deliberately exceeds worker hard deadline")
                    Thread.sleep(5_000)
                }
            }
            return super.checkWithAssumptions(assumptions, timeout)
        }
    }

    class UnknownWorker(ctx: KContext) : KNoodlerHybridSolver(ctx) {
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration) = KSolverStatus.UNKNOWN
        override fun reasonOfUnknown() = "intentional UNKNOWN fixture"
    }

    class FailingWorker(ctx: KContext) : KNoodlerHybridSolver(ctx) {
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus =
            throw KSolverException("intentional backend failure fixture")
    }

    class DelayedZ3Worker(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3) {
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
            Thread.sleep(300)
            return super.checkWithAssumptions(assumptions, timeout)
        }
    }

    class InterruptibleWorker(ctx: KContext) : KNoodlerHybridSolver(ctx) {
        private val stopped = AtomicBoolean()
        private var marker: Path? = null
        override fun configure(configurator: KNoodlerHybridSolverConfiguration.() -> Unit) {
            val values = object : KNoodlerHybridSolverConfiguration by NoodlerYicesConfiguration() {
                override fun setStringParameter(param: String, value: String) {
                    require(param == "test.startedMarker")
                    marker = Path.of(value)
                }
            }
            values.configurator()
        }
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus {
            stopped.set(false)
            marker?.let { Files.writeString(it, "started") }
            val started = System.nanoTime()
            while (!stopped.get() && (System.nanoTime() - started).toDouble() < timeout.inWholeNanoseconds.toDouble()) {
                Thread.sleep(1)
            }
            return KSolverStatus.UNKNOWN
        }
        override fun interrupt() { stopped.set(true) }
        override fun reasonOfUnknown() = "interrupted or timed out fixture"
    }
}

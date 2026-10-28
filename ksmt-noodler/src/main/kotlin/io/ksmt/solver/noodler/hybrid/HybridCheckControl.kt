package io.ksmt.solver.noodler.hybrid

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal class HybridIncomplete(val reason: String) : RuntimeException(reason)

internal class HybridCheckControl(private val timeout: Duration, private val interrupted: AtomicBoolean) {
    private val started = System.nanoTime()

    fun checkpoint() {
        if (interrupted.get()) throw HybridIncomplete("interrupted")
        if (timeout.isFinite() && elapsed() >= timeout) throw HybridIncomplete("timeout")
    }

    fun remaining(): Duration {
        if (interrupted.get()) throw HybridIncomplete("interrupted")
        if (timeout.isInfinite()) return timeout
        val remaining = timeout - elapsed()
        if (remaining <= Duration.ZERO) throw HybridIncomplete("timeout")
        return remaining
    }

    private fun elapsed(): Duration = (System.nanoTime() - started).nanoseconds
}

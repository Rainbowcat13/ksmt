package io.ksmt.solver.noodler

import io.ksmt.solver.noodler.hybrid.HybridCheckControl
import io.ksmt.solver.noodler.hybrid.HybridIncomplete
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

class KNoodlerYicesControlTest {
    @Test
    fun testDefaultIdleLimitStopsAfterTwoCompleteIdleRounds() {
        val configuration = NoodlerYicesConfiguration()
        assertEquals(2, configuration.maxIdleRounds)
        val progress = IdleRoundTracker(configuration.maxIdleRounds)

        assertFalse(progress.record(progress = false))
        assertTrue(progress.record(progress = false))
    }

    @Test
    fun testLearnedInformationResetsConsecutiveIdleRounds() {
        val progress = IdleRoundTracker(2)

        assertFalse(progress.record(progress = false))
        assertFalse(progress.record(progress = true))
        assertFalse(progress.record(progress = false))
        assertTrue(progress.record(progress = false))
    }

    @Test
    fun testCustomIdleLimitAppliesToCompleteRounds() {
        val progress = IdleRoundTracker(3)

        assertFalse(progress.record(progress = false))
        assertFalse(progress.record(progress = false))
        assertTrue(progress.record(progress = false))
    }

    @Test
    fun testCancellationStopsAutomataAndNativeCheckEntry() {
        val interrupted = AtomicBoolean(false)
        val control = HybridCheckControl(Duration.INFINITE, interrupted)
        control.checkpoint()
        assertEquals(Duration.INFINITE, control.remaining())

        interrupted.set(true)
        assertEquals("interrupted", assertFailsWith<HybridIncomplete> { control.checkpoint() }.reason)
        assertEquals("interrupted", assertFailsWith<HybridIncomplete> { control.remaining() }.reason)
    }

    @Test
    fun testExpiredDeadlineStopsBothEntryPoints() {
        val control = HybridCheckControl(Duration.ZERO, AtomicBoolean(false))

        assertEquals("timeout", assertFailsWith<HybridIncomplete> { control.checkpoint() }.reason)
        assertEquals("timeout", assertFailsWith<HybridIncomplete> { control.remaining() }.reason)
    }
}

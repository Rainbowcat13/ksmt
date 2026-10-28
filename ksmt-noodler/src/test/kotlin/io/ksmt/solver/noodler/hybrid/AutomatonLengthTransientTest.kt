package io.ksmt.solver.noodler.hybrid

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomatonLengthTransientTest {
    private fun unary(successors: List<Int>, accepting: Set<Int>): SymbolicDfa = SymbolicDfa(
        successors.map { listOf(Transition(0, MAX_STRING_CODE_POINT, it)) }, 0, accepting,
    )

    @Test
    fun transientAcceptingLengthsRetainResiduesOutsideTheEventualCycle() {
        // Accept length 0, length 2, and every positive multiple of 3.
        val language = unary(listOf(1, 2, 3, 4, 5, 3), setOf(0, 2, 3))
        val summary = language.lengthSummary()
        assertEquals(0, summary.minLength)
        assertNull(summary.maxLength)
        assertEquals(3, summary.modulus)
        assertEquals(setOf(0, 2), summary.residues)
        for (length in 0..60) {
            if (language.accepts(IntArray(length))) assertTrue(summary.contains(length), "Removed length $length")
        }
        assertTrue(summary.contains(2), "A transient acceptance cannot be replaced by the cycle's residue")
        assertFalse(summary.contains(1))
    }

    @Test
    fun multipleCycleResiduesAndAnOddTransientLengthAreAllPreserved() {
        // Accept length 1, then every positive even length, through a four-state cycle.
        val language = unary(listOf(1, 2, 3, 4, 5, 2), setOf(1, 2, 4))
        val summary = language.lengthSummary()
        assertEquals(1, summary.minLength)
        assertNull(summary.maxLength)
        assertEquals(4, summary.modulus)
        assertEquals(setOf(0, 1, 2), summary.residues)
        for (length in 0..60) {
            if (length == 1 || length >= 2 && length % 2 == 0) {
                assertTrue(summary.contains(length), "Removed accepted length $length")
            }
        }
        assertFalse(summary.contains(3))
        assertFalse(summary.contains(7))
    }

    @Test
    fun reachableRejectingCycleDoesNotMakeAFiniteAcceptedPrefixUnbounded() {
        val language = unary(listOf(1, 2, 3, 4, 3), setOf(0, 2))
        val summary = language.lengthSummary()
        assertEquals(0, summary.minLength)
        assertEquals(2, summary.maxLength)
        assertTrue(summary.contains(0))
        assertTrue(summary.contains(2))
        for (length in 3..60) assertFalse(summary.contains(length))
    }
}

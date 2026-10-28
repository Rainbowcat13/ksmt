package io.ksmt.solver.noodler.hybrid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AcceptedLengthPrefixTest {
    @Test
    fun subsetCapLeavesTheFirstUnexaminedAcceptingPositionUnknown() {
        val language = SymbolicDfa(
            List(3) { state -> listOf(Transition(0, MAX_STRING_CODE_POINT, (state + 1) % 3)) },
            0, setOf(1),
        )
        val first = assertFailsWith<LengthProjectionLimitException> {
            language.acceptedLengths(maxSubsetStates = 1)
        }.prefix
        assertEquals(1, first.cutoff)
        assertTrue(first.accepted.isEmpty())
        val second = assertFailsWith<LengthProjectionLimitException> {
            language.acceptedLengths(maxSubsetStates = 2)
        }.prefix
        assertEquals(2, second.cutoff)
        assertEquals(setOf(1), second.accepted)
    }

    @Test
    fun storageFailureCommitsAcceptanceOnlyAfterItsEntryWasStored() {
        val original = SymbolicDfa.literal(listOf(97, 98))
        fun prefix(limit: Int): AcceptedLengthPrefix = assertFailsWith<LengthProjectionLimitException> {
            SymbolicDfa(original.transitions, original.initialState, original.acceptingStates, storageLimit = limit)
                .acceptedLengths()
        }.prefix
        val beforeAcceptingEntry = prefix(13)
        assertEquals(2, beforeAcceptingEntry.cutoff)
        assertTrue(beforeAcceptingEntry.accepted.isEmpty())
        val afterAcceptingEntry = prefix(14)
        assertEquals(3, afterAcceptingEntry.cutoff)
        assertEquals(setOf(2), afterAcceptingEntry.accepted)
    }

    @Test
    fun cappedAndStorageLimitedPrefixesMatchIndependentReachability() {
        val random = Random(913)
        var failures = 0
        repeat(80) {
            val size = random.nextInt(1, 8)
            val transitions = List(size) {
                listOf(Transition(0, 0, random.nextInt(size)),
                    Transition(1, MAX_STRING_CODE_POINT, random.nextInt(size)))
            }
            val accepting = (0 until size).filterTo(linkedSetOf()) { random.nextBoolean() }
            val minimumStorage = 3 * size + accepting.size
            for (storage in minimumStorage..minimumStorage + 24 step 3) {
                val language = SymbolicDfa(transitions, 0, accepting, storageLimit = storage)
                val prefix = try {
                    language.acceptedLengths(maxSubsetStates = 5)
                    continue
                } catch (ex: LengthProjectionLimitException) {
                    failures++
                    ex.prefix
                }
                assertTrue(prefix.accepted.all { it >= 0 && it < prefix.cutoff })
                var reachable = setOf(0)
                repeat(prefix.cutoff) { length ->
                    assertEquals(reachable.any { it in accepting }, length in prefix.accepted)
                    reachable = reachable.flatMapTo(hashSetOf()) { state -> transitions[state].map { it.target } }
                }
            }
        }
        assertTrue(failures > 0)
    }

    @Test
    fun checkpointFailuresArePropagatedWithoutWrapping() {
        class Cancelled : AutomataLimitException("cancelled")
        val language = SymbolicDfa.literal(listOf(97, 98))
        for (failure in listOf(Cancelled(), PreprocessingLimitException("shared budget exhausted"))) {
            val observed = assertFailsWith<AutomataLimitException> {
                language.acceptedLengths(checkpoint = { throw failure })
            }
            assertSame(failure, observed)
        }
    }
}

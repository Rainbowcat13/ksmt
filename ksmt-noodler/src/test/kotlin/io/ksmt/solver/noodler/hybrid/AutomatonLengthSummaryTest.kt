package io.ksmt.solver.noodler.hybrid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomatonLengthSummaryTest {
    @Test
    fun literalsKeepExactBoundsUnderTightStorageLimits() {
        for ((length, limit) in listOf(4 to 26, 10 to 55)) {
            val original = SymbolicDfa.literal(List(length) { 97 })
            val language = SymbolicDfa(
                original.transitions, original.initialState, original.acceptingStates, storageLimit = limit,
            )
            val summary = language.lengthSummary()
            assertEquals(length, summary.minLength)
            assertEquals(length, summary.maxLength)
            for (candidate in 0..length + 2) assertEquals(candidate == length, summary.contains(candidate))
        }
    }

    @Test
    fun rejectingSinkDoesNotDestroyUsefulCycleCongruence() {
        val language = SymbolicDfa.literal(listOf(97, 98, 99)).star()
        val summary = language.lengthSummary()
        assertEquals(0, summary.minLength)
        assertNull(summary.maxLength)
        assertEquals(3, summary.modulus)
        assertEquals(setOf(0), summary.residues)
        for (length in 0..100) assertEquals(length % 3 == 0, summary.contains(length))
    }

    @Test
    fun severalAcceptingStatesKeepDistinctResidues() {
        val language = SymbolicDfa(
            List(6) { state -> listOf(Transition(0, MAX_STRING_CODE_POINT, (state + 1) % 6)) },
            0, setOf(1, 3),
        )
        val summary = language.lengthSummary()
        assertEquals(6, summary.modulus)
        assertEquals(setOf(1, 3), summary.residues)
        assertEquals(1, summary.minLength)
        assertNull(summary.maxLength)
        for (length in 0..100) assertEquals(length % 6 in setOf(1, 3), summary.contains(length))
    }

    @Test
    fun coprimeCyclesHaveCheapSummaryEvenWhenExactOrbitIsCapped() {
        val periods = listOf(17, 19, 23)
        val edges = arrayListOf<List<Transition>>()
        val roots = periods.runningFold(1, Int::plus).dropLast(1)
        edges.add(listOf(Transition(0, 0, roots[0]), Transition(1, 1, roots[1]),
            Transition(2, MAX_STRING_CODE_POINT, roots[2])))
        for ((period, root) in periods.zip(roots)) {
            repeat(period) { offset ->
                edges.add(listOf(Transition(0, MAX_STRING_CODE_POINT, root + (offset + 1) % period)))
            }
        }
        val language = SymbolicDfa(edges, 0, roots.toSet())
        assertFailsWith<AutomataLimitException> { language.acceptedLengths(maxSubsetStates = 100) }
        val summary = language.lengthSummary()
        assertEquals(1, summary.minLength)
        assertNull(summary.maxLength)
        for (length in 0..10_000) {
            if (length >= 1 && periods.any { (length - 1) % it == 0 }) assertTrue(summary.contains(length))
        }
    }

    @Test
    fun graphSummariesAndExactProjectionsMatchIndependentReachabilityOracle() {
        val random = Random(734)
        repeat(200) {
            val size = random.nextInt(1, 9)
            val language = SymbolicDfa(List(size) {
                listOf(Transition(0, 0, random.nextInt(size)),
                    Transition(1, MAX_STRING_CODE_POINT, random.nextInt(size)))
            }, random.nextInt(size), (0 until size).filterTo(linkedSetOf()) { random.nextBoolean() })
            val summary = language.lengthSummary()
            val exact = language.acceptedLengths()
            val seen = hashMapOf<Set<Int>, Int>()
            val accepted = arrayListOf<Boolean>()
            var reachable = setOf(language.initialState)
            while (reachable !in seen) {
                seen[reachable] = accepted.size
                accepted.add(reachable.any { it in language.acceptingStates })
                reachable = reachable.flatMapTo(hashSetOf()) { state -> language.transitions[state].map { it.target } }
            }
            val cycleStart = seen.getValue(reachable)
            val period = accepted.size - cycleStart
            val minimum = accepted.indexOfFirst { it }.takeIf { it >= 0 }
            val unbounded = accepted.drop(cycleStart).any { it }
            val maximum = accepted.indexOfLast { it }.takeIf { it >= 0 && !unbounded }
            assertEquals(minimum, summary.minLength)
            assertEquals(maximum, summary.maxLength)
            repeat(150) { length ->
                val oracle = accepted[if (length < accepted.size) length else cycleStart + (length - cycleStart) % period]
                assertEquals(oracle, exact.contains(length))
                if (oracle) assertTrue(summary.contains(length))
            }
            assertFalse(summary.contains(-1))
        }
    }

    @Test
    fun exactProjectionMinimizesTheAcceptancePeriodAndTransient() {
        val language = SymbolicDfa(
            List(12) { state -> listOf(Transition(0, MAX_STRING_CODE_POINT, (state + 1) % 12)) },
            0, (0 until 12 step 2).toSet(),
        )
        val lengths = language.acceptedLengths()
        assertEquals(0, lengths.cycleStart)
        assertEquals(2, lengths.period)
        assertEquals(setOf(0), lengths.residues)
        val transient = compactLengthPattern(setOf(0, 2, 4, 6), cycleStart = 4, period = 4) {}
        assertEquals(0, transient.cycleStart)
        assertEquals(2, transient.period)
        assertEquals(setOf(0), transient.residues)
    }

    @Test
    fun summariesObserveCancellationDuringEdges() {
        class Cancelled : RuntimeException()
        var calls = 0
        assertFailsWith<Cancelled> {
            SymbolicDfa.literal(listOf(97, 98, 99)).lengthSummary { if (++calls == 4) throw Cancelled() }
        }
    }
}

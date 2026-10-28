package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolicDfaTest {
    private fun word(value: String): SymbolicDfa = SymbolicDfa.literal(value.codePoints().toArray().toList())
    private fun SymbolicDfa.accepts(value: String): Boolean = accepts(value.codePoints().toArray())

    @Test
    fun unicodeDomainIncludesNulSupplementaryAndSurrogateCharacters() {
        val any = SymbolicDfa.range(0, MAX_STRING_CODE_POINT)
        listOf(0, 255, 256, 0xd800, 0x1f600, MAX_STRING_CODE_POINT).forEach {
            assertTrue(any.accepts(intArrayOf(it)))
            assertFalse(SymbolicDfa.epsilon().accepts(intArrayOf(it)))
        }
        assertFalse(any.accepts(intArrayOf(MAX_STRING_CODE_POINT + 1)))
        assertFalse(any.accepts(intArrayOf()))
        assertTrue(SymbolicDfa.literal(listOf(0xd800, 0xdc00)).accepts(intArrayOf(0xd800, 0xdc00)))
        assertFalse(SymbolicDfa.literal(listOf(0xd800, 0xdc00)).accepts(intArrayOf(0x10000)))
    }

    @Test
    fun concatenationAndStarPreserveMultipleStateCycles() {
        val expression = word("ab").star().concat(word("c"))
        assertTrue(expression.accepts("c"))
        assertTrue(expression.accepts("abc"))
        assertTrue(expression.accepts("abababc"))
        assertFalse(expression.accepts("ac"))
        assertFalse(expression.accepts("ab"))
        assertTrue(SymbolicDfa.epsilon().star().accepts(""))
        assertFalse(SymbolicDfa.epsilon().star().accepts("a"))
        assertTrue(SymbolicDfa.none().star().equivalentTo(SymbolicDfa.epsilon()))
    }

    @Test
    fun productsAndComplementsMatchFiniteWordOracle() {
        val endingInA = SymbolicDfa.all().concat(word("a"))
        val alternating = word("ab").star()
        val intersection = endingInA.intersection(alternating)
        val union = endingInA.union(alternating)
        val complement = alternating.complement()
        val words = arrayListOf("")
        repeat(5) { length ->
            words.addAll(words.filter { it.length == length }.flatMap { listOf(it + "a", it + "b") })
        }
        for (sample in words) {
            val left = sample.endsWith("a")
            val right = sample.length % 2 == 0 && sample.chunked(2).all { it == "ab" }
            assertEquals(left && right, intersection.accepts(sample), sample)
            assertEquals(left || right, union.accepts(sample), sample)
            assertEquals(!right, complement.accepts(sample), sample)
        }
        assertTrue(intersection.isEmpty())
    }

    @Test
    fun acceptedLengthsHaveExactTransientAndPeriod() {
        val language = word("a").union(word("bbb").concat(word("cc").star()))
        val lengths = language.acceptedLengths()
        for (length in 0..100) {
            assertEquals(length == 1 || (length >= 3 && length % 2 == 1), lengths.contains(length))
        }
        val even = word("ab").star().acceptedLengths()
        for (length in 0..100) assertEquals(length % 2 == 0, even.contains(length))
        assertFalse(SymbolicDfa.none().acceptedLengths().contains(0))
        assertTrue(SymbolicDfa.epsilon().acceptedLengths().contains(0))
        assertFalse(SymbolicDfa.epsilon().acceptedLengths().contains(1))
    }

    @Test
    fun quotientKeepsArbitraryCyclesAndEmptyBoundaries() {
        val cycle = word("ab").star()
        val target = cycle.concat(word("c"))
        val projection = target.factorBetween(SymbolicDfa.epsilon(), word("c")) {}
        assertTrue(projection.equivalentTo(cycle))
        val middle = word("a").concat(cycle).concat(word("d")).factorBetween(word("a"), word("d")) {}
        assertTrue(middle.equivalentTo(cycle))
    }

    @Test
    fun groundRegexCompilerUsesCodePointsAndSmtLibEmptyLanguageRules() = with(KContext()) {
        val compiler = RegexAutomata()
        val emoji = mkStringLiteral(intArrayOf(0x1f600))
        val literal = mkStringToRegex(emoji)
        assertTrue(compiler.compile(literal).accepts(intArrayOf(0x1f600)))
        assertFalse(compiler.compile(mkRegexEpsilon()).accepts(intArrayOf()))
        assertTrue(compiler.compile(mkStringToRegex(mkStringLiteral(""))).accepts(intArrayOf()))
        assertTrue(compiler.compile(mkRegexLoopNoSimplify(2, 1, literal)).isEmpty())
        assertTrue(compiler.compile(mkRegexRangeNoSimplify(mkStringLiteral("ab"), emoji)).isEmpty())
        assertTrue(compiler.compile(mkRegexRangeNoSimplify(emoji, emoji)).accepts(intArrayOf(0x1f600)))
        assertTrue(compiler.compile(mkRegexPower(2, literal)).accepts(intArrayOf(0x1f600, 0x1f600)))
        assertFalse(compiler.compile(mkRegexComplement(literal)).accepts(intArrayOf(0x1f600)))
    }

    @Test
    fun longOperationsObserveCancellation() {
        class Cancelled : RuntimeException()
        var calls = 0
        val checkpoint = { if (++calls == 2) throw Cancelled() }
        kotlin.test.assertFailsWith<Cancelled> { word("ab").star(checkpoint) }
        calls = 0
        kotlin.test.assertFailsWith<Cancelled> { SymbolicDfa.allWords().accepts(intArrayOf(0, 0x1f600), checkpoint) }
    }

    @Test
    fun storageBudgetStopsSubsetAndConcatenationGrowthWithoutChangingTheLanguage() {
        val original = word("abcd")
        val limited = SymbolicDfa(
            original.transitions, original.initialState, original.acceptingStates, storageLimit = 26
        )
        assertTrue(limited.accepts("abcd"))
        assertFalse(limited.accepts("abc"))
        kotlin.test.assertFailsWith<AutomataLimitException> { limited.acceptedLengths() }
        kotlin.test.assertFailsWith<AutomataLimitException> { limited.concat(limited) }
        val lengths = original.acceptedLengths()
        for (length in 0..10) assertEquals(length == 4, lengths.contains(length))
    }
}

package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomataWitnessSearchTest {
    private fun word(value: String): SymbolicDfa = SymbolicDfa.literal(value.codePoints().toArray().toList())

    @Test
    fun acceptsRequestedLengthRatherThanShortestAcceptedWord() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val language = word("a").union(word("bbb"))
        val witness = assertNotNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to language), mapOf(x to 3), {}, PreprocessingBudget(),
        ))
        assertContentEquals(intArrayOf('b'.code, 'b'.code, 'b'.code), witness.getValue(x))
        assertNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to language), mapOf(x to 2), {}, PreprocessingBudget(),
        ))
    }

    @Test
    fun emptyWordsRequireAnAcceptingInitialState() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val witness = assertNotNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to SymbolicDfa.epsilon()), mapOf(x to 0), {}, PreprocessingBudget(),
        ))
        assertContentEquals(intArrayOf(), witness.getValue(x))
        assertNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to word("a")), mapOf(x to 0), {}, PreprocessingBudget(),
        ))
        assertNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to SymbolicDfa.none()), mapOf(x to 1), {}, PreprocessingBudget(),
        ))
        assertNull(AutomataWitnessSearch.chooseWitness(emptyMap(), mapOf(x to 0), {}, PreprocessingBudget()))
    }

    @Test
    fun reconstructsCyclicPathsAndExactUnicodeCodePoints() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val points = listOf(0, 0xd800, 0x1f600, MAX_STRING_CODE_POINT)
        val unit = points.map { SymbolicDfa.range(it, it) }.reduce { left, right -> left.concat(right) }
        val language = unit.star()
        val witness = assertNotNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to language), mapOf(x to 8), {}, PreprocessingBudget(),
        )).getValue(x)
        assertContentEquals((points + points).toIntArray(), witness)
        assertTrue(language.accepts(witness))
        val interval = SymbolicDfa.range(0x1f600, 0x1f64f)
        val fromInterval = assertNotNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to interval), mapOf(x to 1), {}, PreprocessingBudget(),
        )).getValue(x)
        assertContentEquals(intArrayOf(0x1f600), fromInterval)
    }

    @Test
    fun independentPathsAreNotAProofOfJointSatisfiability() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val languages: Map<KDecl<KStringSort>, SymbolicDfa> =
            mapOf(x to word("ab").union(word("ba")), y to word("a").union(word("b")))
        val equation = StringEquation(
            listOf(StringTermPart.Variable(x), StringTermPart.Variable(y)),
            listOf(StringTermPart.Variable(y), StringTermPart.Variable(x)),
        )
        val refinement = NoodlerRefiner().refine(languages, listOf(equation))
        assertFalse(refinement.contradiction)
        assertFalse(refinement.changed)
        val witness = assertNotNull(AutomataWitnessSearch.chooseWitness(
            refinement.languages, mapOf(x to 2, y to 1), {}, PreprocessingBudget(),
        ))
        val first = witness.getValue(x)
        val second = witness.getValue(y)
        assertFalse((first + second).contentEquals(second + first))
        for (one in listOf("ab", "ba")) {
            for (two in listOf("a", "b")) assertFalse(one + two == two + one)
        }
    }

    @Test
    fun cumulativeBudgetIsSharedAndCannotTurnExhaustionIntoInfeasibility(): Unit = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val language = word("ab").star()
        assertNotNull(AutomataWitnessSearch.chooseWitness(
            mapOf(x to language), mapOf(x to 8), {}, PreprocessingBudget(200),
        ))
        val allowance = PreprocessingBudget(1_000)
        val budget = allowance.limited(200)
        assertFailsWith<PreprocessingLimitException> {
            AutomataWitnessSearch.chooseWitness(
                mapOf(x to language, y to language), mapOf(x to 8, y to 8), {}, budget,
            )
        }
        assertTrue(allowance.remaining in 800..999)
        assertFailsWith<PreprocessingLimitException> {
            AutomataWitnessSearch.chooseWitness(
                mapOf(x to language), mapOf(x to Int.MAX_VALUE), {}, PreprocessingBudget(100),
            )
        }
    }

    @Test
    fun pathExplorationObservesCancellation() = with(KContext()) {
        class Cancelled : RuntimeException()
        val x = mkConstDecl("x", stringSort)
        var calls = 0
        assertFailsWith<Cancelled> {
            AutomataWitnessSearch.chooseWitness(
                mapOf(x to word("ab").star()), mapOf(x to 100),
                { if (++calls == 10) throw Cancelled() }, PreprocessingBudget(),
            )
        }
        assertEquals(10, calls)
    }
}

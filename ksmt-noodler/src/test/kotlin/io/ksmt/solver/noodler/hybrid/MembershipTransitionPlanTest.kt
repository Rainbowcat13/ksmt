package io.ksmt.solver.noodler.hybrid

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MembershipTransitionPlanTest {
    @Test
    fun sparseLayersRetainOnlyTheUsefulLiteralPath() {
        val points = List(40) { 97 }
        val language = SymbolicDfa.literal(points)
        val plan = language.planMembershipTransitions(IntArray(40) { -1 }, true, PreprocessingBudget())
        assertTrue(plan.possible)
        assertEquals(40, plan.steps.size)
        assertTrue(plan.steps.all { it.size == 1 })
        assertEquals(points, plan.steps.map { it.single().edge.lower })
    }

    @Test
    fun literalCharactersAndNegativeFinalsAreRespected() {
        val language = SymbolicDfa.literal(listOf(0, 0x1f600))
        val matching = intArrayOf(0, 0x1f600)
        val different = intArrayOf(0, 0xd800)
        for (positive in listOf(false, true)) {
            assertEquals(positive, language.planMembershipTransitions(matching, positive, PreprocessingBudget()).possible)
            assertEquals(!positive, language.planMembershipTransitions(different, positive, PreprocessingBudget()).possible)
        }
    }

    @Test
    fun emptyWordsAndEmptyOrUniversalLanguagesUseTheRequestedPolarity() {
        for (language in listOf(SymbolicDfa.none(), SymbolicDfa.epsilon(), SymbolicDfa.allWords())) {
            for (positive in listOf(false, true)) {
                val plan = language.planMembershipTransitions(intArrayOf(), positive, PreprocessingBudget())
                assertEquals(language.accepts(intArrayOf()) == positive, plan.possible)
                assertTrue(plan.steps.isEmpty())
            }
        }
    }

    @Test
    fun plannedRunsMatchIndependentWordEnumerationForBothPolarities() {
        val random = Random(4391)
        val alphabet = intArrayOf(0, 1, MAX_STRING_CODE_POINT)
        repeat(80) {
            val size = random.nextInt(1, 7)
            val language = SymbolicDfa(List(size) {
                listOf(Transition(0, 0, random.nextInt(size)), Transition(1, 1, random.nextInt(size)),
                    Transition(2, MAX_STRING_CODE_POINT, random.nextInt(size)))
            }, random.nextInt(size), (0 until size).filterTo(linkedSetOf()) { random.nextBoolean() })
            val known = IntArray(random.nextInt(0, 6)) {
                if (random.nextBoolean()) -1 else alphabet[random.nextInt(alphabet.size)]
            }
            val words = arrayListOf(intArrayOf())
            repeat(known.size) { position ->
                val choices = if (known[position] >= 0) intArrayOf(known[position]) else alphabet
                val next = words.flatMap { prefix -> choices.map { prefix + it } }
                words.clear()
                words.addAll(next)
            }
            for (positive in listOf(false, true)) {
                val plan = language.planMembershipTransitions(known, positive, PreprocessingBudget())
                assertEquals(words.any { language.accepts(it) == positive }, plan.possible)
                for (word in words) {
                    var state = language.initialState
                    var retained = plan.possible
                    if (retained) {
                        for ((position, character) in word.withIndex()) {
                            val edge = plan.steps[position].singleOrNull {
                                it.source == state && character in it.edge.lower..it.edge.upper
                            }
                            if (edge == null) {
                                retained = false
                                break
                            }
                            state = edge.edge.target
                        }
                    }
                    assertEquals(language.accepts(word) == positive, retained)
                }
            }
        }
    }

    @Test
    fun planningObservesStorageAndCancellationBounds() {
        val language = SymbolicDfa.literal(listOf(97, 98, 99))
        assertFailsWith<PreprocessingLimitException> {
            language.planMembershipTransitions(IntArray(3) { -1 }, true, PreprocessingBudget(3))
        }
        class Cancelled : RuntimeException()
        var calls = 0
        assertFailsWith<Cancelled> {
            language.planMembershipTransitions(IntArray(3) { -1 }, true, PreprocessingBudget()) {
                if (++calls == 4) throw Cancelled()
            }
        }
    }
}

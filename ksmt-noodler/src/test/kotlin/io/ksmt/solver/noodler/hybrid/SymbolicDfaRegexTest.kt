package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SymbolicDfaRegexTest {
    @Test
    fun exactRoundTripPreservesCyclesAndUnicodeIntervals(): Unit = with(KContext()) {
        val words = arrayListOf(intArrayOf())
        val symbols = listOf(0, 1, 255, 256, 0xd7ff, 0xd800, 0xdfff, 0xe000, 0x1f600, 0x2ffff)
        repeat(3) { length ->
            words.addAll(words.filter { it.size == length }.flatMap { prefix -> symbols.map { prefix + it } })
        }
        val cycle = SymbolicDfa.literal(listOf(0, 256)).star()
        val interval = SymbolicDfa.range(0xd800, 0xdfff)
        val languages = listOf(
            SymbolicDfa.none(), SymbolicDfa.epsilon(), SymbolicDfa.allWords(),
            cycle, interval, cycle.concat(interval).complement(),
            SymbolicDfa.literal(listOf(0xd800, 0xdc00)), SymbolicDfa.range(0, 0x2ffff)
        )
        for (original in languages) {
            val emitted = original.toRegex(this, RegexEmissionBudget({}))
            val compiled = RegexAutomata().compile(emitted)
            for (word in words) assertEquals(original.accepts(word), compiled.accepts(word), word.contentToString())
        }
    }

    @Test
    fun emissionHonorsBudgetAndCancellation(): Unit = with(KContext()) {
        val language = SymbolicDfa.literal(listOf(1, 2, 3)).star()
        assertFailsWith<AutomataLimitException> { language.toRegex(this, RegexEmissionBudget({}, 1)) }
        assertFailsWith<InterruptedException> {
            language.toRegex(this, RegexEmissionBudget({ throw InterruptedException() }))
        }
    }
}

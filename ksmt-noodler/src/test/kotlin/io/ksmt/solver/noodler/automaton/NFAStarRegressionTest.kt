package io.ksmt.solver.noodler.automaton

import io.ksmt.KContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NFAStarRegressionTest {
    @Test
    fun starDoesNotAcceptNonacceptingCyclesThroughOriginalStart(): Unit = with(KContext()) {
        val a = mkStringToRegex(mkStringLiteral("a"))
        val b = mkStringToRegex(mkStringLiteral("b"))
        val operand = fromRegex(mkRegexConcat(mkRegexStar(a), b), listOf('a', 'b'))
        val closure = operand.kleeneClosure()
        assertTrue(closure.check(""))
        assertTrue(closure.check("b"))
        assertTrue(closure.check("aabaab"))
        assertFalse(closure.check("a"))
        assertFalse(closure.check("aa"))
        assertFalse(closure.check("ba"))
        assertFalse(operand.check(""))
    }
}

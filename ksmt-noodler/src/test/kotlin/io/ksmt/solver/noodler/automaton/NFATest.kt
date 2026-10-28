package io.ksmt.solver.noodler.automaton

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NFATest {

    @Test
    fun testAcceptsEmptyWordWhenStartIsTerminal() {
        val start = node(terminal = true)
        val nfa = NFA(start = start, alphabet = listOf('a'))

        assertTrue(nfa.check(""))
        assertFalse(nfa.check("a"))
    }

    @Test
    fun testAcceptsSingleLetter() {
        val terminalNode = node(terminal = true)
        val start = node(terminal = false)
        start.put('a', terminalNode)

        val nfa = NFA(start = start, alphabet = listOf('a', 'b'))

        assertTrue(nfa.check("a"))
        assertFalse(nfa.check(""))
        assertFalse(nfa.check("b"))
    }

    @Test
    fun testAcceptsViaEpsilonTransition() {
        val terminalNode = node(terminal = true)
        val mid = node(terminal = false)
        val start = node(terminal = false)
        mid.put('b', terminalNode)
        start.put(EPS, mid)

        val nfa = NFA(start = start, alphabet = listOf('a', 'b'))

        assertTrue(nfa.check("b"))
        assertFalse(nfa.check(""))
        assertFalse(nfa.check("a"))
    }

    @Test
    fun testRejectsUnreachableTerminal() {
        val start = node(terminal = false)
        node(terminal = true)
        val nfa = NFA(start = start, alphabet = listOf('a'))

        assertFalse(nfa.check(""))
        assertFalse(nfa.check("a"))
    }

    @Test
    fun testMultipleTransitionsSameChar() {
        val terminalNode = node(terminal = true)
        val deadEnd = node(terminal = false)
        val start = node(terminal = false)
        start.put('a', deadEnd)
        start.put('a', terminalNode)

        val nfa = NFA(start = start, alphabet = listOf('a', 'b'))

        assertTrue(nfa.check("a"))
        assertFalse(nfa.check(""))
        assertFalse(nfa.check("b"))
    }

    @Test
    fun testListNodesFindsAllTerminalStates() {
        val t1 = node(terminal = true)
        val t2 = node(terminal = true)
        val mid = node(terminal = false)
        val start = node(terminal = false)

        mid.put('a', t1)
        mid.put('b', t2)
        start.put('x', mid)
        start.put('y', t2)

        val terminals = listNodes(start, { it.terminal })
        assertEquals(2, terminals.size)
        assertTrue(terminals.any { it === t1 })
        assertTrue(terminals.any { it === t2 })
    }

    @Test
    fun testListNodesHandlesDeepGraphs() {
        val chainLength = 20_000
        var current = node(terminal = false)
        val start = current

        repeat(chainLength - 1) { index ->
            val next = node(terminal = index == chainLength - 2)
            current.put('a', next)
            current = next
        }

        val nodes = listNodes(start, { true })

        assertEquals(chainLength, nodes.size)
        assertTrue(nodes.last().terminal)
    }

    @Test
    fun testNodeCopyPreservesStructure() {
        val terminalNode = node(terminal = true)
        val start = node(terminal = false)
        start.put('a', terminalNode)

        val copied = copy(start)
        assertTrue(copied !== start)
        val copiedChildren = copied.getList('a')
        assertTrue(copiedChildren.isNotEmpty(), "Copied start has no 'a' transition")
        val firstChild = copiedChildren[0]
        assertTrue(firstChild !== terminalNode)
        assertTrue(firstChild.terminal)
    }

    @Test
    fun testNfaCopyPreservesLanguageOnSamples() {
        val terminalNode = node(terminal = true)
        val start = node(terminal = false)
        start.put('a', terminalNode)

        val nfa = NFA(start = start, alphabet = listOf('a'))
        val copied = nfa.copy()

        assertTrue(nfa.check("a"))
        assertTrue(copied.check("a"))
        assertFalse(nfa.check(""))
        assertFalse(copied.check(""))
    }

    @Test
    fun testConcatAcceptsConcatenation() {
        val aTerminal = node(terminal = true)
        val aStart = node(terminal = false)
        aStart.put('a', aTerminal)

        val bTerminal = node(terminal = true)
        val bStart = node(terminal = false)
        bStart.put('b', bTerminal)

        val nfaA = NFA(start = aStart, alphabet = listOf('a', 'b'))
        val nfaB = NFA(start = bStart, alphabet = listOf('a', 'b'))

        val concatenated = nfaA.concat(nfaB)

        assertTrue(concatenated.check("ab"))
        assertFalse(concatenated.check("a"))
        assertFalse(concatenated.check("b"))

        // original automata remain unchanged
        assertTrue(nfaA.check("a"))
        assertTrue(nfaB.check("b"))
    }

    @Test
    fun testProductKeepsCyclesBetweenVisitedPairs() {
        val universal = node(terminal = true)
        universal.put('a', universal)
        universal.put('b', universal)
        val nfaAll = NFA(start = universal, alphabet = listOf('a', 'b'))

        val first = node(terminal = true)
        val second = node(terminal = true)
        first.put('a', second)
        second.put('b', first)
        val nfaPattern = NFA(start = first, alphabet = listOf('a', 'b'))

        val product = nfaAll.product(nfaPattern)

        assertTrue(product.check(""))
        assertTrue(product.check("a"))
        assertTrue(product.check("ab"))
        assertTrue(product.check("aba"))
        assertTrue(product.check("abab"))
        assertFalse(product.check("b"))
        assertFalse(product.check("aa"))
        assertFalse(product.check("abb"))
    }

    @Test
    fun testProductHandlesEpsilonTransitionsFromSingleSide() {
        val terminalA = node(terminal = true)
        val midA = node(terminal = false)
        val startA = node(terminal = false)
        startA.put(EPS, midA)
        midA.put('a', terminalA)
        val nfaA = NFA(start = startA, alphabet = listOf('a', 'b'))

        val terminalB = node(terminal = true)
        val startB = node(terminal = false)
        startB.put('a', terminalB)
        val nfaB = NFA(start = startB, alphabet = listOf('a', 'b'))

        val product = nfaA.product(nfaB)

        assertTrue(product.check("a"))
        assertFalse(product.check(""))
        assertFalse(product.check("b"))
    }

    @Test
    fun testProductEpsilonAllowsEmptyWord() {
        val terminalA = node(terminal = true)
        val startA = node(terminal = false)
        startA.put(EPS, terminalA)
        val nfaA = NFA(start = startA, alphabet = listOf('a'))

        val startB = node(terminal = true)
        val nfaB = NFA(start = startB, alphabet = listOf('a'))

        val product = nfaA.product(nfaB)

        assertTrue(product.check(""))
        assertFalse(product.check("a"))
    }

    @Test
    fun testEpsilonClosureExpandsReachableNodes() {
        val first = node(terminal = false)
        val second = node(terminal = false)
        val third = node(terminal = true)
        first.put(EPS, second)
        second.put(EPS, third)

        val visited = mutableSetOf(first)
        epsilonClosure(visited)

        assertTrue(visited.contains(first))
        assertTrue(visited.contains(second))
        assertTrue(visited.contains(third))
    }

    @Test
    fun testEpsilonClosureIgnoresNonEpsilonTransitions() {
        val first = node(terminal = false)
        val second = node(terminal = true)
        first.put('a', second)

        val visited = mutableSetOf(first)
        epsilonClosure(visited)

        assertTrue(visited.contains(first))
        assertFalse(visited.contains(second))
    }

    @Test
    fun testAnyWordReturnsEmptyStringForEpsilonAcceptedWord() {
        val terminalNode = node(terminal = true)
        val start = node(terminal = false)
        start.put(EPS, terminalNode)

        val nfa = NFA(start = start, alphabet = listOf(EPS, 'a'))

        assertEquals("", nfa.anyWord())
    }

    @Test
    fun testAnyWordReturnsWordThroughEpsilonClosure() {
        val terminalNode = node(terminal = true)
        val mid = node(terminal = false)
        val start = node(terminal = false)

        start.put(EPS, mid)
        mid.put('b', terminalNode)
        mid.put('a', mid)

        val nfa = NFA(start = start, alphabet = listOf(EPS, 'a', 'b'))

        assertEquals("b", nfa.anyWord())
    }

    @Test
    fun testAnyWordReturnsNullWhenNoTerminalIsReachable() {
        val start = node(terminal = false)
        start.put(EPS, start)
        start.put('a', start)

        val nfa = NFA(start = start, alphabet = listOf(EPS, 'a'))

        assertNull(nfa.anyWord())
    }

    private fun node(terminal: Boolean): NFANode {
        return NFANode(terminal, mutableMapOf())
    }
}

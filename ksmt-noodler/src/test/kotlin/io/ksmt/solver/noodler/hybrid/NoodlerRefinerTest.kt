package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoodlerRefinerTest {
    private fun word(value: String): SymbolicDfa = SymbolicDfa.literal(value.codePoints().toArray().toList())

    @Test
    fun equationPropagationPreservesValidCyclicSolutions() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val z = mkConstDecl("z", stringSort)
        val t = mkConstDecl("t", stringSort)
        val variable = { declaration: KDecl<KStringSort> -> StringTermPart.Variable(declaration) }
        val cycle = word("ab").star()
        val equations = listOf(
            StringEquation(listOf(variable(t)), listOf(variable(x), variable(y))),
            StringEquation(listOf(variable(x)), listOf(variable(z))),
        )
        val result = NoodlerRefiner().refine(
            mapOf(x to cycle, y to word("c"), z to word("ab"), t to cycle.concat(word("c"))), equations
        )
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(x).accepts(intArrayOf('a'.code, 'b'.code)))
        assertTrue(result.languages.getValue(t).accepts(intArrayOf('a'.code, 'b'.code, 'c'.code)))
    }

    @Test
    fun repeatedVariableOccurrencesCannotRejectAValidSharedWitness() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val alphabet = word("a").union(word("b"))
        val equation = StringEquation(
            listOf(StringTermPart.Variable(x), StringTermPart.Variable(x)), listOf(StringTermPart.Variable(y))
        )
        val result = NoodlerRefiner().refine(mapOf(x to alphabet, y to word("aa").union(word("bb"))), listOf(equation))
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(x).accepts(intArrayOf('a'.code)))
        assertTrue(result.languages.getValue(x).accepts(intArrayOf('b'.code)))
        val contradiction = NoodlerRefiner().refine(mapOf(x to alphabet, y to word("ab")), listOf(equation))
        assertTrue(contradiction.contradiction)
    }

    @Test
    fun projectionsDoNotPretendToSolveCorrelatedAlternatives() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val t = mkConstDecl("t", stringSort)
        val choices = word("a").union(word("b"))
        val equation = StringEquation(
            listOf(StringTermPart.Variable(t)), listOf(StringTermPart.Variable(x), StringTermPart.Variable(y))
        )
        val result = NoodlerRefiner().refine(
            mapOf(x to choices, y to choices, t to word("ab").union(word("ba"))), listOf(equation)
        )
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(x).equivalentTo(choices))
        assertTrue(result.languages.getValue(y).equivalentTo(choices))
    }

    @Test
    fun literalPartsAndEmptyWordsParticipateInEquations() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val equation = StringEquation(
            listOf(StringTermPart.Literal(listOf(0)), StringTermPart.Variable(x)),
            listOf(StringTermPart.Literal(listOf(0, 0x1f600))),
        )
        val result = NoodlerRefiner().refine(emptyMap(), listOf(equation))
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(x).equivalentTo(SymbolicDfa.literal(listOf(0x1f600))))
        val empty = NoodlerRefiner().refine(emptyMap(), listOf(StringEquation(emptyList(), emptyList())))
        assertFalse(empty.contradiction)
    }

    @Test
    fun sharedVariableBoundariesExposeIncompatibleMiddleWords() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val equation = StringEquation(
            listOf(StringTermPart.Variable(x), StringTermPart.Literal(listOf('a'.code, 'b'.code)),
                StringTermPart.Variable(y)),
            listOf(StringTermPart.Variable(x), StringTermPart.Literal(listOf('b'.code, 'a'.code)),
                StringTermPart.Variable(y)),
        )
        val result = NoodlerRefiner().refine(emptyMap(), listOf(equation))
        assertTrue(result.contradiction)
    }

    @Test
    fun sharedBoundariesCancelBeforeProjectingVariableLanguages() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val z = mkConstDecl("z", stringSort)
        val t = mkConstDecl("t", stringSort)
        val equation = StringEquation(
            listOf(StringTermPart.Variable(x), StringTermPart.Variable(y), StringTermPart.Variable(t)),
            listOf(StringTermPart.Variable(x), StringTermPart.Variable(z), StringTermPart.Variable(t)),
        )
        val result = NoodlerRefiner().refine(
            mapOf(y to word("a").union(word("bb")), z to word("bb").union(word("ccc"))), listOf(equation)
        )
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(y).equivalentTo(word("bb")))
        assertTrue(result.languages.getValue(z).equivalentTo(word("bb")))
        assertTrue(result.languages.getValue(x).equivalentTo(SymbolicDfa.allWords()))
        assertTrue(result.languages.getValue(t).equivalentTo(SymbolicDfa.allWords()))
    }

    @Test
    fun cancellationHandlesSplitLiteralsEmptyPartsAndUnicode() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val equation = StringEquation(
            listOf(StringTermPart.Literal(emptyList()), StringTermPart.Literal(listOf(0, 0x1f600)),
                StringTermPart.Variable(x), StringTermPart.Literal(listOf(0x1f601, 'a'.code))),
            listOf(StringTermPart.Literal(listOf(0)), StringTermPart.Literal(listOf(0x1f600)),
                StringTermPart.Variable(y), StringTermPart.Literal(listOf(0x1f601)),
                StringTermPart.Literal(listOf('a'.code))),
        )
        val result = NoodlerRefiner().refine(mapOf(x to word("ab")), listOf(equation))
        assertFalse(result.contradiction)
        assertTrue(result.languages.getValue(y).equivalentTo(word("ab")))
    }

    @Test
    fun aCancelledIdentityKeepsUnconstrainedVariableDomains() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val variable = StringTermPart.Variable(x)
        val result = NoodlerRefiner().refine(emptyMap(), listOf(StringEquation(listOf(variable), listOf(variable))))
        assertFalse(result.contradiction)
        assertFalse(result.changed)
        assertTrue(result.languages.getValue(x).equivalentTo(SymbolicDfa.allWords()))
    }

    @Test
    fun projectionsPreserveEnumeratedSolutionsOfCyclicSharedVariableEquations() = with(KContext()) {
        val x = mkConstDecl("x", stringSort)
        val y = mkConstDecl("y", stringSort)
        val domains = listOf(
            SymbolicDfa.allWords(), word("a").star(), word("ab").star(),
            word("a").union(word("b")), SymbolicDfa.epsilon(),
        )
        val samples = arrayListOf("")
        repeat(3) { length ->
            samples.addAll(samples.filter { it.length == length }.flatMap { listOf(it + "a", it + "b") })
        }
        val equation = StringEquation(
            listOf(StringTermPart.Variable(x), StringTermPart.Variable(y)),
            listOf(StringTermPart.Variable(y), StringTermPart.Variable(x)),
        )
        for (left in domains) {
            for (right in domains) {
                val result = NoodlerRefiner().refine(mapOf(x to left, y to right), listOf(equation))
                for (one in samples) {
                    for (two in samples) {
                        val first = one.codePoints().toArray()
                        val second = two.codePoints().toArray()
                        if (one + two != two + one || !left.accepts(first) || !right.accepts(second)) continue
                        assertFalse(result.contradiction, "Valid commuting witness: x=$one, y=$two")
                        assertTrue(result.languages.getValue(x).accepts(first), "Removed x=$one from a valid witness")
                        assertTrue(result.languages.getValue(y).accepts(second), "Removed y=$two from a valid witness")
                    }
                }
            }
        }
    }
}

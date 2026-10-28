package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.sort.KRegexSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutomataLimitsTest {
    private val raised = AutomataLimits(maxStates = 300_000, maxStorage = 3_000_000)

    @Test
    fun factoriesAndDerivedAutomataRetainRaisedCaps() {
        val literal = SymbolicDfa.literal(listOf(0, 256), raised)
        val epsilon = SymbolicDfa.epsilon(raised)
        val all = SymbolicDfa.allWords(raised)
        val automata = listOf(
            literal, epsilon, all, SymbolicDfa.none(raised), SymbolicDfa.range(0, 256, raised),
            SymbolicDfa.range(3, 2, raised), literal.complement(), literal.star(),
            literal.concat(epsilon), literal.intersection(all), literal.union(epsilon),
            literal.factorBetween(epsilon, epsilon) {},
        )
        automata.forEach { assertEquals(raised, it.limits) }
        assertTrue(automata.last().accepts(intArrayOf(0, 256)))
        assertFalse(automata.last().accepts(intArrayOf(0)))
    }

    @Test
    fun binaryOperationsKeepBothOperandsCaps() {
        val firstLimits = AutomataLimits(maxStates = 20, maxStorage = 3_000)
        val secondLimits = AutomataLimits(maxStates = 30, maxStorage = 2_000)
        val first = SymbolicDfa.literal(listOf(1), firstLimits)
        val second = SymbolicDfa.literal(listOf(2), secondLimits)
        val combined = AutomataLimits(maxStates = 20, maxStorage = 2_000)
        listOf(first.intersection(second), first.union(second), first.concat(second)).forEach {
            assertEquals(combined, it.limits)
        }
        assertEquals(combined, first.withLimits(secondLimits).limits)
        assertEquals(firstLimits, first.withLimits(raised).limits)
    }

    @Test
    fun smallStateCapsRejectTemporaryProductsAndNfas() {
        val limits = AutomataLimits(maxStates = 3, maxStorage = 10_000)
        val first = SymbolicDfa.literal(listOf(1), limits)
        val second = SymbolicDfa.literal(listOf(2), limits)
        assertFailsWith<AutomataLimitException> { SymbolicDfa.literal(listOf(1, 2), limits) }
        assertFailsWith<AutomataLimitException> { first.intersection(second) }
        assertFailsWith<AutomataLimitException> { first.union(second) }
        assertFailsWith<AutomataLimitException> { first.equivalentTo(second) }
        assertFailsWith<AutomataLimitException> { first.concat(second) }
        assertFailsWith<AutomataLimitException> { first.star() }
        assertFailsWith<AutomataLimitException> {
            first.factorBetween(SymbolicDfa.epsilon(limits), SymbolicDfa.epsilon(limits)) {}
        }
    }

    @Test
    fun exactLengthsHonorSubsetCap() {
        val transitions = listOf(
            listOf(Transition(0, 0, 1), Transition(1, MAX_STRING_CODE_POINT, 2)),
            listOf(Transition(0, MAX_STRING_CODE_POINT, 0)),
            listOf(Transition(0, MAX_STRING_CODE_POINT, 1)),
        )
        val limited = SymbolicDfa(transitions, 0, setOf(2), AutomataLimits(maxStates = 3, maxStorage = 100))
        assertFailsWith<AutomataLimitException> { limited.acceptedLengths() }
        val larger = SymbolicDfa(transitions, 0, setOf(2), AutomataLimits(maxStates = 4, maxStorage = 100))
        val lengths = larger.acceptedLengths()
        (0..8).forEach { assertEquals(it == 1 || it >= 3, lengths.contains(it)) }
    }

    @Test
    fun compilerCarriesCapsThroughEveryRegexFactory(): Unit = KContext().use { ctx ->
        with(ctx) {
            val word = mkStringToRegexNoSimplify(mkStringLiteral("ab"))
            val regexes: List<KExpr<KRegexSort>> = listOf(
                mkRegexEpsilon(), mkRegexAll(), mkRegexAllChar(), word,
                mkRegexRangeNoSimplify(mkStringLiteral("a"), mkStringLiteral("c")),
                mkRegexRangeNoSimplify(mkStringLiteral("ab"), mkStringLiteral("c")),
                mkRegexConcatNoSimplify(word, word), mkRegexUnionNoSimplify(word, mkRegexAllChar()),
                mkRegexIntersectionNoSimplify(word, mkRegexAll()), mkRegexDifferenceNoSimplify(word, word),
                mkRegexComplementNoSimplify(word), mkRegexStarNoSimplify(word), mkRegexCrossNoSimplify(word),
                mkRegexOptionNoSimplify(word), mkRegexPowerNoSimplify(0, word),
                mkRegexPowerNoSimplify(2, word), mkRegexLoopNoSimplify(1, 2, word),
                mkRegexLoopNoSimplify(2, 1, word),
            )
            val compiler = RegexAutomata(limits = raised)
            regexes.forEach { assertEquals(raised, compiler.compile(it).limits) }
            assertFailsWith<AutomataLimitException> {
                RegexAutomata(limits = AutomataLimits(maxStates = 3)).compile(word)
            }
        }
    }

    @Test
    fun compilerStorageCapIncludesCumulativeCache(): Unit = KContext().use { ctx ->
        val first = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("a"))
        val second = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("b"))
        val compiler = RegexAutomata(limits = AutomataLimits(maxStates = 10, maxStorage = 10))
        val compiled = compiler.compile(first)
        assertSame(compiled, compiler.compile(first))
        assertFailsWith<AutomataLimitException> { compiler.compile(second) }
        val larger = RegexAutomata(limits = AutomataLimits(maxStates = 10, maxStorage = 20))
        larger.compile(first)
        assertTrue(larger.compile(second).accepts(intArrayOf('b'.code)))
    }

    @Test
    fun refinerCapsCoverNewVariablesLiteralsAndPreviouslyBuiltLanguages(): Unit = KContext().use { ctx ->
        val declaration = ctx.mkConstDecl("x", ctx.stringSort)
        val variable = StringTermPart.Variable(declaration)
        val equation = StringEquation(listOf(variable), listOf(StringTermPart.Literal(listOf(1))))
        val result = NoodlerRefiner(limits = raised).refine(emptyMap(), listOf(equation))
        assertFalse(result.contradiction)
        assertEquals(raised, result.languages.getValue(declaration).limits)
        assertTrue(result.languages.getValue(declaration).accepts(intArrayOf(1)))
        assertFailsWith<AutomataLimitException> {
            NoodlerRefiner(limits = AutomataLimits(maxStates = 3)).refine(emptyMap(), listOf(equation))
        }
        assertFailsWith<AutomataLimitException> {
            NoodlerRefiner(limits = AutomataLimits(maxStates = 2)).refine(
                mapOf(declaration to SymbolicDfa.literal(listOf(1))), emptyList()
            )
        }
    }

    @Test
    fun detachedModelEvaluationAndValidationUseConfiguredCaps(): Unit = KContext().use { ctx ->
        val literal = ctx.mkStringLiteral("a")
        val assertion = ctx.mkStringInRegexNoSimplify(literal, ctx.mkStringToRegexNoSimplify(literal))
        val limited = NoodlerYicesModel(ctx, emptyMap(), AutomataLimits(maxStates = 2))
        assertFailsWith<AutomataLimitException> { limited.eval(assertion) }
        assertFailsWith<AutomataLimitException> { limited.validates(listOf(assertion), {}) }
        val larger = NoodlerYicesModel(ctx, emptyMap(), raised)
        assertEquals(ctx.trueExpr, larger.eval(assertion))
        assertTrue(larger.validates(listOf(assertion), {}))
    }

    @Test
    fun regexEmissionRetainsAutomataCaps(): Unit = KContext().use { ctx ->
        val storageLimited = SymbolicDfa.literal(listOf(1), AutomataLimits(maxStorage = 10))
        assertFailsWith<AutomataLimitException> { storageLimited.toRegex(ctx, RegexEmissionBudget({})) }
        val stateLimited = SymbolicDfa.literal(listOf(1), AutomataLimits(maxStates = 3))
        assertFailsWith<AutomataLimitException> { stateLimited.toRegex(ctx, RegexEmissionBudget({})) }
        val raisedWord = SymbolicDfa.literal(listOf(1), raised)
        val regex = raisedWord.toRegex(ctx, RegexEmissionBudget({}))
        assertTrue(RegexAutomata(limits = raised).compile(regex).accepts(intArrayOf(1)))
    }
}

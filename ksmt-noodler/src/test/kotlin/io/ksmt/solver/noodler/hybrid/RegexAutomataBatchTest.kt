package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.sort.KRegexSort
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegexAutomataBatchTest {
    @Test
    fun longConcatUsesOneDeterminizationAndDoesNotRetainPrefixAutomata() = KContext().use { ctx ->
        val leaf = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("a"))
        var regex: KExpr<KRegexSort> = leaf
        repeat(159) { regex = ctx.mkRegexConcatNoSimplify(regex, leaf) }
        val compiler = RegexAutomata(limits = AutomataLimits(maxStates = 500, maxStorage = 10_000))
        val language = compiler.compile(regex)
        assertTrue(language.accepts(IntArray(160) { 97 }))
        assertFalse(language.accepts(IntArray(159) { 97 }))
        assertFalse(language.accepts(IntArray(161) { 97 }))
        assertEquals(1, compiler.determinizationCount)
        assertEquals(1, compiler.cachedRootCount)
        assertSame(language, compiler.compile(regex))
    }

    @Test
    fun sharedUnionAndNestedClosuresRemainCompact() = KContext().use { ctx ->
        val leaf = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("a"))
        var shared: KExpr<KRegexSort> = leaf
        repeat(80) { shared = ctx.mkRegexUnionNoSimplify(shared, shared) }
        var closures: KExpr<KRegexSort> = shared
        repeat(2_500) { closures = ctx.mkRegexStarNoSimplify(closures) }
        val compiler = RegexAutomata()
        val language = compiler.compile(closures)
        assertTrue(language.accepts(intArrayOf()))
        assertTrue(language.accepts(intArrayOf(97, 97, 97)))
        assertFalse(language.accepts(intArrayOf(98)))
        assertEquals(1, compiler.determinizationCount)
        assertEquals(1, compiler.cachedRootCount)
    }

    @Test
    fun repeatedSharedConcatIsBudgetedBeforeItsOccurrencesExplode(): Unit = KContext().use { ctx ->
        var regex: KExpr<KRegexSort> = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("a"))
        repeat(80) { regex = ctx.mkRegexConcatNoSimplify(regex, regex) }
        assertFailsWith<PreprocessingLimitException> {
            RegexAutomata(preprocessing = PreprocessingBudget(500)).compile(regex)
        }
    }

    @Test
    fun booleanBarriersReleaseTheirIntermediateDfasAndRetainExactComplements() = KContext().use { ctx ->
        val points = intArrayOf(0, 0xd800, 0x1f600, MAX_STRING_CODE_POINT)
        var regex: KExpr<KRegexSort> = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral(points))
        repeat(2_000) { regex = ctx.mkRegexComplementNoSimplify(regex) }
        val compiler = RegexAutomata()
        val language = compiler.compile(regex)
        assertTrue(language.accepts(points))
        assertFalse(language.accepts(intArrayOf()))
        assertFalse(language.accepts(intArrayOf(0, 0x10000, MAX_STRING_CODE_POINT)))
        assertTrue(compiler.peakIntermediateAutomata <= 2)
        assertEquals(1, compiler.cachedRootCount)
    }

    @Test
    fun rootCacheHasAFixedEntryBound() = KContext().use { ctx ->
        val compiler = RegexAutomata()
        repeat(100) { compiler.compile(ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral(intArrayOf(it)))) }
        assertTrue(compiler.cachedRootCount <= 64)
    }

    @Test
    fun compilerPreservesCancellationIdentity() = KContext().use { ctx ->
        class Cancelled : RuntimeException()
        val failure = Cancelled()
        val leaf = ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("a"))
        val regex = ctx.mkRegexStarNoSimplify(ctx.mkRegexConcatNoSimplify(leaf, leaf))
        var calls = 0
        val observed = assertFailsWith<Cancelled> {
            RegexAutomata(checkpoint = { if (++calls == 20) throw failure }).compile(regex)
        }
        assertSame(failure, observed)
    }

    @Test
    fun randomRegexesMatchAnIndependentSubstringOracleIncludingBooleanBarriers() = KContext().use { ctx ->
        val random = Random(315_009)
        val words = arrayListOf(emptyList<Int>())
        val alphabet = listOf(0, 97, 0xd800, 0x1f600, MAX_STRING_CODE_POINT)
        repeat(3) { length ->
            words.addAll(words.filter { it.size == length }.flatMap { word -> alphabet.map { word + it } })
        }
        repeat(100) {
            val pattern = randomPattern(random, 4, alphabet)
            val language = RegexAutomata().compile(pattern.expression(ctx))
            for (word in words) assertEquals(pattern.matches(word), language.accepts(word.toIntArray()), "$pattern / $word")
        }
    }

    private sealed class Pattern {
        data class Word(val points: List<Int>) : Pattern()
        data class Range(val lower: Int, val upper: Int) : Pattern()
        object None : Pattern()
        object All : Pattern()
        data class Binary(val operation: Int, val left: Pattern, val right: Pattern) : Pattern()
        data class Unary(val operation: Int, val child: Pattern) : Pattern()
        data class Repeat(val child: Pattern, val minimum: Int, val maximum: Int) : Pattern()
    }

    private fun randomPattern(random: Random, depth: Int, alphabet: List<Int>): Pattern {
        if (depth == 0) return when (random.nextInt(4)) {
            0 -> Pattern.Word(List(random.nextInt(3)) { alphabet.random(random) })
            1 -> Pattern.Range(alphabet.random(random), alphabet.random(random))
            2 -> Pattern.None
            else -> Pattern.All
        }
        return when (val operation = random.nextInt(10)) {
            in 0..4 -> Pattern.Binary(operation, randomPattern(random, depth - 1, alphabet), randomPattern(random, depth - 1, alphabet))
            in 5..8 -> Pattern.Unary(operation, randomPattern(random, depth - 1, alphabet))
            else -> Pattern.Repeat(randomPattern(random, depth - 1, alphabet), random.nextInt(3), random.nextInt(4))
        }
    }

    private fun Pattern.expression(ctx: KContext): KExpr<KRegexSort> = with(ctx) {
        when (val pattern = this@expression) {
            is Pattern.Word -> mkStringToRegexNoSimplify(mkStringLiteral(pattern.points.toIntArray()))
            is Pattern.Range -> mkRegexRangeNoSimplify(
                mkStringLiteral(intArrayOf(pattern.lower)), mkStringLiteral(intArrayOf(pattern.upper)),
            )
            Pattern.None -> mkRegexEpsilon()
            Pattern.All -> mkRegexAll()
            is Pattern.Binary -> {
                val left = pattern.left.expression(ctx)
                val right = pattern.right.expression(ctx)
                when (pattern.operation) {
                    0 -> mkRegexConcatNoSimplify(left, right)
                    1 -> mkRegexUnionNoSimplify(left, right)
                    2 -> mkRegexIntersectionNoSimplify(left, right)
                    3 -> mkRegexDifferenceNoSimplify(left, right)
                    else -> mkRegexUnionNoSimplify(left, mkRegexConcatNoSimplify(right, left))
                }
            }
            is Pattern.Unary -> {
                val child = pattern.child.expression(ctx)
                when (pattern.operation) {
                    5 -> mkRegexStarNoSimplify(child)
                    6 -> mkRegexCrossNoSimplify(child)
                    7 -> mkRegexOptionNoSimplify(child)
                    else -> mkRegexComplementNoSimplify(child)
                }
            }
            is Pattern.Repeat -> if (pattern.minimum == pattern.maximum) {
                mkRegexPowerNoSimplify(pattern.minimum, pattern.child.expression(ctx))
            } else {
                mkRegexLoopNoSimplify(pattern.minimum, pattern.maximum, pattern.child.expression(ctx))
            }
        }
    }

    private fun Pattern.matches(word: List<Int>): Boolean {
        val memo = hashMapOf<Pair<Pattern, Int>, Set<Int>>()
        fun ends(pattern: Pattern, from: Int): Set<Int> {
            val key = pattern to from
            memo[key]?.let { return it }
            fun closure(child: Pattern, starts: Set<Int>): Set<Int> {
                val result = starts.toMutableSet()
                val pending = ArrayDeque<Int>()
                pending.addAll(starts)
                while (pending.isNotEmpty()) {
                    for (next in ends(child, pending.removeFirst())) if (result.add(next)) pending.addLast(next)
                }
                return result
            }
            val result = when (pattern) {
                is Pattern.Word -> if (from + pattern.points.size <= word.size &&
                    word.subList(from, from + pattern.points.size) == pattern.points
                ) setOf(from + pattern.points.size) else emptySet()
                is Pattern.Range -> if (from < word.size && word[from] in pattern.lower..pattern.upper) {
                    setOf(from + 1)
                } else emptySet()
                Pattern.None -> emptySet()
                Pattern.All -> (from..word.size).toSet()
                is Pattern.Binary -> {
                    val left = ends(pattern.left, from)
                    when (pattern.operation) {
                        0 -> left.flatMapTo(hashSetOf()) { ends(pattern.right, it) }
                        1 -> left + ends(pattern.right, from)
                        2 -> left intersect ends(pattern.right, from)
                        3 -> left - ends(pattern.right, from)
                        else -> left + ends(pattern.right, from).flatMapTo(hashSetOf()) { ends(pattern.left, it) }
                    }
                }
                is Pattern.Unary -> when (pattern.operation) {
                    5 -> closure(pattern.child, setOf(from))
                    6 -> closure(pattern.child, ends(pattern.child, from))
                    7 -> ends(pattern.child, from) + from
                    else -> (from..word.size).toSet() - ends(pattern.child, from)
                }
                is Pattern.Repeat -> {
                    val accepted = hashSetOf<Int>()
                    var current = setOf(from)
                    for (count in 0..pattern.maximum) {
                        if (count >= pattern.minimum) accepted.addAll(current)
                        current = current.flatMapTo(hashSetOf()) { ends(pattern.child, it) }
                    }
                    accepted
                }
            }
            memo[key] = result
            return result
        }
        return word.size in ends(this, 0)
    }
}

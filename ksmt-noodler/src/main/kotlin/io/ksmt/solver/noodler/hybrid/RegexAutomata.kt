package io.ksmt.solver.noodler.hybrid

import io.ksmt.expr.KExpr
import io.ksmt.expr.KRegexAll
import io.ksmt.expr.KRegexAllChar
import io.ksmt.expr.KRegexComplementExpr
import io.ksmt.expr.KRegexConcatExpr
import io.ksmt.expr.KRegexCrossExpr
import io.ksmt.expr.KRegexDifferenceExpr
import io.ksmt.expr.KRegexEpsilon
import io.ksmt.expr.KRegexIntersectionExpr
import io.ksmt.expr.KRegexLoopExpr
import io.ksmt.expr.KRegexOptionExpr
import io.ksmt.expr.KRegexPowerExpr
import io.ksmt.expr.KRegexRangeExpr
import io.ksmt.expr.KRegexStarExpr
import io.ksmt.expr.KRegexUnionExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KStringToRegexExpr
import io.ksmt.sort.KRegexSort

/** Ground regex compilation with one determinization per regular island, rather than per operator. */
internal class RegexAutomata(
    private val checkpoint: () -> Unit = {},
    private val preprocessing: PreprocessingBudget = PreprocessingBudget(checkpoint = checkpoint),
    private val limits: AutomataLimits = AutomataLimits(),
) {
    // Only explicitly requested roots survive a compile call; barrier intermediates have last-use release.
    private val cache = linkedMapOf<KExpr<KRegexSort>, SymbolicDfa>()
    private val storage = limits.storageBudget()
    var determinizationCount: Int = 0
        private set
    var peakIntermediateAutomata: Int = 0
        private set
    val cachedRootCount: Int get() = cache.size

    @Suppress("ComplexMethod", "NestedBlockDepth") // Stack evaluation and last-use release share the same plan.
    fun compile(regex: KExpr<KRegexSort>): SymbolicDfa {
        checkpoint()
        cache[regex]?.let { return it }
        val plan = compilationPlan(regex)
        val uses = hashMapOf<KExpr<KRegexSort>, Int>()
        for (dependencies in plan.values) {
            for (dependency in dependencies) uses[dependency] = (uses[dependency] ?: 0) + 1
        }
        val results = hashMapOf<KExpr<KRegexSort>, SymbolicDfa>()
        val pending = ArrayDeque<Frame>()
        pending.addLast(Frame(regex, plan.getValue(regex)))
        fun resolve(expression: KExpr<KRegexSort>): SymbolicDfa =
            cache[expression] ?: results.getValue(expression)
        while (pending.isNotEmpty()) {
            checkpoint()
            val frame = pending.last()
            if (frame.next < frame.dependencies.size) {
                val child = frame.dependencies[frame.next++]
                if (child !in cache && child !in results) pending.addLast(Frame(child, plan.getValue(child)))
            } else {
                preprocessing.reserve()
                val result = compileReady(frame.expression, ::resolve)
                // Cumulative materialization/work remains bounded even when intermediates are released.
                storage.reserve(result.storageSize + 1)
                results[frame.expression] = result
                peakIntermediateAutomata = maxOf(peakIntermediateAutomata, results.size)
                pending.removeLast()
                for (dependency in frame.dependencies) {
                    val remaining = uses.getValue(dependency) - 1
                    uses[dependency] = remaining
                    if (remaining == 0) results.remove(dependency)
                }
            }
        }
        return results.getValue(regex).also {
            if (cache.size >= MAX_CACHED_ROOTS) cache.remove(cache.keys.first())
            cache[regex] = it
        }
    }

    private class Frame(
        val expression: KExpr<KRegexSort>, val dependencies: List<KExpr<KRegexSort>>, var next: Int = 0,
    )

    private fun compilationPlan(root: KExpr<KRegexSort>): Map<KExpr<KRegexSort>, List<KExpr<KRegexSort>>> {
        val result = linkedMapOf<KExpr<KRegexSort>, List<KExpr<KRegexSort>>>()
        val pending = ArrayDeque<KExpr<KRegexSort>>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            checkpoint()
            val expression = pending.removeLast()
            if (expression in result || expression in cache) continue
            val dependencies = islandDependencies(expression)
            preprocessing.reserve(1L + dependencies.size)
            result[expression] = dependencies
            pending.addAll(dependencies)
        }
        return result
    }

    private fun islandDependencies(root: KExpr<KRegexSort>): List<KExpr<KRegexSort>> {
        if (isBarrier(root)) return HybridSyntax.regexDependencies(root)
        val dependencies = linkedSetOf<KExpr<KRegexSort>>()
        val seen = hashSetOf<KExpr<KRegexSort>>()
        val pending = ArrayDeque<KExpr<KRegexSort>>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            val expression = pending.removeLast()
            if (!seen.add(expression)) continue
            preprocessing.reserve()
            when {
                expression in cache -> Unit
                isBarrier(expression) -> dependencies.add(expression)
                else -> {
                    val children = HybridSyntax.regexDependencies(expression)
                    preprocessing.reserve(children.size.toLong())
                    pending.addAll(children)
                }
            }
        }
        return dependencies.toList()
    }

    private fun isBarrier(expression: KExpr<KRegexSort>): Boolean = expression is KRegexComplementExpr ||
        expression is KRegexIntersectionExpr || expression is KRegexDifferenceExpr

    @Suppress("ComplexMethod")
    private fun compileReady(
        regex: KExpr<KRegexSort>, resolve: (KExpr<KRegexSort>) -> SymbolicDfa,
    ): SymbolicDfa = when (regex) {
        // Despite its historical class name, KRegexEpsilon denotes re.none.
        is KRegexEpsilon -> SymbolicDfa.none(limits)
        is KRegexAll -> SymbolicDfa.all(limits)
        is KRegexAllChar -> SymbolicDfa.range(0, MAX_STRING_CODE_POINT, limits)
        is KStringToRegexExpr -> {
            val literal = regex.arg as KStringLiteralExpr
            preprocessing.reserve(2L * literal.codePointCount)
            limits.checkLiteralSize(literal.codePointCount)
            SymbolicDfa.literal(literal.codePoints.toList(), limits)
        }
        is KRegexRangeExpr -> {
            val lower = regex.arg0 as KStringLiteralExpr
            val upper = regex.arg1 as KStringLiteralExpr
            if (lower.codePointCount != 1 || upper.codePointCount != 1) {
                SymbolicDfa.none(limits)
            } else {
                preprocessing.reserve(2)
                SymbolicDfa.range(lower.codePoints.single(), upper.codePoints.single(), limits)
            }
        }
        is KRegexIntersectionExpr -> resolve(regex.arg0).intersection(resolve(regex.arg1), checkpoint)
        is KRegexDifferenceExpr -> resolve(regex.arg0).intersection(resolve(regex.arg1).complement(), checkpoint)
        is KRegexComplementExpr -> resolve(regex.arg).complement()
        else -> compileIsland(regex, resolve)
    }

    private data class Fragment(val expression: KExpr<KRegexSort>, val from: Int, val to: Int)

    /** Thompson fragments are instantiated per occurrence; shared endpoints would change word equations. */
    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    private fun compileIsland(root: KExpr<KRegexSort>, resolve: (KExpr<KRegexSort>) -> SymbolicDfa): SymbolicDfa {
        val nfa = SymbolicNfa(limits)
        val start = nfa.addState()
        val end = nfa.addState()
        val pending = ArrayDeque<Fragment>()
        pending.addLast(Fragment(root, start, end))
        fun schedule(expression: KExpr<KRegexSort>, from: Int, to: Int) {
            preprocessing.reserve()
            pending.addLast(Fragment(expression, from, to))
        }
        while (pending.isNotEmpty()) {
            checkpoint()
            val (expression, from, to) = pending.removeLast()
            preprocessing.reserve()
            if (isBarrier(expression) || expression in cache) {
                val dfa = resolve(expression)
                val offset = nfa.append(dfa, checkpoint)
                nfa.addEpsilon(from, offset + dfa.initialState)
                for (accepting in dfa.acceptingStates) {
                    checkpoint()
                    nfa.addEpsilon(offset + accepting, to)
                }
                continue
            }
            when (expression) {
                is KRegexEpsilon -> Unit
                is KRegexAll -> {
                    val loop = nfa.addState()
                    nfa.addEpsilon(from, loop)
                    nfa.addTransition(loop, 0, MAX_STRING_CODE_POINT, loop)
                    nfa.addEpsilon(loop, to)
                }
                is KRegexAllChar -> nfa.addTransition(from, 0, MAX_STRING_CODE_POINT, to)
                is KStringToRegexExpr -> literal(nfa, from, to, expression.arg as KStringLiteralExpr)
                is KRegexRangeExpr -> {
                    val lower = expression.arg0 as KStringLiteralExpr
                    val upper = expression.arg1 as KStringLiteralExpr
                    if (lower.codePointCount == 1 && upper.codePointCount == 1) {
                        preprocessing.reserve(2)
                        val first = lower.codePoints.single()
                        val last = upper.codePoints.single()
                        if (first <= last) nfa.addTransition(from, first, last, to)
                    }
                }
                is KRegexConcatExpr -> {
                    val middle = nfa.addState()
                    schedule(expression.arg1, middle, to)
                    schedule(expression.arg0, from, middle)
                }
                is KRegexUnionExpr -> {
                    schedule(expression.arg0, from, to)
                    if (expression.arg0 != expression.arg1) schedule(expression.arg1, from, to)
                }
                is KRegexStarExpr -> {
                    if (expression.arg is KRegexStarExpr) {
                        schedule(expression.arg, from, to)
                    } else {
                        repeatFragment(nfa, expression.arg, from, to, allowEmpty = true, schedule = ::schedule)
                    }
                }
                is KRegexCrossExpr ->
                    repeatFragment(nfa, expression.arg, from, to, allowEmpty = false, schedule = ::schedule)
                is KRegexOptionExpr -> {
                    nfa.addEpsilon(from, to)
                    schedule(expression.arg, from, to)
                }
                is KRegexPowerExpr ->
                    repeatChain(nfa, expression.arg, expression.power, expression.power, from, to, ::schedule)
                is KRegexLoopExpr ->
                    repeatChain(nfa, expression.arg, expression.from, expression.to, from, to, ::schedule)
                else -> error("Unsupported regex passed the syntax boundary")
            }
        }
        determinizationCount++
        return nfa.determinize(setOf(start), setOf(end), checkpoint)
    }

    private fun literal(nfa: SymbolicNfa, from: Int, to: Int, literal: KStringLiteralExpr) {
        preprocessing.reserve(2L * literal.codePointCount)
        val points = literal.codePoints
        var state = from
        for ((index, point) in points.withIndex()) {
            checkpoint()
            val next = if (index == points.lastIndex) to else nfa.addState()
            nfa.addTransition(state, point, point, next)
            state = next
        }
        if (points.isEmpty()) nfa.addEpsilon(from, to)
    }

    @Suppress("LongParameterList") // A repeat fragment shares the island graph and endpoint IDs.
    private fun repeatFragment(
        nfa: SymbolicNfa, expression: KExpr<KRegexSort>, from: Int, to: Int, allowEmpty: Boolean,
        schedule: (KExpr<KRegexSort>, Int, Int) -> Unit,
    ) {
        val start = nfa.addState()
        val end = nfa.addState()
        nfa.addEpsilon(from, start)
        nfa.addEpsilon(end, to)
        nfa.addEpsilon(end, start)
        if (allowEmpty) nfa.addEpsilon(from, to)
        schedule(expression, start, end)
    }

    @Suppress("LongParameterList") // A repeat fragment shares the island graph and endpoint IDs.
    private fun repeatChain(
        nfa: SymbolicNfa, expression: KExpr<KRegexSort>, minimum: Int, maximum: Int, from: Int, to: Int,
        schedule: (KExpr<KRegexSort>, Int, Int) -> Unit,
    ) {
        require(minimum >= 0 && maximum >= 0)
        if (minimum > maximum) return
        // Reserve expansion before constructing a potentially enormous task/state list.
        preprocessing.reserve(maximum.toLong())
        if (maximum == 0) {
            nfa.addEpsilon(from, to)
            return
        }
        var state = from
        repeat(maximum) { index ->
            checkpoint()
            val next = if (index == maximum - 1) to else nfa.addState()
            if (index >= minimum) nfa.addEpsilon(state, next)
            schedule(expression, state, next)
            state = next
        }
    }

    companion object {
        private const val MAX_CACHED_ROOTS = 64
    }
}

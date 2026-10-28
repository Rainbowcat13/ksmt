package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.sort.KRegexSort

/** Bounds both state-elimination work and expression depth before handing a regex to a native solver. */
internal const val DEFAULT_REGEX_EMISSION_BUDGET = 100_000
private const val MAX_EMITTED_REGEX_DEPTH = 128
private const val GNFA_BOUNDARY_STATES = 2L

internal class RegexEmissionBudget(
    private val checkpoint: () -> Unit,
    private val limit: Int = DEFAULT_REGEX_EMISSION_BUDGET,
) {
    private var used = 0
    fun consume(count: Int = 1) {
        checkpoint()
        require(count >= 0)
        if (count > limit - used) throw AutomataLimitException("regex emission budget exceeded")
        used += count
    }
}

/** Exact GNFA elimination over productive states, including all Unicode interval labels. */
internal fun SymbolicDfa.toRegex(
    ctx: KContext,
    budget: RegexEmissionBudget,
): KExpr<KRegexSort> = DfaRegexEmitter(this, ctx, budget).emit()

private data class RegexLabel(val expression: KExpr<KRegexSort>, val depth: Int)

private class DfaRegexEmitter(
    private val automaton: SymbolicDfa,
    private val ctx: KContext,
    private val budget: RegexEmissionBudget,
) {
    private val edges = linkedMapOf<Pair<Int, Int>, RegexLabel>()
    private val epsilon = RegexLabel(ctx.mkStringToRegexNoSimplify(ctx.mkStringLiteral("")), 1)
    private val storage = automaton.limits.storageBudget()

    private fun consume(count: Int = 1) {
        budget.consume(count)
        storage.reserve(count.toLong())
    }

    private fun label(expression: KExpr<KRegexSort>, depth: Int): RegexLabel {
        consume()
        if (depth > MAX_EMITTED_REGEX_DEPTH) throw AutomataLimitException("regex emission depth exceeded")
        return RegexLabel(expression, depth)
    }

    private fun add(from: Int, to: Int, value: RegexLabel) {
        consume()
        val key = from to to
        val old = edges[key]
        edges[key] = if (old == null || old.expression == value.expression) value else {
            label(ctx.mkRegexUnionNoSimplify(old.expression, value.expression), maxOf(old.depth, value.depth) + 1)
        }
    }

    private fun concat(left: RegexLabel, right: RegexLabel): RegexLabel {
        if (left == epsilon) return right
        if (right == epsilon) return left
        return label(ctx.mkRegexConcatNoSimplify(left.expression, right.expression), maxOf(left.depth, right.depth) + 1)
    }

    fun emit(): KExpr<KRegexSort> {
        val productive = productiveStates()
        if (automaton.initialState !in productive) return ctx.mkRegexEpsilon()
        automaton.limits.checkStates(productive.size + GNFA_BOUNDARY_STATES)
        val start = automaton.transitions.size
        val end = start + 1
        add(start, automaton.initialState, epsilon)
        automaton.acceptingStates.forEach { add(it, end, epsilon) }
        for (source in productive) {
            for (edge in automaton.transitions[source]) {
                consume()
                if (edge.target !in productive) continue
                val lower = ctx.mkStringLiteral(intArrayOf(edge.lower))
                val expression = if (edge.lower == edge.upper) ctx.mkStringToRegexNoSimplify(lower) else {
                    ctx.mkRegexRangeNoSimplify(lower, ctx.mkStringLiteral(intArrayOf(edge.upper)))
                }
                add(source, edge.target, label(expression, 1))
            }
        }
        for (state in productive) eliminate(state)
        return edges[start to end]?.expression ?: ctx.mkRegexEpsilon()
    }

    private fun eliminate(state: Int) {
        consume()
        val incoming = arrayListOf<Pair<Int, RegexLabel>>()
        val outgoing = arrayListOf<Pair<Int, RegexLabel>>()
        for ((pair, value) in edges) {
            consume()
            if (pair.second == state && pair.first != state) incoming += pair.first to value
            if (pair.first == state && pair.second != state) outgoing += pair.second to value
        }
        val loop = edges[state to state]?.let { label(ctx.mkRegexStarNoSimplify(it.expression), it.depth + 1) }
            ?: epsilon
        for ((source, before) in incoming) {
            for ((target, after) in outgoing) {
                consume()
                add(source, target, concat(concat(before, loop), after))
            }
        }
        val iterator = edges.keys.iterator()
        while (iterator.hasNext()) {
            consume()
            val pair = iterator.next()
            if (pair.first == state || pair.second == state) iterator.remove()
        }
    }

    private fun productiveStates(): Set<Int> {
        consume(automaton.transitions.size)
        val reverse = Array(automaton.transitions.size) { arrayListOf<Int>() }
        automaton.transitions.forEachIndexed { source, outgoing ->
            outgoing.forEach { edge ->
                consume()
                reverse[edge.target] += source
            }
        }
        val productive = linkedSetOf<Int>()
        val pending = ArrayDeque<Int>()
        pending.addAll(automaton.acceptingStates)
        while (pending.isNotEmpty()) {
            consume()
            val state = pending.removeFirst()
            if (productive.add(state)) pending.addAll(reverse[state])
        }
        return productive
    }
}

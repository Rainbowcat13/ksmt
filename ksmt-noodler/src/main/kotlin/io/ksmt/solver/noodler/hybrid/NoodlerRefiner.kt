package io.ksmt.solver.noodler.hybrid

import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort

internal sealed class StringTermPart {
    data class Variable(val declaration: KDecl<KStringSort>) : StringTermPart()
    data class Literal(val codePoints: List<Int>) : StringTermPart()
}

internal data class StringEquation(val left: List<StringTermPart>, val right: List<StringTermPart>)

internal data class RefinementResult(
    val languages: Map<KDecl<KStringSort>, SymbolicDfa>,
    val changed: Boolean,
    val contradiction: Boolean,
)

/**
 * One sound propagation sweep. Occurrences are projected independently, so the returned languages
 * overapproximate joint solutions; nonempty languages never establish SAT. In particular, repeated
 * variables and correlations between equations remain obligations of exact candidate realization.
 * Quotients use complete boundary reachability, preserving cycles omitted by simple-path extraction.
 */
internal class NoodlerRefiner(
    private val checkpoint: () -> Unit = {},
    private val preprocessing: PreprocessingBudget? = null,
    private val limits: AutomataLimits = AutomataLimits(),
) {
    // The local projection functions share this sweep's current languages and monotone change flag.
    @Suppress("ComplexMethod")
    fun refine(
        languages: Map<KDecl<KStringSort>, SymbolicDfa>,
        equations: List<StringEquation>,
    ): RefinementResult {
        preprocessing?.reserve(languages.size.toLong())
        val result = languages.mapValuesTo(linkedMapOf()) { (_, language) -> language.withLimits(limits) }
        for (equation in equations) {
            includeVariables(equation.left, result)
            includeVariables(equation.right, result)
        }
        if (result.values.any { it.isEmpty(checkpoint) }) return RefinementResult(result, false, true)
        var changed = false

        fun language(parts: List<StringTermPart>, from: Int = 0, endExclusive: Int = parts.size): SymbolicDfa {
            var language: SymbolicDfa? = null
            for (index in from until endExclusive) {
                checkpoint()
                preprocessing?.reserve()
                val next = when (val part = parts[index]) {
                    is StringTermPart.Variable -> result.getValue(part.declaration)
                    is StringTermPart.Literal -> SymbolicDfa.literal(part.codePoints, limits)
                }
                language = language?.concat(next, checkpoint) ?: next
            }
            return language ?: SymbolicDfa.epsilon(limits)
        }

        fun propagate(parts: List<StringTermPart>, opposite: SymbolicDfa): Boolean {
            for ((index, part) in parts.withIndex()) {
                checkpoint()
                if (part !is StringTermPart.Variable) continue
                val prefix = language(parts, endExclusive = index)
                val suffix = language(parts, from = index + 1)
                val possible = opposite.factorBetween(prefix, suffix, checkpoint)
                val old = result.getValue(part.declaration)
                val refined = old.intersection(possible, checkpoint)
                if (refined.isEmpty(checkpoint)) {
                    result[part.declaration] = refined
                    return false
                }
                if (!old.equivalentTo(refined, checkpoint)) {
                    result[part.declaration] = refined
                    changed = true
                }
            }
            return true
        }

        for (original in equations) {
            checkpoint()
            // Word concatenation is cancellative even when a shared boundary contains a variable.
            // Regular projections treat occurrences independently and cannot recover this correlation.
            val equation = cancelBoundaries(original) ?: return RefinementResult(result, changed, true)
            if (equation.left.isEmpty() && equation.right.isEmpty()) continue
            val left = language(equation.left)
            val right = language(equation.right)
            if (left.intersection(right, checkpoint).isEmpty(checkpoint) ||
                !propagate(equation.left, right) || !propagate(equation.right, left)
            ) {
                return RefinementResult(result, changed, true)
            }
        }
        return RefinementResult(result, changed, false)
    }

    /** Remove equal words at either boundary; incompatible literal boundaries prove inconsistency. */
    private fun cancelBoundaries(equation: StringEquation): StringEquation? {
        preprocessing?.reserve(2L * (equation.left.size.toLong() + equation.right.size) + 2)
        fun parts(source: List<StringTermPart>): ArrayDeque<StringTermPart> = ArrayDeque<StringTermPart>().also {
            for (part in source) {
                checkpoint()
                if (part !is StringTermPart.Literal || part.codePoints.isNotEmpty()) it.addLast(part)
            }
        }
        val left = parts(equation.left)
        val right = parts(equation.right)

        if (!cancelBoundary(left, right, fromStart = true) || !cancelBoundary(left, right, fromStart = false)) {
            return null
        }
        return StringEquation(left.toList(), right.toList())
    }

    @Suppress("NestedBlockDepth") // The two boundary kinds are matched without copying code-point arrays.
    private fun cancelBoundary(
        left: ArrayDeque<StringTermPart>, right: ArrayDeque<StringTermPart>, fromStart: Boolean,
    ): Boolean {
        while (left.isNotEmpty() && right.isNotEmpty()) {
            checkpoint()
            val one = if (fromStart) left.first() else left.last()
            val two = if (fromStart) right.first() else right.last()
            if (one is StringTermPart.Variable && two is StringTermPart.Variable) {
                if (one.declaration != two.declaration) return true
                removeBoundary(left, fromStart)
                removeBoundary(right, fromStart)
            } else if (one is StringTermPart.Literal && two is StringTermPart.Literal) {
                val count = minOf(one.codePoints.size, two.codePoints.size)
                for (offset in 0 until count) {
                    checkpoint()
                    val oneIndex = if (fromStart) offset else one.codePoints.lastIndex - offset
                    val twoIndex = if (fromStart) offset else two.codePoints.lastIndex - offset
                    if (one.codePoints[oneIndex] != two.codePoints[twoIndex]) return false
                }
                consumeLiteral(left, one, count, fromStart)
                consumeLiteral(right, two, count, fromStart)
            } else {
                return true
            }
        }
        return true
    }

    private fun removeBoundary(word: ArrayDeque<StringTermPart>, fromStart: Boolean) {
        if (fromStart) word.removeFirst() else word.removeLast()
    }

    private fun consumeLiteral(
        word: ArrayDeque<StringTermPart>, literal: StringTermPart.Literal, count: Int, fromStart: Boolean,
    ) {
        removeBoundary(word, fromStart)
        val points = literal.codePoints
        if (count == points.size) return
        preprocessing?.reserve()
        val remaining = StringTermPart.Literal(
            if (fromStart) points.subList(count, points.size) else points.subList(0, points.size - count)
        )
        if (fromStart) word.addFirst(remaining) else word.addLast(remaining)
    }

    private fun includeVariables(parts: List<StringTermPart>, languages: MutableMap<KDecl<KStringSort>, SymbolicDfa>) {
        for (part in parts) {
            checkpoint()
            if (part is StringTermPart.Variable && part.declaration !in languages) {
                preprocessing?.reserve()
                languages[part.declaration] = SymbolicDfa.all(limits)
            }
        }
    }
}

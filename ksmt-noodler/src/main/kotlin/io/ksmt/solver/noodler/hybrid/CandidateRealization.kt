package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KExpr
import io.ksmt.expr.KIntNumExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.yices.KYicesSolver
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KStringSort
import io.ksmt.utils.ArithUtils.bigIntegerValue

/** Exact realization at one length vector. No constraint here is asserted in the unbounded context. */
@Suppress("LongParameterList") // The independent resource allowances belong to their surrounding check.
internal class CandidateRealization(
    private val ctx: KContext,
    private val solver: KYicesSolver,
    lengths: Map<KDecl<KStringSort>, Int>,
    private val checkpoint: () -> Unit,
    private val maxPositions: Int,
    private val maxEncodingConstraints: Int,
    private val preprocessing: PreprocessingBudget = PreprocessingBudget(checkpoint = checkpoint),
) {
    private val characters = linkedMapOf<KDecl<KStringSort>, List<KExpr<KIntSort>>>()
    var encodedConstraints = 0L
        private set
    var membershipTransitionsConsidered = 0L
        private set
    var membershipTransitionsEncoded = 0L
        private set
    var membershipPruningFallbacks = 0
        private set

    init {
        for ((declaration, length) in lengths) {
            checkpoint()
            reserve(2L * length)
            characters[declaration] = List(length) {
                checkpoint()
                ctx.mkFreshConst("noodler.character", ctx.intSort).also { character ->
                    solver.assert(ctx.mkArithGe(character, ctx.mkIntNum(0)))
                    solver.assert(ctx.mkArithLe(character, ctx.mkIntNum(MAX_STRING_CODE_POINT)))
                }
            }
        }
    }

    fun assertAtom(atom: StringAtom, positive: Boolean) {
        checkpoint()
        val constraint = when (atom) {
            is StringAtom.Equality -> {
                val left = positions(atom.equation.left)
                val right = positions(atom.equation.right)
                val equality = if (left.size != right.size) {
                    ctx.falseExpr
                } else {
                    reserve(left.size.toLong())
                    ctx.mkAnd(left.zip(right) { one, two ->
                        checkpoint()
                        ctx.mkEq(one, two)
                    })
                }
                if (positive) equality else ctx.mkNot(equality)
            }
            is StringAtom.Membership -> membership(positions(atom.term), atom.language, positive)
        }
        solver.assert(constraint)
    }

    private fun positions(term: List<StringTermPart>): List<KExpr<KIntSort>> {
        val size = term.sumOf {
            checkpoint()
            when (it) {
                is StringTermPart.Variable -> characters.getValue(it.declaration).size.toLong()
                is StringTermPart.Literal -> it.codePoints.size.toLong()
            }
        }
        if (size > maxPositions) throw HybridIncomplete("character realization resource limit ($maxPositions)")
        preprocessing.reserve(size)
        return term.flatMap {
            checkpoint()
            when (it) {
                is StringTermPart.Variable -> characters.getValue(it.declaration)
                is StringTermPart.Literal -> it.codePoints.map(ctx::mkIntNum)
            }
        }
    }

    private fun membership(
        word: List<KExpr<KIntSort>>, language: SymbolicDfa, positive: Boolean,
    ): KExpr<KBoolSort> {
        val fullTransitions = language.transitions.sumOf { it.size.toLong() }
        membershipTransitionsConsidered += fullTransitions * word.size
        if (language.acceptingStates.isEmpty()) return ctx.mkBool(!positive)
        if (language.acceptingStates.size == language.transitions.size) return ctx.mkBool(positive)
        if (word.isEmpty()) {
            return ctx.mkBool((language.initialState in language.acceptingStates) == positive)
        }
        val plan = transitionPlan(word, language, positive)
        if (plan == null) {
            val accepts = completeMembership(word, language)
            return if (positive) accepts else ctx.mkNot(accepts)
        }
        if (!plan.possible) return ctx.falseExpr
        var state: KExpr<KIntSort> = ctx.mkIntNum(language.initialState)
        for ((position, edges) in plan.steps.withIndex()) {
            checkpoint()
            reserve(edges.size.toLong() * CONSTRAINTS_PER_EDGE + 1)
            val next = ctx.mkFreshConst("noodler.dfa.state", ctx.intSort)
            val character = word[position]
            val cases = edges.map { (source, edge) ->
                checkpoint()
                transitionCase(state, next, character, source, edge)
            }
            membershipTransitionsEncoded += edges.size
            solver.assert(ctx.mkOr(cases))
            state = next
        }
        // The final layer contains accepting targets for positive membership and rejecting targets
        // for negative membership. The whole pruned circuit therefore asserts the chosen polarity.
        return ctx.trueExpr
    }

    @Suppress("SwallowedException") // Exhausting optional analysis deliberately selects the exact full encoder.
    private fun transitionPlan(
        word: List<KExpr<KIntSort>>, language: SymbolicDfa, positive: Boolean,
    ): MembershipTransitionPlan? {
        val allowance = minOf(preprocessing.remaining / PRUNING_BUDGET_SHARE, MAX_PRUNING_UNITS)
        // Avoid allocating the character array when even the sparse layer containers cannot fit.
        if (PRUNING_LAYER_STORAGE * word.size + PRUNING_STORAGE_OVERHEAD > allowance) {
            membershipPruningFallbacks++
            return null
        }
        val budget = preprocessing.limited(allowance)
        return try {
            budget.reserve(word.size.toLong())
            val known = IntArray(word.size) { index ->
                checkpoint()
                (word[index] as? KIntNumExpr)?.bigIntegerValue?.intValueExact() ?: -1
            }
            language.planMembershipTransitions(known, positive, budget, checkpoint)
        } catch (ex: PreprocessingLimitException) {
            // Only optional planning is abandoned. The full encoder retains exact semantics.
            checkpoint()
            membershipPruningFallbacks++
            null
        }
    }

    private fun completeMembership(word: List<KExpr<KIntSort>>, language: SymbolicDfa): KExpr<KBoolSort> {
        var state: KExpr<KIntSort> = ctx.mkIntNum(language.initialState)
        val stepCost = language.transitions.sumOf { it.size.toLong() } * CONSTRAINTS_PER_EDGE + 1L
        reserve(stepCost * word.size + language.acceptingStates.size)
        for (character in word) {
            checkpoint()
            val next = ctx.mkFreshConst("noodler.dfa.state", ctx.intSort)
            val cases = language.transitions.flatMapIndexed { source, transitions ->
                transitions.map { edge ->
                    checkpoint()
                    transitionCase(state, next, character, source, edge)
                }
            }
            membershipTransitionsEncoded += cases.size
            // Completeness/determinism makes this definition exact under both membership polarities.
            solver.assert(ctx.mkOr(cases))
            state = next
        }
        return ctx.mkOr(language.acceptingStates.map { ctx.mkEq(state, ctx.mkIntNum(it)) })
    }

    private fun transitionCase(
        state: KExpr<KIntSort>, next: KExpr<KIntSort>, character: KExpr<KIntSort>, source: Int, edge: Transition,
    ): KExpr<KBoolSort> = ctx.mkAnd(
        ctx.mkEq(state, ctx.mkIntNum(source)),
        ctx.mkArithGe(character, ctx.mkIntNum(edge.lower)),
        ctx.mkArithLe(character, ctx.mkIntNum(edge.upper)),
        ctx.mkEq(next, ctx.mkIntNum(edge.target)),
    )

    private fun reserve(count: Long) {
        checkpoint()
        if (count > maxEncodingConstraints.toLong() - encodedConstraints) {
            throw HybridIncomplete("character encoding resource limit ($maxEncodingConstraints)")
        }
        encodedConstraints += count
    }

    fun strings(model: KModel): Map<KDecl<KStringSort>, IntArray> = characters.mapValues { (_, positions) ->
        IntArray(positions.size) { index ->
            checkpoint()
            val value = model.eval(positions[index], isComplete = true) as? KIntNumExpr
                ?: throw HybridIncomplete("Yices did not assign a character")
            value.bigIntegerValue.intValueExact()
        }
    }

    companion object {
        private const val CONSTRAINTS_PER_EDGE = 4L
        private const val PRUNING_BUDGET_SHARE = 4
        private const val MAX_PRUNING_UNITS = 100_000
        private const val PRUNING_LAYER_STORAGE = 3L
        private const val PRUNING_STORAGE_OVERHEAD = 3L
    }
}

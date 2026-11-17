package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KAddArithExpr
import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFalse
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KTrue
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KStringSort
import io.ksmt.utils.ArithUtils.bigIntegerValue
import java.math.BigInteger

internal data class PreflightProof(val reason: String, val periodicCertificates: Int = 0)

/** Optional proofs over necessary conjuncts only. Failure to prove anything has no semantic effect. */
internal class SolverFreePreflight(
    private val ctx: KContext,
    private val abstraction: StringAbstraction,
    private val checkpoint: () -> Unit,
    private val budget: PreprocessingBudget,
    private val limits: AutomataLimits,
    private val periodicReasoning: Boolean,
) {
    private data class Literal(val expression: KExpr<KBoolSort>, val positive: Boolean)
    private data class Sum(val terms: Map<KExpr<*>, BigInteger>, val constant: BigInteger)

    @Suppress("ComplexMethod", "NestedBlockDepth")
    fun prove(input: List<KExpr<KBoolSort>>): PreflightProof? {
        val literals = necessaryLiterals(input)
        val polarities = hashMapOf<KExpr<KBoolSort>, Boolean>()
        for ((expression, positive) in literals) {
            checkpoint()
            if ((expression is KFalse && positive) || (expression is KTrue && !positive)) {
                return PreflightProof("false necessary literal")
            }
            val previous = polarities.put(expression, positive)
            if (previous != null && previous != positive) return PreflightProof("opposite necessary literals")
        }
        val languages = linkedMapOf<KDecl<KStringSort>, SymbolicDfa>()
        val equations = arrayListOf<StringEquation>()
        val disequalities = arrayListOf<StringEquation>()
        val equalSums = hashMapOf<Sum, Sum>()
        fun root(value: Sum): Sum {
            var current = value
            while (equalSums[current] != null) {
                checkpoint()
                current = equalSums.getValue(current)
            }
            return current
        }
        for ((expression, positive) in literals) {
            budget.reserve()
            when (val atom = abstraction.atoms[expression]) {
                is StringAtom.Equality -> if (positive) equations += atom.equation else disequalities += atom.equation
                is StringAtom.Membership -> {
                    val language = if (positive) atom.language else atom.language.complement()
                    if (language.isEmpty(checkpoint)) return PreflightProof("empty necessary regular language")
                    val variable = atom.term.singleOrNull() as? StringTermPart.Variable
                    if (variable == null) {
                        // A fresh word names exactly the constrained concatenation, not its factors.
                        val auxiliary = ctx.mkFreshConstDecl("noodler.preflight.word", ctx.stringSort)
                        languages[auxiliary] = language
                        equations += StringEquation(atom.term, listOf(StringTermPart.Variable(auxiliary)))
                    } else {
                        val old = languages[variable.declaration]
                        languages[variable.declaration] = old?.intersection(language, checkpoint) ?: language
                    }
                }
                null -> if (positive && expression is KEqExpr<*> && expression.lhs.sort is KIntSort) {
                    // Translate the already compiled equality; no generated implication is assumed true.
                    val equality = abstraction.translate(expression) as? KEqExpr<*> ?: continue
                    val left = root(sum(equality.lhs))
                    val right = root(sum(equality.rhs))
                    if (left != right) equalSums[left] = right
                }
            }
        }
        val refined = NoodlerRefiner(checkpoint, budget, limits).refine(languages, equations)
        if (refined.contradiction) return PreflightProof("incompatible necessary string constraints")
        if (!periodicReasoning) return null
        for (equation in disequalities) {
            budget.reserve()
            // A common-period certificate is sufficient only with a proof of equal lengths.
            val left = sum(abstraction.length(equation.left, budget))
            val right = sum(abstraction.length(equation.right, budget))
            if (root(left) != root(right)) continue
            if (PeriodicWordEquality.provesLengthEquivalence(equation, refined.languages, checkpoint, budget)) {
                return PreflightProof("certified equal-length periodic words cannot differ", periodicCertificates = 1)
            }
        }
        return null
    }

    /** No traversal under disjunctions, implications or arbitrary Boolean equalities. */
    private fun necessaryLiterals(input: List<KExpr<KBoolSort>>): List<Literal> {
        val pending = ArrayDeque<Literal>()
        val result = arrayListOf<Literal>()
        input.forEach { pending.addLast(Literal(it, true)) }
        while (pending.isNotEmpty()) {
            budget.reserve()
            val literal = pending.removeLast()
            when (val expression = literal.expression) {
                is KAndExpr -> if (literal.positive) {
                    budget.reserve(expression.args.size.toLong())
                    expression.args.forEach { pending.addLast(Literal(it, true)) }
                } else result += literal
                is KNotExpr -> pending.addLast(Literal(expression.arg, !literal.positive))
                else -> result += literal
            }
        }
        return result
    }

    /** Canonical addition bags prove permutations of length sums equal without any arithmetic search. */
    private fun sum(expression: KExpr<*>): Sum {
        val terms = hashMapOf<KExpr<*>, BigInteger>()
        var constant = BigInteger.ZERO
        val pending = ArrayDeque<KExpr<*>>()
        pending.addLast(expression)
        while (pending.isNotEmpty()) {
            budget.reserve()
            when (val part = pending.removeLast()) {
                is KAddArithExpr<*> -> {
                    budget.reserve(part.args.size.toLong())
                    pending.addAll(part.args)
                }
                is KIntNumExpr -> constant += part.bigIntegerValue
                else -> terms[part] = (terms[part] ?: BigInteger.ZERO) + BigInteger.ONE
            }
        }
        return Sum(terms, constant)
    }
}

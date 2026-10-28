package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KTrue
import io.ksmt.expr.rewrite.simplify.simplifyExpr
import io.ksmt.expr.rewrite.simplify.simplifyStringConcatBasic
import io.ksmt.solver.model.KFuncInterp
import io.ksmt.solver.model.KModelEvaluator
import io.ksmt.solver.model.KModelImpl
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KSort

/** Fully detached model. Ground membership needs evaluation beyond the core string simplifier. */
internal class NoodlerYicesModel(
    ctx: KContext, interpretations: Map<KDecl<*>, KFuncInterp<*>>,
    private val limits: AutomataLimits = AutomataLimits(),
) :
    KModelImpl(ctx, interpretations, emptyMap()) {
    private val partial by lazy { evaluator(complete = false) }
    private val complete by lazy { evaluator(complete = true) }

    override fun <T : KSort> eval(expr: KExpr<T>, isComplete: Boolean): KExpr<T> {
        ctx.ensureContextMatch(expr)
        return (if (isComplete) complete else partial).apply(expr)
    }

    fun validates(
        assertions: List<KExpr<KBoolSort>>, checkpoint: () -> Unit,
        budget: PreprocessingBudget = PreprocessingBudget(checkpoint = checkpoint),
    ): Boolean {
        val validator = evaluator(complete = true, checkpoint = checkpoint, budget = budget)
        return assertions.all {
            checkpoint()
            validator.apply(it) is KTrue
        }
    }

    private fun evaluator(
        complete: Boolean, checkpoint: () -> Unit = {}, budget: PreprocessingBudget? = null,
    ): KModelEvaluator =
        object : KModelEvaluator(ctx, this@NoodlerYicesModel, complete) {
        private val regex = RegexAutomata(checkpoint, budget ?: PreprocessingBudget(checkpoint = checkpoint), limits)

        override fun <T : KSort> exprTransformationRequired(expr: KExpr<T>): Boolean {
            checkpoint()
            budget?.reserve()
            return super.exprTransformationRequired(expr)
        }

        override fun transform(expr: KStringConcatExpr) = simplifyExpr(expr, expr.arg0, expr.arg1) { lhs, rhs ->
            if (lhs is KStringLiteralExpr && rhs is KStringLiteralExpr) {
                // The nonrecursive core evaluator still copies every intermediate literal. Charge
                // both defensive input copies, the merged array and its immutable literal copy.
                budget?.reserve(CONCAT_CODE_POINT_COPIES * (lhs.codePointCount.toLong() + rhs.codePointCount))
            }
            simplifyStringConcatBasic(lhs, rhs, ::mkStringConcatNoSimplify)
        }

        override fun transform(expr: KStringInRegexExpr): KExpr<KBoolSort> =
            simplifyExpr(expr, expr.arg0, expr.arg1) { word, language ->
                if (word is KStringLiteralExpr) {
                    budget?.reserve(word.codePointCount.toLong())
                    ctx.mkBool(regex.compile(language).accepts(word.codePoints, checkpoint))
                } else {
                    ctx.mkStringInRegexNoSimplify(word, language)
                }
            }
    }

    companion object {
        private const val CONCAT_CODE_POINT_COPIES = 3L
    }
}

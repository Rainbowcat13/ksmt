package io.ksmt.solver.noodler.hybrid

import io.ksmt.expr.KAddArithExpr
import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KApp
import io.ksmt.expr.KConst
import io.ksmt.expr.KDistinctExpr
import io.ksmt.expr.KDivArithExpr
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFalse
import io.ksmt.expr.KGeArithExpr
import io.ksmt.expr.KGtArithExpr
import io.ksmt.expr.KImpliesExpr
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KIteExpr
import io.ksmt.expr.KLeArithExpr
import io.ksmt.expr.KLtArithExpr
import io.ksmt.expr.KModIntExpr
import io.ksmt.expr.KMulArithExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KOrExpr
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
import io.ksmt.expr.KRemIntExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringLenExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KStringToRegexExpr
import io.ksmt.expr.KSubArithExpr
import io.ksmt.expr.KTrue
import io.ksmt.expr.KUnaryMinusArithExpr
import io.ksmt.expr.KXorExpr
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KRegexSort
import io.ksmt.sort.KSort
import io.ksmt.sort.KStringSort
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.utils.ArithUtils.bigIntegerValue

/** The single syntax boundary used by abstraction and read-only benchmark eligibility checks. */
internal object HybridSyntax {
    @Suppress("ComplexMethod")
    fun dependencies(expr: KExpr<*>): List<KExpr<*>> {
        if (expr.sort is KRegexSort) {
            @Suppress("UNCHECKED_CAST")
            return regexDependencies(expr as KExpr<KRegexSort>)
        }
        if (expr is KConst<*> && isValueSort(expr.sort)) return emptyList()
        when (expr) {
            is KStringLiteralExpr, is KIntNumExpr, is KTrue, is KFalse -> return emptyList()
            is KStringConcatExpr -> return listOf(expr.arg0, expr.arg1)
            is KStringLenExpr -> return listOf(expr.arg)
            is KStringInRegexExpr -> return listOf(expr.arg0, expr.arg1)
            is KIteExpr<*> -> if (expr.sort is KStringSort) {
                return listOf(expr.condition, expr.trueBranch, expr.falseBranch)
            }
            else -> Unit
        }
        if (expr.sort !is KIntSort && expr.sort !is KBoolSort) unsupported(expr)
        val app = expr as? KApp<*, *> ?: unsupported(expr)
        if (expr is KEqExpr<*> || expr is KDistinctExpr<*>) {
            if (app.args.any { !isValueSort(it.sort) }) unsupported(expr)
        }
        when (expr) {
            is KAndExpr, is KOrExpr, is KNotExpr, is KImpliesExpr, is KXorExpr,
            is KEqExpr<*>, is KDistinctExpr<*>, is KIteExpr<*>,
            is KAddArithExpr<*>, is KSubArithExpr<*>, is KUnaryMinusArithExpr<*>,
            is KLeArithExpr<*>, is KLtArithExpr<*>, is KGeArithExpr<*>, is KGtArithExpr<*> -> Unit
            is KMulArithExpr<*> -> if (expr.args.count { it !is KIntNumExpr } > 1) unsupported(expr)
            is KDivArithExpr<*> -> requireConstantDivisor(expr.rhs)
            is KModIntExpr -> requireConstantDivisor(expr.rhs)
            is KRemIntExpr -> requireConstantDivisor(expr.rhs)
            else -> unsupported(expr)
        }
        return app.args
    }

    @Suppress("ComplexMethod")
    fun regexDependencies(regex: KExpr<KRegexSort>): List<KExpr<KRegexSort>> = when (regex) {
        is KRegexEpsilon, is KRegexAll, is KRegexAllChar -> emptyList()
        is KStringToRegexExpr -> {
            if (regex.arg !is KStringLiteralExpr) unsupported(regex)
            emptyList()
        }
        is KRegexRangeExpr -> {
            if (regex.arg0 !is KStringLiteralExpr || regex.arg1 !is KStringLiteralExpr) unsupported(regex)
            emptyList()
        }
        is KRegexConcatExpr -> listOf(regex.arg0, regex.arg1)
        is KRegexUnionExpr -> listOf(regex.arg0, regex.arg1)
        is KRegexIntersectionExpr -> listOf(regex.arg0, regex.arg1)
        is KRegexDifferenceExpr -> listOf(regex.arg0, regex.arg1)
        is KRegexComplementExpr -> listOf(regex.arg)
        is KRegexStarExpr -> listOf(regex.arg)
        is KRegexCrossExpr -> listOf(regex.arg)
        is KRegexOptionExpr -> listOf(regex.arg)
        is KRegexPowerExpr -> {
            if (regex.power < 0) unsupported(regex)
            listOf(regex.arg)
        }
        is KRegexLoopExpr -> {
            if (regex.from < 0 || regex.to < 0) unsupported(regex)
            listOf(regex.arg)
        }
        else -> unsupported(regex)
    }

    private fun requireConstantDivisor(expr: KExpr<*>) {
        if (expr !is KIntNumExpr || expr.bigIntegerValue.signum() == 0) unsupported(expr)
    }

    private fun isValueSort(sort: KSort): Boolean =
        sort is KBoolSort || sort is KIntSort || sort is KStringSort

    private fun unsupported(expr: KExpr<*>): Nothing = throw KSolverUnsupportedFeatureException(
        "unsupported expression: ${expr::class.simpleName}; expected QF strings, LIA and ground regex"
    )
}

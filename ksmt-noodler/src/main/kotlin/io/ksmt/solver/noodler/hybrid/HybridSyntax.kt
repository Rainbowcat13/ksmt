package io.ksmt.solver.noodler.hybrid

import io.ksmt.expr.KAddArithExpr
import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KApp
import io.ksmt.expr.KBitVecValue
import io.ksmt.expr.KBv2IntExpr
import io.ksmt.expr.KBvAddExpr
import io.ksmt.expr.KBvAddNoOverflowExpr
import io.ksmt.expr.KBvAddNoUnderflowExpr
import io.ksmt.expr.KBvAndExpr
import io.ksmt.expr.KBvArithShiftRightExpr
import io.ksmt.expr.KBvConcatExpr
import io.ksmt.expr.KBvDivNoOverflowExpr
import io.ksmt.expr.KBvExtractExpr
import io.ksmt.expr.KBvLogicalShiftRightExpr
import io.ksmt.expr.KBvMulExpr
import io.ksmt.expr.KBvMulNoOverflowExpr
import io.ksmt.expr.KBvMulNoUnderflowExpr
import io.ksmt.expr.KBvNAndExpr
import io.ksmt.expr.KBvNegNoOverflowExpr
import io.ksmt.expr.KBvNegationExpr
import io.ksmt.expr.KBvNorExpr
import io.ksmt.expr.KBvNotExpr
import io.ksmt.expr.KBvOrExpr
import io.ksmt.expr.KBvReductionAndExpr
import io.ksmt.expr.KBvReductionOrExpr
import io.ksmt.expr.KBvRepeatExpr
import io.ksmt.expr.KBvRotateLeftExpr
import io.ksmt.expr.KBvRotateLeftIndexedExpr
import io.ksmt.expr.KBvRotateRightExpr
import io.ksmt.expr.KBvRotateRightIndexedExpr
import io.ksmt.expr.KBvShiftLeftExpr
import io.ksmt.expr.KBvSignExtensionExpr
import io.ksmt.expr.KBvSignedDivExpr
import io.ksmt.expr.KBvSignedGreaterExpr
import io.ksmt.expr.KBvSignedGreaterOrEqualExpr
import io.ksmt.expr.KBvSignedLessExpr
import io.ksmt.expr.KBvSignedLessOrEqualExpr
import io.ksmt.expr.KBvSignedModExpr
import io.ksmt.expr.KBvSignedRemExpr
import io.ksmt.expr.KBvSubExpr
import io.ksmt.expr.KBvSubNoOverflowExpr
import io.ksmt.expr.KBvSubNoUnderflowExpr
import io.ksmt.expr.KBvUnsignedDivExpr
import io.ksmt.expr.KBvUnsignedGreaterExpr
import io.ksmt.expr.KBvUnsignedGreaterOrEqualExpr
import io.ksmt.expr.KBvUnsignedLessExpr
import io.ksmt.expr.KBvUnsignedLessOrEqualExpr
import io.ksmt.expr.KBvUnsignedRemExpr
import io.ksmt.expr.KBvXNorExpr
import io.ksmt.expr.KBvXorExpr
import io.ksmt.expr.KBvZeroExtensionExpr
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
import io.ksmt.sort.KBvSort
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
            is KStringLiteralExpr, is KIntNumExpr, is KBitVecValue<*>, is KTrue, is KFalse -> return emptyList()
            is KStringConcatExpr -> return listOf(expr.arg0, expr.arg1)
            is KStringLenExpr -> return listOf(expr.arg)
            is KStringInRegexExpr -> return listOf(expr.arg0, expr.arg1)
            is KIteExpr<*> -> if (expr.sort is KStringSort) {
                return listOf(expr.condition, expr.trueBranch, expr.falseBranch)
            }
            else -> Unit
        }
        if (expr.sort !is KIntSort && expr.sort !is KBoolSort && expr.sort !is KBvSort) unsupported(expr)
        val app = expr as? KApp<*, *> ?: unsupported(expr)
        if (expr is KEqExpr<*> || expr is KDistinctExpr<*>) {
            if (app.args.any { !isValueSort(it.sort) }) unsupported(expr)
        }
        if (isBitVectorOperation(expr)) return app.args
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

    /** Native BV operations retain their original sorts, parameters and modular semantics. */
    @Suppress("ComplexMethod")
    private fun isBitVectorOperation(expr: KExpr<*>): Boolean = when (expr) {
        is KBvNotExpr<*>, is KBvReductionAndExpr<*>, is KBvReductionOrExpr<*>,
        is KBvAndExpr<*>, is KBvOrExpr<*>, is KBvXorExpr<*>,
        is KBvNAndExpr<*>, is KBvNorExpr<*>, is KBvXNorExpr<*>,
        is KBvNegationExpr<*>, is KBvAddExpr<*>, is KBvSubExpr<*>,
        is KBvMulExpr<*>, is KBvUnsignedDivExpr<*>, is KBvSignedDivExpr<*>,
        is KBvUnsignedRemExpr<*>, is KBvSignedRemExpr<*>, is KBvSignedModExpr<*>,
        is KBvUnsignedLessExpr<*>, is KBvSignedLessExpr<*>, is KBvUnsignedLessOrEqualExpr<*>,
        is KBvSignedLessOrEqualExpr<*>, is KBvUnsignedGreaterOrEqualExpr<*>, is KBvSignedGreaterOrEqualExpr<*>,
        is KBvUnsignedGreaterExpr<*>, is KBvSignedGreaterExpr<*>, is KBvConcatExpr,
        is KBvExtractExpr, is KBvSignExtensionExpr, is KBvZeroExtensionExpr,
        is KBvRepeatExpr, is KBvShiftLeftExpr<*>, is KBvLogicalShiftRightExpr<*>,
        is KBvArithShiftRightExpr<*>, is KBvRotateLeftExpr<*>, is KBvRotateLeftIndexedExpr<*>,
        is KBvRotateRightExpr<*>, is KBvRotateRightIndexedExpr<*>, is KBv2IntExpr,
        is KBvAddNoOverflowExpr<*>, is KBvAddNoUnderflowExpr<*>, is KBvSubNoOverflowExpr<*>,
        is KBvSubNoUnderflowExpr<*>, is KBvDivNoOverflowExpr<*>, is KBvNegNoOverflowExpr<*>,
        is KBvMulNoOverflowExpr<*>, is KBvMulNoUnderflowExpr<*> -> true
        else -> false
    }

    private fun requireConstantDivisor(expr: KExpr<*>) {
        if (expr !is KIntNumExpr || expr.bigIntegerValue.signum() == 0) unsupported(expr)
    }

    private fun isValueSort(sort: KSort): Boolean =
        sort is KBoolSort || sort is KIntSort || sort is KStringSort || sort is KBvSort

    private fun unsupported(expr: KExpr<*>): Nothing = throw KSolverUnsupportedFeatureException(
        "unsupported expression: ${expr::class.simpleName}; expected QF strings, LIA, native BV and ground regex"
    )
}

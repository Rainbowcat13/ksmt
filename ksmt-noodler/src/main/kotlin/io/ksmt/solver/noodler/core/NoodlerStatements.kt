package io.ksmt.solver.noodler.core

import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KApp
import io.ksmt.expr.KConst
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KRegexRangeExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringContainsExpr
import io.ksmt.expr.KStringFromCodeExpr
import io.ksmt.expr.KStringFromIntExpr
import io.ksmt.expr.KStringGeExpr
import io.ksmt.expr.KStringGtExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringIndexOfExpr
import io.ksmt.expr.KStringIndexOfRegexExpr
import io.ksmt.expr.KStringIsDigitExpr
import io.ksmt.expr.KStringLeExpr
import io.ksmt.expr.KStringLenExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KStringLtExpr
import io.ksmt.expr.KStringPrefixOfExpr
import io.ksmt.expr.KStringReplaceAllExpr
import io.ksmt.expr.KStringReplaceAllWithRegexExpr
import io.ksmt.expr.KStringReplaceExpr
import io.ksmt.expr.KStringReplaceWithRegexExpr
import io.ksmt.expr.KStringReverseExpr
import io.ksmt.expr.KStringSingletonSubExpr
import io.ksmt.expr.KStringSubExpr
import io.ksmt.expr.KStringSuffixOfExpr
import io.ksmt.expr.KStringToCodeExpr
import io.ksmt.expr.KStringToIntExpr
import io.ksmt.expr.KStringToLowerExpr
import io.ksmt.expr.KStringToRegexExpr
import io.ksmt.expr.KStringToUpperExpr
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KStringSort

internal fun KExpr<KBoolSort>.toNoodlerStatementOrNull(checkpoint: () -> Unit = {}): KExpr<*>? = when (this) {
    is KStringInRegexExpr -> asNoodlerRegexConstraintOrNull()
    is KNotExpr -> complementedRegexConstraintOrNull()

    is KEqExpr<*> -> {
        if (lhs.isSupportedStringTerm(checkpoint) && rhs.isSupportedStringTerm(checkpoint)) {
            this
        } else {
            null
        }
    }

    else -> null
}

internal fun extractNoodlerStatements(
    assertions: List<KExpr<KBoolSort>>,
    checkpoint: () -> Unit = {},
): List<KExpr<*>> {
    val result = linkedSetOf<KExpr<*>>()
    val visited = hashSetOf<KExpr<*>>()
    val pending = ArrayDeque<KExpr<KBoolSort>>()
    pending.addAll(assertions)

    while (pending.isNotEmpty()) {
        checkpoint()
        val current = pending.removeLast()
        if (!visited.add(current)) continue
        val supported = current.toNoodlerStatementOrNull(checkpoint)
        if (supported != null) {
            result.add(supported)
        } else if (current is KAndExpr) {
            pending.addAll(current.args)
        }
    }

    return result.toList()
}

private fun KStringInRegexExpr.asNoodlerRegexConstraintOrNull(): KStringInRegexExpr? {
    val variable = arg0 as? KConst<*>
    return if (variable != null && variable.sort is KStringSort) {
        this
    } else {
        null
    }
}

private fun KNotExpr.complementedRegexConstraintOrNull(): KStringInRegexExpr? {
    val regexConstraint = arg as? KStringInRegexExpr ?: return null
    val supported = regexConstraint.asNoodlerRegexConstraintOrNull() ?: return null
    val complementedRegex = supported.ctx.mkRegexComplementNoSimplify(supported.arg1)
    return supported.ctx.mkStringInRegexNoSimplify(supported.arg0, complementedRegex)
}

private fun KExpr<*>.isSupportedStringTerm(checkpoint: () -> Unit = {}): Boolean {
    val pending = ArrayDeque<KExpr<*>>()
    val visited = hashSetOf<KExpr<*>>()
    pending.add(this)
    while (pending.isNotEmpty()) {
        checkpoint()
        val current = pending.removeLast()
        if (!visited.add(current)) continue
        when (current) {
            is KStringConcatExpr -> {
                pending.add(current.arg0)
                pending.add(current.arg1)
            }
            is KConst<*> -> if (current.sort !is KStringSort) return false
            is KStringLiteralExpr -> Unit
            else -> return false
        }
    }
    return true
}

internal data class NoodlerInputAnalysis(
    val skipNoodler: Boolean,
    val skipReason: String?,
    /** Legacy hint only; propagation always uses the complete SMT-LIB Unicode universe. */
    val alphabet: List<Char>,
)

internal fun List<KExpr<KBoolSort>>.analyzeForNoodler(defaultAlphabet: List<Char>): NoodlerInputAnalysis {
    var skipReason: String? = null

    fun noteSkip(reason: String, preferSpecificReason: Boolean = false) {
        if (skipReason == null || preferSpecificReason) {
            skipReason = reason
        }
    }

    val pending = ArrayDeque<KExpr<*>>()
    val visited = linkedSetOf<KExpr<*>>()
    forEach { pending.addLast(it) }

    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!visited.add(current)) {
            continue
        }

        when (current) {
            is KEqExpr<*> -> {
                if (current.lhs.sort is KStringSort &&
                    (!current.lhs.isSupportedStringTerm() || !current.rhs.isSupportedStringTerm())
                ) {
                    noteSkip("unsupported string equality")
                }
            }

            is KStringInRegexExpr -> {
                val variable = current.arg0 as? KConst<*>
                if (variable == null || variable.sort !is KStringSort) {
                    noteSkip("unsupported regex constraint")
                }
            }

            is KRegexRangeExpr -> {
                val from = current.arg0 as? KStringLiteralExpr
                val to = current.arg1 as? KStringLiteralExpr
                if (from == null || to == null) {
                    noteSkip("unsupported regex range")
                }
            }

            is KStringToRegexExpr -> {
                if (current.arg !is KStringLiteralExpr) {
                    noteSkip("unsupported string-to-regex")
                }
            }

            is KStringLenExpr,
            is KStringSuffixOfExpr,
            is KStringPrefixOfExpr,
            is KStringLtExpr,
            is KStringLeExpr,
            is KStringGtExpr,
            is KStringGeExpr,
            is KStringContainsExpr,
            is KStringSingletonSubExpr,
            is KStringSubExpr,
            is KStringIndexOfExpr,
            is KStringIndexOfRegexExpr,
            is KStringReplaceExpr,
            is KStringReplaceAllExpr,
            is KStringReplaceWithRegexExpr,
            is KStringReplaceAllWithRegexExpr,
            is KStringToLowerExpr,
            is KStringToUpperExpr,
            is KStringReverseExpr,
            is KStringIsDigitExpr,
            is KStringToCodeExpr,
            is KStringFromCodeExpr,
            is KStringToIntExpr,
            is KStringFromIntExpr -> {
                noteSkip(
                    current::class.simpleName ?: "unsupported string operation",
                    preferSpecificReason = true
                )
            }
        }

        val app = current as? KApp<*, *> ?: continue
        pending.addAll(app.args)
    }

    return NoodlerInputAnalysis(
        skipNoodler = skipReason != null,
        skipReason = skipReason,
        alphabet = defaultAlphabet,
    )
}

@file:Suppress("UNCHECKED_CAST")

package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KAddArithExpr
import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KApp
import io.ksmt.expr.KConst
import io.ksmt.expr.KDistinctExpr
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFalse
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KIteExpr
import io.ksmt.expr.KMulArithExpr
import io.ksmt.expr.KOrExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringLenExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.expr.KSubArithExpr
import io.ksmt.expr.KTrue
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KStringSort

/** Keeps the Boolean skeleton intact; no string expression can cross the Yices boundary. */
internal class StringAbstraction(
    private val ctx: KContext,
    private val regex: RegexAutomata,
    private val checkpoint: () -> Unit,
    private val budget: PreprocessingBudget = PreprocessingBudget(checkpoint = checkpoint),
) {
    val atoms = linkedMapOf<KExpr<KBoolSort>, StringAtom>()
    val lengths = linkedMapOf<KDecl<KStringSort>, KExpr<KIntSort>>()
    val originalDeclarations = linkedSetOf<KDecl<*>>()
    val definitions = arrayListOf<KExpr<KBoolSort>>()
    private val translated = hashMapOf<KExpr<*>, KExpr<*>>()
    private val terms = hashMapOf<KExpr<*>, Word>()
    private val flattened = hashMapOf<Word, List<StringTermPart>>()

    // Identity equality/hashing is intentional: shared/deep terms must never recurse through children.
    private sealed class Word(val length: KExpr<KIntSort>, val leaves: Long, val emptyWord: Boolean) {
        class Leaf(val part: StringTermPart, length: KExpr<KIntSort>, emptyWord: Boolean) :
            Word(length, 1, emptyWord)
        class Concat(val left: Word, val right: Word, length: KExpr<KIntSort>, leaves: Long) :
            Word(length, leaves, left.emptyWord && right.emptyWord)
    }

    private class Frame(val expression: KExpr<*>, val dependencies: List<KExpr<*>>, var next: Int = 0)

    fun translate(assertion: KExpr<KBoolSort>): KExpr<KBoolSort> {
        val pending = ArrayDeque<Frame>()
        fun schedule(expression: KExpr<*>) {
            budget.reserve()
            val dependencies = if (expression is KStringInRegexExpr) {
                // Regex compilation has its own iterative worklist.
                listOf(expression.arg0)
            } else {
                HybridSyntax.dependencies(expression)
            }
            budget.reserve(dependencies.size.toLong())
            pending.addLast(Frame(expression, dependencies))
        }
        if (!isReady(assertion)) schedule(assertion)
        while (pending.isNotEmpty()) {
            checkpoint()
            val frame = pending.last()
            if (frame.next < frame.dependencies.size) {
                val child = frame.dependencies[frame.next++]
                if (!isReady(child)) schedule(child)
            } else {
                budget.reserve()
                if (frame.expression.sort is KStringSort) {
                    terms[frame.expression] = makeTerm(frame.expression)
                } else {
                    translated[frame.expression] = translateReady(frame.expression)
                }
                pending.removeLast()
            }
        }
        return translated.getValue(assertion) as KExpr<KBoolSort>
    }

    private fun isReady(expression: KExpr<*>): Boolean = expression in translated || expression in terms

    fun length(term: List<StringTermPart>): KExpr<KIntSort> {
        budget.reserve(term.size.toLong())
        val summands = term.map { part ->
            when (part) {
                is StringTermPart.Variable -> lengths.getValue(part.declaration)
                is StringTermPart.Literal -> ctx.mkIntNum(part.codePoints.size)
            }
        }
        return if (summands.isEmpty()) ctx.mkIntNum(0) else ctx.mkArithAddNoSimplify(summands)
    }

    private fun translateReady(expr: KExpr<*>): KExpr<*> = when {
        expr is KStringLenExpr -> terms.getValue(expr.arg).length
        expr is KEqExpr<*> && expr.lhs.sort is KStringSort -> equality(expr)
        expr is KDistinctExpr<*> && expr.args.first().sort is KStringSort -> distinct(expr)
        expr is KStringInRegexExpr -> {
            val stringTerm = flatten(terms.getValue(expr.arg0))
            val language = regex.compile(expr.arg1)
            budget.reserve()
            val proxy = ctx.mkFreshConst("noodler.membership", ctx.boolSort)
            atoms[expr] = StringAtom.Membership(proxy, stringTerm, language)
            proxy
        }
        expr is KConst<*> -> {
            budget.reserve()
            originalDeclarations += expr.decl
            expr
        }
        expr is KIntNumExpr || expr is KTrue || expr is KFalse -> expr
        else -> translateApplication(expr as KApp<*, *>)
    }

    private fun distinct(expr: KDistinctExpr<*>): KExpr<KBoolSort> {
        val count = expr.args.size.toLong()
        budget.reserve(count * (count - 1) / 2)
        val differences = arrayListOf<KExpr<KBoolSort>>()
        for (left in expr.args.indices) {
            for (right in left + 1 until expr.args.size) {
                checkpoint()
                val equation = ctx.mkEqNoSimplify(
                    expr.args[left] as KExpr<KStringSort>, expr.args[right] as KExpr<KStringSort>
                )
                differences += ctx.mkNot(equality(equation))
            }
        }
        return ctx.mkAnd(differences)
    }

    private fun translateApplication(app: KApp<*, *>): KExpr<*> {
        budget.reserve(app.args.size.toLong())
        val args = app.args.map { translated.getValue(it) }
        // Generic declaration application simplifies and flattens n-ary parents. Retaining
        // their shape avoids copying every prefix of a deep Boolean/arithmetic chain.
        return when (app) {
            is KAndExpr -> ctx.mkAndNoSimplify(args as List<KExpr<KBoolSort>>)
            is KOrExpr -> ctx.mkOrNoSimplify(args as List<KExpr<KBoolSort>>)
            is KAddArithExpr<*> -> ctx.mkArithAddNoSimplify(args as List<KExpr<KIntSort>>)
            is KSubArithExpr<*> -> ctx.mkArithSubNoSimplify(args as List<KExpr<KIntSort>>)
            is KMulArithExpr<*> -> ctx.mkArithMulNoSimplify(args as List<KExpr<KIntSort>>)
            else -> app.decl.apply(args)
        }
    }

    private fun equality(expr: KEqExpr<*>): KExpr<KBoolSort> {
        atoms[expr]?.let { return it.proxy }
        val left = terms.getValue(expr.lhs)
        val right = terms.getValue(expr.rhs)
        val equation = StringEquation(flatten(left), flatten(right))
        budget.reserve(EQUALITY_STORAGE_UNITS)
        val proxy = ctx.mkFreshConst("noodler.equality", ctx.boolSort)
        atoms[expr] = StringAtom.Equality(proxy, equation)
        definitions += ctx.mkImplies(proxy, ctx.mkEq(left.length, right.length))
        if (left.emptyWord || right.emptyWord) {
            definitions += ctx.mkEq(proxy, ctx.mkEq(left.length, right.length))
        }
        return proxy
    }

    private fun variable(declaration: KDecl<KStringSort>, original: Boolean): Word {
        budget.reserve(VARIABLE_STORAGE_UNITS)
        if (original) originalDeclarations += declaration
        val length = lengths.getOrPut(declaration) {
            ctx.mkFreshConst("noodler.length", ctx.intSort).also {
                definitions += ctx.mkArithGe(it, ctx.mkIntNum(0))
            }
        }
        return Word.Leaf(StringTermPart.Variable(declaration), length, emptyWord = false)
    }

    private fun makeTerm(expr: KExpr<*>): Word = when (expr) {
        is KConst<*> -> variable(expr.decl as KDecl<KStringSort>, original = true)
        is KStringLiteralExpr -> {
            // Reserve before obtaining the defensive IntArray or copying it into a part.
            budget.reserve(2L * expr.codePointCount + 1)
            Word.Leaf(
                StringTermPart.Literal(expr.codePoints.toList()), ctx.mkIntNum(expr.codePointCount),
                emptyWord = expr.codePointCount == 0,
            )
        }
        is KStringConcatExpr -> {
            val left = terms.getValue(expr.arg0)
            val right = terms.getValue(expr.arg1)
            budget.reserve()
            Word.Concat(
                left, right, ctx.mkArithAddNoSimplify(listOf(left.length, right.length)),
                budget.addSizes(left.leaves, right.leaves),
            )
        }
        is KIteExpr<*> -> {
            val auxiliary = ctx.mkFreshConst("noodler.ite", ctx.stringSort)
            val word = variable(auxiliary.decl, original = false)
            budget.reserve()
            terms[auxiliary] = word
            val left = equality(ctx.mkEqNoSimplify(auxiliary, expr.trueBranch as KExpr<KStringSort>))
            val right = equality(ctx.mkEqNoSimplify(auxiliary, expr.falseBranch as KExpr<KStringSort>))
            definitions += ctx.mkIte(translated.getValue(expr.condition) as KExpr<KBoolSort>, left, right)
            word
        }
        else -> error("Unsupported string term passed the syntax boundary")
    }

    private fun flatten(word: Word): List<StringTermPart> {
        flattened[word]?.let { return it }
        // Each occurrence is charged, even when the compact term shares both concat children.
        budget.reserve(word.leaves + 1)
        val result = ArrayList<StringTermPart>(word.leaves.toInt())
        val pending = ArrayDeque<Word>()
        pending.addLast(word)
        while (pending.isNotEmpty()) {
            checkpoint()
            when (val part = pending.removeLast()) {
                is Word.Leaf -> result += part.part
                is Word.Concat -> {
                    pending.addLast(part.right)
                    pending.addLast(part.left)
                }
            }
        }
        flattened[word] = result
        return result
    }

    companion object {
        private const val EQUALITY_STORAGE_UNITS = 3L
        private const val VARIABLE_STORAGE_UNITS = 4L
    }
}

internal sealed class StringAtom(val proxy: KExpr<KBoolSort>) {
    class Equality(proxy: KExpr<KBoolSort>, val equation: StringEquation) : StringAtom(proxy)
    class Membership(proxy: KExpr<KBoolSort>, val term: List<StringTermPart>, val language: SymbolicDfa) :
        StringAtom(proxy)
}

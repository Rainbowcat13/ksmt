package io.ksmt.solver.noodler.core

import io.ksmt.decl.KDecl
import io.ksmt.expr.KConst
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.automaton.ALL_CHAR_ALPHABET
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.noodler.hybrid.NoodlerRefiner
import io.ksmt.solver.noodler.hybrid.RegexAutomata
import io.ksmt.solver.noodler.hybrid.RegexEmissionBudget
import io.ksmt.solver.noodler.hybrid.StringEquation
import io.ksmt.solver.noodler.hybrid.StringTermPart
import io.ksmt.solver.noodler.hybrid.SymbolicDfa
import io.ksmt.solver.noodler.hybrid.toRegex
import io.ksmt.sort.KRegexSort
import io.ksmt.sort.KStringSort

private const val MAX_PROPAGATION_TERM_PARTS = 100_000

/** A sound, incomplete language propagator. A backend must decide the original joint formula. */
class Noodler(
    private val statements: List<KExpr<*>>,
    /** Compatibility hint only: string semantics always use the full SMT-LIB Unicode alphabet. */
    val alphabet: List<Char> = ALL_CHAR_ALPHABET,
) {
    val equations: List<KEqExpr<*>> = statements.filterIsInstance<KEqExpr<*>>()
    val regularConstraints: List<KStringInRegexExpr> = statements.filterIsInstance<KStringInRegexExpr>()

    var inputRegularConstraints: Map<KDecl<KStringSort>, KExpr<KRegexSort>> = emptyMap()
        private set
    var derivedRegularConstraints: Map<KDecl<KStringSort>, KExpr<KRegexSort>> = emptyMap()
        private set

    data class PropagationResult(
        val contradiction: Boolean,
        val constraints: List<KStringInRegexExpr>,
    )

    /**
     * Performs one sweep using complete boundary reachability. Per-variable languages overapproximate
     * solutions: shared occurrences and different equations still require a joint backend model.
     * [checkpoint] may abort preprocessing; no partial results are published on failure.
     */
    fun propagate(checkpoint: () -> Unit = {}): PropagationResult {
        inputRegularConstraints = emptyMap()
        derivedRegularConstraints = emptyMap()
        val compiler = RegexAutomata(checkpoint)
        val languages = linkedMapOf<KDecl<KStringSort>, SymbolicDfa>()
        val inputs = linkedMapOf<KDecl<KStringSort>, KExpr<KRegexSort>>()
        val normalized = arrayListOf<StringEquation>()
        val partsBudget = TermPartsBudget(checkpoint)
        for (statement in statements) {
            checkpoint()
            when (statement) {
                is KStringInRegexExpr -> {
                    val variable = statement.arg0 as? KConst<*>
                        ?: throw KSolverUnsupportedFeatureException("Regex constraint requires a string variable")
                    val declaration = variable.stringDeclaration()
                    val language = compiler.compile(statement.arg1)
                    languages[declaration] = languages[declaration]?.intersection(language, checkpoint) ?: language
                    inputs[declaration] = inputs[declaration]?.let {
                        statement.ctx.mkRegexIntersectionNoSimplify(it, statement.arg1)
                    } ?: statement.arg1
                }
                is KEqExpr<*> -> normalized += StringEquation(
                    flatten(statement.lhs, partsBudget), flatten(statement.rhs, partsBudget)
                )
                else -> throw KSolverUnsupportedFeatureException("Expected a string equation or regex constraint")
            }
        }
        val result = NoodlerRefiner(checkpoint).refine(languages, normalized)
        if (result.contradiction) {
            inputRegularConstraints = inputs.toMap()
            return PropagationResult(contradiction = true, constraints = emptyList())
        }
        val emission = RegexEmissionBudget(checkpoint)
        val derived = linkedMapOf<KDecl<KStringSort>, KExpr<KRegexSort>>()
        val constraints = arrayListOf<KStringInRegexExpr>()
        for ((declaration, language) in result.languages) {
            checkpoint()
            val original = languages[declaration] ?: SymbolicDfa.allWords()
            if (original.equivalentTo(language, checkpoint)) continue
            val regex = language.toRegex(declaration.ctx, emission)
            derived[declaration] = regex
            constraints += declaration.ctx.mkStringInRegexNoSimplify(declaration.apply(emptyList()), regex)
        }
        inputRegularConstraints = inputs.toMap()
        derivedRegularConstraints = derived.toMap()
        return PropagationResult(contradiction = false, constraints = constraints)
    }

    /** Compatibility adapter: true means only "no contradiction proved", never SAT. */
    fun solve(): Pair<Boolean, List<KStringInRegexExpr>> = propagate().let { !it.contradiction to it.constraints }

    @Deprecated("Propagation does not construct a joint model. Query KNoodlerSolver.model() after SAT.")
    fun model(): Map<KExpr<KStringSort>, String> = throw KSolverUnsupportedFeatureException(
        "Independent words from projected languages are not a model; query the backend after SAT"
    )
}

private class TermPartsBudget(private val checkpoint: () -> Unit) {
    private var remaining = MAX_PROPAGATION_TERM_PARTS
    fun consume(count: Int = 1) {
        checkpoint()
        if (count > remaining) throw AutomataLimitException("string term expansion budget exceeded")
        remaining -= count
    }
}

private fun flatten(expression: KExpr<*>, budget: TermPartsBudget): List<StringTermPart> {
    val pending = ArrayDeque<KExpr<*>>()
    val result = arrayListOf<StringTermPart>()
    pending.add(expression)
    while (pending.isNotEmpty()) {
        budget.consume()
        when (val current = pending.removeLast()) {
            is KStringConcatExpr -> {
                pending.addLast(current.arg1)
                pending.addLast(current.arg0)
            }
            is KConst<*> -> result += StringTermPart.Variable(current.stringDeclaration())
            is KStringLiteralExpr -> {
                budget.consume(current.codePointCount)
                val codePoints = current.codePoints
                result += StringTermPart.Literal(codePoints.toList())
            }
            else -> throw KSolverUnsupportedFeatureException("Expected string variables, literals or concatenation")
        }
    }
    return result
}

private fun KConst<*>.stringDeclaration(): KDecl<KStringSort> {
    if (sort !is KStringSort) throw KSolverUnsupportedFeatureException("Expected a string variable")
    @Suppress("UNCHECKED_CAST")
    return decl as KDecl<KStringSort>
}

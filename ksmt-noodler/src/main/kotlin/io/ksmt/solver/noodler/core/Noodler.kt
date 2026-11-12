package io.ksmt.solver.noodler.core

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KConst
import io.ksmt.expr.KDistinctExpr
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFalse
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KStringConcatExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.automaton.ALL_CHAR_ALPHABET
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.noodler.hybrid.NoodlerRefiner
import io.ksmt.solver.noodler.hybrid.PeriodicWordEquality
import io.ksmt.solver.noodler.hybrid.PreprocessingBudget
import io.ksmt.solver.noodler.hybrid.RegexAutomata
import io.ksmt.solver.noodler.hybrid.RegexEmissionBudget
import io.ksmt.solver.noodler.hybrid.StringEquation
import io.ksmt.solver.noodler.hybrid.StringTermPart
import io.ksmt.solver.noodler.hybrid.SymbolicDfa
import io.ksmt.solver.noodler.hybrid.toRegex
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KRegexSort
import io.ksmt.sort.KStringSort

private const val MAX_PROPAGATION_TERM_PARTS = 100_000
private const val MAX_PERIODIC_PROOF_UNITS = 100_000

/** A sound, incomplete language propagator. A backend must decide the original joint formula. */
class Noodler(
    private val statements: List<KExpr<*>>,
    /** Compatibility hint only: string semantics always use the full SMT-LIB Unicode alphabet. */
    val alphabet: List<Char> = ALL_CHAR_ALPHABET,
    private val periodicLengthReasoning: Boolean = true,
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
        val lengthConstraints: List<KExpr<KBoolSort>> = emptyList(),
    )

    /**
     * Performs one sweep using complete boundary reachability. Per-variable languages overapproximate
     * solutions: shared occurrences and different equations still require a joint backend model.
     * [checkpoint] may abort preprocessing; no partial results are published on failure.
     */
    @Suppress("ComplexMethod") // Collect conjunctive inputs before refining and publishing certified consequences.
    fun propagate(checkpoint: () -> Unit = {}): PropagationResult {
        inputRegularConstraints = emptyMap()
        derivedRegularConstraints = emptyMap()
        val compiler = RegexAutomata(checkpoint)
        val languages = linkedMapOf<KDecl<KStringSort>, SymbolicDfa>()
        val inputs = linkedMapOf<KDecl<KStringSort>, KExpr<KRegexSort>>()
        val normalized = arrayListOf<StringEquation>()
        val disequations = arrayListOf<Pair<KContext, StringEquation>>()
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
                is KNotExpr -> disequations += normalizeDisequality(statement, partsBudget)
                is KDistinctExpr<*> -> addDistinctDisequalities(statement, disequations, partsBudget)
                else -> throw KSolverUnsupportedFeatureException("Expected a string equation or regex constraint")
            }
        }
        includeDisequalityVariables(disequations.map { it.second }, languages, partsBudget)
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
        val lengthConstraints = if (periodicLengthReasoning) {
            periodicDisequalityConstraints(disequations, result.languages, checkpoint)
        } else emptyList()
        inputRegularConstraints = inputs.toMap()
        derivedRegularConstraints = derived.toMap()
        return PropagationResult(
            contradiction = lengthConstraints.any { it is KFalse }, constraints = constraints,
            lengthConstraints = lengthConstraints,
        )
    }

    /** Compatibility adapter: true means only "no contradiction proved", never SAT. */
    fun solve(): Pair<Boolean, List<KStringInRegexExpr>> = propagate().let { !it.contradiction to it.constraints }

    @Deprecated("Propagation does not construct a joint model. Query KNoodlerSolver.model() after SAT.")
    fun model(): Map<KExpr<KStringSort>, String> = throw KSolverUnsupportedFeatureException(
        "Independent words from projected languages are not a model; query the backend after SAT"
    )
}

private fun normalizeDisequality(
    statement: KNotExpr, budget: TermPartsBudget,
): Pair<KContext, StringEquation> {
    val equation = statement.arg as? KEqExpr<*>
        ?: throw KSolverUnsupportedFeatureException("Expected a negated string equation")
    return statement.ctx to StringEquation(flatten(equation.lhs, budget), flatten(equation.rhs, budget))
}

private fun addDistinctDisequalities(
    statement: KDistinctExpr<*>, disequations: MutableList<Pair<KContext, StringEquation>>, budget: TermPartsBudget,
) {
    val terms = statement.args.map { flatten(it, budget) }
    budget.consumePairs(terms.size.toLong() * (terms.size - 1) / 2)
    for (left in terms.indices) {
        for (right in left + 1 until terms.size) {
            disequations += statement.ctx to StringEquation(terms[left], terms[right])
        }
    }
}

private fun includeDisequalityVariables(
    disequations: List<StringEquation>, languages: MutableMap<KDecl<KStringSort>, SymbolicDfa>,
    budget: TermPartsBudget,
) {
    val unrestricted by lazy { SymbolicDfa.allWords() }
    for (equation in disequations) {
        for (part in equation.left + equation.right) {
            budget.consume()
            if (part is StringTermPart.Variable) languages.putIfAbsent(part.declaration, unrestricted)
        }
    }
}

private fun periodicDisequalityConstraints(
    disequations: List<Pair<KContext, StringEquation>>, languages: Map<KDecl<KStringSort>, SymbolicDfa>,
    checkpoint: () -> Unit,
): List<KExpr<KBoolSort>> {
    val budget = PreprocessingBudget(MAX_PERIODIC_PROOF_UNITS, checkpoint)
    val constraints = arrayListOf<KExpr<KBoolSort>>()
    try {
        for ((ctx, equation) in disequations) {
            budget.reserve()
            if (!PeriodicWordEquality.provesLengthEquivalence(equation, languages, checkpoint, budget)) continue
            constraints += ctx.mkNot(ctx.mkEq(
                periodicTermLength(ctx, equation.left, budget), periodicTermLength(ctx, equation.right, budget),
            ))
        }
    } catch (_: AutomataLimitException) {
        // The optional proof contributes only completed certificates. Cancellation still propagates.
        checkpoint()
    }
    return constraints
}

private fun periodicTermLength(
    ctx: KContext, parts: List<StringTermPart>, budget: PreprocessingBudget,
): KExpr<KIntSort> {
    budget.reserve(parts.size.toLong())
    val lengths = parts.map {
        when (it) {
            is StringTermPart.Variable -> ctx.mkStringLen(it.declaration.apply(emptyList()))
            is StringTermPart.Literal -> ctx.mkIntNum(it.codePoints.size)
        }
    }
    return if (lengths.isEmpty()) ctx.mkIntNum(0) else ctx.mkArithAdd(lengths)
}

private class TermPartsBudget(private val checkpoint: () -> Unit) {
    private var remaining = MAX_PROPAGATION_TERM_PARTS
    fun consume(count: Int = 1) {
        checkpoint()
        if (count > remaining) throw AutomataLimitException("string term expansion budget exceeded")
        remaining -= count
    }

    fun consumePairs(count: Long) {
        checkpoint()
        if (count > remaining) throw AutomataLimitException("string disequality expansion budget exceeded")
        remaining -= count.toInt()
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

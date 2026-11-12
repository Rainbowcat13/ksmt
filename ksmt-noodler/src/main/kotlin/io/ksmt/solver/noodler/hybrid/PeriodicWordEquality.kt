package io.ksmt.solver.noodler.hybrid

import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort

/** Certifies a length-injective common domain; it never samples a proposed length vector. */
internal object PeriodicWordEquality {
    /**
     * If both terms consist of powers of one common nonempty word, equality of their lengths
     * is equivalent to equality of the words. A false result supplies no information.
     * The caller must guard the consequence by the assumptions that justify [languages].
     */
    @Suppress("ComplexMethod", "NestedBlockDepth") // Collect both terms, then certify every guarded language.
    fun provesLengthEquivalence(
        equation: StringEquation,
        languages: Map<KDecl<KStringSort>, SymbolicDfa>,
        checkpoint: () -> Unit,
        budget: PreprocessingBudget,
    ): Boolean {
        val variables = linkedSetOf<KDecl<KStringSort>>()
        val literals = arrayListOf<List<Int>>()
        for (term in listOf(equation.left, equation.right)) {
            for (part in term) {
                checkpoint()
                budget.reserve()
                when (part) {
                    is StringTermPart.Variable -> {
                        if (part.declaration !in languages) return false
                        if (variables.add(part.declaration)) budget.reserve()
                    }
                    is StringTermPart.Literal -> {
                        budget.reserve()
                        literals.add(part.codePoints)
                    }
                }
            }
        }
        var witness = literals.firstOrNull { it.isNotEmpty() }?.let {
            budget.reserve(it.size.toLong())
            it.toIntArray()
        }
        for (variable in variables) {
            if (witness != null) break
            witness = languages.getValue(variable).nonemptyWord(checkpoint, budget)
        }
        // The completed searches proved every language contains no nonempty word.
        val period = witness?.let { primitiveRoot(it, checkpoint, budget) } ?: return true
        for (literal in literals) {
            if (!isPower(literal, period, checkpoint, budget)) return false
        }
        for (variable in variables) {
            if (!languages.getValue(variable).isSubsetOfPowers(period, checkpoint, budget)) return false
        }
        return true
    }

    private data class WordStep(val state: Int, val parent: Int, val character: Int, val length: Int)

    /** BFS starts after one character, so an accepting initial state does not hide nonempty cycles. */
    private fun SymbolicDfa.nonemptyWord(checkpoint: () -> Unit, budget: PreprocessingBudget): IntArray? {
        val storage = limits.storageBudget()
        val steps = arrayListOf<WordStep>()
        val seen = hashSetOf<Int>()
        val pending = ArrayDeque<Int>()
        fun appendEdges(state: Int, parent: Int, length: Int) {
            for (edge in transitions[state]) {
                checkpoint()
                budget.reserve()
                if (edge.target in seen) continue
                limits.checkStates(steps.size.toLong() + 1)
                storage.reserve(WORD_STEP_STORAGE)
                budget.reserve(WORD_STEP_STORAGE)
                seen.add(edge.target)
                pending.add(steps.size)
                steps.add(WordStep(edge.target, parent, edge.lower, length))
            }
        }
        appendEdges(initialState, -1, 1)
        while (pending.isNotEmpty()) {
            checkpoint()
            budget.reserve()
            val index = pending.removeFirst()
            val step = steps[index]
            if (step.state in acceptingStates) {
                budget.reserve(step.length.toLong())
                val word = IntArray(step.length)
                var predecessor = index
                for (position in word.lastIndex downTo 0) {
                    checkpoint()
                    budget.reserve()
                    val previous = steps[predecessor]
                    word[position] = previous.character
                    predecessor = previous.parent
                }
                return word
            }
            appendEdges(step.state, index, step.length + 1)
        }
        return null
    }

    private fun primitiveRoot(word: IntArray, checkpoint: () -> Unit, budget: PreprocessingBudget): IntArray {
        budget.reserve(word.size.toLong())
        val prefix = IntArray(word.size)
        for (index in 1 until word.size) {
            checkpoint()
            budget.reserve()
            var previous = prefix[index - 1]
            while (previous > 0 && word[index] != word[previous]) {
                checkpoint()
                budget.reserve()
                previous = prefix[previous - 1]
            }
            if (word[index] == word[previous]) previous++
            prefix[index] = previous
        }
        val candidate = word.size - prefix.last()
        val length = if (word.size % candidate == 0) candidate else word.size
        budget.reserve(length.toLong())
        return word.copyOf(length)
    }

    private fun isPower(
        word: List<Int>, period: IntArray, checkpoint: () -> Unit, budget: PreprocessingBudget,
    ): Boolean {
        if (word.size % period.size != 0) return false
        for ((index, character) in word.withIndex()) {
            checkpoint()
            budget.reserve()
            if (character != period[index % period.size]) return false
        }
        return true
    }

    /** The implicit period DFA accepts phase zero and has one rejecting state for a mismatched letter. */
    @Suppress("NestedBlockDepth") // Traverse only reachable pairs and split each interval by the expected letter.
    private fun SymbolicDfa.isSubsetOfPowers(
        period: IntArray, checkpoint: () -> Unit, budget: PreprocessingBudget,
    ): Boolean {
        val storage = limits.storageBudget()
        val dead = period.size
        val seen = hashSetOf<Pair<Int, Int>>()
        val pending = ArrayDeque<Pair<Int, Int>>()
        fun enqueue(state: Int, phase: Int) {
            val pair = state to phase
            if (pair in seen) return
            limits.checkStates(seen.size.toLong() + 1)
            storage.reserve(PAIR_STORAGE)
            budget.reserve(PAIR_STORAGE)
            seen.add(pair)
            pending.add(pair)
        }
        enqueue(initialState, 0)
        while (pending.isNotEmpty()) {
            checkpoint()
            budget.reserve()
            val (state, phase) = pending.removeFirst()
            if (state in acceptingStates && phase != 0) return false
            for (edge in transitions[state]) {
                checkpoint()
                budget.reserve()
                if (phase == dead) {
                    enqueue(edge.target, dead)
                } else {
                    val expected = period[phase]
                    if (expected in edge.lower..edge.upper) enqueue(edge.target, (phase + 1) % period.size)
                    if (edge.lower < expected || edge.upper > expected) enqueue(edge.target, dead)
                }
            }
        }
        return true
    }

    private const val WORD_STEP_STORAGE = 7L
    private const val PAIR_STORAGE = 5L
}

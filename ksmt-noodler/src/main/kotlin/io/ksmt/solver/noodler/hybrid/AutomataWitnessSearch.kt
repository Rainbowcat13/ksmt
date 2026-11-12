package io.ksmt.solver.noodler.hybrid

import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort

/** Independent accepted words are only a candidate: the caller must validate their joint assignment. */
internal object AutomataWitnessSearch {
    fun chooseWitness(
        languages: Map<KDecl<KStringSort>, SymbolicDfa>,
        lengths: Map<KDecl<KStringSort>, Int>,
        checkpoint: () -> Unit,
        budget: PreprocessingBudget,
    ): Map<KDecl<KStringSort>, IntArray>? {
        checkpoint()
        budget.reserve()
        val words = linkedMapOf<KDecl<KStringSort>, IntArray>()
        for ((declaration, length) in lengths) {
            checkpoint()
            budget.reserve()
            val language = languages[declaration] ?: return null
            val word = language.acceptedWord(length, checkpoint, budget) ?: return null
            budget.reserve()
            words[declaration] = word
        }
        return words
    }

    private data class Predecessor(val state: Int, val character: Int)

    private fun SymbolicDfa.acceptedWord(
        length: Int,
        checkpoint: () -> Unit,
        budget: PreprocessingBudget,
    ): IntArray? {
        if (length < 0 || acceptingStates.isEmpty()) return null
        // Reserve before allocating the layer container, including for a very large proposed length.
        budget.reserve(length.toLong() + 2)
        val layers = ArrayList<Map<Int, Predecessor>>(length)
        var frontier: Set<Int> = setOf(initialState)
        repeat(length) {
            checkpoint()
            budget.reserve()
            val next = linkedMapOf<Int, Predecessor>()
            for (state in frontier) {
                checkpoint()
                for (edge in transitions[state]) {
                    checkpoint()
                    budget.reserve()
                    if (edge.target !in next) {
                        budget.reserve(PREDECESSOR_STORAGE)
                        next[edge.target] = Predecessor(state, edge.lower)
                    }
                }
            }
            layers.add(next)
            frontier = next.keys
        }
        var state = frontier.firstOrNull {
            checkpoint()
            budget.reserve()
            it in acceptingStates
        } ?: return null
        budget.reserve(length.toLong())
        val word = IntArray(length)
        for (position in word.lastIndex downTo 0) {
            checkpoint()
            budget.reserve()
            val previous = layers[position].getValue(state)
            word[position] = previous.character
            state = previous.state
        }
        return word
    }

    private const val PREDECESSOR_STORAGE = 3L
}

package io.ksmt.solver.noodler.hybrid

internal data class MembershipTransition(val source: Int, val edge: Transition)

/** Only transitions lying on a run with the requested final polarity are retained at each position. */
internal data class MembershipTransitionPlan(
    val possible: Boolean,
    val steps: List<List<MembershipTransition>>,
)

/** -1 denotes an unconstrained character; every other entry is a known literal code point. */
// The forward and backward passes share a bounded set of sparse frontiers, never a dense L x Q matrix.
@Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
internal fun SymbolicDfa.planMembershipTransitions(
    knownCharacters: IntArray,
    positive: Boolean,
    budget: PreprocessingBudget,
    checkpoint: () -> Unit = {},
): MembershipTransitionPlan {
    budget.reserve(2L * knownCharacters.size + 2)
    val forward = ArrayList<Set<Int>>(knownCharacters.size + 1)
    budget.reserve()
    forward.add(setOf(initialState))
    for (character in knownCharacters) {
        checkpoint()
        budget.reserve()
        val next = hashSetOf<Int>()
        for (state in forward.last()) {
            checkpoint()
            for (edge in transitions[state]) {
                checkpoint()
                budget.reserve()
                if ((character < 0 || character in edge.lower..edge.upper) && edge.target !in next) {
                    budget.reserve()
                    next.add(edge.target)
                }
            }
        }
        forward.add(next)
    }

    val finals = hashSetOf<Int>()
    for (state in forward.last()) {
        checkpoint()
        budget.reserve()
        if ((state in acceptingStates) == positive) {
            budget.reserve()
            finals.add(state)
        }
    }
    if (finals.isEmpty()) return MembershipTransitionPlan(possible = false, steps = emptyList())

    val reversed = ArrayList<List<MembershipTransition>>(knownCharacters.size)
    var useful: Set<Int> = finals
    for (position in knownCharacters.lastIndex downTo 0) {
        checkpoint()
        budget.reserve(2)
        val previous = hashSetOf<Int>()
        val retained = arrayListOf<MembershipTransition>()
        val character = knownCharacters[position]
        for (state in forward[position]) {
            checkpoint()
            for (edge in transitions[state]) {
                checkpoint()
                budget.reserve()
                if ((character < 0 || character in edge.lower..edge.upper) && edge.target in useful) {
                    budget.reserve(2)
                    retained.add(MembershipTransition(state, edge))
                    if (state !in previous) {
                        budget.reserve()
                        previous.add(state)
                    }
                }
            }
        }
        reversed.add(retained)
        useful = previous
    }
    reversed.reverse()
    return MembershipTransitionPlan(possible = true, steps = reversed)
}

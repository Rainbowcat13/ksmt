package io.ksmt.solver.noodler.hybrid

import kotlin.math.abs

/** Necessary bounds and absolute modular residues of accepted word lengths. A null minimum means empty. */
internal data class AutomatonLengthSummary(
    val minLength: Int?,
    val maxLength: Int?,
    val modulus: Int,
    val residues: Set<Int>,
) {
    fun contains(length: Int): Boolean = minLength != null && length >= minLength &&
        (maxLength == null || length <= maxLength) && length % modulus in residues
}

/**
 * Linear graph analysis, without constructing the possibly exponential unary subset orbit.
 * Only states on an accepting path contribute: a rejecting sink's self-loop must not erase parity.
 */
// Reachability, gcd and topological passes reuse the same guarded primitive arrays.
@Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth")
internal fun SymbolicDfa.lengthSummary(checkpoint: () -> Unit = {}): AutomatonLengthSummary {
    val storage = limits.storageBudget()
    storage.reserve(2L * transitions.size)
    val distance = IntArray(transitions.size) { -1 }
    val useful = BooleanArray(transitions.size)
    simpleLengthSummary(distance, useful, storage, checkpoint)?.let { return it }
    distance.fill(-1)
    useful.fill(false)

    storage.reserve(2L * transitions.size)
    val incoming = List(transitions.size) { arrayListOf<Int>() }
    val pending = IntArray(transitions.size)
    var head = 0
    var tail = 1
    pending[0] = initialState
    distance[initialState] = 0
    while (head < tail) {
        checkpoint()
        val state = pending[head++]
        for (edge in transitions[state]) {
            checkpoint()
            storage.reserve(1)
            incoming[edge.target].add(state)
            if (distance[edge.target] < 0) {
                distance[edge.target] = distance[state] + 1
                pending[tail++] = edge.target
            }
        }
    }

    head = 0
    tail = 0
    var minimum: Int? = null
    for (state in acceptingStates) {
        checkpoint()
        if (distance[state] >= 0) {
            minimum = minOf(minimum ?: distance[state], distance[state])
            useful[state] = true
            pending[tail++] = state
        }
    }
    if (minimum == null) return emptyLengthSummary()
    while (head < tail) {
        checkpoint()
        for (state in incoming[pending[head++]]) {
            checkpoint()
            if (!useful[state]) {
                useful[state] = true
                pending[tail++] = state
            }
        }
    }

    storage.reserve(transitions.size.toLong())
    val indegree = IntArray(transitions.size)
    var divisor = 0
    var usefulCount = 0
    for (state in transitions.indices) {
        checkpoint()
        if (!useful[state]) continue
        usefulCount++
        for (edge in transitions[state]) {
            checkpoint()
            if (useful[edge.target]) {
                indegree[edge.target]++
                divisor = lengthGcd(divisor, abs(distance[state] + 1 - distance[edge.target]))
            }
        }
    }
    val modulus = maxOf(1, divisor)
    val residues = linkedSetOf<Int>()
    for (state in acceptingStates) {
        checkpoint()
        if (useful[state]) {
            val residue = distance[state] % modulus
            if (residue !in residues) {
                storage.reserve(1)
                residues.add(residue)
            }
        }
    }

    // Kahn's traversal proves finiteness exactly, and computes the maximum in an acyclic useful graph.
    distance.fill(-1)
    distance[initialState] = 0
    head = 0
    tail = 0
    for (state in transitions.indices) {
        checkpoint()
        if (useful[state] && indegree[state] == 0) pending[tail++] = state
    }
    var maximum = 0
    while (head < tail) {
        checkpoint()
        val state = pending[head++]
        if (state in acceptingStates) maximum = maxOf(maximum, distance[state])
        for (edge in transitions[state]) {
            checkpoint()
            if (useful[edge.target]) {
                distance[edge.target] = maxOf(distance[edge.target], distance[state] + 1)
                if (--indegree[edge.target] == 0) pending[tail++] = edge.target
            }
        }
    }
    return AutomatonLengthSummary(minimum, maximum.takeIf { head == usefulCount }, modulus, residues)
}

/** Paths/cycles with rejecting self-loop sinks need only two primitive state arrays. */
@Suppress("ComplexMethod") // A single path walk handles dead ends, transient states and cycles.
private fun SymbolicDfa.simpleLengthSummary(
    distance: IntArray,
    dead: BooleanArray,
    storage: AutomataStorageBudget,
    checkpoint: () -> Unit,
): AutomatonLengthSummary? {
    for (state in transitions.indices) {
        checkpoint()
        dead[state] = state !in acceptingStates && transitions[state].all {
            checkpoint()
            it.target == state
        }
    }
    val accepted = arrayListOf<Int>()
    var state = initialState
    var length = 0
    while (!dead[state] && distance[state] < 0) {
        checkpoint()
        distance[state] = length
        if (state in acceptingStates) {
            storage.reserve(1)
            accepted.add(length)
        }
        var successor = -1
        for (edge in transitions[state]) {
            checkpoint()
            if (dead[edge.target]) continue
            if (successor >= 0 && successor != edge.target) return null
            successor = edge.target
        }
        if (successor < 0) break
        state = successor
        length++
    }
    if (accepted.isEmpty()) return emptyLengthSummary()
    val cycleStart = distance[state]
    val hasAcceptingCycle = cycleStart >= 0 && length > cycleStart && accepted.last() >= cycleStart
    val modulus = if (hasAcceptingCycle) length - cycleStart else 1
    val residues = linkedSetOf<Int>()
    for (value in accepted) {
        checkpoint()
        val residue = value % modulus
        if (residue !in residues) {
            storage.reserve(1)
            residues.add(residue)
        }
    }
    return AutomatonLengthSummary(accepted.first(), accepted.last().takeUnless { hasAcceptingCycle }, modulus, residues)
}

private fun emptyLengthSummary(): AutomatonLengthSummary = AutomatonLengthSummary(null, null, 1, emptySet())

private fun lengthGcd(left: Int, right: Int): Int {
    var one = left
    var two = right
    while (two != 0) {
        val remainder = one % two
        one = two
        two = remainder
    }
    return one
}

/** Minimize the Boolean acceptance period and move its boundary backwards through a matching prefix. */
@Suppress("ComplexMethod") // Divisor tests and backwards matching share the immutable acceptance pattern.
internal fun compactLengthPattern(
    accepted: Set<Int>, cycleStart: Int, period: Int, checkpoint: () -> Unit,
): AcceptedLengths {
    fun hasPeriod(candidate: Int): Boolean {
        for (offset in candidate until period) {
            checkpoint()
            if ((cycleStart + offset in accepted) != (cycleStart + offset % candidate in accepted)) return false
        }
        return true
    }

    var reduced = period
    var remaining = period
    var factor = 2
    while (factor <= remaining / factor) {
        checkpoint()
        if (remaining % factor == 0) {
            while (remaining % factor == 0) remaining /= factor
            while (reduced % factor == 0 && hasPeriod(reduced / factor)) reduced /= factor
        }
        factor++
    }
    if (remaining > 1) {
        while (reduced % remaining == 0 && hasPeriod(reduced / remaining)) reduced /= remaining
    }
    var boundary = cycleStart
    while (boundary > 0) {
        checkpoint()
        val previous = boundary - 1
        val periodic = cycleStart + Math.floorMod(previous - cycleStart, reduced) in accepted
        if ((previous in accepted) != periodic) break
        boundary--
    }
    val prefix = linkedSetOf<Int>()
    val residues = linkedSetOf<Int>()
    for (length in accepted) {
        checkpoint()
        when {
            length < boundary -> prefix.add(length)
            length >= cycleStart && length - cycleStart < reduced ->
                residues.add(Math.floorMod(length - boundary, reduced))
        }
    }
    return AcceptedLengths(prefix, boundary, reduced, residues)
}

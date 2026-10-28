package io.ksmt.solver.noodler.hybrid

/** SMT-LIB Strings 2.7 includes every code point in Unicode planes 0 through 2. */
internal const val MAX_STRING_CODE_POINT: Int = 0x2ffff

internal open class AutomataLimitException(message: String) : RuntimeException(message)

internal const val DEFAULT_AUTOMATA_STATE_LIMIT: Int = 100_000

/** Conservative allocation-entry cap per operation and regex compiler cache; exhaustion means UNKNOWN. */
internal const val DEFAULT_AUTOMATA_STORAGE_LIMIT: Int = 1_000_000

/** Immutable per-check caps; derived automata retain the stricter limit of their operands. */
internal data class AutomataLimits(
    val maxStates: Int = DEFAULT_AUTOMATA_STATE_LIMIT,
    val maxStorage: Int = DEFAULT_AUTOMATA_STORAGE_LIMIT,
) {
    init {
        require(maxStates > 0)
        require(maxStorage > 0)
    }

    fun intersect(other: AutomataLimits): AutomataLimits = AutomataLimits(
        minOf(maxStates, other.maxStates), minOf(maxStorage, other.maxStorage)
    )

    fun checkStates(size: Long) {
        if (size > maxStates) throw AutomataLimitException("Symbolic automaton exceeds $maxStates states")
    }

    fun storageBudget(): AutomataStorageBudget = AutomataStorageBudget(maxStorage)

    fun checkLiteralSize(length: Int) {
        checkStates(length.toLong() + 2)
        storageBudget().reserve(LITERAL_CODE_POINT_STORAGE_ENTRIES * length + LITERAL_STORAGE_OVERHEAD)
    }
}

internal data class Transition(val lower: Int, val upper: Int, val target: Int)

/** Exactly the accepted lengths: [prefix], followed by [residues] modulo [period] from [cycleStart]. */
internal data class AcceptedLengths(
    val prefix: Set<Int>,
    val cycleStart: Int,
    val period: Int,
    val residues: Set<Int>,
) {
    fun contains(length: Int): Boolean = length >= 0 && if (length < cycleStart) {
        length in prefix
    } else {
        (length - cycleStart) % period in residues
    }
}

/** A complete deterministic automaton; intervals are inclusive, disjoint and sorted. */
internal class SymbolicDfa(
    val transitions: List<List<Transition>>,
    val initialState: Int,
    val acceptingStates: Set<Int>,
    val limits: AutomataLimits = AutomataLimits(),
) {
    constructor(
        transitions: List<List<Transition>>, initialState: Int, acceptingStates: Set<Int>, storageLimit: Int,
    ) : this(transitions, initialState, acceptingStates, AutomataLimits(maxStorage = storageLimit))

    init {
        limits.checkStates(transitions.size.toLong())
        limits.storageBudget().reserve(storageSize)
        require(initialState in transitions.indices)
        require(acceptingStates.all { it in transitions.indices })
        transitions.forEach { edges ->
            require(edges.isNotEmpty() && edges.first().lower == 0 && edges.last().upper == MAX_STRING_CODE_POINT)
            edges.forEach { edge ->
                require(edge.lower <= edge.upper && edge.target in transitions.indices)
            }
            for (index in 1 until edges.size) require(edges[index - 1].upper + 1 == edges[index].lower)
        }
    }

    /** Number of retained transition, state and accepting-set entries (not a byte estimate). */
    val storageSize: Long
        get() = transitions.size.toLong() + acceptingStates.size + transitions.sumOf { it.size.toLong() }

    fun withLimits(requested: AutomataLimits): SymbolicDfa {
        val combined = limits.intersect(requested)
        return if (combined == limits) this else SymbolicDfa(transitions, initialState, acceptingStates, combined)
    }

    fun accepts(word: IntArray, checkpoint: () -> Unit = {}): Boolean {
        var state = initialState
        for (point in word) {
            checkpoint()
            if (point !in 0..MAX_STRING_CODE_POINT) return false
            val edges = transitions[state]
            var low = 0
            var high = edges.lastIndex
            while (low <= high) {
                val middle = low + (high - low) / 2
                val edge = edges[middle]
                when {
                    point < edge.lower -> high = middle - 1
                    point > edge.upper -> low = middle + 1
                    else -> {
                        state = edge.target
                        break
                    }
                }
            }
        }
        return state in acceptingStates
    }

    fun isEmpty(checkpoint: () -> Unit = {}): Boolean {
        val storage = limits.storageBudget()
        storage.reserve(2)
        val seen = hashSetOf(initialState)
        val pending = ArrayDeque<Int>()
        pending.add(initialState)
        while (pending.isNotEmpty()) {
            checkpoint()
            val state = pending.removeFirst()
            if (state in acceptingStates) return false
            transitions[state].forEach {
                if (it.target !in seen) {
                    storage.reserve(2)
                    seen.add(it.target)
                    pending.add(it.target)
                }
            }
        }
        return true
    }

    fun complement(): SymbolicDfa {
        limits.storageBudget().reserve(storageSize + transitions.size - 2L * acceptingStates.size)
        return SymbolicDfa(
            transitions, initialState,
            transitions.indices.filterTo(linkedSetOf()) { it !in acceptingStates }, limits
        )
    }

    fun intersection(other: SymbolicDfa, checkpoint: () -> Unit = {}): SymbolicDfa =
        product(other, checkpoint) { left, right -> left && right }

    fun union(other: SymbolicDfa, checkpoint: () -> Unit = {}): SymbolicDfa =
        product(other, checkpoint) { left, right -> left || right }

    fun equivalentTo(other: SymbolicDfa, checkpoint: () -> Unit = {}): Boolean {
        val combined = limits.intersect(other.limits)
        val storage = combined.storageBudget()
        storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
        val pending = ArrayDeque<Pair<Int, Int>>()
        val seen = hashSetOf(initialState to other.initialState)
        pending.add(initialState to other.initialState)
        while (pending.isNotEmpty()) {
            checkpoint()
            val (left, right) = pending.removeFirst()
            if ((left in acceptingStates) != (right in other.acceptingStates)) return false
            zipTransitions(transitions[left], other.transitions[right]) { _, _, one, two ->
                val pair = one to two
                if (pair !in seen) {
                    combined.checkStates(seen.size.toLong() + 1)
                    storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
                    seen.add(pair)
                    pending.add(pair)
                }
            }
        }
        return true
    }

    fun concat(other: SymbolicDfa, checkpoint: () -> Unit = {}): SymbolicDfa {
        val nfa = SymbolicNfa(limits.intersect(other.limits))
        val left = nfa.append(this)
        val right = nfa.append(other)
        acceptingStates.forEach { nfa.addEpsilon(left + it, right + other.initialState) }
        val accepting = other.acceptingStates.mapTo(hashSetOf()) { right + it }
        return nfa.determinize(setOf(left + initialState), accepting, checkpoint)
    }

    fun star(checkpoint: () -> Unit = {}): SymbolicDfa {
        val nfa = SymbolicNfa(limits)
        val root = nfa.addState()
        val offset = nfa.append(this)
        nfa.addEpsilon(root, offset + initialState)
        acceptingStates.forEach { nfa.addEpsilon(offset + it, root) }
        return nfa.determinize(setOf(root), setOf(root), checkpoint)
    }

    /** Forgetting labels gives a unary NFA. Repetition of its *whole* reachable subset is exact. */
    // Repetition detection encloses the state/edge traversal and its per-entry allocation guard.
    @Suppress("NestedBlockDepth")
    fun acceptedLengths(
        checkpoint: () -> Unit = {},
        maxSubsetStates: Int = limits.maxStates,
    ): AcceptedLengths {
        require(maxSubsetStates > 0)
        val storage = limits.storageBudget()
        val seen = hashMapOf<Set<Int>, Int>()
        val accepted = linkedSetOf<Int>()
        var certifiedCutoff = 0
        fun exhausted(message: String): Nothing = throw LengthProjectionLimitException(
            AcceptedLengthPrefix(certifiedCutoff, accepted), message,
        )
        fun reserve(entries: Long) {
            try {
                storage.reserve(entries)
            } catch (ex: AutomataLimitException) {
                exhausted(ex.message ?: "Length projection storage exhausted")
            }
        }
        reserve(1)
        val subsetLimit = minOf(maxSubsetStates, limits.maxStates)
        var current: Set<Int> = setOf(initialState)
        var length = 0
        while (true) {
            checkpoint()
            val previous = seen[current]
            if (previous != null) {
                reserve(2L * accepted.size)
                return compactLengthPattern(accepted, previous, length - previous, checkpoint)
            }
            if (seen.size >= subsetLimit) {
                exhausted("Length projection exceeds $subsetLimit reachable subsets")
            }
            reserve(current.size.toLong() + 1)
            seen[current] = length
            if (current.any { it in acceptingStates }) {
                reserve(1)
                accepted.add(length)
            }
            // Commit only after acceptance has been evaluated and any accepting entry was stored.
            certifiedCutoff = length + 1
            val next = sortedSetOf<Int>()
            for (state in current) {
                checkpoint()
                transitions[state].forEach {
                    checkpoint()
                    if (it.target !in next) {
                        reserve(1)
                        next.add(it.target)
                    }
                }
            }
            current = next
            length++
        }
    }

    /** Exists p in [prefix] and s in [suffix] such that p ++ result ++ s is in this language. */
    // The forward and backward product traversals share one allocation budget and boundary representation.
    @Suppress("ComplexMethod", "LongMethod")
    internal fun factorBetween(prefix: SymbolicDfa, suffix: SymbolicDfa, checkpoint: () -> Unit): SymbolicDfa {
        val combined = limits.intersect(prefix.limits).intersect(suffix.limits)
        val storage = combined.storageBudget()
        storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
        val starts = linkedSetOf<Int>()
        val pending = ArrayDeque<Pair<Int, Int>>()
        val seen = hashSetOf(initialState to prefix.initialState)
        pending.add(initialState to prefix.initialState)
        while (pending.isNotEmpty()) {
            checkpoint()
            val (state, prefixState) = pending.removeFirst()
            if (prefixState in prefix.acceptingStates && state !in starts) {
                storage.reserve(1)
                starts.add(state)
            }
            zipTransitions(transitions[state], prefix.transitions[prefixState]) { _, _, one, two ->
                val pair = one to two
                if (pair !in seen) {
                    combined.checkStates(seen.size.toLong() + 1)
                    storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
                    seen.add(pair)
                    pending.add(pair)
                }
            }
        }

        // Backwards reachability in the full product identifies all possible suffix boundaries.
        val suffixSize = suffix.transitions.size
        val productSize = transitions.size.toLong() * suffixSize
        combined.checkStates(productSize)
        storage.reserve(productSize)
        val predecessors = List(productSize.toInt()) { hashSetOf<Int>() }
        val productive = hashSetOf<Int>()
        val backwards = ArrayDeque<Int>()
        for (state in transitions.indices) {
            checkpoint()
            for (suffixState in suffix.transitions.indices) {
                val source = state * suffixSize + suffixState
                if (state in acceptingStates && suffixState in suffix.acceptingStates) {
                    storage.reserve(2)
                    productive.add(source)
                    backwards.add(source)
                }
                zipTransitions(transitions[state], suffix.transitions[suffixState]) { _, _, one, two ->
                    val incoming = predecessors[one * suffixSize + two]
                    if (source !in incoming) {
                        storage.reserve(1)
                        incoming.add(source)
                    }
                }
            }
        }
        while (backwards.isNotEmpty()) {
            checkpoint()
            predecessors[backwards.removeFirst()].forEach {
                if (it !in productive) {
                    storage.reserve(2)
                    productive.add(it)
                    backwards.add(it)
                }
            }
        }
        val ends = hashSetOf<Int>()
        for (state in transitions.indices) {
            if (state * suffixSize + suffix.initialState in productive) {
                storage.reserve(1)
                ends.add(state)
            }
        }
        val nfa = SymbolicNfa(combined, storage)
        nfa.append(this)
        return nfa.determinize(starts, ends, checkpoint)
    }

    private fun product(
        other: SymbolicDfa,
        checkpoint: () -> Unit,
        accepts: (Boolean, Boolean) -> Boolean,
    ): SymbolicDfa {
        val combined = limits.intersect(other.limits)
        val storage = combined.storageBudget()
        storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
        val states = arrayListOf(initialState to other.initialState)
        val ids = hashMapOf(states.single() to 0)
        val edges = arrayListOf<List<Transition>>()
        val accepting = linkedSetOf<Int>()
        var index = 0
        while (index < states.size) {
            checkpoint()
            val (left, right) = states[index]
            if (accepts(left in acceptingStates, right in other.acceptingStates)) {
                storage.reserve(1)
                accepting.add(index)
            }
            val outgoing = arrayListOf<Transition>()
            zipTransitions(transitions[left], other.transitions[right]) { lower, upper, one, two ->
                checkpoint()
                val target = ids.getOrPut(one to two) {
                    combined.checkStates(states.size.toLong() + 1)
                    storage.reserve(PRODUCT_PAIR_STORAGE_ENTRIES)
                    states.add(one to two)
                    states.lastIndex
                }
                appendTransition(outgoing, lower, upper, target, storage)
            }
            edges.add(outgoing)
            index++
        }
        return SymbolicDfa(edges, 0, accepting, combined)
    }

    companion object {
        fun all(limits: AutomataLimits = AutomataLimits()): SymbolicDfa =
            SymbolicDfa(listOf(listOf(Transition(0, MAX_STRING_CODE_POINT, 0))), 0, setOf(0), limits)
        fun allWords(limits: AutomataLimits = AutomataLimits()): SymbolicDfa = all(limits)
        fun none(limits: AutomataLimits = AutomataLimits()): SymbolicDfa = all(limits).complement()
        fun epsilon(limits: AutomataLimits = AutomataLimits()): SymbolicDfa = literal(emptyList(), limits)

        fun literal(word: List<Int>, limits: AutomataLimits = AutomataLimits()): SymbolicDfa {
            require(word.all { it in 0..MAX_STRING_CODE_POINT })
            limits.checkLiteralSize(word.size)
            val sink = word.size + 1
            val transitions = word.map { point ->
                val target = ArrayList<Transition>()
                if (point > 0) target.add(Transition(0, point - 1, sink))
                target.add(Transition(point, point, 0))
                if (point < MAX_STRING_CODE_POINT) target.add(Transition(point + 1, MAX_STRING_CODE_POINT, sink))
                target
            }.mapIndexed { index, edges -> edges.map { if (it.target == sink) it else it.copy(target = index + 1) } }
            val dead = listOf(Transition(0, MAX_STRING_CODE_POINT, sink))
            return SymbolicDfa(transitions + listOf(dead, dead), 0, setOf(word.size), limits)
        }

        fun range(lower: Int, upper: Int, limits: AutomataLimits = AutomataLimits()): SymbolicDfa {
            if (lower > upper) return none(limits)
            require(lower >= 0 && upper <= MAX_STRING_CODE_POINT)
            limits.checkStates(RANGE_STATE_COUNT)
            limits.storageBudget().reserve(RANGE_STORAGE_ENTRIES)
            val edges = arrayListOf<Transition>()
            if (lower > 0) edges.add(Transition(0, lower - 1, 2))
            edges.add(Transition(lower, upper, 1))
            if (upper < MAX_STRING_CODE_POINT) edges.add(Transition(upper + 1, MAX_STRING_CODE_POINT, 2))
            val sink = listOf(Transition(0, MAX_STRING_CODE_POINT, 2))
            return SymbolicDfa(listOf(edges, sink, sink), 0, setOf(1), limits)
        }
    }
}

// Two component state IDs, plus worklist and visited-map membership for a product state.
private const val PRODUCT_PAIR_STORAGE_ENTRIES = 4L
// A literal position contributes one state and at most three disjoint transition intervals.
private const val LITERAL_CODE_POINT_STORAGE_ENTRIES = 4L
// Accepting/sink states, their full-domain transitions, and accepting-set membership.
private const val LITERAL_STORAGE_OVERHEAD = 5L
private const val RANGE_STATE_COUNT = 3L
// Three states, up to five intervals, and one accepting-set entry.
private const val RANGE_STORAGE_ENTRIES = 9L

/** Also charges temporary subset construction, so repeated scratch allocation has a finite budget. */
internal class AutomataStorageBudget(private val limit: Int = DEFAULT_AUTOMATA_STORAGE_LIMIT) {
    private var used = 0L

    fun reserve(entries: Long) {
        require(entries >= 0)
        if (entries > limit.toLong() - used) {
            throw AutomataLimitException("Symbolic automata storage/work exceeds $limit entries")
        }
        used += entries
    }
}

private inline fun zipTransitions(
    left: List<Transition>, right: List<Transition>, block: (Int, Int, Int, Int) -> Unit,
) {
    var one = 0
    var two = 0
    while (one < left.size && two < right.size) {
        val first = left[one]
        val second = right[two]
        val lower = maxOf(first.lower, second.lower)
        val upper = minOf(first.upper, second.upper)
        if (lower <= upper) block(lower, upper, first.target, second.target)
        if (first.upper <= second.upper) one++
        if (second.upper <= first.upper) two++
    }
}

private fun appendTransition(
    edges: MutableList<Transition>, lower: Int, upper: Int, target: Int, storage: AutomataStorageBudget,
) {
    val previous = edges.lastOrNull()
    if (previous != null && previous.target == target && previous.upper + 1 == lower) {
        edges[edges.lastIndex] = previous.copy(upper = upper)
    } else {
        storage.reserve(1)
        edges.add(Transition(lower, upper, target))
    }
}

/** Epsilon has its own edge set and cannot collide with U+0000. */
internal class SymbolicNfa(
    private val limits: AutomataLimits,
    private val storage: AutomataStorageBudget = limits.storageBudget(),
) {
    val transitions = arrayListOf<MutableList<Transition>>()
    private val epsilon = arrayListOf<MutableSet<Int>>()

    fun addState(): Int {
        limits.checkStates(transitions.size.toLong() + 1)
        storage.reserve(2)
        transitions.add(arrayListOf())
        epsilon.add(linkedSetOf())
        return transitions.lastIndex
    }

    fun addEpsilon(from: Int, to: Int) {
        if (to !in epsilon[from]) {
            storage.reserve(1)
            epsilon[from].add(to)
        }
    }

    fun addTransition(from: Int, lower: Int, upper: Int, to: Int) {
        require(lower in 0..MAX_STRING_CODE_POINT && upper in lower..MAX_STRING_CODE_POINT)
        storage.reserve(1)
        transitions[from].add(Transition(lower, upper, to))
    }

    fun append(dfa: SymbolicDfa, checkpoint: () -> Unit = {}): Int {
        val offset = transitions.size
        dfa.transitions.forEach { outgoing ->
            checkpoint()
            val state = addState()
            outgoing.forEach {
                checkpoint()
                storage.reserve(1)
                transitions[state].add(it.copy(target = it.target + offset))
            }
        }
        return offset
    }

    // Keep epsilon closure, interval partitioning and subset interning together with their storage accounting.
    @Suppress("ComplexMethod", "LongMethod", "NestedBlockDepth")
    fun determinize(starts: Set<Int>, accepting: Set<Int>, checkpoint: () -> Unit): SymbolicDfa {
        storage.reserve(starts.size.toLong() + accepting.size)
        fun closure(states: Set<Int>): Set<Int> {
            storage.reserve(2L * states.size)
            val result = states.toSortedSet()
            val pending = ArrayDeque<Int>()
            pending.addAll(states)
            while (pending.isNotEmpty()) {
                checkpoint()
                epsilon[pending.removeFirst()].forEach {
                    if (it !in result) {
                        storage.reserve(2)
                        result.add(it)
                        pending.add(it)
                    }
                }
            }
            return result
        }

        val subsets = arrayListOf(closure(starts))
        storage.reserve(2)
        val ids = hashMapOf(subsets.single() to 0)
        val result = arrayListOf<List<Transition>>()
        val resultAccepting = linkedSetOf<Int>()
        var index = 0
        while (index < subsets.size) {
            checkpoint()
            val subset = subsets[index]
            if (subset.any { it in accepting }) {
                storage.reserve(1)
                resultAccepting.add(index)
            }
            storage.reserve(2)
            val boundaries = sortedSetOf(0, MAX_STRING_CODE_POINT + 1)
            val outgoing = arrayListOf<Transition>()
            for (state in subset) {
                checkpoint()
                storage.reserve(transitions[state].size.toLong())
                outgoing.addAll(transitions[state])
            }
            outgoing.forEach {
                checkpoint()
                if (it.lower !in boundaries) {
                    storage.reserve(1)
                    boundaries.add(it.lower)
                }
                if (it.upper + 1 !in boundaries) {
                    storage.reserve(1)
                    boundaries.add(it.upper + 1)
                }
            }
            storage.reserve(boundaries.size.toLong())
            val intervals = boundaries.toList()
            val resultEdges = arrayListOf<Transition>()
            for (position in 0 until intervals.lastIndex) {
                checkpoint()
                val lower = intervals[position]
                val targets = hashSetOf<Int>()
                for (edge in outgoing) {
                    if (lower in edge.lower..edge.upper && edge.target !in targets) {
                        storage.reserve(1)
                        targets.add(edge.target)
                    }
                }
                val next = closure(targets)
                val target = ids.getOrPut(next) {
                    limits.checkStates(subsets.size.toLong() + 1)
                    storage.reserve(2)
                    subsets.add(next)
                    subsets.lastIndex
                }
                appendTransition(resultEdges, lower, intervals[position + 1] - 1, target, storage)
            }
            result.add(resultEdges)
            index++
        }
        return SymbolicDfa(result, 0, resultAccepting, limits)
    }
}

package io.ksmt.solver.noodler.automaton


abstract class Node<N: Node<N>>(
    var terminal: Boolean,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    abstract fun copy(): N
    abstract fun put(letter: Char, v: N)
    abstract fun getList(letter: Char): List<N>
    abstract fun getAllList(): Map<Char, List<N>>
    abstract fun newEmpty(terminal: Boolean): N
    abstract fun remove(letter: Char)
}

class DFANode(terminal: Boolean, val next: MutableMap<Char, DFANode>) : Node<DFANode>(terminal) {
    override fun copy(): DFANode {
        return DFANode(terminal, mutableMapOf())
    }

    override fun put(letter: Char, v: DFANode) {
        next[letter] = v
    }

    override fun getList(letter: Char): List<DFANode> {
        val v = next[letter] ?: return emptyList()
        return listOf(v)
    }

    override fun getAllList(): Map<Char, List<DFANode>> {
        return next.mapValues { listOf(it.value) }
    }

    override fun newEmpty(terminal: Boolean): DFANode {
        return DFANode(terminal, mutableMapOf())
    }

    override fun remove(letter: Char) {
        next.remove(letter)
    }

    fun get(letter: Char): DFANode? {
        return next[letter]
    }

    fun getAll(): Map<Char, DFANode> {
        return next
    }
}

class NFANode(terminal: Boolean, val next: MutableMap<Char, MutableList<NFANode>>) : Node<NFANode>(terminal) {
    override fun copy(): NFANode {
        return NFANode(terminal, mutableMapOf())
    }

    override fun put(letter: Char, v: NFANode) {
        next.getOrPut(letter) { mutableListOf() }.add(v)
    }

    override fun getList(letter: Char): List<NFANode> {
        return next[letter] ?: emptyList()
    }

    override fun getAllList(): Map<Char, List<NFANode>> {
        return next
    }

    override fun newEmpty(terminal: Boolean): NFANode {
        return NFANode(terminal, mutableMapOf())
    }

    override fun remove(letter: Char) {
        next.remove(letter)
    }
}

fun <N : Node<N>> copy(v: N, copies: MutableMap<N, N> = mutableMapOf()): N {
    val copied = v.copy()
    copies[v] = copied
    for ((letter, children) in v.getAllList()) {
        for (u in children) {
            var cu = copies[u]
            if (u == v) {
                cu = copied
            } else if (cu == null) {
                cu = copy(u, copies)
                copies[u] = cu
            }
            copied.put(letter, cu)
        }
    }
    return copied
}

fun <N : Node<N>> listNodes(v: N, predicate: (N) -> Boolean, visited: MutableSet<N> = mutableSetOf()): List<N> {
    val result = LinkedHashSet<N>()
    val pending = ArrayDeque<N>()

    visited.add(v)
    pending.add(v)

    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (predicate(current)) {
            result.add(current)
        }

        for (u in current.getAllList().values.flatten()) {
            if (visited.add(u)) {
                pending.add(u)
            }
        }
    }

    return result.toList()
}

fun <N : Node<N>> product(one: N, other: N): N {
    val new = one.newEmpty(one.terminal && other.terminal)
    val keep = one.getAllList().keys.intersect(other.getAllList().keys)
    for (letter in keep) {
        new.put(letter, one.newEmpty(false))
    }
    return new
}

package io.ksmt.solver.noodler.automaton

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

abstract class Automaton<N: Node<N>, A: Automaton<N, A>>(
    val start: N,
    var alphabet: List<Char>,
) {
    var terminal: List<N> = listNodes(start, { it.terminal })

    protected abstract fun newAutomaton(start: N, alphabet: List<Char>): Automaton<N, A>
    protected abstract fun scanExtraSymbols(visited: MutableSet<N>)

    open fun check(word: String): Boolean {
        var visited = mutableSetOf(start)
        scanExtraSymbols(visited)
        for (letter in word) {
            val newVisited = mutableSetOf<N>()
            for (current in visited) {
                for (new in current.getList(letter)) {
                    if (!newVisited.contains(new)) {
                        newVisited.add(new)
                    }
                }
            }
            visited = newVisited
            scanExtraSymbols(visited)
        }
        return visited.intersect(terminal.toSet()).isNotEmpty()
    }

    open fun anyWord(): String? {
        if (isEmpty()) {
            return null
        }

        val startStates = linkedSetOf(start)
        scanExtraSymbols(startStates)
        if (startStates.any { it.terminal }) {
            return ""
        }

        val queue = ArrayDeque<Pair<Set<N>, String>>()
        val visited = mutableSetOf<Set<N>>()
        val startSnapshot = LinkedHashSet(startStates)

        queue.add(startSnapshot to "")
        visited.add(startSnapshot)

        while (queue.isNotEmpty()) {
            val (currentStates, word) = queue.removeFirst()
            val nextByLetter = sortedMapOf<Char, MutableSet<N>>()

            for (state in currentStates) {
                for ((letter, nextStates) in state.getAllList()) {
                    if (letter == EPS) {
                        continue
                    }
                    nextByLetter.getOrPut(letter) { LinkedHashSet() }.addAll(nextStates)
                }
            }

            for ((letter, nextStates) in nextByLetter) {
                val reachable = LinkedHashSet(nextStates)
                scanExtraSymbols(reachable)
                if (reachable.any { it.terminal }) {
                    return word + letter
                }

                val snapshot = LinkedHashSet(reachable)
                if (visited.add(snapshot)) {
                    queue.add(snapshot to (word + letter))
                }
            }
        }

        return null
    }

    open fun isEmpty(): Boolean {
        return listNodes(start, { true }).all { !it.terminal}
    }

    open fun isNotEmpty(): Boolean {
        return !isEmpty()
    }

    fun copy(): Automaton<N, A> {
        val root = copy(start)
        return newAutomaton(root, alphabet)
    }

    fun picture(filename: String) {
        val output = Paths.get(filename).toAbsolutePath()
        val format = graphvizFormat(output.fileName.toString())

        output.parent?.let(Files::createDirectories)

        val process = try {
            ProcessBuilder("dot", "-T$format", "-o", output.toString())
                .redirectErrorStream(true)
                .start()
        } catch (ex: IOException) {
            throw IllegalStateException(
                "Failed to start Graphviz 'dot'. Make sure Graphviz is installed and available on PATH.",
                ex
            )
        }

        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(toDot())
        }

        val graphvizOutput = process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText().trim()
        }
        val exitCode = process.waitFor()

        check(exitCode == 0) {
            val details = if (graphvizOutput.isEmpty()) "" else ": $graphvizOutput"
            "Graphviz failed to render automaton$details"
        }
        check(Files.exists(output) && Files.size(output) > 0) {
            "Graphviz did not create output file '$output'"
        }
    }

    internal fun toDot(): String {
        val nodes = orderedNodes()
        val nodeIds = nodes.mapIndexed { index, node -> node to "q$index" }.toMap()

        return buildString {
            fun appendDotLine(line: String) {
                append(line)
                append('\n')
            }

            appendDotLine("digraph Automaton {")
            appendDotLine("    rankdir=LR;")
            appendDotLine("    start [shape=point];")
            appendDotLine("    start -> ${nodeIds.getValue(start)};")

            for (node in nodes) {
                val nodeId = nodeIds.getValue(node)
                val shape = if (node.terminal) "doublecircle" else "circle"
                appendDotLine("    $nodeId [shape=$shape, label=\"${escapeGraphviz(nodeId)}\"];")
            }

            for (node in nodes) {
                val groupedTransitions = linkedMapOf<N, MutableSet<Char>>()
                for ((letter, children) in node.getAllList().entries.sortedBy { it.key.code }) {
                    for (child in children.sortedBy { nodeIds.getValue(it) }) {
                        groupedTransitions.getOrPut(child) { linkedSetOf() }.add(letter)
                    }
                }

                for ((child, letters) in groupedTransitions.entries.sortedBy { nodeIds.getValue(it.key) }) {
                    val label = letters
                        .sortedBy { it.code }
                        .joinToString(", ") { graphvizLabel(it) }

                    appendDotLine(
                        "    ${nodeIds.getValue(node)} -> ${nodeIds.getValue(child)} " +
                            "[label=\"${escapeGraphviz(label)}\"];"
                    )
                }
            }

            appendDotLine("}")
        }
    }

    private fun orderedNodes(): List<N> {
        val ordered = LinkedHashSet<N>()
        val queue = ArrayDeque<N>()

        ordered.add(start)
        queue.add(start)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for ((_, children) in current.getAllList().entries.sortedBy { it.key.code }) {
                for (child in children) {
                    if (ordered.add(child)) {
                        queue.add(child)
                    }
                }
            }
        }

        return ordered.toList()
    }
}

private fun graphvizFormat(filename: String): String {
    val extension = filename.substringAfterLast('.', "")
    require(extension.isNotBlank()) {
        "filename must have an extension supported by Graphviz, for example '.png' or '.svg'"
    }

    return when (extension.lowercase()) {
        "jpg" -> "jpeg"
        else -> extension.lowercase()
    }
}

private fun graphvizLabel(symbol: Char): String = when (symbol) {
    EPS -> "eps"
    ' ' -> "space"
    else -> if (Character.isISOControl(symbol)) {
        "U+" + symbol.code.toString(16).uppercase().padStart(4, '0')
    } else {
        symbol.toString()
    }
}

private fun escapeGraphviz(value: String): String = buildString {
    for (symbol in value) {
        when (symbol) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            else -> append(symbol)
        }
    }
}

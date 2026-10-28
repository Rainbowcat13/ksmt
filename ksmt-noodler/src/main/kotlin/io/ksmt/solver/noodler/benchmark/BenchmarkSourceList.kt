package io.ksmt.solver.noodler.benchmark

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** The original list bytes are retained so every run has an immutable selection snapshot. */
internal class BenchmarkSourceList private constructor(
    val path: Path,
    val content: ByteArray,
    val relativePaths: List<String>,
) {
    val sha256: String = sha256Bytes(content)

    fun metadata(): Map<String, String> = mapOf(
        "source-list" to path.toString(), "source-list-sha256" to sha256,
        "source-list-count" to relativePaths.size.toString(),
    )

    companion object {
        fun read(path: Path): BenchmarkSourceList {
            val absolute = path.toAbsolutePath().normalize()
            val bytes = Files.readAllBytes(absolute)
            val names = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF")
                .lineSequence().filter { it.isNotEmpty() }.toList()
            require(names.isNotEmpty()) { "Source list is empty: $absolute" }
            require(names.distinct().size == names.size) { "Duplicate source-list path: $absolute" }
            names.forEach { name ->
                val canonical = name == name.trim() && '\\' !in name && ':' !in name
                val relative = name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
                require(canonical && relative && name.endsWith(".smt2")) {
                    "Source-list entries must be exact relative .smt2 paths using forward slashes: '$name'"
                }
            }
            return BenchmarkSourceList(absolute, bytes, names)
        }
    }
}

internal fun discoverSamples(source: Path, prefix: String, selection: BenchmarkSourceList?): List<BenchmarkSample> {
    if (selection != null) {
        require(prefix.isEmpty()) { "--source-list cannot be combined with --prefix" }
        val realSource = source.toRealPath()
        val realPaths = hashSetOf<Path>()
        return selection.relativePaths.sorted().map { name ->
            val path = source.resolve(name).normalize()
            require(path.startsWith(source) && Files.isRegularFile(path)) { "Listed source does not exist: $name" }
            val realPath = path.toRealPath()
            require(realPath.startsWith(realSource)) { "Listed source escapes the corpus: $name" }
            require(realPaths.add(realPath)) { "Source-list aliases resolve to the same source: $name" }
            describeSample(source, path)
        }
    }
    return Files.walk(source).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".smt2") }
            .filter { source.relativize(it).invariantSeparatorsPathString.startsWith(prefix) }
            .sorted().map { describeSample(source, it) }.toList()
    }
}

private fun describeSample(source: Path, path: Path): BenchmarkSample {
    val text = Files.readString(path)
    return BenchmarkSample(source.relativize(path).invariantSeparatorsPathString, sha256File(path), Files.size(path),
        Regex("\\(set-logic\\s+([^\\s)]+)\\s*\\)").find(text)?.groupValues?.get(1).orEmpty(),
        Regex("\\(set-info\\s+:status\\s+(sat|unsat|unknown)\\s*\\)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.uppercase() ?: "UNKNOWN")
}

package io.ksmt.solver.noodler.benchmark

import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.noodler.KNoodlerSolver
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import io.ksmt.solver.z3.KZ3Solver
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.UUID

internal const val BENCHMARK_SCHEMA = "1"
private const val HASH_BUFFER_BYTES = 64 * 1024
private const val BYTE_MASK = 0xff

internal enum class BenchmarkBackend(val id: String) {
    Z3("z3"), CVC5("cvc5"), NOODLER_Z3("noodler-z3"), NOODLER_CVC5("noodler-cvc5"),
    NOODLER_YICES("noodler-yices");

    fun create(
        ctx: KContext,
        hybrid: BenchmarkHybridSettings = BenchmarkHybridSettings(),
    ): KSolver<out KSolverConfiguration> = when (this) {
        Z3 -> KZ3Solver(ctx)
        CVC5 -> KCvc5Solver(ctx)
        NOODLER_Z3 -> KNoodlerSolver(ctx, KZ3Solver(ctx))
        NOODLER_CVC5 -> KNoodlerSolver(ctx, KCvc5Solver(ctx))
        NOODLER_YICES -> KNoodlerYicesSolver(ctx).also { it.configure { hybrid.applyTo(this) } }
    }

    val validator: BenchmarkBackend get() = when (this) {
        Z3, NOODLER_Z3 -> CVC5
        else -> Z3
    }

    companion object {
        fun parse(value: String): BenchmarkBackend = values().firstOrNull { it.id == value }
            ?: error("Unknown solver '$value'; expected ${values().joinToString { it.id }}")
    }
}

internal data class BenchmarkLimits(
    val solveMs: Long = 10_000,
    val startupParseMs: Long = 30_000,
    val validationMs: Long = 10_000,
) {
    init {
        require(solveMs > 0 && startupParseMs > 0 && validationMs > 0) { "Timeouts must be positive" }
    }
}

internal data class BenchmarkSample(
    val relativePath: String,
    val sha256: String,
    val size: Long,
    val logic: String,
    val expectedStatus: String,
) {
    val family: String get() = relativePath.split('/').take(2).joinToString("/")
    val id: String get() = sha256Text("$relativePath\n$sha256")
}

/** Length-safe, line-oriented worker protocol; stdout may also contain arbitrary solver logs. */
internal class BenchmarkProtocol(private val token: String) {
    private val prefix = "KSMT_BENCHMARK:$token:"

    fun encode(kind: String, values: Map<String, String>): String = prefix + kind + "\t" +
        values.entries.sortedBy { it.key }.joinToString("\t") { "${encodeField(it.key)}=${encodeField(it.value)}" }

    fun decode(line: String): Pair<String, Map<String, String>>? {
        if (!line.startsWith(prefix)) return null
        val parts = line.removePrefix(prefix).split('\t')
        return try {
            parts.first() to parts.drop(1).filter { it.isNotEmpty() }.associate {
                val separator = it.indexOf('=')
                require(separator >= 0)
                decodeField(it.substring(0, separator)) to decodeField(it.substring(separator + 1))
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodeField(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
}

internal object BenchmarkCsv {
    fun row(values: Iterable<String>): String = values.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" } + "\n"

    // Explicit CSV state transitions keep quote/newline handling together.
    @Suppress("ComplexMethod")
    fun parse(text: String): List<List<String>> {
        val rows = arrayListOf<List<String>>()
        var row = arrayListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '"' && quoted && text.getOrNull(index + 1) == '"' -> { field.append('"'); index++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { row.add(field.toString()); field.setLength(0) }
                ch == '\r' && !quoted && text.getOrNull(index + 1) == '\n' -> Unit
                ch == '\n' && !quoted -> {
                    row.add(field.toString()); rows.add(row)
                    row = arrayListOf(); field.setLength(0)
                }
                else -> field.append(ch)
            }
            index++
        }
        require(!quoted) { "Truncated CSV field" }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}

internal fun sha256Text(value: String): String = sha256Bytes(value.toByteArray(StandardCharsets.UTF_8))

internal fun sha256Bytes(value: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(value))

internal fun sha256File(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

internal fun writeProperties(path: Path, values: Map<String, String>) {
    val temporary = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
    val properties = Properties().also { it.putAll(values) }
    Files.newOutputStream(temporary).use { properties.store(it, "KSMT benchmark schema $BENCHMARK_SCHEMA") }
    try {
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temporary, path)
    }
}

internal fun readProperties(path: Path): Map<String, String> = Properties().let { properties ->
    Files.newInputStream(path).use(properties::load)
    properties.stringPropertyNames().associateWith(properties::getProperty)
}

internal fun benchmarkClassPath(): String = System.getProperty("noodler.benchmark.classpath")
    ?: System.getProperty("java.class.path")

internal fun parseOptions(args: List<String>): Map<String, String> {
    require(args.size % 2 == 0) { "Every option requires a value" }
    val result = linkedMapOf<String, String>()
    args.chunked(2).forEach { (name, value) ->
        require(name.startsWith("--") && result.put(name.removePrefix("--"), value) == null) {
            "Duplicate/invalid option $name"
        }
    }
    return result
}

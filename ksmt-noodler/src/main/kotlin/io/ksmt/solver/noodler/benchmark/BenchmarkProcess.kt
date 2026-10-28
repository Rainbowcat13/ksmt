package io.ksmt.solver.noodler.benchmark

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

private const val PROCESS_POLL_MS = 25L
private const val TERMINATION_GRACE_MS = 5_000L

/** Every backend uses this same parent watchdog and continuously drained output pipe. */
internal class BenchmarkProcess(private val limits: BenchmarkLimits) {
    // The harness serializes arbitrary worker/transport failures instead of losing the whole run.
    @Suppress("TooGenericExceptionCaught", "ComplexMethod")
    fun run(command: List<String>, token: String, logPath: Path, workingDirectory: Path): Map<String, String> {
        val started = System.nanoTime()
        val protocol = BenchmarkProtocol(token)
        val snapshot = Snapshot(started)
        val process = try {
            ProcessBuilder(command).directory(workingDirectory.toFile()).redirectErrorStream(true).start()
        } catch (error: Exception) {
            val detail = "${error.javaClass.simpleName}: ${error.message}"
            Files.writeString(logPath, detail)
            return mapOf("outcome" to "CRASH", "raw_status" to "NOT_RUN", "details" to detail,
                "wall_ns" to (System.nanoTime() - started).toString())
        }
        val drainer = Thread({
            try {
                Files.newBufferedWriter(logPath, StandardCharsets.UTF_8).use { log ->
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            log.appendLine(line)
                            protocol.decode(line)?.let { (kind, values) ->
                                synchronized(snapshot) { snapshot.accept(kind, values, System.nanoTime()) }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                synchronized(snapshot) { snapshot.streamError = "${error.javaClass.simpleName}: ${error.message}" }
            }
        }, "noodler-benchmark-output").apply { isDaemon = true; start() }

        var timedOutPhase: String? = null
        while (!process.waitFor(PROCESS_POLL_MS, TimeUnit.MILLISECONDS)) {
            synchronized(snapshot) {
                val budgetMs = when (snapshot.phase) {
                    "SOLVE" -> limits.solveMs
                    "VALIDATE" -> limits.validationMs
                    "FINISH" -> TERMINATION_GRACE_MS
                    else -> limits.startupParseMs
                }
                if (System.nanoTime() - snapshot.phaseStarted >= TimeUnit.MILLISECONDS.toNanos(budgetMs)) {
                    timedOutPhase = snapshot.phase
                }
            }
            if (timedOutPhase != null) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
                break
            }
        }
        drainer.join(TERMINATION_GRACE_MS)
        val exit = if (process.isAlive) "alive-after-kill" else process.exitValue().toString()
        return synchronized(snapshot) {
            if (drainer.isAlive) snapshot.streamError = "Worker output drainer did not terminate"
            finishResult(snapshot, exit, timedOutPhase, started)
        }
    }

    // Terminal classification retains native status independently of transport/deadline failures.
    @Suppress("ComplexMethod")
    private fun finishResult(
        snapshot: Snapshot,
        exit: String,
        timedOutPhase: String?,
        started: Long,
    ): Map<String, String> {
        val result = LinkedHashMap(snapshot.values)
        result.putIfAbsent("raw_status", "NOT_RUN")
        result.putIfAbsent("validation_status", "NOT_APPLICABLE")
        result["startup_ns"] = (snapshot.workerStarted?.minus(started) ?: (System.nanoTime() - started)).toString()
        result["wall_ns"] = (System.nanoTime() - started).toString()
        result["worker_exit"] = exit
        if (timedOutPhase != null) {
            result["timeout_phase"] = timedOutPhase
            result["outcome"] = when (timedOutPhase) {
                "SOLVE" -> "TIMEOUT"
                "VALIDATE" -> "UNVERIFIED_WITNESS"
                "FINISH" -> "CRASH"
                else -> "STARTUP_PARSE_TIMEOUT"
            }
            if (timedOutPhase == "SOLVE") {
                result["raw_status"] = "NOT_RETURNED"
                result["solve_ns"] = TimeUnit.MILLISECONDS.toNanos(limits.solveMs).toString()
            }
            if (timedOutPhase == "VALIDATE") {
                result["validation_status"] = "TIMEOUT"
                result["validation_ns"] = TimeUnit.MILLISECONDS.toNanos(limits.validationMs).toString()
            }
            result["details"] = "${result["details"].orEmpty()} External $timedOutPhase watchdog expired".trim()
        } else if (exit != "0" || !snapshot.finished || snapshot.streamError != null) {
            result["outcome"] = "CRASH"
            result["details"] = "Worker exit=$exit; resultReceived=${snapshot.finished}; " +
                "${snapshot.streamError.orEmpty()}; ${result["details"].orEmpty()}"
        } else if ((result["solve_ns"]?.toLongOrNull() ?: 0) > TimeUnit.MILLISECONDS.toNanos(limits.solveMs)) {
            result["outcome"] = "TIMEOUT"
            result["timeout_phase"] = "SOLVE"
            result["details"] = "Reported assertion/check duration exceeded the common solve deadline"
        } else if (result["raw_status"] == "SAT" &&
            (result["validation_ns"]?.toLongOrNull() ?: 0) > TimeUnit.MILLISECONDS.toNanos(limits.validationMs)) {
            result["outcome"] = "UNVERIFIED_WITNESS"
            result["timeout_phase"] = "VALIDATE"
            result["validation_status"] = "TIMEOUT"
            result["details"] = "Reported witness validation duration exceeded its deadline"
        }
        return result
    }

    private class Snapshot(started: Long) {
        var phase = "STARTUP_PARSE"
        var phaseStarted = started
        var workerStarted: Long? = null
        var finished = false
        var streamError: String? = null
        val values = linkedMapOf<String, String>()

        fun accept(kind: String, incoming: Map<String, String>, now: Long) {
            when (kind) {
                "PHASE" -> {
                    val next = incoming.getValue("phase")
                    if (next == "PARSE") workerStarted = now
                    if ((next == "SOLVE" || next == "VALIDATE") && phase != next) { phase = next; phaseStarted = now }
                }
                "ELIGIBILITY" -> values.putAll(incoming)
                "SOLVED" -> {
                    values.putAll(incoming)
                    phase = if (incoming["raw_status"] == "SAT") "VALIDATE" else "FINISH"
                    phaseStarted = now
                }
                "RESULT" -> {
                    values.putAll(incoming)
                    finished = true
                    phase = "FINISH"
                    phaseStarted = now
                }
            }
        }
    }
}

internal fun benchmarkJavaCommand(mainClass: String, arguments: List<String>): List<String> {
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java"
    return listOf(
        Paths.get(System.getProperty("java.home"), "bin", executable).toString(),
        "-Xss8m", "-cp", benchmarkClassPath(), mainClass,
    ) + arguments
}

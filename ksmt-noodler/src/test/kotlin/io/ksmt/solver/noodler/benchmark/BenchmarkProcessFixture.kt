package io.ksmt.solver.noodler.benchmark

import kotlin.system.exitProcess

/** A subprocess fixture exercises the real OS pipes/watchdog without relying on solver timing. */
fun main(args: Array<String>) {
    val protocol = BenchmarkProtocol(args[0])
    fun emit(kind: String, values: Map<String, String>) {
        println(protocol.encode(kind, values))
        System.out.flush()
    }
    emit("PHASE", mapOf("phase" to "PARSE"))
    when (args[1]) {
        "parse-hang" -> while (true) Thread.sleep(1_000)
        "crash" -> exitProcess(7)
    }
    emit("ELIGIBILITY", mapOf("eligible" to "true", "parse_ns" to "1000"))
    emit("PHASE", mapOf("phase" to "SOLVE"))
    when (args[1]) {
        "solve-hang" -> while (true) Thread.sleep(1_000)
        "verbose" -> repeat(2_000) { println("fixture-$it " + "x".repeat(1024)) }
    }
    val result = mapOf("raw_status" to "SAT", "outcome" to "SAT", "validation_status" to "SAT",
        "solve_ns" to if (args[1] == "late-result") "100000001" else "1000")
    emit("SOLVED", result)
    emit("PHASE", mapOf("phase" to "VALIDATE"))
    if (args[1] == "validation-hang") while (true) Thread.sleep(1_000)
    emit("RESULT", result)
}

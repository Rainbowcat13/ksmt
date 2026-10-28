package io.ksmt.solver.noodler.core

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.expr.KConst
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.automaton.ALL_CHAR_ALPHABET
import io.ksmt.solver.z3.KZ3SMTLibParser
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KStringSort
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.time.Duration

private const val DEFAULT_SAMPLE = "0013__v003__Burns_sat_non_incre_equiv_init_0_0.smt2"
private const val HEX_RADIX = 16

fun main(args: Array<String>) {
    val repoRoot = Paths.get("").toAbsolutePath().normalize()
    val sample = resolveSamplePath(repoRoot, args)

    with(KContext()) {
        val assertions = KZ3SMTLibParser(this).parse(sample)
        val analysis = assertions.analyzeForNoodler(ALL_CHAR_ALPHABET)
        val constants = collectConstants(assertions)

        println("Sample: ${sample.invariantSeparatorsPathString}")
        println("Assertions: ${assertions.size}")
        println("Constants: ${constants.joinToString { it.decl.name }}")
        println(
            "Noodler analysis: skip=${analysis.skipNoodler}, " +
                "reason=${analysis.skipReason ?: "<none>"}, alphabetSize=${analysis.alphabet.size}"
        )

        val noodlerStatements = extractNoodlerStatements(assertions)
        println("Noodler statements: ${noodlerStatements.size}")

        if (!analysis.skipNoodler && noodlerStatements.isNotEmpty()) {
            val noodler = Noodler(noodlerStatements, analysis.alphabet)
            val (sat, extraConstraints) = noodler.solve()
            val noodlerStatus = if (sat) "NO CONTRADICTION PROVED" else "CONTRADICTION PROVED"
            println("Noodler propagation: $noodlerStatus")
            println("Noodler extra constraints: ${extraConstraints.size}")
        } else {
            println("Noodler status: SKIPPED")
        }

        runZ3Sample(sample, assertions, constants)
    }
}

private fun runZ3Sample(
    sample: Path,
    assertions: List<KExpr<KBoolSort>>,
    constants: List<KConst<*>>,
) = with(assertions.firstOrNull()?.ctx ?: error("No context available for $sample")) {
    KZ3Solver(this).use { solver ->
        assertions.forEach(solver::assert)

        val status = solver.check(Duration.INFINITE)
        println("Z3 status: $status")
        if (status == KSolverStatus.UNKNOWN) {
            println("Z3 reason: ${solver.reasonOfUnknown()}")
            return
        }

        if (status != KSolverStatus.SAT) {
            return
        }

        solver.model().use { model ->
            printModel(model, constants)
        }
    }
}

private fun printModel(model: KModel, constants: List<KConst<*>>) {
    println("Z3 model:")
    for (constant in constants.sortedBy { it.decl.name }) {
        val value = try {
            model.eval(constant, isComplete = true)
        } catch (error: Throwable) {
            println("  ${constant.decl.name} = <error: ${error::class.simpleName}${error.message?.let { ": $it" } ?: ""}>")
            continue
        }

        println("  ${constant.decl.name} = ${renderValue(value)}")
    }
}

private fun renderValue(value: Any): String = when (value) {
    is KStringLiteralExpr -> value.codePoints.joinToString(separator = "", prefix = "\"", postfix = "\"") {
        "\\u{${it.toString(HEX_RADIX)}}"
    }
    else -> value.toString()
}

private fun collectConstants(assertions: List<KExpr<KBoolSort>>): List<KConst<*>> {
    val collected = linkedMapOf<KDecl<*>, KConst<*>>()
    val pending = ArrayDeque<KExpr<*>>()
    val visited = linkedSetOf<KExpr<*>>()
    pending.addAll(assertions)

    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (!visited.add(current)) {
            continue
        }

        if (current is KConst<*>) {
            collected.putIfAbsent(current.decl, current)
        }

        val app = current as? io.ksmt.expr.KApp<*, *> ?: continue
        pending.addAll(app.args)
    }

    return collected.values.toList()
}

private fun resolveSamplePath(repoRoot: Path, args: Array<String>): Path {
    val explicitSample = parseSampleArgument(args)
    val candidate = explicitSample ?: DEFAULT_SAMPLE

    val direct = Paths.get(candidate)
    if (direct.isAbsolute() && Files.isRegularFile(direct)) {
        return direct.normalize()
    }

    val fromWorkingDir = repoRoot.resolve(candidate).normalize()
    if (Files.isRegularFile(fromWorkingDir)) {
        return fromWorkingDir
    }

    val fromQfSimple = repoRoot.resolve("ksmt-noodler").resolve("qf_simple_tests").resolve(candidate).normalize()
    require(Files.isRegularFile(fromQfSimple)) {
        "Sample '$candidate' not found in working dir or ksmt-noodler/qf_simple_tests"
    }
    return fromQfSimple
}

private fun parseSampleArgument(args: Array<String>): String? {
    var index = 0
    while (index < args.size) {
        when (val argument = args[index]) {
            "--sample" -> return args.getOrNull(index + 1)
                ?: error("Missing sample path for --sample")

            else -> error("Unknown argument '$argument'")
        }
    }
    return null
}

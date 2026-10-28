package io.ksmt.solver.noodler.benchmark

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val RUN_ID_SUFFIX_LENGTH = 8
private const val SUMMARY_INTERVAL = 25
private const val BYTE_MASK = 0xff

fun main(args: Array<String>) = runNoodlerBenchmarks(args.toList())

/** Also used by the historical QF runner's run/launch aliases. */
fun runNoodlerBenchmarks(args: List<String>) {
    require(args.isNotEmpty()) { "Expected run, summarize, or worker" }
    val options = parseOptions(args.drop(1))
    when (args.first()) {
        "worker" -> runBenchmarkWorker(options)
        "summarize" -> {
            val directory = Paths.get(options.getValue("resume")).toAbsolutePath().normalize()
            val metadata = readProperties(directory.resolve("metadata.properties"))
            val samples = readManifest(directory.resolve("manifest.csv"))
            writeBenchmarkSummary(directory, metadata, samples, readRecords(directory), readExclusions(directory))
            println("Summary: ${directory.resolve("summary.md")}")
        }
        "run" -> runComparison(options)
        else -> error("Unknown command '${args.first()}'")
    }
}

// Keep the manifest/configuration transaction and the resumable sequential job loop in one coordinator.
@Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
private fun runComparison(options: Map<String, String>) {
    val allowed = setOf("source", "source-list", "output", "resume", "solvers", "solve-ms",
        "startup-parse-ms", "validation-ms", "repeats", "prefix", "offset", "limit", "max-jobs", "max-wall-ms") +
        BenchmarkVariant.optionNames
    require(options.keys.all { it in allowed }) { "Unknown options: ${options.keys - allowed}" }
    val workingDirectory = Paths.get("").toAbsolutePath().normalize()
    val existing = options["resume"]?.let { Paths.get(it).toAbsolutePath().normalize() }
    val saved = existing?.let { readProperties(it.resolve("metadata.properties")) }
    val environment = benchmarkRuntimeEnvironment()
    saved?.let { requireResumeEnvironment(it, environment) }
    val variant = BenchmarkVariant.resolve(options, saved)
    val selection = (options["source-list"] ?: saved?.get("source-list"))?.takeIf { it.isNotEmpty() }
        ?.let { BenchmarkSourceList.read(Paths.get(it)) }
    val source = Paths.get(options["source"] ?: saved?.get("source") ?: locateCorpus(workingDirectory).toString())
        .toAbsolutePath().normalize()
    require(Files.isDirectory(source)) { "Source directory does not exist: $source" }
    val defaults = linkedMapOf(
        "source" to source.toString(),
        "solvers" to BenchmarkBackend.values().joinToString(",") { it.id },
        "solve-ms" to "10000", "startup-parse-ms" to "30000", "validation-ms" to "10000",
        "repeats" to "1", "prefix" to "", "offset" to "0", "limit" to Int.MAX_VALUE.toString(),
    )
    val configuration = defaults.mapValues { (key, default) ->
        if (key == "source") source.toString() else options[key] ?: saved?.get(key) ?: default
    } + variant.options() + (selection?.metadata() ?: mapOf(
        "source-list" to "", "source-list-sha256" to "", "source-list-count" to "0",
    ))
    val backends = configuration.getValue("solvers").split(',').map(BenchmarkBackend::parse)
    require(backends.isNotEmpty() && backends.distinct().size == backends.size) { "Solver IDs must be unique" }
    val repeats = configuration.getValue("repeats").toInt().also { require(it > 0) }
    val limits = BenchmarkLimits(configuration.getValue("solve-ms").toLong(),
        configuration.getValue("startup-parse-ms").toLong(), configuration.getValue("validation-ms").toLong())
    val offset = configuration.getValue("offset").toInt().also { require(it >= 0) }
    val limit = configuration.getValue("limit").toInt().also { require(it >= 0) }
    require(selection == null || (offset == 0 && limit == Int.MAX_VALUE)) {
        "--source-list selects exact sources; it cannot be combined with --offset or --limit"
    }
    val maxJobs = options["max-jobs"]?.toInt()?.also { require(it >= 0) } ?: Int.MAX_VALUE
    val maxWallMs = options["max-wall-ms"]?.toLong()?.also { require(it > 0) } ?: Long.MAX_VALUE
    val fingerprint = runtimeFingerprint()
    val configurationHash = sha256Text(BENCHMARK_SCHEMA + "\n" + fingerprint + "\n" +
        configuration.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
    if (saved != null) {
        require(saved["configuration_sha256"] == configurationHash) {
            "Resume requires the original binaries, corpus/list, variant/settings, solvers, trials, and timeouts. " +
                "Use a new output run after changing them. --max-jobs and --max-wall-ms may change."
        }
    }
    val samples = discoverSamples(source, configuration.getValue("prefix"), selection).drop(offset).take(limit)
    require(samples.isNotEmpty()) { "No .smt2 files selected" }
    val manifest = manifestText(samples)
    val directory = existing ?: createRunDirectory(
        Paths.get(options["output"] ?: workingDirectory.resolve("build/reports/noodler-benchmarks").toString())
    )
    val metadata = saved ?: (configuration + environment + mapOf(
        "schema" to BENCHMARK_SCHEMA, "configuration_sha256" to configurationHash,
        "runtime_sha256" to fingerprint, "created_utc" to Instant.now().toString(),
        "available_processors" to Runtime.getRuntime().availableProcessors().toString(),
        "classpath" to benchmarkClassPath(), "git_commit" to gitOutput(workingDirectory, "rev-parse", "HEAD"),
        "git_worktree_status" to gitOutput(workingDirectory, "status", "--porcelain"),
        "git_diff_sha256" to sha256Text(gitOutput(workingDirectory, "diff", "--binary")),
        "manifest_sha256" to sha256Text(manifest),
        "hybrid-configuration-sha256" to variant.settings.sha256,
        "timing_policy" to ("fresh sequential process; startup+parse30s default; " +
                "assertions+check shared solve deadline; independent validation separate"),
    ))
    val lockPath = directory.resolve(".run.lock")
    FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockFile ->
        val lock = lockFile.tryLock() ?: error("Another process is using this benchmark run: $directory")
        lock.use {
            if (saved == null) {
                writeProperties(directory.resolve("metadata.properties"), metadata)
                Files.writeString(directory.resolve("manifest.csv"), manifest, StandardOpenOption.CREATE_NEW)
                selection?.let {
                    Files.write(directory.resolve("source-list.txt"), it.content, StandardOpenOption.CREATE_NEW)
                }
            } else {
                require(Files.readString(directory.resolve("manifest.csv")) == manifest) {
                    "Corpus files or their contents changed since the manifest was recorded"
                }
                selection?.let {
                    require(sha256File(directory.resolve("source-list.txt")) == it.sha256) {
                        "Saved source-list snapshot differs from the configured selection"
                    }
                }
            }
            listOf("logs", "records", "exclusions").forEach { Files.createDirectories(directory.resolve(it)) }
            val records = readRecords(directory).toMutableList()
            val completed = records.mapTo(hashSetOf()) { it.getValue("key") }
            val exclusions = readExclusions(directory).toMutableMap()
            records.filter { it["eligible"] == "false" }.forEach { row ->
                val id = row.getValue("sample_id")
                if (id !in exclusions) {
                    val exclusion = mapOf("sample_id" to id, "sample" to row.getValue("sample"),
                        "source_sha256" to row.getValue("source_sha256"), "reason" to row["exclusion_reason"].orEmpty())
                    writeProperties(directory.resolve("exclusions/$id.properties"), exclusion)
                    exclusions[id] = exclusion
                }
            }
            restoreAggregateCsv(directory, records, exclusions)
            val processRunner = BenchmarkProcess(limits)
            var launched = 0
            val invocationStarted = System.nanoTime()
            println("Benchmark run: $directory")
            println("Manifest=${samples.size}; variant=${variant.id}; solvers=${backends.joinToString { it.id }}; " +
                "completed=${records.size}; excluded=${exclusions.size}")
            jobs@ for (sample in samples) {
                if (sample.id in exclusions) continue
                for (trial in 1..repeats) {
                    for (backend in backends) {
                        val key = sha256Text("${sample.id}\n${backend.id}\n$trial\n$configurationHash")
                        if (key in completed) continue
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - invocationStarted)
                        if (launched >= maxJobs || elapsedMs >= maxWallMs) break@jobs
                        require(sha256File(source.resolve(sample.relativePath)) == sample.sha256) {
                            "Changed source: ${sample.relativePath}"
                        }
                        val token = UUID.randomUUID().toString()
                        val logName = "logs/$key-${token.take(RUN_ID_SUFFIX_LENGTH)}.log"
                        val command = benchmarkJavaCommand(
                            "io.ksmt.solver.noodler.benchmark.NoodlerBenchmarkRunnerKt",
                            listOf("worker", "--token", token,
                                "--sample", source.resolve(sample.relativePath).toString(),
                                "--sha256", sample.sha256, "--solver", backend.id,
                                "--solve-ms", limits.solveMs.toString(),
                                "--startup-parse-ms", limits.startupParseMs.toString(),
                                "--validation-ms", limits.validationMs.toString()) + variant.workerArguments(),
                        )
                        val result = processRunner.run(command, token, directory.resolve(logName), workingDirectory)
                        val row = result + mapOf(
                            "key" to key, "sample_id" to sample.id, "sample" to sample.relativePath,
                            "source_sha256" to sample.sha256, "family" to sample.family, "logic" to sample.logic,
                            "expected_status" to sample.expectedStatus, "solver" to backend.id,
                            "trial" to trial.toString(),
                            "variant" to variant.id, "hybrid_configuration_sha256" to variant.settings.sha256,
                            "completed_utc" to Instant.now().toString(), "log" to logName,
                        ) + variant.settings.options().mapKeys { (key, _) -> key.replace('-', '_') }
                        // One immutable checkpoint per completed attempt is authoritative after interruption.
                        writeProperties(directory.resolve("records/$key.properties"), row)
                        records += row
                        completed += key
                        appendRecordCsv(directory.resolve("raw.csv"), row)
                        launched++
                        println("${records.size}: ${sample.relativePath} [${backend.id}/$trial] ${row["outcome"]} " +
                            "raw=${row["raw_status"]} solve=${formatMillis(row["solve_ns"])}ms")
                        if (row["eligible"] == "false") {
                            val exclusion = mapOf("sample_id" to sample.id, "sample" to sample.relativePath,
                                "source_sha256" to sample.sha256, "reason" to row["exclusion_reason"].orEmpty())
                            writeProperties(directory.resolve("exclusions/${sample.id}.properties"), exclusion)
                            exclusions[sample.id] = exclusion
                            appendExclusionCsv(directory.resolve("exclusions.csv"), exclusion)
                            continue@jobs
                        }
                        if (launched % SUMMARY_INTERVAL == 0) {
                            writeBenchmarkSummary(directory, metadata, samples, records, exclusions)
                        }
                    }
                }
            }
            writeBenchmarkSummary(directory, metadata, samples, records, exclusions)
            println("Results: ${directory.resolve("raw.csv")}")
            println("Overview: ${directory.resolve("summary.md")}")
        }
    }
}

internal fun benchmarkRuntimeEnvironment(): Map<String, String> = mapOf(
    "java_version" to System.getProperty("java.version"), "java_vendor" to System.getProperty("java.vendor"),
    "os" to System.getProperty("os.name"), "os_version" to System.getProperty("os.version"),
    "architecture" to System.getProperty("os.arch"),
)

internal fun requireResumeEnvironment(saved: Map<String, String>, current: Map<String, String>) {
    current.forEach { (key, value) ->
        require(saved[key] == value) {
            "Resume requires the original runtime environment: $key changed from '${saved[key]}' to '$value'"
        }
    }
}

private fun createRunDirectory(output: Path): Path {
    Files.createDirectories(output)
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())
    val suffix = UUID.randomUUID().toString().take(RUN_ID_SUFFIX_LENGTH)
    return Files.createDirectory(output.resolve("$timestamp-$suffix")).toAbsolutePath()
}

private val manifestColumns = listOf("sample_id", "sample", "sha256", "bytes", "logic", "family", "expected_status")

private fun manifestText(samples: List<BenchmarkSample>): String =
    BenchmarkCsv.row(manifestColumns) + samples.joinToString("") {
        BenchmarkCsv.row(listOf(it.id, it.relativePath, it.sha256, it.size.toString(),
            it.logic, it.family, it.expectedStatus))
    }

internal fun readManifest(path: Path): List<BenchmarkSample> {
    val rows = BenchmarkCsv.parse(Files.readString(path))
    require(rows.first() == manifestColumns) { "Unexpected manifest schema" }
    return rows.drop(1).map {
        val row = manifestColumns.zip(it).toMap()
        BenchmarkSample(row.getValue("sample"), row.getValue("sha256"), row.getValue("bytes").toLong(),
            row.getValue("logic"), row.getValue("expected_status"))
    }
}

internal fun readRecords(directory: Path): List<Map<String, String>> =
    readPropertyDirectory(directory.resolve("records")).sortedWith(compareBy({ it["completed_utc"] }, { it["key"] }))

internal fun readExclusions(directory: Path): Map<String, Map<String, String>> =
    readPropertyDirectory(directory.resolve("exclusions")).associateBy { it.getValue("sample_id") }

private fun readPropertyDirectory(directory: Path): List<Map<String, String>> =
    if (!Files.isDirectory(directory)) emptyList() else Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".properties") }.map(::readProperties).toList()
    }

private fun locateCorpus(repoRoot: Path): Path {
    val base = repoRoot.resolve("ksmt-test/testData/string-data")
    if (Files.isDirectory(base.resolve("QF_S")) || Files.isDirectory(base.resolve("QF_SLIA"))) return base
    require(Files.isDirectory(base)) { "No string corpus at $base; supply --source" }
    return Files.list(base).use {
        it.filter { path -> Files.isDirectory(path) }.sorted().findFirst().orElseThrow { error("Empty string corpus") }
    }
}

private fun runtimeFingerprint(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    benchmarkClassPath().split(java.io.File.pathSeparator).forEach { entry ->
        val path = Paths.get(entry).toAbsolutePath().normalize()
        fingerprintEntry(path, digest)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
}

private fun fingerprintEntry(path: Path, digest: MessageDigest) {
    digest.update(path.toString().toByteArray(StandardCharsets.UTF_8))
    if (Files.isDirectory(path)) {
        Files.walk(path).use { files ->
            files.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                digest.update(path.relativize(file).toString().toByteArray(StandardCharsets.UTF_8))
                digest.update(sha256File(file).toByteArray(StandardCharsets.UTF_8))
            }
        }
    } else if (Files.isRegularFile(path)) {
        digest.update(sha256File(path).toByteArray(StandardCharsets.UTF_8))
    }
}

private fun gitOutput(directory: Path, vararg args: String): String = try {
    val process = ProcessBuilder(listOf("git") + args).directory(directory.toFile()).redirectErrorStream(true)
        .apply { environment()["GIT_OPTIONAL_LOCKS"] = "0" }.start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output else "unavailable"
} catch (_: IOException) { "unavailable" }

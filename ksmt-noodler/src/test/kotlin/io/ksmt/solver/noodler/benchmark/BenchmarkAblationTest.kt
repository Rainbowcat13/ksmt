package io.ksmt.solver.noodler.benchmark

import io.ksmt.solver.noodler.NoodlerYicesConfiguration
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BenchmarkAblationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun baselineMatchesSolverDefaultsAndAllFourteenPresetsAreDistinct() {
        val baseline = BenchmarkVariant.resolve(emptyMap())
        val defaults = NoodlerYicesConfiguration()
        assertEquals(defaults.maxAutomataStates, baseline.settings.maxAutomataStates)
        assertEquals(defaults.maxAutomataStorage, baseline.settings.maxAutomataStorage)
        assertEquals(defaults.maxEncodingConstraints, baseline.settings.maxEncodingConstraints)
        assertEquals(defaults.maxPreprocessingUnits, baseline.settings.maxPreprocessingUnits)
        assertEquals(defaults.maxCharacterPositions, baseline.settings.maxCharacterPositions)
        assertEquals(defaults.maxIdleRounds, baseline.settings.maxIdleRounds)
        assertEquals(defaults.optionalPreprocessing, baseline.settings.optionalPreprocessing)
        assertEquals(defaults.smallFirstSearch, baseline.settings.smallFirstSearch)
        assertEquals(defaults.adaptiveLengthProjection, baseline.settings.adaptiveLengthProjection)
        val variants = BenchmarkVariant.ids.map { BenchmarkVariant.resolve(mapOf("variant" to it)) }
        assertEquals(14, variants.size)
        assertEquals(14, variants.map { it.settings.sha256 }.distinct().size)
    }

    @Test
    fun eachCapVariantChangesOnlyItsNamedCapAndCombinedKeepsDefaultCaps() {
        val baseline = BenchmarkHybridSettings().options()
        val dimensions = mapOf("states" to "max-automata-states", "storage" to "max-automata-storage",
            "encoding" to "max-encoding-constraints", "preprocessing" to "max-preprocessing-units",
            "positions" to "max-character-positions")
        for ((variant, field) in dimensions) {
            for (factor in listOf(2, 4)) {
                val settings = BenchmarkVariant.resolve(mapOf("variant" to "$variant-${factor}x")).settings.options()
                assertEquals(baseline.getValue(field).toInt() * factor, settings.getValue(field).toInt())
                assertEquals(baseline - field, settings - field)
            }
        }
        val combined = BenchmarkVariant.resolve(mapOf("variant" to "combined")).settings
        assertTrue(combined.optionalPreprocessing)
        assertTrue(combined.smallFirstSearch)
        assertEquals(BenchmarkHybridSettings(), combined.copy(optionalPreprocessing = false, smallFirstSearch = false))
    }

    @Test
    fun explicitSettingsSurviveParentWorkerRoundTripAndAreAppliedToConfiguration() {
        val variant = BenchmarkVariant.resolve(mapOf("variant" to "combined", "max-automata-storage" to "1234567",
            "max-character-positions" to "42", "optional-preprocessing" to "false",
            "adaptive-length-projection" to "false"))
        val workerVariant = BenchmarkVariant.resolve(parseOptions(variant.workerArguments()))
        assertEquals(variant, workerVariant)
        val configuration = NoodlerYicesConfiguration()
        workerVariant.settings.applyTo(configuration)
        assertEquals(1_234_567, configuration.maxAutomataStorage)
        assertEquals(42, configuration.maxCharacterPositions)
        assertFalse(configuration.optionalPreprocessing)
        assertTrue(configuration.smallFirstSearch)
        assertFalse(configuration.adaptiveLengthProjection)
        assertEquals(variant, BenchmarkVariant.resolve(emptyMap(), variant.options()))
        assertNotEquals(variant.settings.sha256, BenchmarkHybridSettings().sha256)
        assertNotEquals(BenchmarkHybridSettings().sha256,
            BenchmarkHybridSettings(adaptiveLengthProjection = false).sha256)
        assertFailsWith<IllegalArgumentException> {
            BenchmarkVariant.resolve(mapOf("max-character-positions" to "-1"))
        }
        assertFailsWith<IllegalStateException> {
            BenchmarkVariant.resolve(mapOf("small-first-search" to "yes"))
        }
    }

    @Test
    fun sourceListSelectsExactRelativePathsWithoutBasenameAmbiguity() {
        val source = corpus()
        val list = temporary.resolve("selection.txt")
        Files.writeString(list, "\uFEFFsecond/sample.smt2\r\n")
        val selection = BenchmarkSourceList.read(list)
        val samples = discoverSamples(source, "", selection)
        assertEquals(listOf("second/sample.smt2"), samples.map { it.relativePath })
        assertEquals(sha256File(source.resolve("second/sample.smt2")), samples.single().sha256)
        assertEquals(sha256File(list), selection.sha256)
        assertFailsWith<IllegalArgumentException> { discoverSamples(source, "first/", selection) }
        for (invalid in listOf("../outside.smt2", "/absolute.smt2", "C:/absolute.smt2",
            "first/sample.smt2\nfirst/sample.smt2", "first\\sample.smt2", " first/sample.smt2")) {
            Files.writeString(list, invalid)
            assertFailsWith<IllegalArgumentException> { BenchmarkSourceList.read(list) }
        }
        Files.writeString(list, "missing.smt2")
        assertFailsWith<IllegalArgumentException> { discoverSamples(source, "", BenchmarkSourceList.read(list)) }
        if (Files.isRegularFile(source.resolve("FIRST/sample.smt2"))) {
            Files.writeString(list, "first/sample.smt2\nFIRST/sample.smt2\n")
            assertFailsWith<IllegalArgumentException> { discoverSamples(source, "", BenchmarkSourceList.read(list)) }
        }
    }

    @Test
    fun variantAndSelectionAreImmutableAcrossResumeWithoutLaunchingWorkers() {
        val source = corpus()
        val output = temporary.resolve("runs")
        val list = temporary.resolve("selection.txt")
        Files.writeString(list, "first/sample.smt2\n")
        runNoodlerBenchmarks(listOf("run", "--source", source.toString(), "--source-list", list.toString(),
            "--output", output.toString(), "--solvers", "noodler-yices", "--variant", "states-4x", "--max-jobs", "0"))
        val run = Files.list(output).use { it.findFirst().orElseThrow() }
        val metadata = readProperties(run.resolve("metadata.properties"))
        assertEquals("states-4x", metadata["variant"])
        assertEquals("400000", metadata["max-automata-states"])
        assertEquals("1000000", metadata["max-automata-storage"])
        assertEquals("true", metadata["adaptive-length-projection"])
        assertEquals(sha256File(list), metadata["source-list-sha256"])
        assertEquals(Files.readString(list), Files.readString(run.resolve("source-list.txt")))
        runNoodlerBenchmarks(listOf("run", "--resume", run.toString(), "--max-jobs", "0"))
        assertTrue(readRecords(run).isEmpty())
        assertFailsWith<IllegalArgumentException> {
            runNoodlerBenchmarks(listOf("run", "--resume", run.toString(), "--variant", "baseline", "--max-jobs", "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            runNoodlerBenchmarks(listOf("run", "--resume", run.toString(),
                "--max-automata-states", "400001", "--max-jobs", "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            runNoodlerBenchmarks(listOf("run", "--resume", run.toString(),
                "--adaptive-length-projection", "false", "--max-jobs", "0"))
        }
        Files.writeString(list, "second/sample.smt2\n")
        assertFailsWith<IllegalArgumentException> {
            runNoodlerBenchmarks(listOf("run", "--resume", run.toString(), "--max-jobs", "0"))
        }
    }

    @Test
    fun resumeRejectsDifferentJvmOrOperatingEnvironment() {
        val environment = benchmarkRuntimeEnvironment()
        requireResumeEnvironment(environment, environment)
        for (field in listOf("java_version", "java_vendor", "os", "os_version", "architecture")) {
            assertFailsWith<IllegalArgumentException> {
                requireResumeEnvironment(environment + (field to "different"), environment)
            }
            assertFailsWith<IllegalArgumentException> {
                requireResumeEnvironment(environment - field, environment)
            }
        }
    }

    @Test
    fun hybridWorkerReceivesCapOverridesAndStillValidatesSatIndependently() {
        val source = corpus()
        val output = temporary.resolve("worker-runs")
        val list = temporary.resolve("worker-selection.txt")
        Files.writeString(list, "first/sample.smt2\n")
        fun run(extra: List<String>): Map<String, String> {
            val existing = if (Files.isDirectory(output)) Files.list(output).use { it.toList().toSet() } else emptySet()
            runNoodlerBenchmarks(listOf("run", "--source", source.toString(), "--source-list", list.toString(),
                "--output", output.toString(), "--solvers", "noodler-yices") + extra)
            val run = Files.list(output).use { stream -> stream.filter { it !in existing }.findFirst().orElseThrow() }
            return readRecords(run).single()
        }
        val limited = run(listOf("--variant", "positions-2x", "--max-character-positions", "0"))
        assertEquals("positions-2x", limited["variant"])
        assertEquals("0", limited["max_character_positions"])
        assertEquals("UNKNOWN", limited["outcome"])
        assertTrue(limited["details"].orEmpty().contains("resource limit"))
        val ordinary = run(listOf("--variant", "combined", "--adaptive-length-projection", "false"))
        assertEquals("SAT", ordinary["outcome"])
        assertEquals("SAT", ordinary["validation_status"])
        assertEquals("z3", ordinary["validator"])
        assertEquals("combined", ordinary["variant"])
        assertEquals("true", ordinary["optional_preprocessing"])
        assertEquals("true", ordinary["small_first_search"])
        assertEquals("false", ordinary["adaptive_length_projection"])
        assertEquals("0", ordinary["length_projection_fallbacks"])
        assertTrue(ordinary["length_projection_cache_hits"]!!.toInt() >= 0)
        assertEquals(BenchmarkVariant.resolve(mapOf("variant" to "combined",
            "adaptive-length-projection" to "false")).settings.sha256,
            ordinary["hybrid_configuration_sha256"])
    }

    private fun corpus(): Path {
        val source = Files.createDirectory(temporary.resolve("source"))
        for ((folder, value) in listOf("first" to "ab", "second" to "cd")) {
            val directory = Files.createDirectory(source.resolve(folder))
            Files.writeString(directory.resolve("sample.smt2"),
                "(set-logic QF_S)\n(declare-fun x () String)\n(assert (= x \"$value\"))\n(check-sat)\n")
        }
        return source
    }
}

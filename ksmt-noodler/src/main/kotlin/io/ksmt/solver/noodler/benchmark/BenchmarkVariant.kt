package io.ksmt.solver.noodler.benchmark

import io.ksmt.solver.noodler.KNoodlerYicesSolverConfiguration

private const val DEFAULT_SMALL_LIMIT = 100_000
private const val DEFAULT_LARGE_LIMIT = 1_000_000
private const val FOUR_TIMES = 4

/** Every effective setting is explicit, including settings unchanged by a named ablation. */
internal data class BenchmarkHybridSettings(
    val maxAutomataStates: Int = DEFAULT_SMALL_LIMIT,
    val maxAutomataStorage: Int = DEFAULT_LARGE_LIMIT,
    val maxEncodingConstraints: Int = DEFAULT_LARGE_LIMIT,
    val maxPreprocessingUnits: Int = DEFAULT_LARGE_LIMIT,
    val maxCharacterPositions: Int = DEFAULT_SMALL_LIMIT,
    val maxIdleRounds: Int = 2,
    val optionalPreprocessing: Boolean = false,
    val smallFirstSearch: Boolean = false,
    val adaptiveLengthProjection: Boolean = true,
) {
    init {
        require(maxAutomataStates > 0 && maxAutomataStorage > 0) { "Automata caps must be positive" }
        require(listOf(maxEncodingConstraints, maxPreprocessingUnits, maxCharacterPositions).all { it >= 0 }) {
            "Encoding, preprocessing, and character-position caps must be nonnegative"
        }
        require(maxIdleRounds > 0) { "Hybrid idle rounds must be positive" }
    }

    fun applyTo(configuration: KNoodlerYicesSolverConfiguration) = with(configuration) {
        maxAutomataStates = this@BenchmarkHybridSettings.maxAutomataStates
        maxAutomataStorage = this@BenchmarkHybridSettings.maxAutomataStorage
        maxEncodingConstraints = this@BenchmarkHybridSettings.maxEncodingConstraints
        maxPreprocessingUnits = this@BenchmarkHybridSettings.maxPreprocessingUnits
        maxCharacterPositions = this@BenchmarkHybridSettings.maxCharacterPositions
        maxIdleRounds = this@BenchmarkHybridSettings.maxIdleRounds
        optionalPreprocessing = this@BenchmarkHybridSettings.optionalPreprocessing
        smallFirstSearch = this@BenchmarkHybridSettings.smallFirstSearch
        adaptiveLengthProjection = this@BenchmarkHybridSettings.adaptiveLengthProjection
    }

    fun options(): Map<String, String> = linkedMapOf(
        "max-automata-states" to maxAutomataStates.toString(),
        "max-automata-storage" to maxAutomataStorage.toString(),
        "max-encoding-constraints" to maxEncodingConstraints.toString(),
        "max-preprocessing-units" to maxPreprocessingUnits.toString(),
        "max-character-positions" to maxCharacterPositions.toString(),
        "max-idle-rounds" to maxIdleRounds.toString(),
        "optional-preprocessing" to optionalPreprocessing.toString(),
        "small-first-search" to smallFirstSearch.toString(),
        "adaptive-length-projection" to adaptiveLengthProjection.toString(),
    )

    val sha256: String get() = sha256Text(options().entries.sortedBy { it.key }
        .joinToString("\n") { "${it.key}=${it.value}" })
}

internal data class BenchmarkVariant(val id: String, val settings: BenchmarkHybridSettings) {
    fun options(): Map<String, String> = mapOf("variant" to id) + settings.options()
    fun workerArguments(): List<String> = options().flatMap { (key, value) -> listOf("--$key", value) }

    companion object {
        val optionNames: Set<String> = BenchmarkHybridSettings().options().keys + "variant"
        val ids: List<String> = listOf("baseline") +
            listOf("states", "storage", "encoding", "preprocessing", "positions")
                .flatMap { listOf("$it-2x", "$it-4x") } + listOf("optional", "small-first", "combined")

        fun resolve(options: Map<String, String>, saved: Map<String, String>? = null): BenchmarkVariant {
            val id = options["variant"] ?: saved?.get("variant") ?: "baseline"
            val preset = preset(id).options()
            fun value(key: String): String = options[key] ?: saved?.get(key) ?: preset.getValue(key)
            fun flag(key: String): Boolean = when (val value = value(key)) {
                "true" -> true
                "false" -> false
                else -> error("--$key must be true or false, got '$value'")
            }
            return BenchmarkVariant(id, BenchmarkHybridSettings(
                maxAutomataStates = value("max-automata-states").toInt(),
                maxAutomataStorage = value("max-automata-storage").toInt(),
                maxEncodingConstraints = value("max-encoding-constraints").toInt(),
                maxPreprocessingUnits = value("max-preprocessing-units").toInt(),
                maxCharacterPositions = value("max-character-positions").toInt(),
                maxIdleRounds = value("max-idle-rounds").toInt(),
                optionalPreprocessing = flag("optional-preprocessing"),
                smallFirstSearch = flag("small-first-search"),
                adaptiveLengthProjection = flag("adaptive-length-projection"),
            ))
        }

        private fun preset(id: String): BenchmarkHybridSettings {
            require(id in ids) { "Unknown variant '$id'; expected ${ids.joinToString()}" }
            val defaults = BenchmarkHybridSettings()
            val factor = if (id.endsWith("-4x")) FOUR_TIMES else 2
            return when (id.substringBefore('-')) {
                "states" -> defaults.copy(maxAutomataStates = defaults.maxAutomataStates * factor)
                "storage" -> defaults.copy(maxAutomataStorage = defaults.maxAutomataStorage * factor)
                "encoding" -> defaults.copy(maxEncodingConstraints = defaults.maxEncodingConstraints * factor)
                "preprocessing" -> defaults.copy(maxPreprocessingUnits = defaults.maxPreprocessingUnits * factor)
                "positions" -> defaults.copy(maxCharacterPositions = defaults.maxCharacterPositions * factor)
                "optional" -> defaults.copy(optionalPreprocessing = true)
                "small" -> defaults.copy(smallFirstSearch = true)
                "combined" -> defaults.copy(optionalPreprocessing = true, smallFirstSearch = true)
                else -> defaults
            }
        }
    }
}

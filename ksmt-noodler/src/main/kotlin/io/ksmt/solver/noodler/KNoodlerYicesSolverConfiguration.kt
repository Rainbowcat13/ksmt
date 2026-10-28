package io.ksmt.solver.noodler

import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.KSolverUnsupportedParameterException
import io.ksmt.solver.KTheory
import io.ksmt.solver.noodler.hybrid.AutomataLimits
import io.ksmt.solver.noodler.hybrid.PreprocessingBudget
import io.ksmt.solver.yices.KYicesSolver

/** Configuration for unbounded Boolean/string/LIA solving. Resource limits never change string semantics. */
interface KNoodlerYicesSolverConfiguration : KSolverConfiguration {
    var maxIdleRounds: Int

    /** Maximum total character positions materialized in a candidate. Exceeding it returns UNKNOWN. */
    var maxCharacterPositions: Int

    /** Cumulative primitive constraints permitted in one exact candidate encoding. */
    var maxEncodingConstraints: Int

    /** Cumulative syntax, term-expansion and model-validation storage units per check. */
    var maxPreprocessingUnits: Int

    /** Bound automata construction without changing the accepted string language. */
    var maxAutomataStates: Int
    var maxAutomataStorage: Int

    /** Allow necessary-condition preparation to be skipped when its separate allowance is exhausted. */
    var optionalPreprocessing: Boolean

    /** Prefer temporary, increasing total-length bounds; bounded failure never proves UNSAT. */
    var smallFirstSearch: Boolean

    /** Use graph bounds/congruences and bounded exact length projection with a sound fallback. */
    var adaptiveLengthProjection: Boolean

    fun setYicesOption(option: String, value: String)
}

internal class NoodlerYicesConfiguration : KNoodlerYicesSolverConfiguration {
    override var maxIdleRounds: Int = 2
        set(value) {
            require(value > 0) { "maxIdleRounds must be positive" }
            field = value
        }
    override var maxCharacterPositions: Int = DEFAULT_MAX_POSITIONS
        set(value) {
            require(value >= 0) { "maxCharacterPositions must be nonnegative" }
            field = value
        }
    override var maxEncodingConstraints: Int = DEFAULT_MAX_ENCODING_CONSTRAINTS
        set(value) {
            require(value >= 0) { "maxEncodingConstraints must be nonnegative" }
            field = value
        }
    override var maxPreprocessingUnits: Int = PreprocessingBudget.DEFAULT_LIMIT
        set(value) {
            require(value >= 0) { "maxPreprocessingUnits must be nonnegative" }
            field = value
        }
    override var maxAutomataStates: Int = AutomataLimits().maxStates
        set(value) {
            require(value > 0) { "maxAutomataStates must be positive" }
            field = value
        }
    override var maxAutomataStorage: Int = AutomataLimits().maxStorage
        set(value) {
            require(value > 0) { "maxAutomataStorage must be positive" }
            field = value
        }
    override var optionalPreprocessing: Boolean = false
    override var smallFirstSearch: Boolean = false
    override var adaptiveLengthProjection: Boolean = true
    private val options = linkedMapOf<String, String>()

    override fun setYicesOption(option: String, value: String) {
        options[option] = value
    }

    override fun setIntParameter(param: String, value: Int) = when (param) {
        "noodler.maxIdleRounds" -> { maxIdleRounds = value }
        "noodler.maxCharacterPositions" -> { maxCharacterPositions = value }
        "noodler.maxEncodingConstraints" -> { maxEncodingConstraints = value }
        "noodler.maxPreprocessingUnits" -> { maxPreprocessingUnits = value }
        "noodler.maxAutomataStates" -> { maxAutomataStates = value }
        "noodler.maxAutomataStorage" -> { maxAutomataStorage = value }
        else -> throw KSolverUnsupportedParameterException("Unsupported integer parameter: $param")
    }

    override fun setStringParameter(param: String, value: String) = setYicesOption(param.removePrefix("yices."), value)

    override fun setBoolParameter(param: String, value: Boolean) = when (param) {
        "noodler.optionalPreprocessing" -> { optionalPreprocessing = value }
        "noodler.smallFirstSearch" -> { smallFirstSearch = value }
        "noodler.adaptiveLengthProjection" -> { adaptiveLengthProjection = value }
        else -> throw KSolverUnsupportedParameterException("Unsupported Boolean parameter: $param")
    }

    override fun setDoubleParameter(param: String, value: Double): Unit =
        throw KSolverUnsupportedParameterException("Unsupported floating-point parameter: $param")

    override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) {
        if (quantifiersAllowed || theories?.any { it != KTheory.S && it != KTheory.LIA } == true) {
            throw KSolverUnsupportedFeatureException("Noodler/Yices supports quantifier-free strings and LIA")
        }
    }

    fun configure(solver: KYicesSolver) {
        solver.configure {
            optimizeForTheories(setOf(KTheory.LIA), quantifiersAllowed = false)
            options.forEach { (name, value) -> setYicesOption(name, value) }
        }
    }

    fun copy(): NoodlerYicesConfiguration = NoodlerYicesConfiguration().also {
        it.maxIdleRounds = maxIdleRounds
        it.maxCharacterPositions = maxCharacterPositions
        it.maxEncodingConstraints = maxEncodingConstraints
        it.maxPreprocessingUnits = maxPreprocessingUnits
        it.maxAutomataStates = maxAutomataStates
        it.maxAutomataStorage = maxAutomataStorage
        it.optionalPreprocessing = optionalPreprocessing
        it.smallFirstSearch = smallFirstSearch
        it.adaptiveLengthProjection = adaptiveLengthProjection
        it.options.putAll(options)
    }

    companion object {
        private const val DEFAULT_MAX_POSITIONS = 100_000
        private const val DEFAULT_MAX_ENCODING_CONSTRAINTS = 1_000_000
    }
}

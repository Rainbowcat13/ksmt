package io.ksmt.solver.noodler

import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.KSolverUnsupportedParameterException
import io.ksmt.solver.KTheory
import io.ksmt.solver.noodler.hybrid.AutomataLimits
import io.ksmt.solver.noodler.hybrid.DEFAULT_MEMBERSHIP_PRUNING_UNITS
import io.ksmt.solver.noodler.hybrid.PreprocessingBudget
import io.ksmt.solver.yices.KYicesSolverConfiguration

/**
 * Encoding of generated finite-domain constraints in exact, fixed-length string realization.
 *
 * Both targets preserve the complete SMT-LIB character domain. Neither changes original integer
 * arithmetic or the unbounded integer length search into wrapping arithmetic.
 */
enum class NoodlerEncodingTarget { INT, BV }

/**
 * Configuration for the shared hybrid algorithm. Configure before the first check; assertions may
 * already be buffered. Backend choice belongs to the solver constructor, independently of this target.
 * Resource limits never change string semantics: exhaustion produces UNKNOWN.
 */
interface KNoodlerHybridSolverConfiguration : KSolverConfiguration {
    /** Generated character values and DFA state identifiers only. Defaults to [NoodlerEncodingTarget.INT]. */
    var encodingTarget: NoodlerEncodingTarget

    var maxIdleRounds: Int

    /** Maximum total character positions materialized in a candidate. Exceeding it returns UNKNOWN. */
    var maxCharacterPositions: Int

    /** Cumulative primitive constraints permitted in one exact candidate encoding. */
    var maxEncodingConstraints: Int

    /** Optional work per membership transition plan, additionally limited to a quarter of remaining preprocessing. */
    var maxMembershipPruningUnits: Int

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

    /** Try bounded automaton witness construction before exact arithmetic character realization. */
    var automataWitnessSearch: Boolean

    /** Derive exact length equalities for words restricted to powers of a common period. */
    var periodicLengthReasoning: Boolean

    /** Compatibility convenience. Applying this option to a non-Yices backend is rejected explicitly. */
    fun setYicesOption(option: String, value: String)
}

/** Source compatibility for the former Yices-specific hybrid entry point. */
typealias KNoodlerYicesSolverConfiguration = KNoodlerHybridSolverConfiguration

internal class NoodlerYicesConfiguration : KNoodlerHybridSolverConfiguration {
    override var encodingTarget: NoodlerEncodingTarget = NoodlerEncodingTarget.INT
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
    override var maxMembershipPruningUnits: Int = DEFAULT_MEMBERSHIP_PRUNING_UNITS
        set(value) {
            require(value >= 0) { "maxMembershipPruningUnits must be nonnegative" }
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
    override var automataWitnessSearch: Boolean = true
    override var periodicLengthReasoning: Boolean = true
    private val backendOptions = arrayListOf<KSolverConfiguration.() -> Unit>()

    override fun setYicesOption(option: String, value: String) {
        backendOptions += {
            if (this !is KYicesSolverConfiguration) {
                throw KSolverUnsupportedParameterException("Yices option '$option' requires a Yices backend")
            }
            setYicesOption(option, value)
        }
    }

    override fun setIntParameter(param: String, value: Int) = when (param) {
        "noodler.maxIdleRounds" -> { maxIdleRounds = value }
        "noodler.maxCharacterPositions" -> { maxCharacterPositions = value }
        "noodler.maxEncodingConstraints" -> { maxEncodingConstraints = value }
        "noodler.maxMembershipPruningUnits" -> { maxMembershipPruningUnits = value }
        "noodler.maxPreprocessingUnits" -> { maxPreprocessingUnits = value }
        "noodler.maxAutomataStates" -> { maxAutomataStates = value }
        "noodler.maxAutomataStorage" -> { maxAutomataStorage = value }
        else -> backendParameter(param) { setIntParameter(param, value) }
    }

    override fun setStringParameter(param: String, value: String) {
        when {
            param == "noodler.encodingTarget" -> {
                encodingTarget = NoodlerEncodingTarget.values().firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?: throw KSolverUnsupportedParameterException("Unsupported Noodler encoding target: $value")
            }
            param.startsWith("yices.") -> setYicesOption(param.removePrefix("yices."), value)
            else -> backendParameter(param) { setStringParameter(param, value) }
        }
    }

    override fun setBoolParameter(param: String, value: Boolean) = when (param) {
        "noodler.optionalPreprocessing" -> { optionalPreprocessing = value }
        "noodler.smallFirstSearch" -> { smallFirstSearch = value }
        "noodler.adaptiveLengthProjection" -> { adaptiveLengthProjection = value }
        "noodler.automataWitnessSearch" -> { automataWitnessSearch = value }
        "noodler.periodicLengthReasoning" -> { periodicLengthReasoning = value }
        else -> backendParameter(param) { setBoolParameter(param, value) }
    }

    override fun setDoubleParameter(param: String, value: Double) =
        backendParameter(param) { setDoubleParameter(param, value) }

    private fun backendParameter(param: String, apply: KSolverConfiguration.() -> Unit) {
        if (param.startsWith("noodler.")) {
            throw KSolverUnsupportedParameterException("Unsupported Noodler parameter: $param")
        }
        backendOptions += apply
    }

    override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) {
        if (quantifiersAllowed || theories?.any { it != KTheory.S && it != KTheory.LIA && it != KTheory.BV } == true) {
            throw KSolverUnsupportedFeatureException("Noodler hybrid supports quantifier-free strings, LIA and native bit-vectors")
        }
    }

    fun configure(solver: KSolver<*>) {
        solver.configure {
            // The accepted input fragment is checked separately. Generated constraints always retain
            // mathematical integer lengths, and may also contain native/input finite bit-vectors.
            optimizeForTheories(setOf(KTheory.LIA, KTheory.BV), quantifiersAllowed = false)
            backendOptions.forEach { it() }
        }
    }

    fun copy(): NoodlerYicesConfiguration = NoodlerYicesConfiguration().also {
        it.encodingTarget = encodingTarget
        it.maxIdleRounds = maxIdleRounds
        it.maxCharacterPositions = maxCharacterPositions
        it.maxEncodingConstraints = maxEncodingConstraints
        it.maxMembershipPruningUnits = maxMembershipPruningUnits
        it.maxPreprocessingUnits = maxPreprocessingUnits
        it.maxAutomataStates = maxAutomataStates
        it.maxAutomataStorage = maxAutomataStorage
        it.optionalPreprocessing = optionalPreprocessing
        it.smallFirstSearch = smallFirstSearch
        it.adaptiveLengthProjection = adaptiveLengthProjection
        it.automataWitnessSearch = automataWitnessSearch
        it.periodicLengthReasoning = periodicLengthReasoning
        it.backendOptions.addAll(backendOptions)
    }

    companion object {
        private const val DEFAULT_MAX_POSITIONS = 100_000
        private const val DEFAULT_MAX_ENCODING_CONSTRAINTS = 1_000_000
    }
}

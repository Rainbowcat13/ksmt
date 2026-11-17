package io.ksmt.solver.noodler

import io.ksmt.solver.KSolverUniversalConfigurationBuilder
import io.ksmt.solver.KTheory

/** Serializes hybrid options using the existing KSMT worker configuration protocol. */
class KNoodlerHybridSolverUniversalConfiguration(
    private val builder: KSolverUniversalConfigurationBuilder,
) : KNoodlerHybridSolverConfiguration {
    private val values = NoodlerYicesConfiguration()

    override var encodingTarget: NoodlerEncodingTarget
        get() = values.encodingTarget
        set(value) {
            values.encodingTarget = value
            builder.buildStringParameter("noodler.encodingTarget", value.name)
        }

    override var maxIdleRounds: Int
        get() = values.maxIdleRounds
        set(value) {
            values.maxIdleRounds = value
            builder.buildIntParameter("noodler.maxIdleRounds", value)
        }

    override var maxCharacterPositions: Int
        get() = values.maxCharacterPositions
        set(value) {
            values.maxCharacterPositions = value
            builder.buildIntParameter("noodler.maxCharacterPositions", value)
        }

    override var maxEncodingConstraints: Int
        get() = values.maxEncodingConstraints
        set(value) {
            values.maxEncodingConstraints = value
            builder.buildIntParameter("noodler.maxEncodingConstraints", value)
        }

    override var maxMembershipPruningUnits: Int
        get() = values.maxMembershipPruningUnits
        set(value) {
            values.maxMembershipPruningUnits = value
            builder.buildIntParameter("noodler.maxMembershipPruningUnits", value)
        }

    override var maxPreprocessingUnits: Int
        get() = values.maxPreprocessingUnits
        set(value) {
            values.maxPreprocessingUnits = value
            builder.buildIntParameter("noodler.maxPreprocessingUnits", value)
        }

    override var maxAutomataStates: Int
        get() = values.maxAutomataStates
        set(value) {
            values.maxAutomataStates = value
            builder.buildIntParameter("noodler.maxAutomataStates", value)
        }

    override var maxAutomataStorage: Int
        get() = values.maxAutomataStorage
        set(value) {
            values.maxAutomataStorage = value
            builder.buildIntParameter("noodler.maxAutomataStorage", value)
        }

    override var optionalPreprocessing: Boolean
        get() = values.optionalPreprocessing
        set(value) {
            values.optionalPreprocessing = value
            builder.buildBoolParameter("noodler.optionalPreprocessing", value)
        }

    override var smallFirstSearch: Boolean
        get() = values.smallFirstSearch
        set(value) {
            values.smallFirstSearch = value
            builder.buildBoolParameter("noodler.smallFirstSearch", value)
        }

    override var adaptiveLengthProjection: Boolean
        get() = values.adaptiveLengthProjection
        set(value) {
            values.adaptiveLengthProjection = value
            builder.buildBoolParameter("noodler.adaptiveLengthProjection", value)
        }

    override var automataWitnessSearch: Boolean
        get() = values.automataWitnessSearch
        set(value) {
            values.automataWitnessSearch = value
            builder.buildBoolParameter("noodler.automataWitnessSearch", value)
        }

    override var periodicLengthReasoning: Boolean
        get() = values.periodicLengthReasoning
        set(value) {
            values.periodicLengthReasoning = value
            builder.buildBoolParameter("noodler.periodicLengthReasoning", value)
        }

    override fun setYicesOption(option: String, value: String) {
        values.setYicesOption(option, value)
        builder.buildStringParameter("yices.$option", value)
    }
    override fun setBoolParameter(param: String, value: Boolean) {
        values.setBoolParameter(param, value)
        builder.buildBoolParameter(param, value)
    }
    override fun setIntParameter(param: String, value: Int) {
        values.setIntParameter(param, value)
        builder.buildIntParameter(param, value)
    }
    override fun setStringParameter(param: String, value: String) {
        values.setStringParameter(param, value)
        builder.buildStringParameter(param, value)
    }
    override fun setDoubleParameter(param: String, value: Double) {
        values.setDoubleParameter(param, value)
        builder.buildDoubleParameter(param, value)
    }
    override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) {
        values.optimizeForTheories(theories, quantifiersAllowed)
        builder.buildOptimizeForTheories(theories, quantifiersAllowed)
    }
}

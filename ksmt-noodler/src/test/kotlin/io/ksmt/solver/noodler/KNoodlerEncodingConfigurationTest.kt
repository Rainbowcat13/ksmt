package io.ksmt.solver.noodler

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedParameterException
import io.ksmt.solver.KTheory
import io.ksmt.sort.KBoolSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class KNoodlerEncodingConfigurationTest {
    @Test
    fun integerDefaultAndIndependentCopy() {
        val original = NoodlerYicesConfiguration()
        assertEquals(NoodlerEncodingTarget.INT, original.encodingTarget)
        val copy = original.copy().apply { encodingTarget = NoodlerEncodingTarget.BV }
        assertEquals(NoodlerEncodingTarget.INT, original.encodingTarget)
        assertEquals(NoodlerEncodingTarget.BV, copy.encodingTarget)
    }

    @Test
    fun encodingParameterIsExplicitAndRejectsUnknownTargets() {
        val configuration = NoodlerYicesConfiguration()
        configuration.setStringParameter("noodler.encodingTarget", "bv")
        assertEquals(NoodlerEncodingTarget.BV, configuration.encodingTarget)
        assertFailsWith<KSolverUnsupportedParameterException> {
            configuration.setStringParameter("noodler.encodingTarget", "16bit")
        }
        assertFailsWith<KSolverUnsupportedParameterException> {
            configuration.setIntParameter("noodler.characterWidth", 16)
        }
    }

    @Test
    fun backendParametersAreIndependentOfEncodingAndAreCopied() {
        val configuration = NoodlerYicesConfiguration().apply {
            setStringParameter("backend-option", "value")
            encodingTarget = NoodlerEncodingTarget.BV
        }
        val backend = ConfigurationOnlySolver()
        configuration.copy().configure(backend)
        assertEquals(mapOf("backend-option" to "value"), backend.options)
        assertEquals(setOf(KTheory.LIA, KTheory.BV), backend.theories)
    }

    @Test
    fun legacyYicesOptionsRejectOtherBackends() {
        val configuration = NoodlerYicesConfiguration().apply { setYicesOption("mode", "push-pop") }
        assertFailsWith<KSolverUnsupportedParameterException> { configuration.configure(ConfigurationOnlySolver()) }
    }

    private class ConfigurationOnlySolver : KSolver<KSolverConfiguration> {
        val options = linkedMapOf<String, String>()
        var theories: Set<KTheory>? = null
        private val configuration = object : KSolverConfiguration {
            override fun setStringParameter(param: String, value: String) { options[param] = value }
            override fun setBoolParameter(param: String, value: Boolean) = error("unexpected Boolean option")
            override fun setIntParameter(param: String, value: Int) = error("unexpected integer option")
            override fun setDoubleParameter(param: String, value: Double) = error("unexpected double option")
            override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) {
                this@ConfigurationOnlySolver.theories = theories
            }
        }
        override fun configure(configurator: KSolverConfiguration.() -> Unit) = configuration.configurator()
        override fun assert(expr: KExpr<KBoolSort>) = error("not a solving backend")
        override fun assertAndTrack(expr: KExpr<KBoolSort>) = error("not a solving backend")
        override fun push() = error("not a solving backend")
        override fun pop(n: UInt) = error("not a solving backend")
        override fun check(timeout: Duration): KSolverStatus = error("not a solving backend")
        override fun checkWithAssumptions(assumptions: List<KExpr<KBoolSort>>, timeout: Duration): KSolverStatus =
            error("not a solving backend")
        override fun model(): KModel = error("not a solving backend")
        override fun unsatCore(): List<KExpr<KBoolSort>> = error("not a solving backend")
        override fun reasonOfUnknown(): String = error("not a solving backend")
        override fun interrupt() = Unit
        override fun close() = Unit
    }
}

package io.ksmt.solver.noodler.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoodlerQfSimpleRunnerTest {
    @Test
    fun testHybridBackendIsSelectedDirectly() {
        assertEquals(KNoodlerBackend.YICES, KNoodlerBackend.fromCliName("yices"))
        io.ksmt.KContext().use { ctx ->
            KNoodlerBackend.YICES.factory(ctx).use { solver ->
                assertTrue(solver is io.ksmt.solver.noodler.KNoodlerYicesSolver)
            }
        }
    }

    @Test
    fun testSerializeStringAssignmentsRoundTrip() {
        val assignments = linkedMapOf(
            "x" to "ab",
            "y" to "",
            "z" to "line1\nline2\t\"quoted\"",
        )

        val serialized = serializeStringAssignments(assignments)
        val deserialized = deserializeStringAssignments(serialized)

        assertEquals(assignments, deserialized)
    }

    @Test
    fun testSerializedCanonicalLiteralsPreserveSurrogateDistinction() {
        io.ksmt.KContext().use { ctx ->
            val assignments = mapOf(
                "surrogates" to ctx.mkStringLiteral(intArrayOf(0xD83D, 0xDE00)).toString(),
                "supplementary" to ctx.mkStringLiteral(intArrayOf(0x1F600)).toString(),
                "nul" to ctx.mkStringLiteral(intArrayOf(0)).toString(),
            )
            assertEquals(assignments, deserializeStringAssignments(serializeStringAssignments(assignments)))
            assertTrue(assignments.getValue("surrogates") != assignments.getValue("supplementary"))
        }
    }

    @Test
    fun testCompareStringModelsReportsMatch() {
        val comparison = compareStringModels(
            noodlerStatus = "SAT",
            noodlerModel = mapOf("x" to "ab", "y" to ""),
            referenceSolverName = "Z3",
            referenceStatus = "SAT",
            referenceModel = mapOf("x" to "ab", "y" to "", "z" to "unused"),
        )

        assertEquals(StringModelComparisonStatus.MATCH, comparison.status)
        assertTrue(comparison.details.contains("overlap=2"))
    }

    @Test
    fun testCompareStringModelsReportsDifferentWitness() {
        val comparison = compareStringModels(
            noodlerStatus = "SAT",
            noodlerModel = mapOf("x" to "ab"),
            referenceSolverName = "Z3",
            referenceStatus = "SAT",
            referenceModel = mapOf("x" to "b"),
        )

        assertEquals(StringModelComparisonStatus.DIFFERENT_WITNESS, comparison.status)
        assertTrue(comparison.details.contains("x("))
    }

    @Test
    fun testCompareStringModelsSkipsWhenZ3IsNotSat() {
        val comparison = compareStringModels(
            noodlerStatus = "SAT",
            noodlerModel = mapOf("x" to "ab"),
            referenceSolverName = "Z3",
            referenceStatus = "UNKNOWN",
            referenceModel = emptyMap(),
        )

        assertEquals(StringModelComparisonStatus.SKIPPED, comparison.status)
        assertTrue(comparison.details.contains("Z3 status=UNKNOWN"))
    }
}

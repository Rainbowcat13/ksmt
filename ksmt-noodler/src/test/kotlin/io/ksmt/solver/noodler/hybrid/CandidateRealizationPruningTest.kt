package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import io.ksmt.solver.yices.KYicesSolver
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CandidateRealizationPruningTest {
    @Test
    fun longLiteralMembershipFitsASmallEncodingBudgetForBothPolarities() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx ->
            with(ctx) {
                val x = mkConst("x", stringSort)
                val word = mkStringLiteral("a".repeat(40))
                for (positive in listOf(false, true)) {
                    KNoodlerYicesSolver(ctx).use { solver ->
                        solver.configure { maxEncodingConstraints = 300 }
                        val language = mkStringToRegex(word).let { if (positive) it else mkRegexComplement(it) }
                        val membership = mkStringInRegex(x, language)
                        solver.assert(if (positive) membership else mkNot(membership))
                        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                        solver.model().use { assertEquals(word, it.eval(x)) }
                        val statistics = assertNotNull(solver.lastCheckStatistics)
                        assertEquals(40L, statistics.membershipTransitionsEncoded)
                        assertTrue(statistics.membershipTransitionsConsidered > statistics.membershipTransitionsEncoded)
                        assertTrue(assertNotNull(statistics.lastCandidateEncodingConstraints) <= 300)
                        assertTrue(assertNotNull(statistics.lastCandidateEncodingEstimate) > 300)
                    }
                }
            }
        }

    @Test
    fun literalBoundariesPreserveNulAndSupplementaryCharacters() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val points = listOf(97, 0, 0x1f600)
            for (positive in listOf(false, true)) {
                KYicesSolver(ctx).use { solver ->
                    val encoder = CandidateRealization(ctx, solver, mapOf(x.decl to 1), {}, 100, 20)
                    val base = SymbolicDfa.literal(points)
                    val language = if (positive) base else base.complement()
                    val atom = StringAtom.Membership(mkFreshConst("membership", boolSort), listOf(
                        StringTermPart.Literal(listOf(97)), StringTermPart.Variable(x.decl),
                        StringTermPart.Literal(listOf(0x1f600)),
                    ), language)
                    encoder.assertAtom(atom, positive)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
                    solver.model().use { assertContentEquals(intArrayOf(0), encoder.strings(it).getValue(x.decl)) }
                    assertEquals(3L, encoder.membershipTransitionsEncoded)
                    assertTrue(encoder.encodedConstraints <= 20)
                }
            }
        }
    }

    @Test
    fun repeatedVariablesKeepTheirSharedCharactersAfterPruning() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            KYicesSolver(ctx).use { solver ->
                val encoder = CandidateRealization(ctx, solver, mapOf(x.decl to 1), {}, 100, 100)
                val language = SymbolicDfa.literal(listOf(97, 98)).union(SymbolicDfa.literal(listOf(98, 97)))
                val atom = StringAtom.Membership(mkFreshConst("membership", boolSort),
                    listOf(StringTermPart.Variable(x.decl), StringTermPart.Variable(x.decl)), language)
                encoder.assertAtom(atom, positive = true)
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                assertTrue(encoder.membershipTransitionsEncoded < encoder.membershipTransitionsConsidered)
            }
        }
    }

    @Test
    fun exhaustedOptionalPlanningFallsBackToExactEncodingUnderBothPolarities() = KContext().use { ctx ->
        with(ctx) {
            for (point in listOf(97, 98)) {
                for (positive in listOf(false, true)) {
                    KYicesSolver(ctx).use { solver ->
                        val encoder = CandidateRealization(ctx, solver, emptyMap(), {}, 100, 100,
                            PreprocessingBudget(3))
                        val atom = StringAtom.Membership(mkFreshConst("membership", boolSort),
                            listOf(StringTermPart.Literal(listOf(point))), SymbolicDfa.literal(listOf(97)))
                        encoder.assertAtom(atom, positive)
                        assertEquals(if ((point == 97) == positive) KSolverStatus.SAT else KSolverStatus.UNSAT,
                            solver.check(10.seconds))
                        assertEquals(1, encoder.membershipPruningFallbacks)
                        assertEquals(encoder.membershipTransitionsConsidered, encoder.membershipTransitionsEncoded)
                    }
                }
            }
        }
    }
}

package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.noodler.core.Noodler
import io.ksmt.solver.noodler.core.extractNoodlerStatements
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.z3.KZ3Solver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerPeriodicLengthTest {
    @Test
    fun periodicRuleCanBeDisabledForAblation() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val assertions = listOf(mkStringInRegex(x, language), mkStringInRegex(y, language),
                mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x))))
            val result = Noodler(extractNoodlerStatements(assertions), periodicLengthReasoning = false).propagate()
            assertTrue(result.lengthConstraints.isEmpty())
            assertEquals(false, result.contradiction)
        }
    }

    @Test
    fun commonPowerDisequalitiesAreReducedToLengthsForBothBackends() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            for (period in listOf("a", "ab")) {
                solver.push()
                val language = mkRegexStar(mkStringToRegex(mkStringLiteral(period)))
                val assertions = listOf(
                    mkStringInRegex(x, language), mkStringInRegex(y, language),
                    mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x))),
                )
                val propagation = Noodler(extractNoodlerStatements(assertions)).propagate()
                assertEquals(1, propagation.lengthConstraints.size)
                solver.assert(assertions)
                assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
                assertTrue(assertNotNull(solver.lastCheckStatistics).generatedConstraints > 0)
                solver.pop()
            }
        }
    }

    @Test
    fun commonPowersWithDifferentLengthsStillHaveDisequalityModels() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val assertions = listOf(
                mkStringInRegex(x, language), mkStringInRegex(y, language), mkNot(mkEq(x, y)),
                mkEq(mkStringLen(x), mkIntNum(2)), mkEq(mkStringLen(y), mkIntNum(4)),
            )
            solver.assert(assertions)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                KZ3Solver(ctx).use { validator ->
                    validator.assert(assertions)
                    validator.assert(mkEq(x, model.eval(x)))
                    validator.assert(mkEq(y, model.eval(y)))
                    assertEquals(KSolverStatus.SAT, validator.check(10.seconds))
                }
            }
            assertEquals(1, assertNotNull(solver.lastCheckStatistics).generatedConstraints)
        }
    }

    @Test
    fun incompatiblePeriodsDoNotTurnEqualLengthsIntoEqualStrings() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val assertions = listOf(
                mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))),
                mkStringInRegex(y, mkRegexStar(mkStringToRegex(mkStringLiteral("ba")))),
                mkEq(mkStringLen(x), mkIntNum(2)), mkEq(mkStringLen(y), mkStringLen(x)), mkNot(mkEq(x, y)),
            )
            assertTrue(Noodler(extractNoodlerStatements(assertions)).propagate().lengthConstraints.isEmpty())
            solver.assert(assertions)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                KZ3Solver(ctx).use { validator ->
                    validator.assert(assertions)
                    validator.assert(mkEq(x, model.eval(x)))
                    validator.assert(mkEq(y, model.eval(y)))
                    assertEquals(KSolverStatus.SAT, validator.check(10.seconds))
                }
            }
        }
    }

    @Test
    fun aDisequalityInsideDisjunctionIsNotAnUnconditionalFact() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val choose = mkConst("choose", boolSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
            val assertions = listOf(mkStringInRegex(x, language), mkStringInRegex(y, language),
                mkOr(choose, mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x)))))
            assertTrue(Noodler(extractNoodlerStatements(assertions)).propagate().lengthConstraints.isEmpty())
            solver.assert(assertions)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { assertEquals(trueExpr, it.eval(choose)) }
        }
    }

    @Test
    fun generatedLengthLemmasAreLocalToScopesAndAssumptionChecks() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val disequality = mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x)))
            solver.assert(listOf(mkStringInRegex(x, language), mkStringInRegex(y, language)))
            assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(disequality), 10.seconds))
            assertTrue(assertNotNull(solver.lastCheckStatistics).generatedConstraints > 0)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            assertEquals(0, assertNotNull(solver.lastCheckStatistics).generatedConstraints)
            solver.push()
            solver.assert(disequality)
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            solver.pop()
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun negativeMembershipCanJustifyTheSharedPeriodicRule() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            solver.assert(listOf(
                mkNot(mkStringInRegex(x, mkRegexComplement(language))),
                mkNot(mkStringInRegex(y, mkRegexComplement(language))),
                mkNot(mkEq(mkStringConcat(x, y), mkStringConcat(y, x))),
            ))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            assertTrue(assertNotNull(solver.lastCheckStatistics).generatedConstraints > 0)
        }
    }

    @Test
    fun distinctStringTermsAreExpandedIntoNecessaryPairwiseDisequalities() = forEachBackend { ctx, solver ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val y = mkConst("y", stringSort)
            val z = mkConst("z", stringSort)
            val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
            val assertions = listOf(mkStringInRegex(x, language), mkStringInRegex(y, language),
                mkStringInRegex(z, language), mkDistinctNoSimplify(listOf(
                    mkStringConcat(x, y), mkStringConcat(y, x), z,
                )))
            assertEquals(3, Noodler(extractNoodlerStatements(assertions)).propagate().lengthConstraints.size)
            solver.assert(assertions)
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        }
    }

    @Test
    fun largeDistinctExpansionRemainsResourceLimited(): Unit = KContext().use { ctx ->
        with(ctx) {
            val variables = List(500) { mkFreshConst("word", stringSort) }
            val assertions = listOf(mkDistinctNoSimplify(variables))
            assertFailsWith<AutomataLimitException> { Noodler(extractNoodlerStatements(assertions)).propagate() }
        }
    }

    private fun forEachBackend(action: (KContext, KNoodlerSolver) -> Unit) {
        val backends: List<(KContext) -> KSolver<out KSolverConfiguration>> = listOf(
            { KZ3Solver(it) }, { KCvc5Solver(it) },
        )
        for (factory in backends) {
            KContext().use { ctx -> KNoodlerSolver(ctx, factory(ctx)).use { action(ctx, it) } }
        }
    }
}

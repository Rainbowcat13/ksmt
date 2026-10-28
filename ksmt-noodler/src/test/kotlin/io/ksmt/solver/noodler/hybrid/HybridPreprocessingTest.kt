package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.model.KModelImpl
import io.ksmt.solver.noodler.KNoodlerYicesSolver
import io.ksmt.solver.noodler.checkNoodlerYicesSupport
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KRegexSort
import io.ksmt.sort.KStringSort
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HybridPreprocessingTest {
    @Test
    fun deepConcatFlattensOnlyTheRequestedRoot(): Unit = KContext().use { ctx ->
        val leaf = ctx.mkStringLiteral("a")
        var word: KExpr<KStringSort> = leaf
        repeat(DEEP_INPUT_SIZE) { word = ctx.mkStringConcatNoSimplify(word, leaf) }
        val original = ctx.mkEqNoSimplify(word, ctx.mkConst("x", ctx.stringSort))
        val abstraction = StringAbstraction(ctx, RegexAutomata(), {})

        abstraction.translate(original)

        val atom = abstraction.atoms.getValue(original) as StringAtom.Equality
        assertEquals(DEEP_INPUT_SIZE + 1, atom.equation.left.size)
        assertEquals(1, atom.equation.right.size)
    }

    @Test
    fun sharedDoublingHasAnExactLengthWithoutUnfoldingTheWord(): Unit = KContext().use { ctx ->
        var word: KExpr<KStringSort> = ctx.mkStringLiteral(intArrayOf(0x1f600))
        repeat(80) { word = ctx.mkStringConcatNoSimplify(word, word) }
        val original = ctx.mkEqNoSimplify(
            ctx.mkStringLenNoSimplify(word), ctx.mkIntNum(BigInteger.ONE.shiftLeft(80))
        )
        val abstraction = StringAbstraction(ctx, RegexAutomata(), {})
        val translated = abstraction.translate(original)

        KModelImpl(ctx, emptyMap(), emptyMap()).use { model ->
            assertEquals(ctx.trueExpr, model.eval(translated, isComplete = true))
        }
        assertTrue(abstraction.atoms.isEmpty())
    }

    @Test
    fun exponentiallyExpandedAtomsStopBeforeAllocatingTheirLeaves(): Unit = KContext().use { ctx ->
        var word: KExpr<KStringSort> = ctx.mkStringLiteral("a")
        repeat(80) { word = ctx.mkStringConcatNoSimplify(word, word) }
        val original = ctx.mkEqNoSimplify(word, ctx.mkConst("x", ctx.stringSort))
        assertNull(checkNoodlerYicesSupport(listOf(original)))
        val abstraction = StringAbstraction(ctx, RegexAutomata(), {}, PreprocessingBudget(10_000))

        val failure = assertFailsWith<PreprocessingLimitException> { abstraction.translate(original) }
        assertTrue(requireNotNull(failure.message).contains("preprocessing resource limit"))
        assertTrue(abstraction.atoms.isEmpty())
    }

    @Test
    fun wideDistinctReservesAllPairsBeforeCreatingAtoms(): Unit = KContext().use { ctx ->
        val variables = List(1_000) { ctx.mkConst("x$it", ctx.stringSort) }
        val assertion = ctx.mkDistinctNoSimplify(variables)
        val abstraction = StringAbstraction(ctx, RegexAutomata(), {}, PreprocessingBudget(20_000))

        assertFailsWith<PreprocessingLimitException> { abstraction.translate(assertion) }
        assertTrue(abstraction.atoms.isEmpty())
    }

    @Test
    fun deeplyNestedRegexIsCompiledWithoutRecursion(): Unit = KContext().use { ctx ->
        val points = intArrayOf(0xd800, 0xdc00, 0x1f600, 0x2ffff)
        var expression: KExpr<KRegexSort> = ctx.mkStringToRegex(ctx.mkStringLiteral(points))
        repeat(DEEP_INPUT_SIZE) { expression = ctx.mkRegexComplementNoSimplify(expression) }

        val language = RegexAutomata().compile(expression)
        assertTrue(language.accepts(points))
        assertEquals(false, language.accepts(intArrayOf(0x10000, 0x1f600, 0x2ffff)))
    }

    @Test
    fun enormousRegexPowerReservesItsExpansionBeforeIterating(): Unit = KContext().use { ctx ->
        val expression = ctx.mkRegexPowerNoSimplify(
            Int.MAX_VALUE, ctx.mkStringToRegex(ctx.mkStringLiteral(""))
        )
        assertFailsWith<PreprocessingLimitException> { RegexAutomata().compile(expression) }
    }

    @Test
    fun deeplyNestedBooleanInputReachesSat(): Unit = KContext().use { ctx ->
        val variable = ctx.mkConst("p", ctx.boolSort)
        var assertion: KExpr<KBoolSort> = variable
        repeat(DEEP_INPUT_SIZE) { assertion = ctx.mkAndNoSimplify(assertion, variable) }
        assertNull(checkNoodlerYicesSupport(listOf(assertion)))
        KNoodlerYicesSolver(ctx).use { solver ->
            solver.assert(assertion)
            assertEquals(KSolverStatus.SAT, solver.check(20.seconds))
            assertEquals(ctx.trueExpr, solver.model().eval(variable))
        }
    }

    @Test
    fun modelValidationChargesIntermediateConcatCopies(): Unit = KContext().use { ctx ->
        var word: KExpr<KStringSort> = ctx.mkStringLiteral("a")
        repeat(1_000) { word = ctx.mkStringConcatNoSimplify(word, ctx.mkStringLiteral("a")) }
        val assertion = ctx.mkEqNoSimplify(ctx.mkStringLenNoSimplify(word), ctx.mkIntNum(1_001))
        val model = NoodlerYicesModel(ctx, emptyMap())

        val failure = assertFailsWith<PreprocessingLimitException> {
            model.validates(listOf(assertion), {}, PreprocessingBudget(10_000))
        }
        assertTrue(requireNotNull(failure.message).contains("preprocessing resource limit"))
    }

    @Test
    fun validationResourceExhaustionIsUnknownAndCheckLocal(): Unit = KContext().use { ctx ->
        var word: KExpr<KStringSort> = ctx.mkStringLiteral("a")
        repeat(1_000) { word = ctx.mkStringConcatNoSimplify(word, ctx.mkStringLiteral("a")) }
        KNoodlerYicesSolver(ctx).use { solver ->
            solver.configure { maxPreprocessingUnits = 50_000 }
            solver.push()
            solver.assert(ctx.mkEqNoSimplify(ctx.mkStringLenNoSimplify(word), ctx.mkIntNum(1_001)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(20.seconds))
            assertTrue(solver.reasonOfUnknown().contains("preprocessing resource limit"))
            solver.pop()
            solver.assert(ctx.trueExpr)
            assertEquals(KSolverStatus.SAT, solver.check(20.seconds))
        }
    }

    @Test
    fun configuredZeroBudgetIsAnExplicitUnknown(): Unit = KContext().use { ctx ->
        KNoodlerYicesSolver(ctx).use { solver ->
            solver.configure { setIntParameter("noodler.maxPreprocessingUnits", 0) }
            solver.assert(ctx.trueExpr)
            assertEquals(KSolverStatus.UNKNOWN, solver.check(20.seconds))
            assertEquals("preprocessing resource limit (0)", solver.reasonOfUnknown())
        }
    }

    @Test
    fun concatDagAndWitnessKeepDistinctSurrogateCharacters(): Unit = KContext().use { ctx ->
        val expected = intArrayOf(0xd800, 0xdc00, 0x1f600, 0x2ffff)
        val word = ctx.mkStringConcatNoSimplify(
            ctx.mkStringLiteral(expected.copyOfRange(0, 2)), ctx.mkStringLiteral(expected.copyOfRange(2, 4))
        )
        val variable = ctx.mkConst("unicode", ctx.stringSort)
        KNoodlerYicesSolver(ctx).use { solver ->
            solver.assert(ctx.mkEqNoSimplify(variable, word))
            solver.assert(ctx.mkEqNoSimplify(ctx.mkStringLenNoSimplify(variable), ctx.mkIntNum(4)))
            assertEquals(KSolverStatus.SAT, solver.check(20.seconds))
            val value = solver.model().eval(variable) as KStringLiteralExpr
            assertContentEquals(expected, value.codePoints)
        }
    }

    @Test
    fun syntaxCheckerRejectsUnsupportedAndSymbolicRegexWithoutCompilingAutomata(): Unit = KContext().use { ctx ->
        val string = ctx.mkConst("x", ctx.stringSort)
        assertTrue(checkNoodlerYicesSupport(listOf(ctx.mkStringContains(string, ctx.mkStringLiteral("a")))) != null)
        assertTrue(checkNoodlerYicesSupport(listOf(ctx.mkStringInRegex(string, ctx.mkStringToRegex(string)))) != null)
    }

    companion object {
        private const val DEEP_INPUT_SIZE = 10_000
    }
}

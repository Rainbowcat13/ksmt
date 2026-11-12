package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.decl.KDecl
import io.ksmt.sort.KStringSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodicWordEqualityTest {
    private fun word(value: String): SymbolicDfa = SymbolicDfa.literal(value.codePoints().toArray().toList())

    @Test
    fun reorderedConcatenationsOfCommonWordPowersHaveLengthEquivalence() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val y = mkConstDecl("y", stringSort)
            val z = mkConstDecl("z", stringSort)
            val variables = listOf(x, y, z).map { StringTermPart.Variable(it) }
            val equation = StringEquation(variables + variables[0], variables.reversed() + variables[0])
            for (period in listOf("a", "ab")) {
                val languages: Map<KDecl<KStringSort>, SymbolicDfa> =
                    listOf(x, y, z).associateWith { word(period).star() }
                assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                    equation, languages, {}, PreprocessingBudget(),
                ))
            }
        }
    }

    @Test
    fun certificateDoesNotDependOnAChosenLengthAndAllowsGaps() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val y = mkConstDecl("y", stringSort)
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Variable(y)))
            // The first nonempty accepted word is abab, whose primitive root must be reduced to ab.
            assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to word("abab").star(), y to word("ab").star()), {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun distinctPrimitiveWordsDoNotJustifyLengthEquality() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val y = mkConstDecl("y", stringSort)
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Variable(y)))
            for ((left, right) in listOf("a" to "b", "ab" to "ba")) {
                assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                    equation, mapOf(x to word(left).star(), y to word(right).star()), {}, PreprocessingBudget(),
                ))
            }
        }
    }

    @Test
    fun equalRegularLanguagesWithMultipleWordsPerLengthAreNotACertificate() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val y = mkConstDecl("y", stringSort)
            val language = word("ab").union(word("ba"))
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Variable(y)))
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to language, y to language), {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun intervalAlternativesAreCheckedBeyondTheSampledCharacter() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Literal(listOf(97))))
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to SymbolicDfa.range(97, 98).star()), {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun literalsMustBeWholePowersOfTheSameWord() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val languages: Map<KDecl<KStringSort>, SymbolicDfa> = mapOf(x to word("ab").star())
            fun equation(literal: String) = StringEquation(
                listOf(StringTermPart.Variable(x)),
                listOf(StringTermPart.Literal(literal.codePoints().toArray().toList())),
            )
            assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                equation("abab"), languages, {}, PreprocessingBudget(),
            ))
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                equation("a"), languages, {}, PreprocessingBudget(),
            ))
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                equation("ba"), languages, {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun emptyDomainsAndEmptyWordsAreHandledWithoutInventingAPeriod() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val y = mkConstDecl("y", stringSort)
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Variable(y)))
            assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to SymbolicDfa.epsilon(), y to SymbolicDfa.epsilon()), {}, PreprocessingBudget(),
            ))
            assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to SymbolicDfa.none(), y to SymbolicDfa.epsilon()), {}, PreprocessingBudget(),
            ))
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to SymbolicDfa.epsilon()), {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun unicodeCodePointsAndNonemptyAcceptingCyclesArePreserved() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val points = listOf(0, 0xd800, 0x1f600, MAX_STRING_CODE_POINT)
            val equation = StringEquation(
                listOf(StringTermPart.Variable(x)), listOf(StringTermPart.Literal(points + points)),
            )
            assertTrue(PeriodicWordEquality.provesLengthEquivalence(
                equation, mapOf(x to SymbolicDfa.literal(points).star()), {}, PreprocessingBudget(),
            ))
            val unconstrained = StringEquation(listOf(StringTermPart.Variable(x)), emptyList())
            assertFalse(PeriodicWordEquality.provesLengthEquivalence(
                unconstrained, mapOf(x to SymbolicDfa.allWords()), {}, PreprocessingBudget(),
            ))
        }
    }

    @Test
    fun optionalWorkExhaustionAndAutomataCapsRemainObservable(): Unit = KContext().use { ctx ->
        with(ctx) {
            val x = mkConstDecl("x", stringSort)
            val equation = StringEquation(listOf(StringTermPart.Variable(x)), emptyList())
            val parent = PreprocessingBudget(1_000)
            assertFailsWith<PreprocessingLimitException> {
                PeriodicWordEquality.provesLengthEquivalence(
                    equation, mapOf(x to word("ab").star()), {}, parent.limited(5),
                )
            }
            assertTrue(parent.remaining >= 995)
            val limited = word("ab").withLimits(AutomataLimits(maxStorage = 14))
            assertFailsWith<AutomataLimitException> {
                PeriodicWordEquality.provesLengthEquivalence(equation, mapOf(x to limited), {}, PreprocessingBudget())
            }
        }
    }

    @Test
    fun cancellationIsNeverConvertedIntoAProof(): Unit = KContext().use { ctx ->
        with(ctx) {
            class Cancelled : RuntimeException()
            val x = mkConstDecl("x", stringSort)
            var checks = 0
            assertFailsWith<Cancelled> {
                PeriodicWordEquality.provesLengthEquivalence(
                    StringEquation(listOf(StringTermPart.Variable(x)), emptyList()),
                    mapOf(x to word("ab").star()), { if (++checks == 8) throw Cancelled() }, PreprocessingBudget(),
                )
            }
            assertEquals(8, checks)
        }
    }
}

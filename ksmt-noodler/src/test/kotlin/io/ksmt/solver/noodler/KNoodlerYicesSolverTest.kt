package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.sort.KBoolSort
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KNoodlerYicesSolverTest {
    @Test
    fun testPeriodicLanguageRejectsOddLengthAndRealizesEvenLength() = inContext {
        val x = mkConst("x", stringSort)
        val language = mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkStringInRegex(x, language))
            solver.push()
            solver.assert(mkEq(mkStringLen(x), mkIntNum(3)))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
            solver.pop()
            val length = mkEq(mkStringLen(x), mkIntNum(4))
            solver.assert(length)
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                assertEquals(mkStringLiteral("abab"), model.eval(x))
                assertEquals(trueExpr, model.eval(length))
                assertEquals(trueExpr, model.eval(mkStringInRegex(x, language)))
            }
        }
    }

    @Test
    fun testRepeatedOccurrencesShareCharacters() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkStringInRegex(x, mkRegexUnion(
                mkStringToRegex(mkStringLiteral("a")), mkStringToRegex(mkStringLiteral("b"))
            )))
            solver.assert(mkEq(y, mkStringLiteral("ab")))
            solver.assert(mkEq(mkStringConcat(x, x), y))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        }
    }

    @Test
    fun testNestedBooleanFormulaAndStringIte() = inContext {
        val x = mkConst("x", stringSort)
        val chooseA = mkConst("chooseA", boolSort)
        val isA = mkEq(x, mkStringLiteral("a"))
        val isB = mkEq(x, mkStringLiteral("b"))
        val assertions = listOf(
            mkOr(mkAnd(chooseA, isA), mkAnd(mkNot(chooseA), isB)),
            mkImplies(chooseA, mkNot(isB)),
            mkXor(isA, isB),
            mkEq(mkIte(chooseA, x, mkStringLiteral("b")), mkStringLiteral("b")),
            mkIte(chooseA, isA, isB),
        )
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { model ->
                assertEquals(falseExpr, model.eval(chooseA))
                assertEquals(mkStringLiteral("b"), model.eval(x))
            }
        }
    }

    @Test
    fun testEqualLengthStringsCanBeDifferent() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val assertions = listOf(
            mkEq(mkStringLen(x), mkIntNum(1)),
            mkEq(mkStringLen(y), mkStringLen(x)),
            mkNot(mkEq(x, y)),
        )
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { assertNotEquals(it.eval(x), it.eval(y)) }
        }
    }

    @Test
    fun testNegativeRegexMembershipUsesComplement() = inContext {
        val x = mkConst("x", stringSort)
        val a = mkStringToRegex(mkStringLiteral("a"))
        val b = mkStringToRegex(mkStringLiteral("b"))
        val assertions = listOf(mkStringInRegex(x, mkRegexUnion(a, b)), mkNot(mkStringInRegex(x, a)))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { assertEquals(mkStringLiteral("b"), it.eval(x)) }
        }
    }

    @Test
    fun testSharedVariablesPreserveCorrelatedAlternatives() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val z = mkConst("z", stringSort)
        val assertions = listOf(
            mkEq(mkStringConcat(x, y), mkStringLiteral("ab")),
            mkEq(mkStringConcat(y, z), mkStringLiteral("ba")),
            mkEq(mkStringLen(x), mkIntNum(1)),
            mkEq(x, z),
        )
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { model ->
                assertEquals(mkStringLiteral("a"), model.eval(x))
                assertEquals(mkStringLiteral("b"), model.eval(y))
                assertEquals(mkStringLiteral("a"), model.eval(z))
            }
        }
    }

    @Test
    fun testCyclicLanguagesRetainLongerWords() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val aStar = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
        val assertions = listOf(
            mkStringInRegex(x, aStar), mkStringInRegex(y, aStar),
            mkEq(mkStringLen(x), mkIntNum(3)), mkEq(mkStringLen(y), mkIntNum(4)),
            mkEq(mkStringConcat(x, y), mkStringLiteral("aaaaaaa")),
        )
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
        }
    }

    @Test
    fun testRegexOnlyProblemProducesAStringModel() = inContext {
        val x = mkConst("x", stringSort)
        val assertion = mkStringInRegex(x, mkRegexCross(mkStringToRegex(mkStringLiteral("ab"))))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertion)
            assertSatAndValid(solver, listOf(assertion))
            solver.model().use { assertTrue(it.eval(x) is KStringLiteralExpr) }
        }
    }

    @Test
    fun testZeroLengthIsExactlyEmptyString() = inContext {
        val x = mkConst("x", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkEq(mkStringLen(x), mkIntNum(0)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { assertEquals(mkStringLiteral(""), it.eval(x)) }
            solver.assert(mkNot(mkEq(x, mkStringLiteral(""))))
            assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        }
    }

    @Test
    fun testNulAndSupplementaryCharactersHaveCodePointLengths() = inContext {
        val x = mkConst("x", stringSort)
        val literal = mkStringLiteral("\u0000\uD83D\uDE00")
        val assertions = listOf(mkEq(x, literal), mkEq(mkStringLen(x), mkIntNum(2)))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { assertEquals(literal, it.eval(x)) }
        }
    }

    @Test
    fun testExplicitSurrogateSequenceDiffersFromSupplementaryCodePoint() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val surrogateSequence = mkStringLiteral(intArrayOf(0xD83D, 0xDE00))
        val supplementary = mkStringLiteral(intArrayOf(0x1F600))
        val assertions = listOf(
            mkEq(x, surrogateSequence), mkEq(y, supplementary), mkNot(mkEq(x, y)),
            mkEq(mkStringLen(x), mkIntNum(2)), mkEq(mkStringLen(y), mkIntNum(1)),
        )
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { model ->
                assertContentEquals(surrogateSequence.codePoints, (model.eval(x) as KStringLiteralExpr).codePoints)
                assertContentEquals(supplementary.codePoints, (model.eval(y) as KStringLiteralExpr).codePoints)
            }
        }
    }

    @Test
    fun testCharacterResourceLimitReturnsUnknownAndCanBeRaised() = inContext {
        val x = mkConst("x", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.configure { maxCharacterPositions = 2 }
            solver.assert(mkEq(mkStringLen(x), mkIntNum(3)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertFalse(solver.reasonOfUnknown().isBlank())
        }
        KNoodlerYicesSolver(this).use { solver ->
            solver.configure { maxCharacterPositions = 3 }
            solver.assert(mkEq(mkStringLen(x), mkIntNum(3)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        }
    }

    @Test
    fun testPushPopAndDetachedModel() = inContext {
        val x = mkConst("x", stringSort)
        val a = mkStringLiteral("a")
        val solver = KNoodlerYicesSolver(this)
        solver.assert(mkEq(x, a))
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        val detached = solver.model().detach()
        solver.push()
        assertFails { solver.model() }
        solver.assert(mkEq(x, mkStringLiteral("b")))
        assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        solver.pop()
        assertFails { solver.model() }
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        solver.close()
        detached.use { assertEquals(a, it.eval(x)) }
    }

    @Test
    fun testAssumptionsAndUnsatCoreDoNotLeakBetweenChecks() = inContext {
        val x = mkConst("x", stringSort)
        val tracked = mkEq(x, mkStringLiteral("a"))
        val assumption = mkEq(x, mkStringLiteral("b"))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assertAndTrack(tracked)
            assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(assumption), 10.seconds))
            val core = solver.unsatCore()
            assertTrue(core.contains(tracked))
            assertTrue(core.contains(assumption))
            assertTrue(core.all { it == tracked || it == assumption })
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { assertEquals(mkStringLiteral("a"), it.eval(x)) }
        }
    }

    @Test
    fun testModelContainsOnlyOriginalDeclarations() = inContext {
        val x = mkConst("x", stringSort)
        val n = mkConst("n", intSort)
        val b = mkConst("b", boolSort)
        val assertions = listOf(b, mkEq(n, mkStringLen(x)), mkEq(x, mkStringLiteral("ab")))
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(assertions)
            assertSatAndValid(solver, assertions)
            solver.model().use { model ->
                assertEquals(setOf(x.decl, n.decl, b.decl), model.declarations)
                assertEquals(mkIntNum(2), model.eval(n))
                assertEquals(trueExpr, model.eval(b))
            }
        }
    }

    @Test
    fun testExpiredDeadlineReturnsUnknown() = inContext {
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkEq(mkConst("x", stringSort), mkStringLiteral("a")))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(Duration.ZERO))
            assertFalse(solver.reasonOfUnknown().isBlank())
            assertFails { solver.model() }
        }
    }

    @Test
    fun testUnsupportedStringOperationReturnsUnknown() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkEq(x, mkStringReplaceAll(y, mkStringLiteral("a"), mkStringLiteral("b"))))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertFalse(solver.reasonOfUnknown().isBlank())
        }
    }

    @Test
    fun testSymbolicRegexReturnsUnknown() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkStringInRegex(x, mkStringToRegex(y)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertFalse(solver.reasonOfUnknown().isBlank())
        }
    }

    @Test
    fun testNonlinearArithmeticReturnsUnknown() = inContext {
        val n = mkConst("n", intSort)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkEq(mkArithMul(n, n), mkIntNum(4)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertFalse(solver.reasonOfUnknown().isBlank())
        }
    }

    @Test
    fun testQuantifierReturnsUnknown() = inContext {
        val x = mkConst("x", stringSort)
        val body = mkEq(mkStringConcat(x, mkStringLiteral("a")), x)
        KNoodlerYicesSolver(this).use { solver ->
            solver.assert(mkExistentialQuantifier(body, listOf(x.decl)))
            assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
            assertFalse(solver.reasonOfUnknown().isBlank())
        }
    }

    @Test
    fun testSmallWordEquationsAgreeWithZ3() = inContext {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val alphabet = mkRegexStar(mkRegexUnion(
            mkStringToRegex(mkStringLiteral("a")), mkStringToRegex(mkStringLiteral("b"))
        ))
        for (word in listOf("", "a", "ab", "aa")) {
            for (length in 0..2) {
                val assertions = listOf(
                    mkEq(mkStringConcat(x, y), mkStringLiteral(word)),
                    mkEq(x, y), mkEq(mkStringLen(x), mkIntNum(length)),
                    mkStringInRegex(x, alphabet),
                )
                KZ3Solver(this).use { reference ->
                    KNoodlerYicesSolver(this).use { hybrid ->
                        reference.assert(assertions)
                        hybrid.assert(assertions)
                        val expected = reference.check(10.seconds)
                        assertNotEquals(KSolverStatus.UNKNOWN, expected)
                        val actual = hybrid.check(10.seconds)
                        assertEquals(expected, actual, "word=$word, length=$length")
                        if (actual == KSolverStatus.SAT) {
                            hybrid.model().use { model ->
                                assertions.forEach { assertEquals(trueExpr, model.eval(it, isComplete = true)) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun KContext.assertSatAndValid(
        solver: KNoodlerYicesSolver,
        assertions: List<KExpr<KBoolSort>>,
    ) {
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
        solver.model().use { model ->
            assertions.forEach { assertion ->
                assertEquals(trueExpr, model.eval(assertion, isComplete = true), "Invalid witness for $assertion")
            }
        }
    }

    private fun inContext(test: KContext.() -> Unit) = KContext().use { it.test() }
}

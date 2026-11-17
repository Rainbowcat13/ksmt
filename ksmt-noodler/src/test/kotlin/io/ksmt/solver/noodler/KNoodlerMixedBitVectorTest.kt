package io.ksmt.solver.noodler

import io.ksmt.KContext
import io.ksmt.expr.KBitVecValue
import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KTheory
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.BvUtils.bigIntValue
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KNoodlerMixedBitVectorTest {
    @Test
    fun pureBvPreservesWidthsAndModularArithmetic() = acrossConfigurations { solver ->
        val wide = mkConst("original.wide", mkBvSort(65u))
        val byte = mkConst("original.byte", mkBvSort(8u))
        val large = BigInteger.ONE.shiftLeft(64).add(BigInteger.valueOf(3))
        val assertions = listOf(
            mkEq(wide, mkBv(large, 65u)),
            mkEq(byte, mkBv(255, 8u)),
            mkEq(mkBvAddExpr(byte, mkBv(1, 8u)), mkBv(0, 8u)),
            mkBvSignedLessExpr(byte, mkBv(0, 8u)),
            mkBvUnsignedGreaterExpr(byte, mkBv(0, 8u)),
            mkEq(mkBvExtractExpr(7, 0, wide), mkBv(3, 8u)),
        )
        solver.assert(assertions)
        assertSatAndValidated(solver, assertions)
        val value = solver.model().eval(wide) as KBitVecValue<*>
        assertEquals(65u, value.sort.sizeBits)
        assertEquals(large, value.bigIntValue())
        assertTrue(solver.model().declarations.all { it.name.startsWith("original.") })
        solver.assert(mkNot(assertions[2]))
        assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
    }

    @Test
    fun signedAndUnsignedConversionsCoupleStringLengthAndBranches() = acrossConfigurations { solver ->
        val bits = mkConst("bits", mkBvSort(4u))
        val string = mkConst("s", stringSort)
        val unsigned = mkBv2IntExpr(bits, isSigned = false)
        val signed = mkBv2IntExpr(bits, isSigned = true)
        val negative = mkBvSignedLessExpr(bits, mkBv(0, 4u))
        val assertions = listOf(
            mkEq(bits, mkBv(15, 4u)),
            mkEq(mkStringLen(string), unsigned),
            mkEq(signed, mkIntNum(-1)),
            mkStringInRegex(string, mkRegexStar(mkStringToRegex(mkStringLiteral("a")))),
            mkEq(mkIte(negative, string, mkStringLiteral("b")), string),
        )
        solver.assert(assertions)
        assertSatAndValidated(solver, assertions)
        assertEquals(mkStringLiteral("a".repeat(15)), solver.model().eval(string))
        assertEquals(mkBv(15, 4u), solver.model().eval(bits))
        solver.push()
        solver.assert(mkBvUnsignedLessExpr(bits, mkBv(8, 4u)))
        assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        solver.pop()
        assertSatAndValidated(solver, assertions)
    }

    @Test
    fun overflowCouplingAndOneBitSignedConversion() = acrossConfigurations { solver ->
        val bits = mkConst("byte", mkBvSort(8u))
        val one = mkConst("one", bv1Sort)
        val empty = mkConst("empty", stringSort)
        val single = mkConst("single", stringSort)
        val assertions = listOf(
            mkEq(bits, mkBv(255, 8u)),
            mkEq(one, mkBv(true)),
            mkEq(mkStringLen(empty), mkBv2IntExpr(mkBvAddExpr(bits, mkBv(1, 8u)), false)),
            mkEq(mkStringLen(single), mkArithUnaryMinus(mkBv2IntExpr(one, true))),
            mkEq(single, mkStringLiteral("z")),
        )
        solver.assert(assertions)
        assertSatAndValidated(solver, assertions)
        assertEquals(mkStringLiteral(""), solver.model().eval(empty))
        assertEquals(mkIntNum(-1), solver.model().eval(mkBv2IntExpr(one, true)))
        assertEquals(mkIntNum(1), solver.model().eval(mkBv2IntExpr(one, false)))
    }

    @Test
    fun mixedAssumptionsAndScopesDoNotLeak() = acrossConfigurations { solver ->
        val bits = mkConst("length", mkBvSort(2u))
        val string = mkConst("s", stringSort)
        val assertions = listOf(
            mkEq(mkStringLen(string), mkBv2IntExpr(bits, false)),
            mkArithLe(mkStringLen(string), mkIntNum(2)),
            mkStringInRegex(string, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))),
        )
        solver.assert(assertions)
        val impossible = mkEq(bits, mkBv(3, 2u))
        assertEquals(KSolverStatus.UNSAT, solver.checkWithAssumptions(listOf(impossible), 10.seconds))
        assertEquals(listOf(impossible), solver.unsatCore())
        assertSatAndValidated(solver, assertions)
        solver.push()
        val tracked = mkEq(bits, mkBv(1, 2u))
        solver.assertAndTrack(tracked)
        assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds))
        assertEquals(listOf(tracked), solver.unsatCore())
        solver.pop()
        assertEquals(KSolverStatus.SAT,
            solver.checkWithAssumptions(listOf(mkEq(bits, mkBv(2, 2u))), 10.seconds))
        assertEquals(mkStringLiteral("ab"), solver.model().eval(string))
    }

    @Test
    fun nativeOperatorFamiliesValidateWithStrings() = acrossConfigurations { solver ->
        val a = mkConst("a", mkBvSort(8u))
        val b = mkConst("b", mkBvSort(8u))
        val valueA = mkBv(129, 8u)
        val valueB = mkBv(3, 8u)
        val string = mkConst("s", stringSort)
        val assertions = mutableListOf(
            mkEq(a, valueA), mkEq(b, valueB),
            mkEq(mkBvAndExpr(a, b), mkBvAndExpr(valueA, valueB)),
            mkEq(mkBvNotExpr(a), mkBvNotExpr(valueA)),
            mkEq(mkBvReductionOrExpr(a), mkBvReductionOrExpr(valueA)),
            mkEq(mkBvMulExpr(a, b), mkBvMulExpr(valueA, valueB)),
            mkEq(mkBvSignedDivExpr(a, b), mkBvSignedDivExpr(valueA, valueB)),
            mkEq(mkBvUnsignedDivExpr(a, b), mkBvUnsignedDivExpr(valueA, valueB)),
            mkEq(mkBvSignedRemExpr(a, b), mkBvSignedRemExpr(valueA, valueB)),
            mkEq(mkBvSignedModExpr(a, b), mkBvSignedModExpr(valueA, valueB)),
            mkEq(mkBvConcatExpr(a, b), mkBvConcatExpr(valueA, valueB)),
            mkEq(mkBvSignExtensionExpr(3, a), mkBvSignExtensionExpr(3, valueA)),
            mkEq(mkBvZeroExtensionExpr(3, a), mkBvZeroExtensionExpr(3, valueA)),
            mkEq(mkBvRepeatExpr(2, a), mkBvRepeatExpr(2, valueA)),
            mkEq(mkBvArithShiftRightExpr(a, b), mkBvArithShiftRightExpr(valueA, valueB)),
            mkEq(mkBvRotateLeftExpr(a, b), mkBvRotateLeftExpr(valueA, valueB)),
            mkEq(mkBvRotateRightIndexedExpr(3, a), mkBvRotateRightIndexedExpr(3, valueA)),
            mkEq(mkBvAddNoOverflowExpr(a, b, true), mkBvAddNoOverflowExpr(valueA, valueB, true)),
            mkEq(mkBvMulNoUnderflowExpr(a, b), mkBvMulNoUnderflowExpr(valueA, valueB)),
            mkEq(mkStringLen(string), mkBv2IntExpr(mkBvExtractExpr(1, 0, b), false)),
            mkEq(string, mkStringLiteral("abc")),
        )
        for (width in listOf(1u, 2u, 3u)) {
            val word = mkConst("word.$width", mkBvSort(width))
            val amount = mkConst("rotation.$width", mkBvSort(width))
            val knownWord = mkBv(5, width)
            val knownAmount = mkBv(7, width)
            assertions += mkEq(word, knownWord)
            assertions += mkEq(amount, knownAmount)
            assertions += mkEq(mkBvRotateLeftExpr(word, amount), mkBvRotateLeftExpr(knownWord, knownAmount))
            assertions += mkEq(mkBvRotateRightExpr(word, amount), mkBvRotateRightExpr(knownWord, knownAmount))
        }
        solver.assert(assertions)
        assertSatAndValidated(solver, assertions)
    }

    @Test
    fun unsupportedSortsAreStillRejected() {
        KContext().use { ctx ->
            val real = ctx.mkConst("real", ctx.realSort)
            val assertion = ctx.mkEq(real, ctx.mkRealNum(1))
            assertNotNull(checkNoodlerHybridSupport(listOf(assertion)))
        }
    }

    private fun acrossConfigurations(block: KContext.(KNoodlerHybridSolver) -> Unit) {
        for (backend in KNoodlerBackend.values()) for (target in NoodlerEncodingTarget.values()) {
            KContext().use { ctx ->
                KNoodlerHybridSolver(ctx, backend).use { solver ->
                    solver.configure {
                        encodingTarget = target
                        optimizeForTheories(setOf(KTheory.S, KTheory.LIA, KTheory.BV), false)
                    }
                    ctx.block(solver)
                }
            }
        }
    }

    private fun KContext.assertSatAndValidated(
        solver: KNoodlerHybridSolver, assertions: List<KExpr<KBoolSort>>,
    ) {
        assertEquals(KSolverStatus.SAT, solver.check(10.seconds), solver.lastCheckStatistics?.terminationReason)
        val model = solver.model()
        assertions.forEach { assertEquals(trueExpr, model.eval(it), it.toString()) }
    }
}

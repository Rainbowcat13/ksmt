package io.ksmt.solver.z3

import io.ksmt.KContext
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StringUnicodeTest {
    @Test
    fun printedLiteralsRoundTripThroughSmtLibParser() = KContext().use { ctx -> with(ctx) {
        val codes = intArrayOf(0, '"'.code, '\\'.code, 0xd800, 0xdc00, 0x10000, 0x2ffff)
        val literal = mkStringLiteral(codes)
        val assertion = KZ3SMTLibParser(this).parse(
            "(declare-const s String) (assert (= s $literal))"
        ).single() as KEqExpr<*>
        val parsed = assertion.args.filterIsInstance<KStringLiteralExpr>().single()
        assertContentEquals(codes, parsed.codePoints)
    } }

    @Test
    fun nativeConstructionAndModelExtractionPreserveCharacterCodes() = KContext().use { ctx -> with(ctx) {
        KZ3Solver(this).use { solver ->
            val separate = mkStringLiteral(intArrayOf(0xd800, 0xdc00))
            val supplementary = mkStringLiteral(intArrayOf(0x10000))
            solver.assert(mkEqNoSimplify(mkStringLenNoSimplify(separate), mkIntNum(2)))
            solver.assert(mkEqNoSimplify(mkStringLenNoSimplify(supplementary), mkIntNum(1)))
            solver.assert(mkNotNoSimplify(mkEqNoSimplify(separate, supplementary)))
            val variable = mkConst("s", stringSort)
            val computed = mkStringConcatNoSimplify(
                mkStringFromCodeNoSimplify(mkIntNum(0xd801)),
                mkStringFromCodeNoSimplify(mkIntNum(0xdc01))
            )
            solver.assert(mkEqNoSimplify(variable, computed))
            assertEquals(KSolverStatus.SAT, solver.check())
            assertContentEquals(intArrayOf(0xd801, 0xdc01), (solver.model().eval(variable) as KStringLiteralExpr).codePoints)
        }
    } }
}

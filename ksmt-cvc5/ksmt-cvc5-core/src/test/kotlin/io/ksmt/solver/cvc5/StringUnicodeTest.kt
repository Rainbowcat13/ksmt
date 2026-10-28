package io.ksmt.solver.cvc5

import io.ksmt.KContext
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KStringSort
import io.github.cvc5.Solver
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class StringUnicodeTest {
    @Test
    fun converterReadsNativeCharacterCodesWithoutUtf16Ambiguity() = KContext().use { ctx ->
        KCvc5Solver(ctx).close() // Load native libraries before creating the term manager.
        KCvc5TermManager().use { manager ->
            val solver = manager.builder { Solver(this) }
            KCvc5Context(manager, ctx).use { nativeContext ->
                val converter = KCvc5ExprConverter(ctx, nativeContext, solver)
                val codes = intArrayOf(0, 0xd800, 0xdc00, 0x10000, 0x2ffff)
                val nativeValue = manager.builder {
                    mkString("\\u{0}\\u{d800}\\u{dc00}\\u{10000}\\u{2ffff}", true)
                }
                val converted = with(converter) { nativeValue.convertExpr<KStringSort>() } as KStringLiteralExpr
                assertContentEquals(codes, converted.codePoints)
            }
        }
    }

    @Test
    fun nativeConstructionAndModelExtractionPreserveCharacterCodes() = KContext().use { ctx -> with(ctx) {
        KCvc5Solver(this).use { solver ->
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

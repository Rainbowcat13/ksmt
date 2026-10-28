package io.ksmt

import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.solver.model.KModelImpl
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class StringUnicodeTest {
    @Test
    fun canonicalIdentityPreservesSurrogateCharacters() = KContext().use { ctx -> with(ctx) {
        val separate = mkStringLiteral(intArrayOf(0xd800, 0xdc00))
        val supplementary = mkStringLiteral(intArrayOf(0x10000))
        assertNotEquals(separate, supplementary)
        assertNotEquals(separate.decl, supplementary.decl)
        assertSame(separate, mkStringLiteral(intArrayOf(0xd800, 0xdc00)))
        assertSame(supplementary, mkStringLiteral("\uD800\uDC00"))
        assertSame(separate, separate.decl.apply())
        assertEquals(mkIntNum(2), mkStringLen(separate))
        assertEquals(mkIntNum(1), mkStringLen(supplementary))
        assertFailsWith<IllegalStateException> { separate.value }
        assertEquals("\uD800\uDC00", supplementary.value)
    } }

    @Test
    fun literalStorageIsImmutableAndValidatesAlphabet() = KContext().use { ctx -> with(ctx) {
        val input = intArrayOf(0, 0x2ffff)
        val literal = mkStringLiteral(input)
        input[0] = 42
        literal.codePoints[1] = 42
        literal.decl.codePoints[0] = 42
        assertContentEquals(intArrayOf(0, 0x2ffff), literal.codePoints)
        assertFailsWith<IllegalArgumentException> { mkStringLiteral(intArrayOf(-1)) }
        assertFailsWith<IllegalArgumentException> { mkStringLiteral(intArrayOf(0x30000)) }
        assertFailsWith<IllegalArgumentException> { mkStringLiteral(String(Character.toChars(0x30000))) }
    } }

    @Test
    fun concatenationAndReversalDoNotCombineSurrogates() = KContext().use { ctx -> with(ctx) {
        val high = mkStringLiteral(intArrayOf(0xd800))
        val low = mkStringLiteral(intArrayOf(0xdc00))
        val pair = mkStringConcat(high, low) as KStringLiteralExpr
        assertContentEquals(intArrayOf(0xd800, 0xdc00), pair.codePoints)
        val reversed = mkStringReverse(pair) as KStringLiteralExpr
        assertContentEquals(intArrayOf(0xdc00, 0xd800), reversed.codePoints)
        assertEquals(mkIntNum(2), mkStringLen(pair))
    } }

    @Test
    fun positionalOperationsUseCharacterCodes() = KContext().use { ctx -> with(ctx) {
        val value = mkStringLiteral(intArrayOf(0x10000, 0xd800, 0xdc00, 'x'.code))
        val high = mkStringLiteral(intArrayOf(0xd800))
        val pair = mkStringLiteral(intArrayOf(0xd800, 0xdc00))
        assertEquals(high, mkStringSingletonSub(value, mkIntNum(1)))
        assertEquals(pair, mkStringSub(value, mkIntNum(1), mkIntNum(2)))
        assertEquals(mkIntNum(1), mkStringIndexOf(value, pair, mkIntNum(0)))
        assertEquals(trueExpr, mkStringContains(value, pair))
        assertEquals(falseExpr, mkStringContains(pair, mkStringLiteral(intArrayOf(0x10000))))
        assertEquals(mkStringLiteral(intArrayOf(0x10000, 'z'.code, 'x'.code)),
            mkStringReplace(value, pair, mkStringLiteral("z")))
        assertEquals(trueExpr, mkStringLt(mkStringLiteral(intArrayOf(0xe000)), mkStringLiteral(intArrayOf(0x10000))))
    } }

    @Test
    fun arbitraryPrecisionIndicesAndNumericConversions() = KContext().use { ctx -> with(ctx) {
        val huge = BigInteger.ONE.shiftLeft(100)
        val hugeExpr = mkIntNum(huge)
        val value = mkStringLiteral(intArrayOf(0x10000, 'x'.code))
        assertEquals(mkStringLiteral(""), mkStringSingletonSub(value, hugeExpr))
        assertEquals(mkStringLiteral(""), mkStringSub(value, hugeExpr, mkIntNum(1)))
        assertEquals(value, mkStringSub(value, mkIntNum(0), hugeExpr))
        assertEquals(mkIntNum(-1), mkStringIndexOf(value, mkStringLiteral(""), hugeExpr))
        assertEquals(mkStringLiteral(""), mkStringFromCode(hugeExpr))
        assertEquals(mkStringLiteral(huge.toString()), mkStringFromInt(hugeExpr))
        assertEquals(hugeExpr, mkStringToInt(mkStringLiteral(huge.toString())))
        assertEquals(mkIntNum(-1), mkStringToInt(mkStringLiteral("\u0661")))
        assertEquals(falseExpr, mkStringIsDigit(mkStringLiteral("\u0661")))
        assertEquals(mkStringLiteral(intArrayOf(0x2ffff)), mkStringFromCode(mkIntNum(0x2ffff)))
        assertEquals(mkIntNum(0x10000), mkStringToCode(mkStringLiteral(intArrayOf(0x10000))))
    } }

    @Test
    fun printingEscapesTheFullAlphabet() = KContext().use { ctx -> with(ctx) {
        val literal = mkStringLiteral(intArrayOf(0, '"'.code, '\\'.code, 0xd800, 0xdc00, 0x10000, 0x2ffff))
        assertEquals("\"\\u{0}\"\"\\u{5c}\\u{d800}\\u{dc00}\\u{10000}\\u{2ffff}\"", literal.toString())
        assertNotEquals(mkStringLiteral("\\u{10000}").decl, mkStringLiteral(intArrayOf(0x10000)).decl)
    } }

    @Test
    fun modelEvaluationDistinguishesLiteralSequences() = KContext().use { ctx -> with(ctx) {
        val separate = mkStringLiteral(intArrayOf(0xd800, 0xdc00))
        val supplementary = mkStringLiteral(intArrayOf(0x10000))
        val model = KModelImpl(this, emptyMap(), emptyMap())
        assertEquals(falseExpr, model.eval(mkEqNoSimplify(separate, supplementary)))
        assertEquals(trueExpr, model.eval(mkDistinctNoSimplify(listOf(separate, supplementary, mkStringLiteral("")))))
        assertEquals(trueExpr, model.eval(mkEqNoSimplify(separate, mkStringConcatNoSimplify(
            mkStringLiteral(intArrayOf(0xd800)), mkStringLiteral(intArrayOf(0xdc00))
        ))))
    } }

    @Test
    fun modelEvaluationFoldsStringsWithoutContextSimplification() =
        KContext(simplificationMode = KContext.SimplificationMode.NO_SIMPLIFY).use { ctx -> with(ctx) {
            val high = mkStringLiteral(intArrayOf(0xd800))
            val low = mkStringLiteral(intArrayOf(0xdc00))
            val concat = mkStringConcat(high, low)
            val model = KModelImpl(this, emptyMap(), emptyMap())
            assertEquals(mkStringLiteral(intArrayOf(0xd800, 0xdc00)), model.eval(concat))
            assertEquals(mkIntNum(2), model.eval(mkStringLen(concat)))
            assertEquals(falseExpr, model.eval(mkEq(concat, mkStringLiteral(intArrayOf(0x10000)))))
        } }
}

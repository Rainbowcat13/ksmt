package io.ksmt.runner.serializer

import com.jetbrains.rd.framework.createAbstractBuffer
import com.jetbrains.rd.framework.writeEnum
import io.ksmt.KContext
import io.ksmt.expr.KStringLiteralExpr
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertEquals

class StringUnicodeSerializationTest {
    @Test
    fun legacyStringLiteralPayloadRemainsReadable() = KContext().use { context ->
        val state = AstSerializationCtx().apply { initCtx(context) }
        val buffer = createAbstractBuffer()
        buffer.writeInt(1)
        buffer.writeEnum(AstKind.Expr)
        buffer.writeEnum(ExprKind.StringLiteralExpr)
        buffer.writeString("legacy\uD800\uDC00")
        buffer.writeInt(AstSerializationCtx.SERIALIZED_AST_ENTRY_END)
        buffer.writeInt(AstSerializationCtx.SERIALIZED_DATA_END_IDX)
        buffer.writeInt(1)
        buffer.rewind()
        val restored = AstDeserializer(state, buffer).deserializeAst() as KStringLiteralExpr
        assertEquals(context.mkStringLiteral("legacy\uD800\uDC00"), restored)
    }

    @Test
    fun literalRoundTripPreservesSurrogatesAndSupplementaryCharacters() {
        KContext().use { source ->
            KContext().use { target ->
                val sourceState = AstSerializationCtx().apply { initCtx(source) }
                val targetState = AstSerializationCtx().apply { initCtx(target) }
                val codes = intArrayOf(0, 0xd800, 0xdc00, 0x10000, 0x2ffff, '\\'.code, '"'.code)
                val buffer = createAbstractBuffer()
                AstSerializer(sourceState, buffer).serializeAst(source.mkStringLiteral(codes))
                buffer.rewind()
                val restored = AstDeserializer(targetState, buffer).deserializeAst() as KStringLiteralExpr
                assertContentEquals(codes, restored.codePoints)
                assertNotEquals(target.mkStringLiteral(intArrayOf(0xd800, 0xdc00)),
                    target.mkStringLiteral(intArrayOf(0x10000)))
            }
        }
    }
}

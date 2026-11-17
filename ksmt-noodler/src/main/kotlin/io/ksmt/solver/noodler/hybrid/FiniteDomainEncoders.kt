package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KBitVecValue
import io.ksmt.expr.KExpr
import io.ksmt.expr.KIntNumExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.noodler.NoodlerEncodingTarget
import io.ksmt.sort.KBvSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KSort
import io.ksmt.utils.ArithUtils.bigIntegerValue
import io.ksmt.utils.BvUtils.toBigIntegerUnsigned

/** Erasure is confined here; each domain creates and consumes terms of its own exact sort. */
@Suppress("UNCHECKED_CAST")
internal fun finiteDomainEncoding(
    ctx: KContext, target: NoodlerEncodingTarget, maximumValue: Int,
): FiniteDomainEncoding<KSort> = when (target) {
    NoodlerEncodingTarget.INT -> IntegerFiniteDomainEncoding(ctx, maximumValue)
    NoodlerEncodingTarget.BV -> BitVectorFiniteDomainEncoding(ctx, maximumValue)
} as FiniteDomainEncoding<KSort>

internal class IntegerFiniteDomainEncoding(
    private val ctx: KContext, override val maximumValue: Int,
) : FiniteDomainEncoding<KIntSort> {
    init { require(maximumValue >= 0) }

    override fun freshVariable(name: String) = ctx.mkFreshConst(name, ctx.intSort)
    override fun value(value: Int): KExpr<KIntSort> {
        require(value in 0..maximumValue) { "Value $value is outside 0..$maximumValue" }
        return ctx.mkIntNum(value)
    }
    override fun equal(left: KExpr<KIntSort>, right: KExpr<KIntSort>) = ctx.mkEq(left, right)
    override fun range(value: KExpr<KIntSort>, lower: Int, upper: Int) = ctx.mkAnd(
        ctx.mkArithGe(value, value(lower)), ctx.mkArithLe(value, value(upper)),
    ).also { require(lower <= upper) }
    override fun knownValue(value: KExpr<KIntSort>): Int? =
        (value as? KIntNumExpr)?.bigIntegerValue?.intValueExact()

    override fun decode(model: KModel, value: KExpr<KIntSort>): Int {
        val decoded = knownValue(model.eval(value, isComplete = true))
            ?: throw HybridIncomplete("Backend did not assign an integer finite-domain value")
        if (decoded !in 0..maximumValue) throw HybridIncomplete("Backend assigned an out-of-domain value")
        return decoded
    }
}

/** Unsigned domains; no finite-width arithmetic is needed or exposed by this encoder. */
internal class BitVectorFiniteDomainEncoding(
    private val ctx: KContext, override val maximumValue: Int,
) : FiniteDomainEncoding<KBvSort> {
    init { require(maximumValue >= 0) }
    // A singleton domain still requires a legal, nonzero BV width. For 2^k states the maximum
    // identifier is 2^k-1, so exactly k bits suffice without overflowing a state-count addition.
    val width: UInt = maxOf(1, Int.SIZE_BITS - maximumValue.countLeadingZeroBits()).toUInt()
    private val sort = ctx.mkBvSort(width)

    override fun freshVariable(name: String) = ctx.mkFreshConst(name, sort)
    override fun value(value: Int): KExpr<KBvSort> {
        require(value in 0..maximumValue) { "Value $value is outside 0..$maximumValue" }
        return ctx.mkBv(value, sort)
    }
    override fun equal(left: KExpr<KBvSort>, right: KExpr<KBvSort>) = ctx.mkEq(left, right)
    override fun range(value: KExpr<KBvSort>, lower: Int, upper: Int) = ctx.mkAnd(
        ctx.mkBvUnsignedGreaterOrEqualExpr(value, value(lower)),
        ctx.mkBvUnsignedLessOrEqualExpr(value, value(upper)),
    ).also { require(lower <= upper) }
    override fun knownValue(value: KExpr<KBvSort>): Int? =
        (value as? KBitVecValue<*>)?.toBigIntegerUnsigned()?.intValueExact()

    override fun decode(model: KModel, value: KExpr<KBvSort>): Int {
        val decoded = knownValue(model.eval(value, isComplete = true))
            ?: throw HybridIncomplete("Backend did not assign a bit-vector finite-domain value")
        if (decoded !in 0..maximumValue) throw HybridIncomplete("Backend assigned an out-of-domain value")
        return decoded
    }
}

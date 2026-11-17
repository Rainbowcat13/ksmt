package io.ksmt.solver.noodler.hybrid

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KSort

/**
 * Ordinary KSMT terms for one finite, nonnegative domain in exact character realization.
 * Implementations must represent every value through [maximumValue] without truncation and decode
 * model values exactly. BV order is unsigned. This contract deliberately offers no wrapping
 * arithmetic and does not encode unrestricted input integers or string lengths.
 */
internal interface FiniteDomainEncoding<S : KSort> {
    val maximumValue: Int
    fun freshVariable(name: String): KExpr<S>
    fun value(value: Int): KExpr<S>
    fun equal(left: KExpr<S>, right: KExpr<S>): KExpr<KBoolSort>
    fun range(value: KExpr<S>, lower: Int, upper: Int): KExpr<KBoolSort>
    fun knownValue(value: KExpr<S>): Int?
    fun decode(model: KModel, value: KExpr<S>): Int
}

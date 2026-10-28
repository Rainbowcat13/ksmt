package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KIntSort

/** Check-local, bounded analysis cache. Failed analysis never excludes a possible string length. */
internal class AutomatonLengthProjection(
    private val ctx: KContext,
    private val adaptive: Boolean,
) {
    private data class Projection(
        val summary: AutomatonLengthSummary?, val exact: AcceptedLengths?, val prefix: AcceptedLengthPrefix? = null,
    ) {
        val size: Int get() = 1 + (summary?.residues?.size ?: 0) +
            (exact?.prefix?.size ?: 0) + (exact?.residues?.size ?: 0) + (prefix?.accepted?.size ?: 0)
    }

    private val cache = linkedMapOf<SymbolicDfa, Projection>()
    private var cacheSize = 0L
    var cacheHits: Int = 0
        private set
    var fallbacks: Int = 0
        private set

    fun constraint(
        length: KExpr<KIntSort>, language: SymbolicDfa, checkpoint: () -> Unit,
    ): KExpr<KBoolSort> {
        checkpoint()
        // Disabling the policy reproduces the original exact projection, including its resource limits.
        if (!adaptive) return exactConstraint(length, language.acceptedLengths(checkpoint), checkpoint)
        val projection = cache[language]?.also { cacheHits++ } ?: analyze(language, checkpoint).also {
            val weight = language.storageSize + it.size
            if (weight <= MAX_CACHE_ENTRIES) {
                while (cacheSize + weight > MAX_CACHE_ENTRIES) {
                    val oldest = cache.entries.iterator()
                    val entry = oldest.next()
                    cacheSize -= entry.key.storageSize + entry.value.size
                    oldest.remove()
                }
                cache[language] = it
                cacheSize += weight
            }
        }
        val consequence = projection.exact?.let { exactConstraint(length, it, checkpoint) }
            ?: projection.summary?.let { summaryConstraint(length, it, checkpoint) }
            ?: ctx.trueExpr
        val prefix = projection.prefix ?: return consequence
        return ctx.mkAnd(consequence, prefixConstraint(length, prefix, checkpoint))
    }

    private fun analyze(language: SymbolicDfa, checkpoint: () -> Unit): Projection {
        val summary = attempt { language.lengthSummary(checkpoint) }
        // Empty and singleton length sets are already exact; subset enumeration adds no information.
        if (summary != null && (summary.minLength == null || summary.minLength == summary.maxLength)) {
            return Projection(summary, null)
        }
        var prefix: AcceptedLengthPrefix? = null
        val exact = attempt(onFailure = { if (it is LengthProjectionLimitException) prefix = it.prefix }) {
            language.acceptedLengths(checkpoint, MAX_EXACT_SUBSETS)
        }
        return Projection(summary, exact, prefix)
    }

    private fun <T> attempt(onFailure: (AutomataLimitException) -> Unit = {}, action: () -> T): T? = try {
        action()
    } catch (ex: AutomataLimitException) {
        // Shared preprocessing exhaustion and cancellation must retain their normal handling.
        if (ex is PreprocessingLimitException) throw ex
        fallbacks++
        onFailure(ex)
        null
    }

    private fun prefixConstraint(
        length: KExpr<KIntSort>, prefix: AcceptedLengthPrefix, checkpoint: () -> Unit,
    ): KExpr<KBoolSort> = with(ctx) {
        if (prefix.cutoff == prefix.accepted.size) return trueExpr
        val known = prefix.accepted.map {
            checkpoint()
            mkEq(length, mkIntNum(it))
        }
        mkOr(known + mkArithGe(length, mkIntNum(prefix.cutoff)))
    }

    private fun summaryConstraint(
        length: KExpr<KIntSort>, summary: AutomatonLengthSummary, checkpoint: () -> Unit,
    ): KExpr<KBoolSort> = with(ctx) {
        val minimum = summary.minLength ?: return falseExpr
        if (minimum == summary.maxLength) return mkEq(length, mkIntNum(minimum))
        val constraints = arrayListOf(mkArithGe(length, mkIntNum(minimum)))
        summary.maxLength?.let { constraints += mkArithLe(length, mkIntNum(it)) }
        if (summary.modulus > 1) {
            val remainder = mkIntMod(length, mkIntNum(summary.modulus))
            constraints += mkOr(summary.residues.map {
                checkpoint()
                mkEq(remainder, mkIntNum(it))
            })
        }
        mkAnd(constraints)
    }

    private fun exactConstraint(
        length: KExpr<KIntSort>, accepted: AcceptedLengths, checkpoint: () -> Unit,
    ): KExpr<KBoolSort> = with(ctx) {
        val finite = accepted.prefix.map {
            checkpoint()
            mkEq(length, mkIntNum(it))
        }
        val shifted = mkArithSub(length, mkIntNum(accepted.cycleStart))
        val remainder = mkIntMod(shifted, mkIntNum(accepted.period))
        val periodic = accepted.residues.map { residue ->
            checkpoint()
            mkAnd(
                mkArithGe(length, mkIntNum(accepted.cycleStart + residue)),
                mkEq(remainder, mkIntNum(residue)),
            )
        }
        mkOr(finite + periodic)
    }

    companion object {
        private const val MAX_EXACT_SUBSETS = 256
        private const val MAX_CACHE_ENTRIES = 16_384
    }
}

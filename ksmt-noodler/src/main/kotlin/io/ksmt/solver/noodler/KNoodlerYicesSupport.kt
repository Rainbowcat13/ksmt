package io.ksmt.solver.noodler

import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.hybrid.HybridSyntax
import io.ksmt.sort.KBoolSort

/** Returns null for the supported syntax, or its exclusion reason, without creating solver state. */
fun checkNoodlerYicesSupport(assertions: List<KExpr<KBoolSort>>): String? = try {
    val seen = hashSetOf<KExpr<*>>()
    val pending = ArrayDeque<KExpr<*>>()
    pending.addAll(assertions)
    while (pending.isNotEmpty()) {
        val expression = pending.removeLast()
        if (seen.add(expression)) pending.addAll(HybridSyntax.dependencies(expression))
    }
    null
} catch (ex: KSolverUnsupportedFeatureException) {
    ex.message ?: "unsupported expression"
}

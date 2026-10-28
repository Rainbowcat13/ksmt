package io.ksmt.solver.noodler.hybrid

/** Exact membership below [cutoff]; no information about lengths at or above that boundary. */
internal data class AcceptedLengthPrefix(val cutoff: Int, val accepted: Set<Int>)

/** Transfers the traversal's completed prefix without copying it after allocation allowance exhaustion. */
internal class LengthProjectionLimitException(
    val prefix: AcceptedLengthPrefix,
    message: String,
) : AutomataLimitException(message)

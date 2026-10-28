package io.ksmt.solver.noodler.hybrid

/** Cumulative check-local allocation/work allowance; exhausting it never constrains string lengths. */
internal class PreprocessingBudget(
    val limit: Int = DEFAULT_LIMIT,
    private val checkpoint: () -> Unit = {},
    private val parent: PreprocessingBudget? = null,
) {
    private var used = 0L

    val remaining: Int get() = (limit - used).toInt()

    /** Child work also counts against this allowance; child exhaustion leaves the parent remainder intact. */
    fun limited(allowance: Int): PreprocessingBudget =
        PreprocessingBudget(minOf(remaining, allowance), checkpoint, this)

    init { require(limit >= 0) }

    fun reserve(units: Long = 1) {
        checkpoint()
        require(units >= 0)
        if (units > limit.toLong() - used) {
            throw PreprocessingLimitException("preprocessing resource limit ($limit)")
        }
        parent?.reserve(units)
        used += units
    }

    /** Saturating size arithmetic for shared DAGs with exponentially many unfolded occurrences. */
    fun addSizes(left: Long, right: Long): Long =
        minOf(limit.toLong() + 1, left + minOf(right, limit.toLong() + 1))

    companion object {
        const val DEFAULT_LIMIT = 1_000_000
    }
}

internal class PreprocessingLimitException(message: String) : AutomataLimitException(message)

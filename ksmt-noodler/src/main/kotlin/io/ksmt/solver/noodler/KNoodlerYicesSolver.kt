package io.ksmt.solver.noodler

import io.ksmt.KContext

/** Compatibility entry point for the shared hybrid algorithm with Yices in both native roles. */
class KNoodlerYicesSolver(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.YICES)

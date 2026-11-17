package io.ksmt.solver.noodler

import io.ksmt.KContext

/** Reflection-friendly entry points for KSMT worker processes; all share one hybrid algorithm. */
class KNoodlerHybridZ3Solver(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.Z3)
class KNoodlerHybridCvc5Solver(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.CVC5)
class KNoodlerHybridYicesSolver(ctx: KContext) : KNoodlerHybridSolver(ctx, KNoodlerBackend.YICES)

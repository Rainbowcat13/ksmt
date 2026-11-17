package io.ksmt.solver.noodler.hybrid

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.noodler.KNoodlerBackend
import io.ksmt.solver.noodler.KNoodlerHybridSolver
import io.ksmt.solver.noodler.NoodlerEncodingTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BitVectorRealizationTest {
    @Test
    fun decodingRejectsUnusedDomainValues() = KContext().use { ctx ->
        // Deliberately omit the domain constraint to simulate a malformed backend assignment.
        // Reconstruction must reject it rather than truncate or reinterpret it as a valid ID.
        for (backend in KNoodlerBackend.values()) backend.create(ctx).use { solver ->
            val bvEncoding = BitVectorFiniteDomainEncoding(ctx, 2)
            val bv = bvEncoding.freshVariable("invalid.bv")
            solver.assert(ctx.mkEq(bv, ctx.mkBv(3, 2u)))
            val intEncoding = IntegerFiniteDomainEncoding(ctx, 2)
            val integer = intEncoding.freshVariable("invalid.integer")
            solver.assert(ctx.mkEq(integer, ctx.mkIntNum(3)))
            assertEquals(KSolverStatus.SAT, solver.check(10.seconds))
            solver.model().use { model ->
                assertFailsWith<HybridIncomplete> { bvEncoding.decode(model, bv) }
                assertFailsWith<HybridIncomplete> { intEncoding.decode(model, integer) }
            }
        }
    }

    @Test
    fun finiteDomainWidthsAndUnusedPatterns() = KContext().use { ctx ->
        for ((maximum, width) in listOf(0 to 1u, 1 to 1u, 2 to 2u, 3 to 2u, 4 to 3u,
            7 to 3u, 8 to 4u, 65_535 to 16u, 65_536 to 17u, MAX_STRING_CODE_POINT to 18u)) {
            val encoding = BitVectorFiniteDomainEncoding(ctx, maximum)
            assertEquals(width, encoding.width)
            assertEquals(maximum, encoding.knownValue(encoding.value(maximum)))
            assertFailsWith<IllegalArgumentException> { encoding.value(-1) }
            assertFailsWith<IllegalArgumentException> { encoding.value(maximum + 1) }
            // Where unused bit patterns exist, the unsigned range explicitly excludes them.
            if (maximum.toLong() + 1 < (1L shl width.toInt())) {
                for (backend in KNoodlerBackend.values()) backend.create(ctx).use { solver ->
                    val variable = encoding.freshVariable("state")
                    solver.assert(encoding.range(variable, 0, maximum))
                    solver.assert(ctx.mkEq(variable, ctx.mkBv(maximum + 1, width)))
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds), backend.name)
                }
            }
        }
    }

    @Test
    fun exactUnicodeDecodingAndNoSixteenBitTruncation() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val variable = listOf(StringTermPart.Variable(x.decl))
            val points = listOf(0, 0xffff, 0x10000, 0x1ffff, 0x20000, MAX_STRING_CODE_POINT)
            for (backend in KNoodlerBackend.values()) for (target in NoodlerEncodingTarget.values()) {
                for (point in points) backend.create(this).use { solver ->
                    val encoder = CandidateRealization(this, solver, mapOf(x.decl to 1), {}, 10, 1_000,
                        encodingTarget = target)
                    encoder.assertAtom(StringAtom.Equality(mkFreshConst("equal", boolSort),
                        StringEquation(variable, listOf(StringTermPart.Literal(listOf(point))))), true)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds), "$backend/$target/$point")
                    solver.model().use { model ->
                        assertContentEquals(intArrayOf(point), encoder.strings(model).getValue(x.decl))
                    }
                    val different = (point xor 0x10000).takeIf { it <= MAX_STRING_CODE_POINT } ?: (point xor 1)
                    encoder.assertAtom(StringAtom.Equality(mkFreshConst("different", boolSort),
                        StringEquation(variable, listOf(StringTermPart.Literal(listOf(different))))), true)
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds), "$backend/$target/$point")
                }
            }
        }
    }

    @Test
    fun membershipPolaritySharingEmptyWordsAndStateWidthsAgree() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            // A complete cycle with 1, 2, 3, 4, 5 states exercises singleton, exact powers of two,
            // and unused identifiers. Every character has the same transition.
            for (states in 1..5) {
                val language = SymbolicDfa(List(states) { source ->
                    listOf(Transition(0, MAX_STRING_CODE_POINT, (source + 1) % states))
                }, 0, setOf(0))
                for (length in 0..5) for (positive in listOf(false, true)) {
                    for (backend in KNoodlerBackend.values()) for (target in NoodlerEncodingTarget.values()) {
                        backend.create(this).use { solver ->
                            val encoder = CandidateRealization(this, solver, mapOf(x.decl to length), {}, 20,
                                1_000, maxMembershipPruningUnits = 0, encodingTarget = target)
                            val term = listOf(StringTermPart.Variable(x.decl), StringTermPart.Variable(x.decl))
                            encoder.assertAtom(StringAtom.Membership(mkFreshConst("membership", boolSort),
                                term, language), positive)
                            val accepts = 2 * length % states == 0
                            assertEquals(if (accepts == positive) KSolverStatus.SAT else KSolverStatus.UNSAT,
                                solver.check(10.seconds), "$backend/$target/states=$states/length=$length/$positive")
                        }
                    }
                }
            }
            // Sharing must forbid x.x = two different characters, including above 16 bits.
            for (target in NoodlerEncodingTarget.values()) for (pruning in listOf(0, 250_000)) {
                KNoodlerBackend.Z3.create(this).use { solver ->
                    val encoder = CandidateRealization(this, solver, mapOf(x.decl to 1), {}, 10, 1_000,
                        maxMembershipPruningUnits = pruning, encodingTarget = target)
                    encoder.assertAtom(StringAtom.Membership(mkFreshConst("membership", boolSort),
                        listOf(StringTermPart.Variable(x.decl), StringTermPart.Variable(x.decl)),
                        SymbolicDfa.literal(listOf(0, 0x10000))), true)
                    assertEquals(KSolverStatus.UNSAT, solver.check(10.seconds), "$target/pruning=$pruning")
                }
            }
        }
    }

    @Test
    fun publicTargetsPreserveUnboundedArithmeticAndValidateModels() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            val n = mkConst("n", intSort)
            val enormous = mkIntNum("18446744073709551616")
            val assertions = listOf(
                mkEq(n, enormous),
                mkArithGt(mkArithAdd(n, mkIntNum(1)), n),
                mkEq(mkStringLen(x), mkIntNum(1)),
                mkStringInRegex(x, mkRegexRange(mkStringLiteral(intArrayOf(0x20000)),
                    mkStringLiteral(intArrayOf(MAX_STRING_CODE_POINT)))),
            )
            for (backend in KNoodlerBackend.values()) for (target in NoodlerEncodingTarget.values()) {
                KNoodlerHybridSolver(this, backend).use { solver ->
                    solver.configure { encodingTarget = target; automataWitnessSearch = false }
                    solver.assert(assertions)
                    assertEquals(KSolverStatus.SAT, solver.check(10.seconds), "$backend/$target")
                    assertTrue(requireNotNull(solver.lastCheckStatistics).nativeRealizationChecks > 0)
                    solver.model().use { model ->
                        assertEquals(enormous, model.eval(n))
                        assertions.forEach { assertEquals(trueExpr, model.eval(it), "$backend/$target") }
                    }
                }
            }
        }
    }

    @Test
    fun finiteResourceFailureNeverBecomesGlobalUnsat() = KContext().use { ctx ->
        with(ctx) {
            val x = mkConst("x", stringSort)
            for (target in NoodlerEncodingTarget.values()) KNoodlerHybridSolver(this).use { solver ->
                solver.configure {
                    encodingTarget = target
                    maxCharacterPositions = 2
                    smallFirstSearch = true
                    automataWitnessSearch = false
                }
                solver.assert(mkEq(mkStringLen(x), mkIntNum(262_144)))
                assertEquals(KSolverStatus.UNKNOWN, solver.check(10.seconds))
                assertTrue(solver.reasonOfUnknown().contains("character realization resource limit"))
            }
        }
    }
}

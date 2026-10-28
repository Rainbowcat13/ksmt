package io.ksmt.solver.noodler.core

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.expr.KStringInRegexExpr
import io.ksmt.solver.KSolverUnsupportedFeatureException
import io.ksmt.solver.noodler.hybrid.AutomataLimitException
import io.ksmt.solver.noodler.hybrid.RegexAutomata
import io.ksmt.sort.KStringSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NoodlerTest {
    @Test
    fun propagatesTargetOnlyWhenStrictlyStronger(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val t = mkConst("t", stringSort)
        val engine = Noodler(listOf(
            mkEq(mkStringConcat(x, y), t),
            mkStringInRegex(x, mkStringToRegex(mkStringLiteral("a"))),
            mkStringInRegex(y, mkStringToRegex(mkStringLiteral("b")))
        ))
        val result = engine.propagate()
        assertFalse(result.contradiction)
        assertEquals(1, engine.equations.size)
        assertEquals(2, engine.regularConstraints.size)
        assertEquals(setOf(x.decl, y.decl), engine.inputRegularConstraints.keys)
        assertEquals(setOf(t.decl), engine.derivedRegularConstraints.keys)
        assertEquals(1, result.constraints.size)
        val language = RegexAutomata().compile(engine.derivedRegularConstraints.getValue(t.decl))
        assertTrue(language.accepts("ab".codePoints().toArray()))
        assertFalse(language.accepts("a".codePoints().toArray()))
        assertFalse(language.accepts("b".codePoints().toArray()))
    }

    @Test
    fun duplicateConstraintsIntersect(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val engine = Noodler(listOf(
            mkStringInRegex(x, mkRegexCross(mkStringToRegex(mkStringLiteral("a")))),
            mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("b"))))
        ))
        assertTrue(engine.propagate().contradiction)
        assertTrue(RegexAutomata().compile(engine.inputRegularConstraints.getValue(x.decl)).isEmpty())
    }

    @Test
    fun regexOnNonVariableIsUnsupported(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val constraint = mkStringInRegexNoSimplify(mkStringConcat(x, y), mkRegexAll())
        assertFailsWith<KSolverUnsupportedFeatureException> { Noodler(listOf(constraint)).propagate() }
    }

    @Test
    fun equationLiteralsRetainExactUnicodeWords(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val word = intArrayOf(0, 256, 0xd800, 0xdc00, 0x1f600)
        val engine = Noodler(listOf(mkEq(x, mkStringLiteral(word))), listOf('a'))
        assertFalse(engine.propagate().contradiction)
        val language = RegexAutomata().compile(engine.derivedRegularConstraints.getValue(x.decl))
        assertTrue(language.accepts(word))
        assertFalse(language.accepts(intArrayOf(0, 256, 0x10000, 0x1f600)))
    }

    @Test
    fun compatibilityAnalysisDoesNotRoundTripThroughUtf16(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val canonical = mkStringLiteral(intArrayOf(0xd800, 0xdc00))
        val hint = listOf('a')
        val analysis = listOf(mkStringInRegex(x, mkStringToRegex(canonical))).analyzeForNoodler(hint)
        assertFalse(analysis.skipNoodler)
        assertEquals(hint, analysis.alphabet)
    }

    @Test
    fun cyclicSegmentationKeepsMultiStateCycles(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val t = mkConst("t", stringSort)
        val engine = Noodler(listOf(
            mkStringInRegex(x, mkRegexStar(mkStringToRegex(mkStringLiteral("ab")))),
            mkStringInRegex(y, mkStringToRegex(mkStringLiteral("ab"))),
            mkEq(t, mkStringConcat(x, y)),
            mkEq(x, y)
        ), listOf('a', 'b'))
        assertFalse(engine.propagate().contradiction)
        assertTrue(RegexAutomata().compile(engine.derivedRegularConstraints.getValue(x.decl))
            .accepts("ab".codePoints().toArray()))
        assertTrue(RegexAutomata().compile(engine.derivedRegularConstraints.getValue(t.decl))
            .accepts("abab".codePoints().toArray()))
    }

    @Test
    fun starComplementDoesNotRejectValidProperPrefix(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val regex = mkRegexStar(mkRegexConcat(
            mkRegexStar(mkStringToRegex(mkStringLiteral("a"))),
            mkStringToRegex(mkStringLiteral("b"))
        ))
        val assertions = listOf(
            mkStringInRegex(x, mkStringToRegex(mkStringLiteral("a"))),
            mkNot(mkStringInRegex(x, regex))
        )
        assertFalse(Noodler(extractNoodlerStatements(assertions)).propagate().contradiction)
    }

    @Test
    fun allCharUsesFullUnicodeEvenWithFiniteAlphabetHint(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        for (codePoint in listOf(0, 256, 0xd800, 0x1f600, 0x2ffff)) {
            val engine = Noodler(listOf(
                mkStringInRegex(x, mkRegexAllChar()),
                mkStringInRegex(x, mkStringToRegex(mkStringLiteral(intArrayOf(codePoint))))
            ), listOf('a'))
            assertFalse(engine.propagate().contradiction)
        }
    }

    @Test
    fun declarationKeysDoNotMergeEqualPrintedNames(): Unit = with(KContext()) {
        val first = mkFreshConst("x", stringSort)
        val second = mkConst(first.decl.name, stringSort)
        assertNotEquals(first.decl, second.decl)
        val engine = Noodler(listOf(
            mkStringInRegex(first, mkStringToRegex(mkStringLiteral("a"))),
            mkStringInRegex(second, mkStringToRegex(mkStringLiteral("b")))
        ))
        assertFalse(engine.propagate().contradiction)
        assertEquals(setOf(first.decl, second.decl), engine.inputRegularConstraints.keys)
    }

    @Test
    fun finiteHintDoesNotRestrictUnconstrainedConjugacyVariables(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val y = mkConst("y", stringSort)
        val z = mkConst("z", stringSort)
        val t = mkConst("t", stringSort)
        val engine = Noodler(listOf(
            mkEq(mkStringConcat(x, z), mkStringConcat(z, y)),
            mkStringInRegex(t, mkStringToRegex(mkStringLiteral("a")))
        ), listOf('a'))
        val result = engine.propagate()
        assertFalse(result.contradiction)
        assertTrue(result.constraints.isEmpty())
    }

    @Test
    @Suppress("DEPRECATION")
    fun standalonePropagationDoesNotClaimToConstructJointModel(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val engine = Noodler(listOf(mkEq(mkStringConcat(x, x), mkStringLiteral("a"))))
        assertFailsWith<KSolverUnsupportedFeatureException> { engine.model() }
    }

    @Test
    fun extractionOnlyUsesNecessaryConjunctsAndComplementsNegativeMembership(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val a = mkStringInRegex(x, mkStringToRegex(mkStringLiteral("a")))
        val b = mkStringInRegex(x, mkStringToRegex(mkStringLiteral("b")))
        assertTrue(extractNoodlerStatements(listOf(mkOr(a, b))).isEmpty())
        val extracted = extractNoodlerStatements(listOf(mkAnd(mkNot(a), mkOr(a, b))))
        val regex = (extracted.single() as KStringInRegexExpr).arg1
        val language = RegexAutomata().compile(regex)
        assertFalse(language.accepts(intArrayOf('a'.code)))
        assertTrue(language.accepts(intArrayOf(0x1f600)))
    }

    @Test
    fun propagationChecksCancellationAndClearsPriorSnapshots(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        val engine = Noodler(listOf(mkEq(x, mkStringLiteral("a"))))
        assertFalse(engine.propagate().contradiction)
        assertFailsWith<InterruptedException> { engine.propagate { throw InterruptedException() } }
        assertTrue(engine.derivedRegularConstraints.isEmpty())
    }

    @Test
    fun sharedConcatExpansionHasFiniteBudget(): Unit = with(KContext()) {
        val x = mkConst("x", stringSort)
        var term: KExpr<KStringSort> = x
        repeat(20) { term = mkStringConcatNoSimplify(term, term) }
        val engine = Noodler(listOf(mkEqNoSimplify(term, x)))
        assertFailsWith<AutomataLimitException> { engine.propagate() }
    }
}

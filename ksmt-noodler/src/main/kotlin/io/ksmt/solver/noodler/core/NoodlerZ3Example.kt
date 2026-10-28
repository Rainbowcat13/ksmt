package io.ksmt.solver.noodler.core

import io.ksmt.KContext

fun main() = with(KContext()) {
    val z = mkConst("z", stringSort)
    val y = mkConst("y", stringSort)

    // zy = z
    // y in a+b+
    // z in b+
    val equation = mkEq(mkStringConcat(z, y), z)

    val aPlus = mkRegexCross(mkStringToRegex(mkStringLiteral("a")))
    val bPlus = mkRegexCross(mkStringToRegex(mkStringLiteral("b")))
    val yConstraint = mkStringInRegex(y, mkRegexConcat(aPlus, bPlus))
    val zConstraint = mkStringInRegex(z, bPlus)

    val assertions = listOf(equation, yConstraint, zConstraint)

    val n = Noodler(assertions, listOf('a', 'b'))

    val (sat, extra) = n.solve()
    println(if (sat) "No contradiction proved" else "Contradiction proved")
    for (a in extra) {
        println(a)
    }

    checkExampleFromArticle()
}

fun checkExampleFromArticle() = with(KContext()) {
    // zyx = xxz & x in a* & y in a+b+ & z in b*
    val x = mkConst("x", stringSort)
    val y = mkConst("y", stringSort)
    val z = mkConst("z", stringSort)

    val equation = mkEq(mkStringConcat(z, mkStringConcat(y, x)),
        mkStringConcat(x, mkStringConcat(x, z)))

    val astar = mkRegexStar(mkStringToRegex(mkStringLiteral("a")))
    val bstar = mkRegexStar(mkStringToRegex(mkStringLiteral("b")))
    val apbp = mkRegexConcat(mkRegexCross(mkStringToRegex(mkStringLiteral("a"))),
        mkRegexCross(mkStringToRegex(mkStringLiteral("b"))))

    val xc = mkStringInRegex(x, astar)
    val yc = mkStringInRegex(y, apbp)
    val zc = mkStringInRegex(z, bstar)

    val assertions = listOf(equation, xc, yc, zc)
    val n = Noodler(assertions, listOf('a', 'b'))
    val (sat, extra) = n.solve()
    println(if (sat) "No contradiction proved" else "Contradiction proved")
    for (a in extra) {
        println(a)
    }
}

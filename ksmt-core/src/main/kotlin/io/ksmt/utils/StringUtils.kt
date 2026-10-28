package io.ksmt.utils

import io.ksmt.KContext
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KInterpretedValue
import io.ksmt.expr.KStringLiteralExpr
import io.ksmt.sort.KBoolSort
import java.math.BigInteger

object StringUtils {
    const val STRING_FROM_CODE_UPPER_BOUND: Int = 0x2ffff

    @JvmStatic
    fun getStringLen(arg: KStringLiteralExpr): KIntNumExpr = arg.ctx.mkIntNum(arg.codePoints.size)

    @JvmStatic
    fun concatStrings(lhs: KStringLiteralExpr, rhs: KStringLiteralExpr): KStringLiteralExpr =
        lhs.ctx.mkStringLiteral(lhs.codePoints + rhs.codePoints)

    @JvmStatic
    fun isStringSuffix(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> {
        val suffix = arg0.codePoints
        val value = arg1.codePoints
        return arg0.ctx.mkBool(matchesAt(value, suffix, value.size - suffix.size)).uncheckedCast()
    }

    @JvmStatic
    fun isStringPrefix(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(matchesAt(arg1.codePoints, arg0.codePoints, 0)).uncheckedCast()

    @JvmStatic
    fun stringLt(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(compare(arg0.codePoints, arg1.codePoints) < 0).uncheckedCast()

    @JvmStatic
    fun stringLe(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(compare(arg0.codePoints, arg1.codePoints) <= 0).uncheckedCast()

    @JvmStatic
    fun stringGt(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(compare(arg0.codePoints, arg1.codePoints) > 0).uncheckedCast()

    @JvmStatic
    fun stringGe(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(compare(arg0.codePoints, arg1.codePoints) >= 0).uncheckedCast()

    @JvmStatic
    fun stringContains(arg0: KStringLiteralExpr, arg1: KStringLiteralExpr): KInterpretedValue<KBoolSort> =
        arg0.ctx.mkBool(indexOf(arg0.codePoints, arg1.codePoints, 0) >= 0).uncheckedCast()

    // The solver string case-conversion extensions transform ASCII letters only.
    @JvmStatic
    fun stringToLowerCase(arg: KStringLiteralExpr): KStringLiteralExpr =
        arg.ctx.mkStringLiteral(arg.codePoints.map {
            if (it in 'A'.code..'Z'.code) it + ('a' - 'A') else it
        }.toIntArray())

    @JvmStatic
    fun stringToUpperCase(arg: KStringLiteralExpr): KStringLiteralExpr =
        arg.ctx.mkStringLiteral(arg.codePoints.map {
            if (it in 'a'.code..'z'.code) it - ('a' - 'A') else it
        }.toIntArray())

    @JvmStatic
    fun stringReverse(arg: KStringLiteralExpr): KStringLiteralExpr =
        arg.ctx.mkStringLiteral(arg.codePoints.reversedArray())

    @JvmStatic
    fun stringIsDigit(arg: KStringLiteralExpr): KInterpretedValue<KBoolSort> {
        val codes = arg.codePoints
        return arg.ctx.mkBool(codes.size == 1 && codes[0] in '0'.code..'9'.code).uncheckedCast()
    }

    @JvmStatic
    fun stringToCode(arg: KStringLiteralExpr): KIntNumExpr = arg.ctx.mkIntNum(arg.codePoints.singleOrNull() ?: -1)

    @JvmStatic
    fun stringToInt(arg: KStringLiteralExpr): KIntNumExpr {
        val codes = arg.codePoints
        val value = if (codes.isNotEmpty() && codes.all { it in '0'.code..'9'.code }) {
            BigInteger(buildString { codes.forEach { append(it.toChar()) } })
        } else {
            BigInteger.valueOf(-1)
        }
        return arg.ctx.mkIntNum(value)
    }

    @JvmStatic
    fun strintReplace(
        arg0: KStringLiteralExpr,
        arg1: KStringLiteralExpr,
        arg2: KStringLiteralExpr
    ): KStringLiteralExpr {
        val str = arg0.codePoints
        val search = arg1.codePoints
        val replacement = arg2.codePoints
        val index = indexOf(str, search, 0)
        val result = if (index < 0) str else {
            str.copyOfRange(0, index) + replacement + str.copyOfRange(index + search.size, str.size)
        }
        return arg0.ctx.mkStringLiteral(result)
    }

    @JvmStatic
    fun strintReplaceAll(
        arg0: KStringLiteralExpr,
        arg1: KStringLiteralExpr,
        arg2: KStringLiteralExpr
    ): KStringLiteralExpr {
        val str = arg0.codePoints
        val search = arg1.codePoints
        if (search.isEmpty()) return arg0
        val replacement = arg2.codePoints
        val result = ArrayList<Int>()
        var position = 0
        while (position < str.size) {
            if (matchesAt(str, search, position)) {
                replacement.forEach { result.add(it) }
                position += search.size
            } else {
                result.add(str[position++])
            }
        }
        return arg0.ctx.mkStringLiteral(result.toIntArray())
    }

    /** Search in SMT-LIB character positions, without UTF-16 surrogate pairing. */
    @JvmStatic
    fun indexOf(value: IntArray, pattern: IntArray, start: Int): Int {
        if (start < 0 || start > value.size) return -1
        for (position in start..(value.size - pattern.size)) {
            if (matchesAt(value, pattern, position)) return position
        }
        return -1
    }

    private fun matchesAt(value: IntArray, pattern: IntArray, start: Int): Boolean =
        start >= 0 && start <= value.size - pattern.size &&
            pattern.indices.all { value[start + it] == pattern[it] }

    private fun compare(lhs: IntArray, rhs: IntArray): Int {
        for (index in 0 until minOf(lhs.size, rhs.size)) {
            val comparison = lhs[index].compareTo(rhs[index])
            if (comparison != 0) return comparison
        }
        return lhs.size.compareTo(rhs.size)
    }

    @JvmStatic
    fun KContext.sequentialEngAlphabetChars(): List<KStringLiteralExpr> =
        (('A'..'Z') + ('a'..'z')).map { mkStringLiteral(it.toString()) }
}

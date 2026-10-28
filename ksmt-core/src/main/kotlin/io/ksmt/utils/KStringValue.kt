package io.ksmt.utils

/** An immutable SMT-LIB string, whose alphabet includes surrogate character codes. */
internal class KStringValue(codePoints: IntArray) {
    private val contents = codePoints.copyOf().also { copy ->
        require(copy.all { it in 0..StringUtils.STRING_FROM_CODE_UPPER_BOUND }) {
            "String character codes must be in 0..0x2ffff"
        }
    }

    fun copyCodePoints(): IntArray = contents.copyOf()

    val size: Int get() = contents.size

    fun toJavaString(): String {
        val result = buildString { contents.forEach { appendCodePoint(it) } }
        check(result.codePoints().toArray().contentEquals(contents)) {
            "This SMT-LIB string cannot be represented losslessly as UTF-16; use codePoints"
        }
        return result
    }

    fun toSmtLibContent(): String = buildString {
        contents.forEach { code ->
            when {
                code == '"'.code -> append("\"\"")
                code in FIRST_PRINTABLE_ASCII..LAST_PRINTABLE_ASCII && code != '\\'.code -> append(code.toChar())
                else -> append("\\u{").append(code.toString(HEX_RADIX)).append('}')
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KStringValue && contents.contentEquals(other.contents)

    override fun hashCode(): Int = contents.contentHashCode()

    companion object {
        private const val FIRST_PRINTABLE_ASCII = 0x20
        private const val LAST_PRINTABLE_ASCII = 0x7e
        private const val HEX_RADIX = 16
    }
}

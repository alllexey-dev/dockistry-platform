package io.dockistry.platform.shared.domain

/**
 * Checks for text that can't be stored or passed on as-is: PostgreSQL text/jsonb and
 * execve(2) can't represent NUL, and an unpaired surrogate can't be encoded to UTF-8
 * (String.toByteArray silently replaces it with '?').
 */
object StorableText {
    fun findViolation(value: String): String? {
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint == 0) {
                return "must not contain NUL characters"
            }
            if (Character.getType(codePoint) == Character.SURROGATE.toInt()) {
                return "must be valid Unicode text"
            }
            index += Character.charCount(codePoint)
        }
        return null
    }

    /** UTF-8 length of a string that passed [findViolation]. */
    fun utf8Length(value: String): Long {
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            bytes += when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            index += Character.charCount(codePoint)
        }
        return bytes
    }
}

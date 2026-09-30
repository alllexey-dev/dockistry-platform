package io.dockistry.platform.shared.domain

import java.text.Normalizer

object ResourceNameNormalizer {
    const val MAX_LENGTH = 255

    fun normalizeAndValidate(
        name: String,
        invalidName: (String) -> RuntimeException
    ): String {
        val normalized = normalize(name)

        if (normalized.isBlank()) {
            throw invalidName("Resource name must not be blank")
        }

        if (normalized.length > MAX_LENGTH) {
            throw invalidName("Resource name must be at most $MAX_LENGTH characters")
        }

        if (normalized.firstForbiddenCodePoint() != null) {
            throw invalidName(
                "Resource name contains unsupported invisible or control characters"
            )
        }

        return normalized
    }

    fun normalize(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        val compacted = compactWhitespace(normalized)
        return compacted
    }

    private fun compactWhitespace(value: String): String {
        val result = StringBuilder()
        var pendingWhitespace = false
        var index = 0

        while (index < value.length) {
            val codePoint = value.codePointAt(index)

            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingWhitespace = result.isNotEmpty()
            } else {
                if (pendingWhitespace) {
                    result.append(' ')
                    pendingWhitespace = false
                }
                result.appendCodePoint(codePoint)
            }

            index += Character.charCount(codePoint)
        }

        return result.toString()
    }

    private fun String.firstForbiddenCodePoint(): Int? {
        var index = 0

        while (index < length) {
            val codePoint = codePointAt(index)
            val type = Character.getType(codePoint)

            if (
                type == Character.CONTROL.toInt() ||
                type == Character.FORMAT.toInt() ||
                type == Character.SURROGATE.toInt() ||
                type == Character.PRIVATE_USE.toInt() ||
                type == Character.UNASSIGNED.toInt()
            ) {
                return codePoint
            }

            index += Character.charCount(codePoint)
        }

        return null
    }
}

package io.dockistry.platform.projects.domain

import java.text.Normalizer

object ProjectNameNormalizer {
    const val MAX_LENGTH = 255

    fun normalizeAndValidate(name: String): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        val compacted = compactWhitespace(normalized)

        if (compacted.isBlank()) {
            throw InvalidProjectNameException("Project name must not be blank")
        }

        if (compacted.length > MAX_LENGTH) {
            throw InvalidProjectNameException("Project name must be at most $MAX_LENGTH characters")
        }

        val forbiddenCodePoint = compacted.firstForbiddenCodePoint()
        if (forbiddenCodePoint != null) {
            throw InvalidProjectNameException(
                "Project name contains unsupported invisible or control characters"
            )
        }

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

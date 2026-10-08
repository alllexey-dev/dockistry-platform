package io.dockistry.platform.shared.domain

import java.text.Normalizer

object ResourceDescriptionNormalizer {
    const val MAX_LENGTH = 1000

    /** Keeps inner line breaks, unlike [ResourceNameNormalizer]; blank becomes null. */
    fun normalizeAndValidate(
        description: String?,
        invalidDescription: (String) -> RuntimeException
    ): String? {
        if (description == null) {
            return null
        }

        StorableText.findViolation(description)?.let {
            throw invalidDescription("Description $it")
        }

        val normalized = Normalizer.normalize(description, Normalizer.Form.NFC).trim()
        if (normalized.isEmpty()) {
            return null
        }

        if (normalized.length > MAX_LENGTH) {
            throw invalidDescription("Description must be at most $MAX_LENGTH characters")
        }

        return normalized
    }
}

package io.dockistry.platform.secrets.domain

import io.dockistry.platform.shared.domain.StorableText

object SecretValidator {
    const val MAX_KEY_LENGTH = 255
    const val MAX_ENV_NAME_LENGTH = 255
    const val MAX_VALUES_COUNT = 100
    const val MAX_VALUE_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 256 * 1024

    private val keyRegex = Regex("^[A-Za-z0-9_.-]+$")
    private val envNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validateId(value: String): String = SecretIdGenerator.validate(value)

    fun validateKey(value: String): String {
        keyViolation(value)?.let { throw InvalidSecretReferenceFormatException(it) }
        return value
    }

    /**
     * Keys of a new Secret version must be referenceable by [validateKey]. A value must
     * fit into a single container env string: Linux limits "NAME=value" to 128 KiB
     * (MAX_ARG_STRLEN), so values are capped well below that in UTF-8 bytes.
     */
    fun validateValues(values: Map<String, String>): Map<String, String> {
        if (values.isEmpty()) {
            throw EmptySecretValuesException()
        }
        if (values.size > MAX_VALUES_COUNT) {
            throw InvalidSecretValuesException(
                "Secret version must contain at most $MAX_VALUES_COUNT values"
            )
        }

        var totalBytes = 0L
        values.forEach { (key, value) ->
            keyViolation(key)?.let { throw InvalidSecretValuesException(it) }
            StorableText.findViolation(value)?.let {
                throw InvalidSecretValuesException("Secret value for key '$key' $it")
            }

            val valueBytes = StorableText.utf8Length(value)
            if (valueBytes > MAX_VALUE_BYTES) {
                throw InvalidSecretValuesException(
                    "Secret value for key '$key' must be at most $MAX_VALUE_BYTES bytes in UTF-8"
                )
            }
            totalBytes += key.length + valueBytes
        }

        if (totalBytes > MAX_PAYLOAD_BYTES) {
            throw InvalidSecretValuesException(
                "Secret version keys and values must be at most $MAX_PAYLOAD_BYTES bytes in UTF-8"
            )
        }
        return values
    }

    private fun keyViolation(value: String): String? = when {
        value.length > MAX_KEY_LENGTH ->
            "Secret key must be at most $MAX_KEY_LENGTH characters"
        !value.matches(keyRegex) ->
            "Secret key must contain only Latin letters, digits, underscores, dots or hyphens"
        else -> null
    }

    fun validateEnvName(value: String): String {
        if (value.length > MAX_ENV_NAME_LENGTH) {
            throw InvalidSecretReferenceFormatException(
                "Secret environment name must be at most $MAX_ENV_NAME_LENGTH characters"
            )
        }
        if (!value.matches(envNameRegex)) {
            throw InvalidSecretReferenceFormatException(
                "Secret environment name must start with a Latin letter or underscore " +
                    "and contain only Latin letters, digits or underscores"
            )
        }
        return value
    }

    fun validateVersion(value: String): String = SecretVersionIdGenerator.validate(value)

    fun validateOptionalVersion(value: String?): String? = value?.let(::validateVersion)
}

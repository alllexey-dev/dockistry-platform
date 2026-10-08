package io.dockistry.platform.secrets.domain

object SecretValidator {
    const val MAX_KEY_LENGTH = 255
    const val MAX_ENV_NAME_LENGTH = 255

    private val keyRegex = Regex("^[A-Za-z0-9_.-]+$")
    private val envNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validateId(value: String): String = SecretIdGenerator.validate(value)

    fun validateKey(value: String): String {
        keyViolation(value)?.let { throw InvalidSecretReferenceFormatException(it) }
        return value
    }

    /** Keys of a new Secret version must be referenceable by [validateKey]. */
    fun validateValues(values: Map<String, String>): Map<String, String> {
        if (values.isEmpty()) {
            throw EmptySecretValuesException()
        }
        values.keys.forEach { key ->
            keyViolation(key)?.let { throw InvalidSecretValuesException(it) }
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

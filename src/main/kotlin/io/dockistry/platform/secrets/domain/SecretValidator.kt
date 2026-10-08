package io.dockistry.platform.secrets.domain

object SecretValidator {
    private val keyRegex = Regex("^[A-Za-z0-9_.-]+$")
    private val envNameRegex = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun validateId(value: String): String = SecretIdGenerator.validate(value)

    fun validateKey(value: String): String {
        if (!value.matches(keyRegex)) {
            throw InvalidSecretReferenceFormatException(
                "Secret key must contain only Latin letters, digits, underscores, dots or hyphens"
            )
        }
        return value
    }

    fun validateEnvName(value: String): String {
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

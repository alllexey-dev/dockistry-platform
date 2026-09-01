package io.dockistry.platform.secrets.domain

import java.security.SecureRandom

/** Generates opaque, globally unique identifiers for immutable Secret versions. */
object SecretVersionIdGenerator {
    private const val PREFIX = "ver-"
    private const val RANDOM_LENGTH = 12
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    fun generate(): String = buildString(PREFIX.length + RANDOM_LENGTH) {
        append(PREFIX)
        repeat(RANDOM_LENGTH) {
            append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
    }

    fun isValid(value: String): Boolean =
        value.length == PREFIX.length + RANDOM_LENGTH &&
            value.startsWith(PREFIX) &&
            value.drop(PREFIX.length).all { it in ALPHABET }

    fun validate(value: String): String {
        if (!isValid(value)) {
            throw InvalidSecretVersionException("Secret version has an invalid format")
        }
        return value
    }
}

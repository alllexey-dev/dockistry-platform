package io.dockistry.platform.secrets.domain

import java.security.SecureRandom

object SecretIdGenerator {
    private const val PREFIX = "sec-"
    private const val RANDOM_LENGTH = 12
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    fun isValid(value: String): Boolean =
        value.length == PREFIX.length + RANDOM_LENGTH &&
            value.startsWith(PREFIX) &&
            value.drop(PREFIX.length).all { it in ALPHABET }

    fun validate(value: String): String {
        if (!isValid(value)) {
            throw InvalidSecretReferenceException("Secret ID '$value' has an invalid format")
        }
        return value
    }

    fun generate(): String = buildString(PREFIX.length + RANDOM_LENGTH) {
        append(PREFIX)
        repeat(RANDOM_LENGTH) {
            append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
    }
}

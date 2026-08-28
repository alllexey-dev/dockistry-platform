package io.dockistry.platform.secrets.domain

import java.security.SecureRandom

object SecretIdGenerator {
    private const val PREFIX = "sec-"
    private const val RANDOM_LENGTH = 12
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    fun generate(): String = buildString(PREFIX.length + RANDOM_LENGTH) {
        append(PREFIX)
        repeat(RANDOM_LENGTH) {
            append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
    }
}

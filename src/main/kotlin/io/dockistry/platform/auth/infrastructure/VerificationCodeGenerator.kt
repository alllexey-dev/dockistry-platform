package io.dockistry.platform.auth.infrastructure

import java.security.SecureRandom
import java.util.Base64

object VerificationCodeGenerator {
    private const val LINK_CODE_BYTES_LENGTH = 32
    private const val NUMBER_CODE_DIGITS = 6
    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateLinkCode(): String {
        val bytes = ByteArray(LINK_CODE_BYTES_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    fun generateCode(): String {
        return secureRandom.nextInt(1_000_000)
            .toString().padStart(NUMBER_CODE_DIGITS, '0')
    }
}

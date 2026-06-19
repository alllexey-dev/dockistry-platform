package io.dockistry.platform.auth.infrastructure

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class VerificationCodeHasher(
    private val emailVerificationProperties: EmailVerificationProperties
) {
    private val key = SecretKeySpec(
        Base64.getDecoder().decode(emailVerificationProperties.hashSecretBase64),
        "HmacSHA256"
    )

    fun hash(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)

        val digest = mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))

        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }

    fun matches(rawValue: String, storedHash: String): Boolean {
        return MessageDigest.isEqual(
            hash(rawValue).toByteArray(StandardCharsets.UTF_8),
            storedHash.toByteArray(StandardCharsets.UTF_8)
        )
    }

}
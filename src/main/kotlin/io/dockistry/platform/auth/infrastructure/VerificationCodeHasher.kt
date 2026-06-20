package io.dockistry.platform.auth.infrastructure

import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

@Component
class VerificationCodeHasher {
    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

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

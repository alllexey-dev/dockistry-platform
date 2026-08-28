package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import org.springframework.stereotype.Component

@Component
class DummySecretEncryptor : SecretEncryptor {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(encryptedPayload: String): String = encryptedPayload
}

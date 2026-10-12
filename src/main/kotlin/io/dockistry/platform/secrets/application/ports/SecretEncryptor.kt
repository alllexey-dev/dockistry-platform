package io.dockistry.platform.secrets.application.ports

import io.dockistry.platform.secrets.domain.SecretValueContext

interface SecretEncryptor {
    fun encrypt(plaintext: String, context: SecretValueContext): String

    fun decrypt(ciphertext: String, context: SecretValueContext): String
}

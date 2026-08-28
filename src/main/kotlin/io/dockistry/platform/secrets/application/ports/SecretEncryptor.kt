package io.dockistry.platform.secrets.application.ports

interface SecretEncryptor {
    fun encrypt(plaintext: String): String

    fun decrypt(encryptedPayload: String): String
}

package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.domain.SecretDecryptionFailedException
import io.dockistry.platform.secrets.domain.SecretValueContext
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AesGcmSecretEncryptorTests {
    private val context = SecretValueContext("sec-abcdefghijkl", "ver-abcdefghijk1", "password")
    private val encryptor = encryptor(TEST_KEY)

    @Test
    fun `encrypt round-trips unicode value and never stores plaintext`() {
        val plaintext = "pässwörd-🔐"

        val ciphertext = encryptor.encrypt(plaintext, context)

        assertFalse(ciphertext.contains(plaintext))
        assertEquals(plaintext, encryptor.decrypt(ciphertext, context))
    }

    @Test
    fun `same plaintext encrypts differently on every call`() {
        assertNotEquals(encryptor.encrypt("value", context), encryptor.encrypt("value", context))
    }

    @Test
    fun `ciphertext does not decrypt in another secret, version or key`() {
        val ciphertext = encryptor.encrypt("value", context)

        listOf(
            context.copy(secretId = "sec-mnopqrstuvwx"),
            context.copy(versionId = "ver-abcdefghijk2"),
            context.copy(key = "username")
        ).forEach { foreignContext ->
            assertFailsWith<SecretDecryptionFailedException> {
                encryptor.decrypt(ciphertext, foreignContext)
            }
        }
    }

    @Test
    fun `tampered, malformed or foreign-key ciphertext fails with typed error`() {
        val ciphertext = encryptor.encrypt("value", context)
        val bytes = Base64.getDecoder().decode(ciphertext.removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        val tampered = "v1:" + Base64.getEncoder().encodeToString(bytes)
        val otherKeyEncryptor = encryptor(Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))

        listOf(tampered, "value", "v1:not-base64!", "v1:AAAA").forEach {
            assertFailsWith<SecretDecryptionFailedException> { encryptor.decrypt(it, context) }
        }
        assertFailsWith<SecretDecryptionFailedException> {
            otherKeyEncryptor.decrypt(ciphertext, context)
        }
    }

    @Test
    fun `rejects keys that are not 256-bit base64`() {
        listOf("", "not-base64!", Base64.getEncoder().encodeToString(ByteArray(16))).forEach {
            assertFailsWith<IllegalStateException> { encryptor(it) }
        }
    }

    @Test
    fun `prod profile rejects the development key`() {
        assertFailsWith<IllegalStateException> {
            encryptor(AesGcmSecretEncryptor.DEVELOPMENT_KEY, "prod")
        }
        encryptor(AesGcmSecretEncryptor.DEVELOPMENT_KEY)
        encryptor(TEST_KEY, "prod")
    }

    private fun encryptor(key: String, vararg profiles: String) = AesGcmSecretEncryptor(
        SecretEncryptionProperties().apply { this.key = key },
        MockEnvironment().apply { setActiveProfiles(*profiles) }
    )

    private companion object {
        val TEST_KEY: String = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    }
}

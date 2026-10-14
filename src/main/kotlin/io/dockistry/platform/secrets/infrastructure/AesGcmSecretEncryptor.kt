package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.domain.SecretDecryptionFailedException
import io.dockistry.platform.secrets.domain.SecretValueContext
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM with a random 96-bit nonce per value. Stored format:
 * `v1:` + base64(nonce || ciphertext || 128-bit tag).
 *
 * [SecretValueContext] is bound as associated data, so a ciphertext only decrypts in
 * the exact Secret/version/key it was written for. Without it, anyone able to write to
 * the database could copy another owner's ciphertext into their own Secret and read the
 * plaintext from the env of a container they deploy.
 */
@Component
class AesGcmSecretEncryptor(
    properties: SecretEncryptionProperties,
    environment: Environment
) : SecretEncryptor {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val keyBytes = try {
            Base64.getDecoder().decode(properties.key)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("app.secrets.encryption.key must be valid base64")
        }
        check(keyBytes.size == KEY_SIZE_BYTES) {
            "app.secrets.encryption.key must be a base64-encoded 256-bit key"
        }
        check(
            properties.key != DEVELOPMENT_KEY ||
                !environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))
        ) {
            "app.secrets.encryption.key must be overridden when the prod profile is active"
        }
        key = SecretKeySpec(keyBytes, "AES")
    }

    override fun encrypt(plaintext: String, context: SecretValueContext): String {
        val nonce = ByteArray(NONCE_SIZE_BYTES).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return FORMAT_PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    override fun decrypt(ciphertext: String, context: SecretValueContext): String {
        if (!ciphertext.startsWith(FORMAT_PREFIX)) {
            throw SecretDecryptionFailedException()
        }
        return try {
            val bytes = Base64.getDecoder().decode(ciphertext.removePrefix(FORMAT_PREFIX))
            if (bytes.size < NONCE_SIZE_BYTES + TAG_SIZE_BITS / 8) {
                throw SecretDecryptionFailedException()
            }
            val nonce = bytes.copyOfRange(0, NONCE_SIZE_BYTES)
            val cipher = cipher(Cipher.DECRYPT_MODE, nonce, context)
            val plaintext = cipher.doFinal(bytes, NONCE_SIZE_BYTES, bytes.size - NONCE_SIZE_BYTES)
            String(plaintext, Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Wrong key, tampered ciphertext or ciphertext moved from another cell.
            throw SecretDecryptionFailedException()
        } catch (_: IllegalArgumentException) {
            throw SecretDecryptionFailedException()
        }
    }

    // Cipher is stateful and not thread-safe, so every call gets its own instance.
    private fun cipher(mode: Int, nonce: ByteArray, context: SecretValueContext): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
            updateAAD(associatedData(context))
        }

    // Secret/version IDs have fixed formats and keys cannot contain NUL, so the
    // NUL-separated encoding is unambiguous.
    private fun associatedData(context: SecretValueContext): ByteArray =
        listOf(AAD_DOMAIN, context.secretId, context.versionId, context.key)
            .joinToString("\u0000")
            .toByteArray(Charsets.UTF_8)

    companion object {
        /** Default from application.properties; rejected under the prod profile. */
        const val DEVELOPMENT_KEY = "ZG9ja2lzdHJ5LWRldi1vbmx5LWtleS1ub3QtcHJvZCE="

        private const val PRODUCTION_PROFILE = "prod"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_PREFIX = "v1:"
        private const val AAD_DOMAIN = "dockistry-secret-value-v1"
        private const val KEY_SIZE_BYTES = 32
        private const val NONCE_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
    }
}

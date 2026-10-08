package io.dockistry.platform.secrets.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretValidatorValuesTests {
    @Test
    fun `accepts values that are valid environment variable content`() {
        val values = mapOf(
            "empty" to "",
            "pem" to "-----BEGIN KEY-----\nabc=\n-----END KEY-----\n",
            "special" to "a=b \"quoted\" \$HOME `cmd` \t",
            "unicode" to "пароль 😀"
        )

        assertEquals(values, SecretValidator.validateValues(values))
    }

    @Test
    fun `rejects NUL and unpaired surrogates in values`() {
        listOf("a\u0000b", "\ud800", "a\udc00", "\udc00\ud800").forEach { invalid ->
            val error = assertFailsWith<InvalidSecretValuesException> {
                SecretValidator.validateValues(mapOf("password" to invalid))
            }
            assertEquals("invalid_secret_values", error.code)
        }
    }

    @Test
    fun `limits a single value in UTF-8 bytes rather than characters`() {
        val maxValue = "я".repeat(SecretValidator.MAX_VALUE_BYTES / 2)

        SecretValidator.validateValues(mapOf("password" to maxValue))
        assertFailsWith<InvalidSecretValuesException> {
            SecretValidator.validateValues(mapOf("password" to maxValue + "a"))
        }
    }

    @Test
    fun `limits number of values`() {
        val maxValues = (1..SecretValidator.MAX_VALUES_COUNT).associate { "key$it" to "v" }

        SecretValidator.validateValues(maxValues)
        assertFailsWith<InvalidSecretValuesException> {
            SecretValidator.validateValues(maxValues + ("extra" to "v"))
        }
    }

    @Test
    fun `limits total size of keys and values`() {
        // Each value alone is within the 64 KiB per-value limit, so only the total
        // (256 KiB = 4 full values) can fail. Keys count towards the total too.
        val fullValue = "a".repeat(SecretValidator.MAX_VALUE_BYTES)
        // Four 1-byte keys add 4 bytes to the total.
        val keys = listOf("a", "b", "c", "d")
        // 3 full values + (full value - 4 bytes) + 4 key bytes == MAX_PAYLOAD_BYTES exactly.
        val atLimit = mapOf(
            "a" to fullValue,
            "b" to fullValue,
            "c" to fullValue,
            "d" to fullValue.drop(keys.size)
        )

        SecretValidator.validateValues(atLimit)
        // Same payload with "d" one byte longer: MAX_PAYLOAD_BYTES + 1.
        assertFailsWith<InvalidSecretValuesException> {
            SecretValidator.validateValues(atLimit + ("d" to fullValue.drop(keys.size - 1)))
        }
    }
}

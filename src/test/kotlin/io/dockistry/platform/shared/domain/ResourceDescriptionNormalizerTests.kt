package io.dockistry.platform.shared.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResourceDescriptionNormalizerTests {
    private class InvalidDescription(message: String) : RuntimeException(message)

    private fun normalize(value: String?) =
        ResourceDescriptionNormalizer.normalizeAndValidate(value, ::InvalidDescription)

    @Test
    fun `trims and normalizes unicode but keeps inner line breaks`() {
        assertEquals("Café\n\nsecond line", normalize("  Café\n\nsecond line \n"))
    }

    @Test
    fun `blank description becomes null`() {
        assertNull(normalize(null))
        assertNull(normalize(" \n\t "))
    }

    @Test
    fun `accepts max length and rejects longer description`() {
        val max = "a".repeat(ResourceDescriptionNormalizer.MAX_LENGTH)

        assertEquals(max, normalize(max))
        assertFailsWith<InvalidDescription> { normalize(max + "a") }
    }

    @Test
    fun `rejects NUL and unpaired surrogates`() {
        listOf("a\u0000b", "\ud800", "\udc00", "\udc00\ud800").forEach { invalid ->
            assertFailsWith<InvalidDescription> { normalize(invalid) }
        }
    }
}

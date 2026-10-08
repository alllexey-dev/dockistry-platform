package io.dockistry.platform.shared.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StorableTextTests {
    @Test
    fun `accepts well-formed text including control characters and surrogate pairs`() {
        assertNull(StorableText.findViolation("line1\nline2\t=\"\$x\" я 😀"))
        assertNull(StorableText.findViolation(""))
    }

    @Test
    fun `rejects NUL and unpaired or reversed surrogates`() {
        listOf("a\u0000b", "\ud800", "a\udc00", "\udc00\ud800").forEach { invalid ->
            assertNotNull(StorableText.findViolation(invalid), invalid)
        }
    }

    @Test
    fun `utf8 length counts encoded bytes`() {
        val samples = listOf("", "abc", "я", "€", "😀", "a я € 😀\n")

        samples.forEach { sample ->
            assertEquals(
                sample.toByteArray(Charsets.UTF_8).size.toLong(),
                StorableText.utf8Length(sample),
                sample
            )
        }
        assertEquals(4, StorableText.utf8Length("😀"))
    }
}

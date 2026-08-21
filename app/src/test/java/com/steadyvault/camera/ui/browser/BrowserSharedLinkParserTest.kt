package com.steadyvault.camera.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserSharedLinkParserTest {
    @Test
    fun extractsPlainUrl() {
        assertEquals("https://example.com/video", BrowserSharedLinkParser.firstHttpUrl("https://example.com/video"))
    }

    @Test
    fun extractsUrlFromSharedMessage() {
        assertEquals(
            "https://example.com/watch?v=123",
            BrowserSharedLinkParser.firstHttpUrl("Veja este vídeo: https://example.com/watch?v=123")
        )
    }

    @Test
    fun removesOnlyTrailingSentencePunctuation() {
        assertEquals("https://example.com/media", BrowserSharedLinkParser.firstHttpUrl("Link (https://example.com/media)."))
    }

    @Test
    fun rejectsTextWithoutHttpUrl() {
        assertNull(BrowserSharedLinkParser.firstHttpUrl("Nenhum link compartilhado"))
    }
}

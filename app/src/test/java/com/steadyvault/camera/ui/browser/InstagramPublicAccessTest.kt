package com.steadyvault.camera.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramPublicAccessTest {
    @Test
    fun normalizeRemovesTrackingWithoutHardcodingAMediaLink() {
        val normalized = InstagramPublicAccess.normalize(
            "https://instagram.com/reel/ExampleCode/?igsh=tracking-value"
        )

        assertEquals("https://www.instagram.com/reel/ExampleCode/", normalized)
    }

    @Test
    fun publicPagesTryAnonymousEmbedsBeforeTheRegularPage() {
        val pages = InstagramPublicAccess.publicPageUrls(
            "https://www.instagram.com/reel/ExampleCode/"
        )

        assertEquals("https://www.instagram.com/reel/ExampleCode/embed/", pages[0])
        assertEquals("https://www.instagram.com/p/ExampleCode/embed/", pages[1])
        assertEquals("https://www.instagram.com/reel/ExampleCode/", pages[2])
        assertEquals(3, pages.distinct().size)
    }

    @Test
    fun browserCookiesOnlyEnableSessionModeWhenSessionIdExists() {
        assertFalse(InstagramPublicAccess.hasAuthenticatedSession("csrftoken=abc; mid=device"))
        assertFalse(InstagramPublicAccess.hasAuthenticatedSession("sessionid=; csrftoken=abc"))
        assertTrue(InstagramPublicAccess.hasAuthenticatedSession("csrftoken=abc; sessionid=valid-session"))
    }

    @Test
    fun rejectsLookalikeDomains() {
        assertFalse(InstagramPublicAccess.isInstagramUrl("https://instagram.com.example.org/reel/code/"))
        assertTrue(InstagramPublicAccess.isInstagramUrl("https://www.instagram.com/reel/code/"))
    }
}

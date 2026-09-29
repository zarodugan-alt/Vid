package com.example.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMediaHintsTest {

    @Test
    fun `identifies youtube video and shorts pages`() {
        assertTrue(PageMediaHints.isSupportedMediaPage("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(PageMediaHints.isSupportedMediaPage("https://youtube.com/shorts/12345678901"))
        assertTrue(PageMediaHints.isSupportedMediaPage("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects youtube home search and feed pages`() {
        assertFalse(PageMediaHints.isSupportedMediaPage("https://www.youtube.com/"))
        assertFalse(PageMediaHints.isSupportedMediaPage("https://www.youtube.com/results?search_query=cats"))
        assertFalse(PageMediaHints.isSupportedMediaPage("https://www.youtube.com/feed/trending"))
    }

    @Test
    fun `identifies tiktok instagram and twitter status pages`() {
        assertTrue(PageMediaHints.isSupportedMediaPage("https://www.tiktok.com/@user/video/7123456789012345678"))
        assertTrue(PageMediaHints.isSupportedMediaPage("https://www.instagram.com/reel/C123456789/"))
        assertTrue(PageMediaHints.isSupportedMediaPage("https://twitter.com/user/status/1234567890123456789"))
    }

    @Test
    fun `extracts clean source label`() {
        assertEquals("youtube.com", PageMediaHints.sourceLabel("https://www.youtube.com/watch?v=123"))
        assertEquals("tiktok.com", PageMediaHints.sourceLabel("https://m.tiktok.com/v/123"))
    }
}

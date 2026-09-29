package com.example.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewFetcherTest {

    @Test
    fun `extracts youtube id from watch url`() {
        val id = LinkPreviewFetcher.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun `extracts youtube id from shorts url`() {
        val id = LinkPreviewFetcher.youtubeId("https://youtube.com/shorts/abcdef12345?feature=share")
        assertEquals("abcdef12345", id)
    }

    @Test
    fun `extracts youtube id from youtu_be shortlink`() {
        val id = LinkPreviewFetcher.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=42")
        assertEquals("dQw4w9WgXcQ", id)
    }

    @Test
    fun `instant preview constructs hqdefault thumbnail for youtube`() {
        val preview = LinkPreviewFetcher.instant("https://youtu.be/dQw4w9WgXcQ")
        assertNotNull(preview)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", preview?.thumbnailUrl)
    }

    @Test
    fun `instant preview returns null for non-youtube urls`() {
        val preview = LinkPreviewFetcher.instant("https://example.com/video.mp4")
        assertNull(preview)
    }
}

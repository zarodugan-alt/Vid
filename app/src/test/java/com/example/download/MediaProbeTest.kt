package com.example.download

import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProbeTest {

    @Test
    fun `classify identifies direct mp4 video`() {
        val result = MediaProbe.classify(
            url = "https://example.com/videos/nature.mp4",
            contentType = "video/mp4",
            contentLength = 15_000_000L,
            contentDisposition = null
        )
        assertNotNull(result)
        assertEquals("nature.mp4", result?.fileName)
        assertEquals("mp4", result?.extension)
        assertEquals(MediaType.VIDEO, result?.mediaType)
        assertEquals(15_000_000L, result?.contentLength)
        assertEquals(false, result?.isStreamManifest)
    }

    @Test
    fun `classify identifies audio file`() {
        val result = MediaProbe.classify(
            url = "https://example.com/audio/podcast.mp3",
            contentType = "audio/mpeg",
            contentLength = 8_000_000L,
            contentDisposition = null
        )
        assertNotNull(result)
        assertEquals(MediaType.AUDIO, result?.mediaType)
        assertEquals("mp3", result?.extension)
    }

    @Test
    fun `classify identifies document apk and pdf`() {
        val apkResult = MediaProbe.classify(
            url = "https://example.com/downloads/app-release.apk",
            contentType = "application/vnd.android.package-archive",
            contentLength = 25_000_000L,
            contentDisposition = null
        )
        assertNotNull(apkResult)
        assertEquals(MediaType.DOCUMENT, apkResult?.mediaType)
        assertEquals("apk", apkResult?.extension)

        val pdfResult = MediaProbe.classify(
            url = "https://example.com/docs/paper.pdf",
            contentType = "application/pdf",
            contentLength = 2_000_000L,
            contentDisposition = null
        )
        assertNotNull(pdfResult)
        assertEquals(MediaType.DOCUMENT, pdfResult?.mediaType)
        assertEquals("pdf", pdfResult?.extension)
    }

    @Test
    fun `classify rejects html and javascript documents`() {
        val htmlResult = MediaProbe.classify(
            url = "https://example.com/watch?v=123",
            contentType = "text/html; charset=utf-8",
            contentLength = 40_000L,
            contentDisposition = null
        )
        assertNull(htmlResult)

        val jsResult = MediaProbe.classify(
            url = "https://example.com/app.js",
            contentType = "application/javascript",
            contentLength = 100_000L,
            contentDisposition = null
        )
        assertNull(jsResult)
    }

    @Test
    fun `classify identifies hls stream manifest`() {
        val manifestResult = MediaProbe.classify(
            url = "https://example.com/live/master.m3u8",
            contentType = "application/x-mpegURL",
            contentLength = 0L,
            contentDisposition = null
        )
        assertNotNull(manifestResult)
        assertEquals(true, manifestResult?.isStreamManifest)
        assertEquals(MediaType.VIDEO, manifestResult?.mediaType)
    }

    @Test
    fun `looksBlocked catches ad networks and tracking pixels`() {
        assertTrue(MediaProbe.looksBlocked("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(MediaProbe.looksBlocked("https://www.facebook.com/tr?id=123"))
        assertTrue(MediaProbe.looksBlocked("https://example.com/pixel.gif"))
    }
}

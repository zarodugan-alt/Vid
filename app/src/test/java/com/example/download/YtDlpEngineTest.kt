package com.example.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure JVM coverage for the parts of the yt-dlp engine that do not touch the
 * native binaries: error reporting and on-disk progress accounting.
 */
class YtDlpEngineTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `friendly error surfaces the yt-dlp ERROR line`() {
        val stderr = """
            [youtube] Extracting URL: https://youtu.be/dQw4w9WgXcQ
            [youtube] dQw4w9WgXcQ: Downloading webpage
            ERROR: [youtube] dQw4w9WgXcQ: Video unavailable
        """.trimIndent()

        assertEquals(
            "[youtube] dQw4w9WgXcQ: Video unavailable",
            YtDlpEngine.friendlyError(IllegalStateException(stderr))
        )
    }

    @Test
    fun `friendly error falls back to the last meaningful line`() {
        val stderr = "Traceback (most recent call last):\n  ConnectionResetError: [Errno 104]\n\n"

        assertEquals(
            "ConnectionResetError: [Errno 104]",
            YtDlpEngine.friendlyError(IllegalStateException(stderr))
        )
    }

    @Test
    fun `friendly error stays short enough for the UI`() {
        val message = YtDlpEngine.friendlyError(IllegalStateException("ERROR: " + "x".repeat(900)))

        assertTrue(message.length <= 241)
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun `friendly error never returns an empty string`() {
        assertEquals("IllegalStateException", YtDlpEngine.friendlyError(IllegalStateException()))
    }

    @Test
    fun `bytes on disk sums every artifact of a single task`() {
        val directory = temporaryFolder.newFolder("downloads")
        directory.resolve("clip_1700.f137.mp4").writeBytes(ByteArray(1_024))
        directory.resolve("clip_1700.f140.m4a.part").writeBytes(ByteArray(512))
        // Belongs to another task and must not be counted.
        directory.resolve("other_1700.mp4").writeBytes(ByteArray(4_096))

        assertEquals(1_536L, YtDlpEngine.bytesOnDisk(directory, "clip_1700"))
    }

    @Test
    fun `bytes on disk is zero for an unknown task`() {
        val directory = temporaryFolder.newFolder("empty")

        assertEquals(0L, YtDlpEngine.bytesOnDisk(directory, "nothing_here"))
    }
}

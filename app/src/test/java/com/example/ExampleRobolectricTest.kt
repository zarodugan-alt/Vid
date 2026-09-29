package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.download.AppDownloadManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VidDownloader", appName)
    }

    @Test
    fun `test byte formatter`() {
        assertEquals("0 B", AppDownloadManager.formatBytes(0L))
        assertEquals("1.0 KB", AppDownloadManager.formatBytes(1024L))
        assertEquals("1.0 MB", AppDownloadManager.formatBytes(1024L * 1024L))
    }

    @Test
    fun `test duration formatter`() {
        assertEquals("00:00", AppDownloadManager.formatDuration(0L))
        assertEquals("01:15", AppDownloadManager.formatDuration(75L))
        assertEquals("01:01:05", AppDownloadManager.formatDuration(3665L))
    }
}

package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "download_tasks")
data class DownloadTask(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val fileName: String,
    val thumbnailUrl: String? = null,
    val filePath: String? = null,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val mimeType: String = "video/mp4",
    val quality: String = "720p",
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val errorMessage: String? = null,
    val mediaType: MediaType = MediaType.VIDEO,
    /** Which downloader handles this task. */
    val engine: DownloadEngine = DownloadEngine.HTTP,
    /** yt-dlp format selector, only used by [DownloadEngine.YTDLP]. */
    val formatSelector: String? = null,
    /** Page the media came from; yt-dlp re-resolves it at download time so that
     * expiring CDN links never break a queued or paused download. */
    val sourcePageUrl: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val progressPercent: Int
        get() = (progress * 100).toInt()
}

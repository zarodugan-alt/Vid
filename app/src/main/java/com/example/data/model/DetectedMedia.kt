package com.example.data.model

data class DetectedMedia(
    val id: String = java.util.UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val mimeType: String = "video/mp4",
    val quality: String = "720p HD",
    val estimatedBytes: Long = 0L,
    val thumbnailUrl: String? = null,
    val sourcePageUrl: String = "",
    val detectedAt: Long = System.currentTimeMillis()
)

data class ExtractedVideoOption(
    val qualityLabel: String, // e.g. "1080p FHD", "720p HD", "480p SD", "Audio MP3"
    val format: String, // "MP4", "WEBM", "MP3"
    val estimatedBytes: Long,
    /** Direct stream URL for [DownloadEngine.HTTP], or the page URL for yt-dlp. */
    val downloadUrl: String,
    val isAudioOnly: Boolean = false,
    /** Engine that has to run in order to produce this file. */
    val engine: DownloadEngine = DownloadEngine.HTTP,
    /** yt-dlp `-f` selector, e.g. `bestvideo[height<=1080]+bestaudio/best`. */
    val formatSelector: String? = null,
    /** Original page URL handed to yt-dlp. */
    val sourcePageUrl: String? = null,
    /** True when video and audio streams are muxed by ffmpeg after downloading. */
    val requiresMerge: Boolean = false
)

data class VideoInfo(
    val title: String,
    val sourceUrl: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long = 0L,
    val author: String? = null,
    val options: List<ExtractedVideoOption> = emptyList()
)

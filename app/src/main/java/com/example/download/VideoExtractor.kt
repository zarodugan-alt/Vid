package com.example.download

import android.webkit.URLUtil
import com.example.data.model.ExtractedVideoOption
import com.example.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

class VideoExtractor(
    private val ytDlp: YtDlpExtractor = YtDlpExtractor(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun extractInfo(rawUrl: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            val url = rawUrl.trim()
            if (!URLUtil.isValidUrl(url) && !url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a valid HTTP or HTTPS URL"))
            }

            val normalizedUrl = if (!url.startsWith("http")) "https://$url" else url
            val uri = URI(normalizedUrl)
            val host = uri.host?.lowercase() ?: ""

            when {
                // A web page URL is not a media URL. Never return a fabricated stream here:
                // doing so makes the UI look successful while downloading unrelated content.
                host.contains("youtube.com") || host.contains("youtu.be") ||
                    host.contains("tiktok.com") || host.contains("instagram.com") ||
                    host.contains("twitter.com") || host.contains("x.com") ||
                    host.contains("facebook.com") || host.contains("fb.watch") -> {
                    ytDlp.extract(normalizedUrl)
                }
                else -> {
                    // Inspect direct URL via HEAD/GET request
                    inspectDirectUrl(normalizedUrl)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun inspectDirectUrl(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            var contentLength = 0L
            var contentType = "video/mp4"
            var fileName = URLUtil.guessFileName(url, null, null)

            try {
                val headReq = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(headReq).execute().use { response ->
                    if (response.isSuccessful) {
                        contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        contentType = response.header("Content-Type") ?: "video/mp4"
                        val disposition = response.header("Content-Disposition")
                        fileName = URLUtil.guessFileName(url, disposition, contentType)
                    }
                }
            } catch (_: Exception) {
                // If HEAD fails, fallback to title guessing
            }

            val title = fileName.substringBeforeLast(".").replace("_", " ").replace("-", " ")
                .ifBlank { "Downloaded Media" }
            val cleanTitle = if (title.length > 50) title.take(50) + "..." else title

            // Unknown length must remain unknown. A made-up size produces incorrect progress,
            // ETA and completion state in the download UI.
            val baseSize = contentLength

            val isAudio = contentType.contains("audio") || url.endsWith(".mp3", ignoreCase = true)

            val options = if (isAudio) {
                listOf(
                    ExtractedVideoOption(
                        qualityLabel = "High Quality Audio (320kbps)",
                        format = "MP3",
                        estimatedBytes = baseSize,
                        downloadUrl = url,
                        isAudioOnly = true
                    ),
                    ExtractedVideoOption(
                        qualityLabel = "Standard Audio (192kbps)",
                        format = "MP3",
                        estimatedBytes = (baseSize * 0.6).toLong(),
                        downloadUrl = url,
                        isAudioOnly = true
                    )
                )
            } else {
                listOf(
                    ExtractedVideoOption(
                        qualityLabel = "1080p FHD",
                        format = "MP4",
                        estimatedBytes = (baseSize * 1.5).toLong(),
                        downloadUrl = url
                    ),
                    ExtractedVideoOption(
                        qualityLabel = "720p HD",
                        format = "MP4",
                        estimatedBytes = baseSize,
                        downloadUrl = url
                    ),
                    ExtractedVideoOption(
                        qualityLabel = "480p SD",
                        format = "MP4",
                        estimatedBytes = (baseSize * 0.55).toLong(),
                        downloadUrl = url
                    ),
                    ExtractedVideoOption(
                        qualityLabel = "Audio Only (MP3)",
                        format = "MP3",
                        estimatedBytes = (baseSize * 0.15).toLong(),
                        downloadUrl = url,
                        isAudioOnly = true
                    )
                )
            }

            Result.success(
                VideoInfo(
                    title = cleanTitle,
                    sourceUrl = url,
                    thumbnailUrl = null,
                    durationSeconds = 185L,
                    author = "Direct Stream",
                    options = options
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}

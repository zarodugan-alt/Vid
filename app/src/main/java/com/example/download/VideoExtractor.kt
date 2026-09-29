package com.example.download

import android.content.Context
import android.webkit.URLUtil
import com.example.BuildConfig
import com.example.data.model.DownloadEngine
import com.example.data.model.ExtractedVideoOption
import com.example.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Turns a user supplied link into a list of concrete, downloadable options.
 *
 * Routing:
 *  * a URL that points straight at a media file is inspected over HTTP and kept
 *    on the plain HTTP engine (fast, resumable);
 *  * anything else is a web page, so the bundled yt-dlp engine resolves it;
 *  * if the on-device engine cannot run (unsupported ABI, unpack failure) and a
 *    `YTDLP_API_URL` service was configured at build time, that service is used
 *    as a fallback.
 */
class VideoExtractor(
    private val context: Context,
    private val engine: YtDlpEngine = YtDlpEngine,
    private val remote: YtDlpExtractor = YtDlpExtractor(),
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
            if (url.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Please enter a link"))
            }

            val normalizedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }

            if (!URLUtil.isValidUrl(normalizedUrl)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Please enter a valid HTTP or HTTPS URL")
                )
            }

            if (isDirectMediaUrl(normalizedUrl)) {
                val direct = inspectDirectUrl(normalizedUrl)
                if (direct.isSuccess) return@withContext direct
            }

            extractWithYtDlp(normalizedUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** yt-dlp on device first, configured yt-dlp service second. */
    private suspend fun extractWithYtDlp(url: String): Result<VideoInfo> {
        val local = engine.fetchInfo(context, url)
        if (local.isSuccess) return local

        if (BuildConfig.YTDLP_API_URL.isNotBlank()) {
            val fallback = remote.extract(url)
            if (fallback.isSuccess) return fallback
        }

        // Last resort: the link may still be a media file served without a
        // recognisable extension (CDN links, signed URLs, ...).
        val direct = inspectDirectUrl(url)
        if (direct.isSuccess) return direct

        return local
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        return extension in DIRECT_MEDIA_EXTENSIONS
    }

    private suspend fun inspectDirectUrl(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            var contentLength = 0L
            var contentType = ""
            var fileName = URLUtil.guessFileName(url, null, null)

            runCatching {
                val headReq = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(headReq).execute().use { response ->
                    if (response.isSuccessful) {
                        contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                        contentType = response.header("Content-Type").orEmpty()
                        val disposition = response.header("Content-Disposition")
                        fileName = URLUtil.guessFileName(url, disposition, contentType)
                    }
                }
            }

            // An HTML document is a page, not a media file: let yt-dlp handle it.
            if (contentType.contains("text/html", ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalStateException("This link is a web page, not a direct media file")
                )
            }

            val extension = fileName.substringAfterLast('.', "").lowercase().ifBlank {
                when {
                    contentType.contains("audio") -> "mp3"
                    contentType.contains("video") -> "mp4"
                    else -> "bin"
                }
            }
            if (contentType.isBlank() && extension !in DIRECT_MEDIA_EXTENSIONS) {
                return@withContext Result.failure(
                    IllegalStateException("Could not identify any media at this link")
                )
            }

            val title = fileName.substringBeforeLast(".")
                .replace("_", " ")
                .replace("-", " ")
                .trim()
                .ifBlank { "Downloaded media" }
            val cleanTitle = if (title.length > 60) title.take(60) + "…" else title

            val isAudio = contentType.contains("audio") || extension in AUDIO_EXTENSIONS

            // One link is one stream: reporting extra "qualities" here would only
            // download the very same bytes under a different label.
            val option = ExtractedVideoOption(
                qualityLabel = if (isAudio) {
                    "Original audio (${extension.uppercase()})"
                } else {
                    "Original quality (${extension.uppercase()})"
                },
                format = extension.uppercase(),
                estimatedBytes = contentLength,
                downloadUrl = url,
                isAudioOnly = isAudio,
                engine = DownloadEngine.HTTP
            )

            Result.success(
                VideoInfo(
                    title = cleanTitle,
                    sourceUrl = url,
                    thumbnailUrl = null,
                    durationSeconds = 0L,
                    author = "Direct link",
                    options = listOf(option)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")

        private val DIRECT_MEDIA_EXTENSIONS = AUDIO_EXTENSIONS + setOf(
            "mp4", "webm", "mkv", "mov", "avi", "flv", "3gp", "ts", "m4v", "mpg", "mpeg", "wmv"
        )
    }
}

package com.example.download

import com.example.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A URL that was verified to really serve media, with its real metadata. */
data class ProbedMedia(
    val url: String,
    val mimeType: String,
    val contentLength: Long,
    val fileName: String,
    val extension: String,
    val mediaType: MediaType,
    val isStreamManifest: Boolean
)

/**
 * Checks whether a candidate URL sniffed from a web page is an actual media file
 * before it is offered to the user.
 *
 * The browser fires hundreds of requests per page; matching on ".mp4" alone
 * catches thumbnails, tracking pixels, JSON manifests and HTML pages. Every
 * candidate is therefore verified with a real HEAD (or 2-byte ranged GET)
 * request, and only the server's own content type and length are reported.
 */
object MediaProbe {

    /** Below this an "image" is a sprite, icon or tracking pixel. */
    private const val MIN_IMAGE_BYTES = 15_000L

    /** Below this a "video"/"audio" response is a fragment, not a file. */
    private const val MIN_MEDIA_BYTES = 32_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val verified = ConcurrentHashMap<String, ProbedMedia>()
    private val rejected: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val DOCUMENT_EXTENSIONS =
        setOf("pdf", "apk", "zip", "rar", "7z", "tar", "gz", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub")
    private val VIDEO_EXTENSIONS =
        setOf("mp4", "webm", "mkv", "mov", "avi", "flv", "3gp", "m4v", "ts", "mpg", "mpeg", "wmv")
    private val AUDIO_EXTENSIONS =
        setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac")
    private val IMAGE_EXTENSIONS =
        setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

    /** URLs that are never a downloadable file for the user. */
    private val BLOCKED_FRAGMENTS = listOf(
        "googleads", "doubleclick", "/ads/", "adservice", "analytics", "facebook.com/tr",
        "google-analytics", "scorecardresearch", "/pixel", "/beacon", "/tracking",
        "sprite", "favicon", "logo", "avatar", "thumb", "/emoji", "generate_204"
    )

    fun looksBlocked(url: String): Boolean {
        val lower = url.lowercase()
        return BLOCKED_FRAGMENTS.any { lower.contains(it) }
    }

    suspend fun probe(url: String, referer: String? = null): ProbedMedia? = withContext(Dispatchers.IO) {
        verified[url]?.let { return@withContext it }
        if (rejected.contains(url)) return@withContext null

        val result = runCatching { request(url, referer, head = true) }.getOrNull()
            ?: runCatching { request(url, referer, head = false) }.getOrNull()

        if (result == null) rejected.add(url) else verified[url] = result
        result
    }

    private fun request(url: String, referer: String?, head: Boolean): ProbedMedia? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", VideoExtractor.USER_AGENT)
            .header("Accept", "*/*")
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        if (head) builder.head() else builder.header("Range", "bytes=0-1")

        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val contentType = response.header("Content-Type").orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase()
            val contentLength = if (head) {
                response.header("Content-Length")?.toLongOrNull() ?: 0L
            } else {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: 0L
            }
            classify(url, contentType, contentLength, response.header("Content-Disposition"))
        }
    }

    internal fun classify(
        url: String,
        contentType: String,
        contentLength: Long,
        contentDisposition: String?
    ): ProbedMedia? {
        val urlExtension = extensionOf(url)
        val isManifest = urlExtension == "m3u8" || urlExtension == "mpd" ||
            contentType.contains("mpegurl") || contentType.contains("dash+xml")

        if (!isManifest && isDocumentResponse(contentType)) return null

        val mediaType = mediaTypeOf(contentType, urlExtension, isManifest) ?: return null

        // Fragments of an adaptive stream are useless on their own.
        if (!isManifest && contentLength in 1 until minimumSizeFor(mediaType)) return null

        val fileName = fileNameFor(url, contentDisposition, urlExtension, mediaType)
        val extension = fileName.substringAfterLast('.', "").lowercase()
            .ifBlank { defaultExtension(mediaType, isManifest) }

        return ProbedMedia(
            url = url,
            mimeType = contentType.ifBlank { fallbackMime(extension, mediaType) },
            contentLength = contentLength.coerceAtLeast(0L),
            fileName = fileName,
            extension = extension,
            mediaType = mediaType,
            isStreamManifest = isManifest
        )
    }

    private fun isDocumentResponse(contentType: String): Boolean =
        contentType.startsWith("text/") ||
            contentType.contains("html") ||
            contentType.contains("json") ||
            contentType.contains("javascript") ||
            contentType.contains("css") ||
            (contentType.contains("xml") && !contentType.contains("dash"))

    private fun mediaTypeOf(contentType: String, extension: String, isManifest: Boolean): MediaType? = when {
        isManifest -> MediaType.VIDEO
        contentType.startsWith("video/") -> MediaType.VIDEO
        contentType.startsWith("audio/") -> MediaType.AUDIO
        contentType.startsWith("image/") -> MediaType.IMAGE
        contentType == "application/vnd.android.package-archive" -> MediaType.DOCUMENT
        contentType == "application/pdf" -> MediaType.DOCUMENT
        // Generic binary payloads are only accepted with a known file extension.
        extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
        extension in AUDIO_EXTENSIONS -> MediaType.AUDIO
        extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
        extension in DOCUMENT_EXTENSIONS -> MediaType.DOCUMENT
        else -> null
    }

    private fun minimumSizeFor(mediaType: MediaType): Long =
        if (mediaType == MediaType.IMAGE) MIN_IMAGE_BYTES else MIN_MEDIA_BYTES

    /** Pure Kotlin file-name resolution (no Android dependency, so it is testable). */
    internal fun fileNameFor(
        url: String,
        contentDisposition: String?,
        urlExtension: String,
        mediaType: MediaType
    ): String {
        val fromDisposition = contentDisposition
            ?.substringAfter("filename=", "")
            ?.trim()
            ?.trim('"', '\'')
            ?.substringBefore(';')
            ?.takeIf { it.isNotBlank() && !it.contains('/') }

        val fromUrl = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }

        val raw = (fromDisposition ?: fromUrl ?: "media").take(80)
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return if (cleaned.contains('.')) {
            cleaned
        } else {
            "$cleaned.${urlExtension.ifBlank { defaultExtension(mediaType, false) }}"
        }
    }

    private fun defaultExtension(mediaType: MediaType, isManifest: Boolean): String = when {
        isManifest -> "mp4"
        mediaType == MediaType.AUDIO -> "mp3"
        mediaType == MediaType.IMAGE -> "jpg"
        mediaType == MediaType.DOCUMENT -> "bin"
        else -> "mp4"
    }

    private fun fallbackMime(extension: String, mediaType: MediaType): String = when {
        extension == "apk" -> "application/vnd.android.package-archive"
        extension == "pdf" -> "application/pdf"
        mediaType == MediaType.AUDIO -> "audio/mpeg"
        mediaType == MediaType.IMAGE -> "image/jpeg"
        mediaType == MediaType.VIDEO -> "video/mp4"
        else -> "application/octet-stream"
    }

    internal fun extensionOf(url: String): String =
        url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 2..5 && it.all { char -> char.isLetterOrDigit() } }
            .orEmpty()
}

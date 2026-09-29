package com.example.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Title/thumbnail shown while the full extraction is still running. */
data class LinkPreview(
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val author: String? = null
)

/**
 * Cheap metadata for a link.
 *
 * Spawning yt-dlp costs a python start plus several network round trips. oEmbed
 * and Open Graph give a title and a poster in a single request, so the UI can
 * show the video the user pasted within a few hundred milliseconds while the
 * real format list is still being resolved.
 */
object LinkPreviewFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, LinkPreview>()

    private val YOUTUBE_ID = Regex(
        "(?:youtube\\.com/(?:watch\\?(?:[^&]*&)*v=|shorts/|embed/|live/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})"
    )
    private val OG_PROPERTY_FIRST =
        Regex("<meta[^>]+(?:property|name)=[\"']og:([a-z:]+)[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val OG_CONTENT_FIRST =
        Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']og:([a-z:]+)[\"']", RegexOption.IGNORE_CASE)
    private val HTML_TITLE = Regex("<title[^>]*>([^<]{1,200})</title>", RegexOption.IGNORE_CASE)

    fun youtubeId(url: String): String? = YOUTUBE_ID.find(url)?.groupValues?.getOrNull(1)

    /**
     * Zero-latency preview: YouTube thumbnails are addressable by video id, so a
     * poster can be displayed before any request is made at all.
     */
    fun instant(url: String): LinkPreview? {
        val id = youtubeId(url) ?: return null
        return LinkPreview(thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
    }

    suspend fun fetch(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }

        val fetched = runCatching { oEmbed(url) }.getOrNull()
            ?: runCatching { openGraph(url) }.getOrNull()

        val preview = LinkPreview(
            title = fetched?.title,
            thumbnailUrl = fetched?.thumbnailUrl ?: instant(url)?.thumbnailUrl,
            author = fetched?.author
        ).takeIf { it.title != null || it.thumbnailUrl != null }

        preview?.also { cache[url] = it }
    }

    private fun oEmbed(url: String): LinkPreview? {
        val endpoint = oEmbedEndpoint(url) ?: return null
        val request = Request.Builder()
            .url("$endpoint?format=json&url=${URLEncoder.encode(url, "UTF-8")}")
            .header("User-Agent", VideoExtractor.USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use null
            val json = JSONObject(body)
            LinkPreview(
                title = json.optString("title").takeIf { it.isNotBlank() },
                thumbnailUrl = json.optString("thumbnail_url").takeIf { it.isNotBlank() },
                author = json.optString("author_name").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun oEmbedEndpoint(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase().orEmpty()
        return when {
            host.contains("youtube.com") || host.contains("youtu.be") -> "https://www.youtube.com/oembed"
            host.contains("tiktok.com") -> "https://www.tiktok.com/oembed"
            host.contains("vimeo.com") -> "https://vimeo.com/api/oembed.json"
            host.contains("dailymotion.com") -> "https://www.dailymotion.com/services/oembed"
            host.contains("soundcloud.com") -> "https://soundcloud.com/oembed"
            else -> null
        }
    }

    private fun openGraph(url: String): LinkPreview? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VideoExtractor.USER_AGENT)
            // Only the document head is interesting.
            .header("Range", "bytes=0-131071")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val contentType = response.header("Content-Type").orEmpty()
            if (!contentType.contains("html", ignoreCase = true)) return@use null
            val html = response.body?.string()?.take(131_072).orEmpty()
            if (html.isBlank()) return@use null

            val tags = mutableMapOf<String, String>()
            OG_PROPERTY_FIRST.findAll(html).forEach { match ->
                tags.putIfAbsent(match.groupValues[1].lowercase(), match.groupValues[2])
            }
            OG_CONTENT_FIRST.findAll(html).forEach { match ->
                tags.putIfAbsent(match.groupValues[2].lowercase(), match.groupValues[1])
            }

            LinkPreview(
                title = tags["title"] ?: HTML_TITLE.find(html)?.groupValues?.getOrNull(1)?.trim(),
                thumbnailUrl = tags["image"] ?: tags["image:url"],
                author = tags["site_name"]
            ).takeIf { it.title != null || it.thumbnailUrl != null }
        }
    }
}

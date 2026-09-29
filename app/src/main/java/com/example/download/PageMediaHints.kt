package com.example.download

import java.net.URI

/**
 * Recognises web pages whose video is served as adaptive fragments.
 *
 * Sniffing the network traffic of YouTube, TikTok or Instagram only yields
 * short DASH segments (`videoplayback?range=...`) that are useless on their own.
 * For those pages the browser offers a single entry that hands the page URL to
 * yt-dlp, which resolves and muxes the real streams.
 */
object PageMediaHints {

    private val HOSTS = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "tiktok.com",
        "instagram.com",
        "facebook.com", "fb.watch",
        "twitter.com", "x.com",
        "reddit.com",
        "vimeo.com",
        "dailymotion.com", "dai.ly",
        "twitch.tv",
        "soundcloud.com",
        "bilibili.com",
        "ok.ru",
        "rumble.com",
        "pinterest.com", "pin.it",
        "linkedin.com",
        "tumblr.com",
        "bitchute.com",
        "odysee.com"
    )

    /** Paths that are listings, not a single playable item. */
    private val NON_MEDIA_SEGMENTS = setOf(
        "", "feed", "explore", "search", "results", "trending", "settings", "account",
        "login", "signup", "about", "help", "directory", "following", "subscriptions",
        "notifications", "messages", "premium", "shop", "gaming", "playlist", "channel",
        "user", "c", "@me"
    )

    fun hostOf(url: String): String {
        val raw = runCatching { URI(url).host }.getOrNull().orEmpty().lowercase()
        return raw.removePrefix("www.").removePrefix("m.")
    }

    fun isSupportedHost(url: String): Boolean {
        val host = hostOf(url)
        if (host.isBlank()) return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * True when [url] looks like a single video page that yt-dlp can resolve.
     * Home pages, search results and feeds are rejected so the browser never
     * offers a download that cannot exist.
     */
    fun isSupportedMediaPage(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val host = hostOf(url)
        if (!isSupportedHost(url)) return false

        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().trim('/')
        val query = uri.query.orEmpty()

        return when {
            host.endsWith("youtu.be") -> path.length >= 6
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") ->
                (path == "watch" && query.contains("v=")) ||
                    path.startsWith("shorts/") || path.startsWith("live/") ||
                    path.startsWith("embed/") || path.startsWith("clip/")

            host.endsWith("tiktok.com") -> path.contains("/video/") || path.startsWith("t/") ||
                path.startsWith("v/") || path.contains("/photo/")

            host.endsWith("instagram.com") -> path.startsWith("p/") || path.startsWith("reel/") ||
                path.startsWith("reels/") || path.startsWith("tv/") || path.contains("/reel/")

            host.endsWith("facebook.com") -> path.contains("videos/") || path.startsWith("watch") ||
                path.startsWith("reel/") || path.startsWith("share/")

            host.endsWith("fb.watch") -> path.isNotBlank()
            host.endsWith("twitter.com") || host.endsWith("x.com") -> path.contains("/status/")
            host.endsWith("reddit.com") -> path.contains("/comments/")
            host.endsWith("vimeo.com") -> path.firstOrNull()?.isDigit() == true || path.startsWith("video/")
            host.endsWith("twitch.tv") -> path.startsWith("videos/") || path.contains("/clip/")
            else -> {
                val first = path.substringBefore('/').lowercase()
                path.isNotBlank() && first !in NON_MEDIA_SEGMENTS
            }
        }
    }

    /** Short, human readable source label, e.g. `youtube.com`. */
    fun sourceLabel(url: String): String = hostOf(url).ifBlank { "this page" }
}

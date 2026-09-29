package com.example.download

import com.example.BuildConfig
import com.example.data.model.ExtractedVideoOption
import com.example.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Adapter for a yt-dlp JSON service. The service must run yt-dlp with
 * --dump-single-json --no-playlist and return its JSON unchanged. Keeping the
 * binary server-side avoids shipping an untrusted executable in the APK. */
class YtDlpExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
) {
    suspend fun extract(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.YTDLP_API_URL.trimEnd('/')
        if (endpoint.isBlank()) return@withContext Result.failure(
            IllegalStateException("yt-dlp backend is not configured. Set YTDLP_API_URL when building the app.")
        )
        runCatching {
            val body = JSONObject().put("url", url).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(endpoint).post(body)
                .header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("yt-dlp backend returned HTTP ${response.code}")
                parse(response.body?.string() ?: error("Empty yt-dlp response"), url)
            }
        }
    }

    private fun parse(raw: String, source: String): VideoInfo {
        val json = JSONObject(raw)
        val formats = json.optJSONArray("formats") ?: error("yt-dlp returned no formats")
        val options = buildList {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val stream = f.optString("url")
                val protocol = f.optString("protocol")
                if (stream.isBlank() || protocol == "m3u8_native" || protocol == "m3u8") continue
                val video = f.optString("vcodec", "none") != "none"
                val audio = f.optString("acodec", "none") != "none"
                if (!video && !audio) continue
                val ext = f.optString("ext", "mp4").uppercase()
                val height = f.optInt("height", 0)
                add(ExtractedVideoOption(
                    qualityLabel = if (video) "${if (height > 0) "${height}p" else "Video"} ($ext)" else "Audio ($ext)",
                    format = ext, estimatedBytes = f.optLong("filesize", f.optLong("filesize_approx", 0L)),
                    downloadUrl = stream, isAudioOnly = !video
                ))
            }
        }.distinctBy { it.downloadUrl }.take(12)
        if (options.isEmpty()) error("yt-dlp returned no downloadable progressive formats")
        return VideoInfo(json.optString("title", "Downloaded media"), source,
            json.optString("thumbnail").ifBlank { null }, json.optLong("duration", 0L),
            json.optString("uploader", ""), options)
    }
}

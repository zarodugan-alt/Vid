package com.example.download

import android.content.Context
import android.util.Log
import com.example.data.model.DownloadEngine
import com.example.data.model.ExtractedVideoOption
import com.example.data.model.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.yausername.youtubedl_android.mapper.VideoInfo as YtDlpVideoInfo

/**
 * On-device yt-dlp engine.
 *
 * The APK bundles a python runtime, the yt-dlp script and ffmpeg (see
 * `io.github.junkfood02.youtubedl-android`). yt-dlp is what resolves YouTube and
 * friends: it decodes the player response, picks streams, downloads them and asks
 * ffmpeg to mux video + audio. Nothing is hard-coded per site, and no third-party
 * server is involved.
 */
object YtDlpEngine {

    private const val TAG = "YtDlpEngine"

    sealed interface Status {
        /** Engine has not been touched yet. */
        data object Idle : Status

        /** Unpacking python/ffmpeg on first launch. */
        data object Initializing : Status

        /** Ready to extract and download. */
        data class Ready(val version: String?) : Status

        /** Engine cannot run here (unsupported ABI, unpack failure, ...). */
        data class Unavailable(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val initMutex = Mutex()

    val isReady: Boolean get() = _status.value is Status.Ready

    /** yt-dlp writes the decoded player JS here; reusing it saves seconds per call. */
    @Volatile
    private var cacheDirPath: String? = null

    private class CachedInfo(val info: VideoInfo, val createdAt: Long)

    private val infoCache = ConcurrentHashMap<String, CachedInfo>()

    private val selfHealAttempted = AtomicBoolean(false)

    /** Extraction results stay valid for a few minutes, which makes a second
     * analyze of the same link instant. Stream URLs are resolved again at
     * download time anyway, so nothing can expire in between. */
    private const val INFO_TTL_MS = 10 * 60 * 1000L

    /**
     * Unpacks and initialises the engine. Safe to call from anywhere and as often
     * as you like: the work happens once, later callers just await the result.
     */
    suspend fun ensureReady(context: Context): Boolean {
        if (_status.value is Status.Ready) return true
        val appContext = context.applicationContext
        return initMutex.withLock {
            if (_status.value is Status.Ready) return@withLock true
            _status.value = Status.Initializing
            withContext(Dispatchers.IO) {
                try {
                    YoutubeDL.getInstance().init(appContext)
                    cacheDirPath = File(appContext.cacheDir, "ytdlp_cache").apply { mkdirs() }.absolutePath
                    // ffmpeg is optional for progressive streams but required to mux
                    // the separate video/audio tracks that carry anything above 720p.
                    runCatching { FFmpeg.getInstance().init(appContext) }
                        .onFailure { Log.w(TAG, "ffmpeg init failed, merging disabled", it) }
                    _status.value = Status.Ready(runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull())
                    true
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "yt-dlp engine init failed", t)
                    _status.value = Status.Unavailable(
                        t.message?.takeIf { it.isNotBlank() }
                            ?: "The bundled yt-dlp engine could not start on this device"
                    )
                    false
                }
            }
        }
    }

    fun unavailableReason(): String = when (val state = _status.value) {
        is Status.Unavailable -> state.reason
        else -> "The bundled yt-dlp engine is not ready yet"
    }

    /** Runs `yt-dlp --dump-json` and maps the result onto the app's own model. */
    suspend fun fetchInfo(context: Context, url: String): Result<VideoInfo> {
        val cached = infoCache[url]
        if (cached != null && System.currentTimeMillis() - cached.createdAt < INFO_TTL_MS) {
            return Result.success(cached.info)
        }

        if (!ensureReady(context)) {
            return Result.failure(IllegalStateException(unavailableReason()))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = YoutubeDLRequest(url)
                    .addOption("--no-playlist")
                    .addOption("--no-warnings")
                    // Don't verify each format with an extra request; formats are still listed.
                    .addOption("--no-check-formats")
                    .addOption("--socket-timeout", 15)
                    // The Android player client answers in a single request; skipping the
                    // watch page HTML and player configs removes several round trips.
                    .addOption("--extractor-args", "youtube:player_client=android;player_skip=webpage,configs")

                cacheDirPath?.let { request.addOption("--cache-dir", it) }

                val rawInfo = YoutubeDL.getInstance().getInfo(request)
                val mapped = toVideoInfo(rawInfo, url)
                infoCache[url] = CachedInfo(mapped, System.currentTimeMillis())
                mapped
            }.recoverCatching { firstError ->
                // If YouTube extraction failed on an outdated binary, self-update once and retry
                if (url.contains("youtu") && !selfHealAttempted.getAndSet(true)) {
                    Log.i(TAG, "Extraction failed, attempting automatic yt-dlp update...")
                    runCatching { update(context) }
                    val retryReq = YoutubeDLRequest(url)
                        .addOption("--no-playlist")
                        .addOption("--no-warnings")
                        .addOption("--socket-timeout", 25)
                    cacheDirPath?.let { retryReq.addOption("--cache-dir", it) }
                    val retryInfo = YoutubeDL.getInstance().getInfo(retryReq)
                    val mapped = toVideoInfo(retryInfo, url)
                    infoCache[url] = CachedInfo(mapped, System.currentTimeMillis())
                    mapped
                } else {
                    throw IllegalStateException(friendlyError(firstError), firstError)
                }
            }
        }
    }

    /**
     * Downloads [url] with yt-dlp into [outputDir]. The file name is chosen by
     * yt-dlp (the extension depends on the muxed container), so the resulting file
     * is resolved afterwards and returned.
     */
    suspend fun download(
        context: Context,
        processId: String,
        url: String,
        formatSelector: String?,
        audioFormat: String?,
        mergeContainer: String?,
        outputDir: File,
        outputBaseName: String,
        onProgress: (percent: Float, etaSeconds: Long, line: String) -> Unit
    ): Result<File> {
        if (!ensureReady(context)) {
            return Result.failure(IllegalStateException(unavailableReason()))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!outputDir.exists()) outputDir.mkdirs()
                val request = YoutubeDLRequest(url)
                    .addOption("--no-playlist")
                    .addOption("--no-mtime")
                    .addOption("--newline")
                    .addOption("--no-warnings")
                    .addOption("--retries", 5)
                    .addOption("--socket-timeout", 30)
                    .addOption("--continue")
                    .addOption("--extractor-args", "youtube:player_client=android,web")
                    .addOption("-o", File(outputDir, "$outputBaseName.%(ext)s").absolutePath)

                cacheDirPath?.let { request.addOption("--cache-dir", it) }

                if (!formatSelector.isNullOrBlank()) {
                    // Fall back to best if the strict selector fails to match
                    request.addOption("-f", "$formatSelector/best")
                }

                if (!audioFormat.isNullOrBlank()) {
                    request.addOption("-x").addOption("--audio-format", audioFormat)
                } else if (!mergeContainer.isNullOrBlank()) {
                    request.addOption("--merge-output-format", mergeContainer)
                }

                YoutubeDL.getInstance().execute(request, processId, false) { percent, eta, line ->
                    onProgress(percent, eta, line)
                }

                resolveOutput(
                    outputDir = outputDir,
                    baseName = outputBaseName,
                    preferredExtension = audioFormat?.takeIf { it.isNotBlank() }
                        ?: mergeContainer?.takeIf { it.isNotBlank() }
                ) ?: error("yt-dlp finished but produced no output file")
            }.recoverCatching { error ->
                throw IllegalStateException(friendlyError(error), error)
            }
        }
    }

    /** Kills a running yt-dlp process (pause / cancel). */
    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    /** Self-updates the bundled yt-dlp binary from the stable release channel. */
    suspend fun update(context: Context): Result<String> {
        if (!ensureReady(context)) {
            return Result.failure(IllegalStateException(unavailableReason()))
        }
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            runCatching {
                val result = YoutubeDL.getInstance()
                    .updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                val version = runCatching { YoutubeDL.getInstance().version(appContext) }.getOrNull()
                _status.value = Status.Ready(version)
                when (result) {
                    YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to ${version ?: "the latest release"}"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ->
                        "yt-dlp is already up to date${version?.let { " ($it)" } ?: ""}"

                    else -> "yt-dlp update finished"
                }
            }.recoverCatching { throw IllegalStateException(friendlyError(it), it) }
        }
    }

    /**
     * Output files are named `<base>.<ext>`; `.part`/`.ytdl` are work in progress
     * and `<base>.f137.mp4` style names are per-stream leftovers, so the merged
     * `<base>.<ext>` file wins whenever it exists.
     */
    private fun resolveOutput(
        outputDir: File,
        baseName: String,
        preferredExtension: String? = null
    ): File? {
        val candidates = outputDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$baseName.") }
            ?.filterNot { it.name.endsWith(".part") || it.name.endsWith(".ytdl") }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        // The requested container wins (audio extraction may leave the source file
        // behind), then a plain `<base>.<ext>`, then whatever is biggest.
        preferredExtension?.let { extension ->
            candidates.firstOrNull { it.name.equals("$baseName.$extension", ignoreCase = true) }
                ?.let { return it }
        }
        val merged = candidates.filter { !it.name.removePrefix("$baseName.").contains('.') }
        return (merged.takeIf { it.isNotEmpty() } ?: candidates).maxByOrNull { it.length() }
    }

    /** Bytes already on disk for a task, including partial and per-stream files. */
    fun bytesOnDisk(outputDir: File, baseName: String): Long =
        outputDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$baseName.") }
            ?.sumOf { it.length() }
            ?: 0L

    /**
     * yt-dlp failures arrive as a full stderr dump. Surface the actual `ERROR:`
     * line so the UI shows something a human can act on.
     */
    fun friendlyError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty()
        if (raw.isEmpty()) return t::class.java.simpleName
        val errorLine = raw.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("ERROR:") }
            ?.removePrefix("ERROR:")
            ?.trim()
        val message = errorLine?.takeIf { it.isNotBlank() } ?: raw.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() }
            ?: raw
        return if (message.length > 240) message.take(240) + "…" else message
    }

    // ---------------------------------------------------------------------
    // Mapping yt-dlp JSON -> app model
    // ---------------------------------------------------------------------

    private fun toVideoInfo(info: YtDlpVideoInfo, sourceUrl: String): VideoInfo {
        val pageUrl = info.webpageUrl?.takeIf { it.isNotBlank() } ?: sourceUrl
        val duration = info.duration.toLong().coerceAtLeast(0L)
        val formats = info.formats.orEmpty()
        return VideoInfo(
            title = info.title?.takeIf { it.isNotBlank() }
                ?: info.fulltitle?.takeIf { it.isNotBlank() }
                ?: "Downloaded media",
            sourceUrl = pageUrl,
            thumbnailUrl = info.thumbnail?.takeIf { it.isNotBlank() }
                ?: LinkPreviewFetcher.instant(sourceUrl)?.thumbnailUrl,
            durationSeconds = duration,
            author = info.uploader?.takeIf { it.isNotBlank() },
            options = buildOptions(formats, duration, pageUrl)
        )
    }

    private fun buildOptions(
        formats: List<VideoFormat>,
        durationSeconds: Long,
        pageUrl: String
    ): List<ExtractedVideoOption> {
        val videoFormats = formats.filter { it.vcodec.isUsableCodec() && it.height > 0 }
        val audioFormats = formats.filter { it.acodec.isUsableCodec() && !it.vcodec.isUsableCodec() }
        val bestAudioBytes = audioFormats.maxOfOrNull { it.sizeOrEstimate(durationSeconds) } ?: 0L

        val options = mutableListOf<ExtractedVideoOption>()

        options += ExtractedVideoOption(
            qualityLabel = "Best available (auto)",
            format = "MP4",
            estimatedBytes = videoFormats.maxOfOrNull { it.sizeOrEstimate(durationSeconds) }
                ?.plus(bestAudioBytes) ?: 0L,
            downloadUrl = pageUrl,
            engine = DownloadEngine.YTDLP,
            formatSelector = "bestvideo*+bestaudio/best",
            sourcePageUrl = pageUrl,
            requiresMerge = true
        )

        videoFormats
            .groupBy { it.height }
            .entries
            .sortedByDescending { it.key }
            .forEach { (height, group) ->
                val best = group.maxByOrNull { it.sizeOrEstimate(durationSeconds) } ?: return@forEach
                val videoBytes = best.sizeOrEstimate(durationSeconds)
                val progressive = group.any { it.acodec.isUsableCodec() }
                options += ExtractedVideoOption(
                    qualityLabel = "${height}p${qualitySuffix(height)}${if (best.fps > 30) " ${best.fps}fps" else ""}",
                    format = "MP4",
                    estimatedBytes = if (progressive) videoBytes else videoBytes + bestAudioBytes,
                    downloadUrl = pageUrl,
                    engine = DownloadEngine.YTDLP,
                    formatSelector = "bestvideo[height<=$height]+bestaudio/best[height<=$height]/best",
                    sourcePageUrl = pageUrl,
                    requiresMerge = !progressive
                )
            }

        if (audioFormats.isNotEmpty() || videoFormats.isNotEmpty()) {
            options += ExtractedVideoOption(
                qualityLabel = "Audio only (M4A)",
                format = "M4A",
                estimatedBytes = bestAudioBytes,
                downloadUrl = pageUrl,
                isAudioOnly = true,
                engine = DownloadEngine.YTDLP,
                formatSelector = "bestaudio[ext=m4a]/bestaudio/best",
                sourcePageUrl = pageUrl
            )
            options += ExtractedVideoOption(
                qualityLabel = "Audio only (MP3)",
                format = "MP3",
                estimatedBytes = bestAudioBytes,
                downloadUrl = pageUrl,
                isAudioOnly = true,
                engine = DownloadEngine.YTDLP,
                formatSelector = "bestaudio/best",
                sourcePageUrl = pageUrl
            )
        }

        return options.distinctBy { it.qualityLabel }.take(14)
    }

    private fun qualitySuffix(height: Int): String = when {
        height >= 2160 -> " 4K"
        height >= 1440 -> " QHD"
        height >= 1080 -> " FHD"
        height >= 720 -> " HD"
        height >= 480 -> " SD"
        else -> ""
    }

    private fun String?.isUsableCodec(): Boolean =
        !this.isNullOrBlank() && this != "none"

    /** Real size when yt-dlp knows it, otherwise bitrate * duration. */
    private fun VideoFormat.sizeOrEstimate(durationSeconds: Long): Long {
        val known = if (fileSize > 0) fileSize else fileSizeApproximate
        if (known > 0) return known
        val kbitsPerSecond = when {
            tbr > 0 -> tbr
            abr > 0 -> abr
            else -> 0
        }
        return if (kbitsPerSecond > 0 && durationSeconds > 0) {
            kbitsPerSecond.toLong() * 125L * durationSeconds
        } else {
            0L
        }
    }
}

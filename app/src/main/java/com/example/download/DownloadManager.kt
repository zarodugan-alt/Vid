package com.example.download

import android.content.Context
import android.os.Environment
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.db.DownloadDao
import com.example.data.model.DownloadEngine
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class AppDownloadManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var maxConcurrentDownloads: Int = 3
    var isWifiOnly: Boolean = false

    init {
        // Resume any pending tasks on start
        scope.launch {
            checkAndScheduleQueue()
        }
    }

    private fun getDownloadsDirectory(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun startDownload(
        url: String,
        title: String,
        quality: String = "720p HD",
        format: String = "MP4",
        estimatedBytes: Long = 0L,
        thumbnailUrl: String? = null,
        mediaType: MediaType? = null,
        explicitMimeType: String? = null,
        engine: DownloadEngine = DownloadEngine.HTTP,
        formatSelector: String? = null,
        sourcePageUrl: String? = null,
        referer: String? = null
    ): String {
        // Resolve extension cleanly from format, title, or url
        val cleanFormat = format.trim().lowercase()
        val ext = when {
            cleanFormat == "mp3" || cleanFormat == "audio" -> "mp3"
            cleanFormat == "webm" -> "webm"
            cleanFormat == "mp4" || cleanFormat == "video" -> "mp4"
            cleanFormat.isNotBlank() && !cleanFormat.contains(" ") && cleanFormat.length in 2..5 -> cleanFormat
            title.contains(".") -> title.substringAfterLast(".").lowercase()
            url.substringBefore("?").contains(".") -> url.substringBefore("?").substringAfterLast(".").lowercase()
            else -> "mp4"
        }

        val rawTitle = if (title.endsWith(".$ext", ignoreCase = true)) title.substringBeforeLast(".") else title
        val sanitizedTitle = rawTitle.replace(Regex("[^a-zA-Z0-9._ -]"), "_").trim()
            .ifBlank { "download_${System.currentTimeMillis()}" }
        val fileName = "${sanitizedTitle}_${System.currentTimeMillis()}.$ext"
        val destinationFile = File(getDownloadsDirectory(), fileName)

        val resolvedMediaType = mediaType ?: mediaTypeFor(ext)
        val resolvedMimeType = explicitMimeType ?: mimeTypeFor(ext, resolvedMediaType)

        val task = DownloadTask(
            url = url,
            title = title,
            fileName = fileName,
            thumbnailUrl = thumbnailUrl,
            filePath = destinationFile.absolutePath,
            totalBytes = estimatedBytes,
            downloadedBytes = 0L,
            status = DownloadStatus.PENDING,
            mimeType = resolvedMimeType,
            quality = quality,
            mediaType = resolvedMediaType,
            engine = engine,
            formatSelector = formatSelector,
            sourcePageUrl = sourcePageUrl,
            referer = referer
        )

        scope.launch {
            downloadDao.insertOrUpdate(task)
            checkAndScheduleQueue()
        }

        return task.id
    }

    fun pauseDownload(taskId: String) {
        YtDlpEngine.cancel(taskId)
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            downloadDao.update(
                task.copy(
                    status = DownloadStatus.PAUSED,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L
                )
            )
            checkAndScheduleQueue()
        }
    }

    fun resumeDownload(taskId: String) {
        scope.launch {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            downloadDao.update(task.copy(status = DownloadStatus.PENDING, errorMessage = null))
            checkAndScheduleQueue()
        }
    }

    fun retryDownload(taskId: String) {
        YtDlpEngine.cancel(taskId)
        activeJobs.remove(taskId)?.cancel()
        scope.launch {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            deleteArtifacts(task)
            downloadDao.update(
                task.copy(
                    status = DownloadStatus.PENDING,
                    downloadedBytes = 0L,
                    speedBytesPerSec = 0L,
                    errorMessage = null
                )
            )
            checkAndScheduleQueue()
        }
    }

    fun cancelOrDeleteDownload(taskId: String, deleteFile: Boolean = true) {
        YtDlpEngine.cancel(taskId)
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch {
            val task = downloadDao.getTaskById(taskId)
            if (task != null) {
                if (deleteFile) {
                    deleteArtifacts(task)
                }
                downloadDao.deleteById(taskId)
            }
            checkAndScheduleQueue()
        }
    }

    fun pauseAll() {
        activeJobs.forEach { (taskId, job) ->
            YtDlpEngine.cancel(taskId)
            job.cancel()
        }
        activeJobs.clear()
        scope.launch {
            downloadDao.updateStatusForBatch(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
            downloadDao.updateStatusForBatch(DownloadStatus.PENDING, DownloadStatus.PAUSED)
        }
    }

    fun resumeAll() {
        scope.launch {
            downloadDao.updateStatusForBatch(DownloadStatus.PAUSED, DownloadStatus.PENDING)
            checkAndScheduleQueue()
        }
    }

    private suspend fun checkAndScheduleQueue() = withContext(Dispatchers.IO) {
        val currentlyRunning = activeJobs.size
        if (currentlyRunning >= maxConcurrentDownloads) return@withContext

        val slotsAvailable = maxConcurrentDownloads - currentlyRunning
        val pendingList = downloadDao.getTasksByStatus(DownloadStatus.PENDING)
        pendingList.take(slotsAvailable).forEach { task ->
            enqueueTaskExecution(task)
        }
    }

    fun enqueueTaskExecution(task: DownloadTask) {
        if (activeJobs.containsKey(task.id)) return

        val job = scope.launch(Dispatchers.IO) {
            executeDownload(task)
        }
        activeJobs[task.id] = job
    }

    private suspend fun executeDownload(task: DownloadTask) {
        try {
            if (isWifiOnly && !isOnWifi()) {
                downloadDao.update(
                    task.copy(status = DownloadStatus.PAUSED, errorMessage = "Waiting for Wi-Fi")
                )
                return
            }
            when (task.engine) {
                DownloadEngine.YTDLP -> executeYtDlpDownload(task)
                DownloadEngine.HTTP -> executeHttpDownload(task)
            }
        } finally {
            // Runs even when the job was cancelled by pause/cancel, so the queue
            // never gets stuck with a phantom "running" slot.
            withContext(NonCancellable) {
                activeJobs.remove(task.id)
                checkAndScheduleQueue()
            }
        }
    }

    // ------------------------------------------------------------------
    // yt-dlp engine
    // ------------------------------------------------------------------

    private suspend fun executeYtDlpDownload(task: DownloadTask) {
        val targetDir = task.filePath?.let { File(it).parentFile } ?: getDownloadsDirectory()
        val baseName = task.fileName.substringBeforeLast(".")
        val requestedExt = task.fileName.substringAfterLast('.', "mp4").lowercase()
        val isAudio = task.mediaType == MediaType.AUDIO
        val pageUrl = task.sourcePageUrl?.takeIf { it.isNotBlank() } ?: task.url

        downloadDao.update(
            task.copy(
                status = DownloadStatus.DOWNLOADING,
                errorMessage = null,
                downloadedBytes = YtDlpEngine.bytesOnDisk(targetDir, baseName)
            )
        )

        var lastEmit = 0L
        var lastBytes = 0L
        var lastTimestamp = System.currentTimeMillis()

        val result = YtDlpEngine.download(
            context = context,
            processId = task.id,
            url = pageUrl,
            formatSelector = task.formatSelector,
            audioFormat = if (isAudio) requestedExt else null,
            mergeContainer = if (!isAudio) requestedExt else null,
            outputDir = targetDir,
            outputBaseName = baseName
        ) { percent, etaSeconds, _ ->
            // Stop reporting as soon as the task was paused or removed, otherwise a
            // late callback would resurrect the DOWNLOADING state.
            if (!activeJobs.containsKey(task.id)) return@download
            val now = System.currentTimeMillis()
            if (now - lastEmit < PROGRESS_INTERVAL_MS) return@download
            lastEmit = now

            val bytes = YtDlpEngine.bytesOnDisk(targetDir, baseName)
            val total = when {
                percent > 1f && bytes > 0 -> (bytes / (percent / 100f)).toLong()
                task.totalBytes > 0 -> task.totalBytes
                else -> 0L
            }
            val elapsed = (now - lastTimestamp).coerceAtLeast(1L)
            val speed = if (bytes > lastBytes) (bytes - lastBytes) * 1000 / elapsed else 0L
            lastBytes = bytes
            lastTimestamp = now

            scope.launch {
                downloadDao.update(
                    task.copy(
                        status = DownloadStatus.DOWNLOADING,
                        downloadedBytes = bytes,
                        totalBytes = maxOf(total, bytes),
                        speedBytesPerSec = speed,
                        etaSeconds = etaSeconds.coerceAtLeast(0L)
                    )
                )
            }
        }

        result.onSuccess { file ->
            val extension = file.extension.lowercase()
            val mediaType = if (isAudio) MediaType.AUDIO else mediaTypeFor(extension)
            val localThumbnail = MediaThumbnails.create(context, file, mediaType)
            val finalThumbnail = localThumbnail ?: task.thumbnailUrl
            withContext(NonCancellable) {
                downloadDao.update(
                    task.copy(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        thumbnailUrl = finalThumbnail,
                        mimeType = mimeTypeFor(extension, mediaType),
                        mediaType = mediaType,
                        status = DownloadStatus.COMPLETED,
                        downloadedBytes = file.length(),
                        totalBytes = file.length(),
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L,
                        errorMessage = null,
                        completedAt = System.currentTimeMillis()
                    )
                )
            }
        }.onFailure { error ->
            // A cancelled job means the user paused or removed the task; its state
            // is owned by pauseDownload()/cancelOrDeleteDownload().
            if (!currentCoroutineContext().isActive) return@onFailure
            withContext(NonCancellable) {
                downloadDao.update(
                    task.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = YtDlpEngine.friendlyError(error),
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Plain HTTP engine
    // ------------------------------------------------------------------

    private suspend fun executeHttpDownload(currentTask: DownloadTask) {
        val destinationFile = File(
            currentTask.filePath ?: File(getDownloadsDirectory(), currentTask.fileName).absolutePath
        )
        val existingLength = if (destinationFile.exists()) destinationFile.length() else 0L

        downloadDao.update(
            currentTask.copy(
                status = DownloadStatus.DOWNLOADING,
                downloadedBytes = existingLength
            )
        )

        try {
            val requestBuilder = Request.Builder()
                .url(currentTask.url)
                .header("User-Agent", VideoExtractor.USER_AGENT)

            currentTask.referer?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("Referer", it)
            }

            if (existingLength > 0) {
                requestBuilder.header("Range", "bytes=$existingLength-")
            }

            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                    // If server doesn't support Range or failed, fallback to 0 or error
                    if (response.code == 416) {
                        // Range not satisfiable -> already completed
                        completeDownload(currentTask, destinationFile)
                        return
                    }
                    throw IllegalStateException("HTTP server error code: ${response.code}")
                }

                // Servers are allowed to ignore Range and return 200. Appending in that case
                // corrupts the file, so restart from byte zero.
                val resumeAccepted = existingLength > 0 && response.code == 206
                val body = response.body ?: throw IllegalStateException("Empty response body from server")
                val serverLength = body.contentLength()
                val totalBytes = if (serverLength > 0) {
                    if (response.code == 206) existingLength + serverLength else serverLength
                } else if (currentTask.totalBytes > 0) {
                    currentTask.totalBytes
                } else {
                    0L
                }

                downloadDao.update(currentTask.copy(totalBytes = totalBytes))

                val inputStream: InputStream = body.byteStream()
                val outputStream = if (resumeAccepted) {
                    FileOutputStream(destinationFile, true)
                } else {
                    FileOutputStream(destinationFile, false)
                }

                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var totalDownloaded = if (resumeAccepted) existingLength else 0L

                var lastUpdateTime = System.currentTimeMillis()
                var bytesSinceLastUpdate = 0L

                outputStream.use { out ->
                    inputStream.use { input ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            totalDownloaded += bytesRead
                            bytesSinceLastUpdate += bytesRead

                            val now = System.currentTimeMillis()
                            val diff = now - lastUpdateTime

                            if (diff >= PROGRESS_INTERVAL_MS) {
                                val speed = (bytesSinceLastUpdate * 1000) / diff
                                val remainingBytes = (totalBytes - totalDownloaded).coerceAtLeast(0L)
                                val eta = if (speed > 0) remainingBytes / speed else 0L

                                downloadDao.update(
                                    currentTask.copy(
                                        downloadedBytes = totalDownloaded,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speed,
                                        etaSeconds = eta,
                                        status = DownloadStatus.DOWNLOADING
                                    )
                                )
                                lastUpdateTime = now
                                bytesSinceLastUpdate = 0L
                            }
                        }
                    }
                }

                completeDownload(currentTask.copy(totalBytes = totalDownloaded), destinationFile)
            }
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) {
                // Was paused or cancelled intentionally
                return
            }
            withContext(NonCancellable) {
                downloadDao.update(
                    currentTask.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = e.localizedMessage ?: "Download failed. Please check network.",
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L
                    )
                )
            }
        }
    }

    private fun isOnWifi(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun completeDownload(task: DownloadTask, file: File) {
        val finalSize = file.length()
        val localThumbnail = MediaThumbnails.create(context, file, task.mediaType)
        val finalThumbnail = localThumbnail ?: task.thumbnailUrl
        withContext(NonCancellable) {
            downloadDao.update(
                task.copy(
                    status = DownloadStatus.COMPLETED,
                    downloadedBytes = finalSize,
                    totalBytes = finalSize,
                    filePath = file.absolutePath,
                    thumbnailUrl = finalThumbnail,
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L,
                    completedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Removes the finished file plus any yt-dlp intermediates (`.part`, `.fXXX`). */
    private fun deleteArtifacts(task: DownloadTask) {
        runCatching {
            task.filePath?.let { path ->
                val file = File(path)
                if (file.exists()) file.delete()
                val directory = file.parentFile ?: return@let
                val baseName = file.name.substringBeforeLast(".")
                directory.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("$baseName.") }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun mediaTypeFor(ext: String): MediaType = when (ext.lowercase()) {
        "mp3", "m4a", "wav", "aac", "ogg", "opus", "flac" -> MediaType.AUDIO
        "mp4", "webm", "mkv", "mov", "avi", "flv", "3gp", "ts", "m4v" -> MediaType.VIDEO
        "pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "7z", "tar", "gz", "apk" -> MediaType.DOCUMENT
        "jpg", "jpeg", "png", "webp", "gif", "svg" -> MediaType.IMAGE
        else -> MediaType.OTHER
    }

    private fun mimeTypeFor(ext: String, mediaType: MediaType): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "rar" -> "application/x-rar-compressed"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> if (mediaType == MediaType.AUDIO) "audio/mpeg" else "video/mp4"
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS = 400L

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val formatVal = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return String.format("%.1f %s", formatVal, units[digitGroups.coerceIn(0, units.size - 1)])
        }

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 KB/s"
            val kb = bytesPerSec / 1024.0
            return if (kb < 1000) {
                String.format("%.1f KB/s", kb)
            } else {
                String.format("%.2f MB/s", kb / 1024.0)
            }
        }

        fun formatDuration(seconds: Long): String {
            if (seconds <= 0) return "00:00"
            val m = seconds / 60
            val s = seconds % 60
            val h = m / 60
            return if (h > 0) {
                String.format("%02d:%02d:%02d", h, m % 60, s)
            } else {
                String.format("%02d:%02d", m, s)
            }
        }
    }
}

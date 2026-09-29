package com.example.download

import android.content.Context
import android.os.Environment
import com.example.data.db.DownloadDao
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
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
        explicitMimeType: String? = null
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

        val resolvedMediaType = mediaType ?: when (ext) {
            "mp3", "m4a", "wav", "aac", "ogg", "flac" -> MediaType.AUDIO
            "mp4", "webm", "mkv", "mov", "avi", "flv", "3gp", "ts" -> MediaType.VIDEO
            "pdf", "doc", "docx", "xls", "xlsx", "zip", "rar", "7z", "tar", "gz", "apk" -> MediaType.DOCUMENT
            "jpg", "jpeg", "png", "webp", "gif", "svg" -> MediaType.IMAGE
            else -> MediaType.OTHER
        }

        val resolvedMimeType = explicitMimeType ?: when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> if (resolvedMediaType == MediaType.AUDIO) "audio/mpeg" else "video/mp4"
        }

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
            mediaType = resolvedMediaType
        )

        scope.launch {
            downloadDao.insertOrUpdate(task)
            checkAndScheduleQueue()
        }

        return task.id
    }

    fun pauseDownload(taskId: String) {
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
            downloadDao.update(task.copy(status = DownloadStatus.PENDING))
            checkAndScheduleQueue()
        }
    }

    fun retryDownload(taskId: String) {
        scope.launch {
            val task = downloadDao.getTaskById(taskId) ?: return@launch
            val file = task.filePath?.let { File(it) }
            if (file != null && file.exists()) {
                file.delete()
            }
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
        val job = activeJobs.remove(taskId)
        job?.cancel()

        scope.launch {
            val task = downloadDao.getTaskById(taskId)
            if (task != null) {
                if (deleteFile && task.filePath != null) {
                    try {
                        val file = File(task.filePath)
                        if (file.exists()) file.delete()
                    } catch (_: Exception) {}
                }
                downloadDao.deleteById(taskId)
            }
            checkAndScheduleQueue()
        }
    }

    fun pauseAll() {
        activeJobs.forEach { (_, job) -> job.cancel() }
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

    private suspend fun executeDownload(initialTask: DownloadTask) {
        var currentTask = initialTask
        val destinationFile = File(currentTask.filePath ?: File(getDownloadsDirectory(), currentTask.fileName).absolutePath)
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

            if (existingLength > 0) {
                requestBuilder.header("Range", "bytes=$existingLength-")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                // If server doesn't support Range or failed, fallback to 0 or error
                if (response.code == 416) {
                    // Range not satisfiable -> already completed
                    completeDownload(currentTask, destinationFile)
                    return
                }
                throw IllegalStateException("HTTP server error code: ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body from server")
            val serverLength = body.contentLength()
            val totalBytes = if (serverLength > 0) {
                if (response.code == 206) existingLength + serverLength else serverLength
            } else if (currentTask.totalBytes > 0) {
                currentTask.totalBytes
            } else {
                35_000_000L
            }

            downloadDao.update(currentTask.copy(totalBytes = totalBytes))

            val inputStream: InputStream = body.byteStream()
            val outputStream = if (existingLength > 0 && response.code == 206) {
                FileOutputStream(destinationFile, true)
            } else {
                FileOutputStream(destinationFile, false)
            }

            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            var totalDownloaded = if (response.code == 206) existingLength else 0L

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

                        if (diff >= 400) {
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

        } catch (e: Exception) {
            if (activeJobs[currentTask.id]?.isCancelled == true) {
                // Was paused or cancelled intentionally
                return
            }
            // Error occurred
            downloadDao.update(
                currentTask.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "Download failed. Please check network.",
                    speedBytesPerSec = 0L,
                    etaSeconds = 0L
                )
            )
        } finally {
            activeJobs.remove(currentTask.id)
            checkAndScheduleQueue()
        }
    }

    private suspend fun completeDownload(task: DownloadTask, file: File) {
        val finalSize = file.length()
        downloadDao.update(
            task.copy(
                status = DownloadStatus.COMPLETED,
                downloadedBytes = finalSize,
                totalBytes = finalSize,
                filePath = file.absolutePath,
                speedBytesPerSec = 0L,
                etaSeconds = 0L,
                completedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
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

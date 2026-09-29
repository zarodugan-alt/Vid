package com.example.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.example.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Builds a local preview image for a finished download: a real video frame, the
 * embedded cover art of an audio file, or a downscaled copy of an image. Remote
 * poster URLs can expire or require cookies, so every completed file gets its
 * own thumbnail on disk.
 */
object MediaThumbnails {

    private const val MAX_EDGE = 480

    suspend fun create(context: Context, source: File, mediaType: MediaType): String? =
        withContext(Dispatchers.IO) {
            if (!source.exists() || source.length() <= 0L) return@withContext null

            val bitmap = when (mediaType) {
                MediaType.VIDEO -> videoFrame(source)
                MediaType.AUDIO -> embeddedArt(source)
                MediaType.IMAGE -> decodeScaled(source)
                else -> null
            } ?: return@withContext null

            val directory = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val target = File(directory, "${source.nameWithoutExtension.take(60)}_${source.length()}.jpg")
            val path = runCatching {
                FileOutputStream(target).use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
                }
                target.absolutePath
            }.getOrNull()
            runCatching { bitmap.recycle() }
            path
        }

    private fun videoFrame(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // A frame one second in avoids the black fade-in most videos start with.
            val timeUs = if (durationMs > 3_000L) 1_000_000L else 0L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            frame?.let { scale(it) }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun embeddedArt(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { scale(it) }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeScaled(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (longestEdge > MAX_EDGE) longestEdge / MAX_EDGE else 1
        }
        BitmapFactory.decodeFile(file.absolutePath, options)?.let { scale(it) }
    } catch (_: Throwable) {
        null
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        if (longestEdge <= MAX_EDGE || longestEdge == 0) return bitmap
        val ratio = MAX_EDGE.toFloat() / longestEdge
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
            if (scaled != bitmap) bitmap.recycle()
            scaled
        }.getOrDefault(bitmap)
    }
}

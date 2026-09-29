package com.example.data.repository

import com.example.data.db.DownloadDao
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType
import kotlinx.coroutines.flow.Flow

class DownloadRepository(private val downloadDao: DownloadDao) {
    val allTasks: Flow<List<DownloadTask>> = downloadDao.getAllTasks()
    val activeTasks: Flow<List<DownloadTask>> = downloadDao.getActiveTasks()
    val completedTasks: Flow<List<DownloadTask>> = downloadDao.getCompletedTasks()
    val totalDownloadedBytes: Flow<Long?> = downloadDao.getTotalDownloadedBytes()

    fun getCompletedTasksByMediaType(mediaType: MediaType): Flow<List<DownloadTask>> {
        return downloadDao.getCompletedTasksByMediaType(mediaType = mediaType)
    }

    suspend fun getTaskById(id: String): DownloadTask? = downloadDao.getTaskById(id)

    fun observeTaskById(id: String): Flow<DownloadTask?> = downloadDao.observeTaskById(id)

    suspend fun insertOrUpdate(task: DownloadTask) = downloadDao.insertOrUpdate(task)

    suspend fun deleteById(id: String) = downloadDao.deleteById(id)

    suspend fun deleteCompleted() = downloadDao.deleteByStatus(DownloadStatus.COMPLETED)

    suspend fun pauseAll() {
        downloadDao.updateStatusForBatch(DownloadStatus.DOWNLOADING, DownloadStatus.PAUSED)
    }

    suspend fun resumeAll() {
        downloadDao.updateStatusForBatch(DownloadStatus.PAUSED, DownloadStatus.PENDING)
    }
}

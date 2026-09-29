package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE status IN (:activeStatuses) ORDER BY createdAt DESC")
    fun getActiveTasks(activeStatuses: List<DownloadStatus> = listOf(DownloadStatus.DOWNLOADING, DownloadStatus.PENDING, DownloadStatus.PAUSED, DownloadStatus.FAILED)): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE status = :completedStatus ORDER BY completedAt DESC, createdAt DESC")
    fun getCompletedTasks(completedStatus: DownloadStatus = DownloadStatus.COMPLETED): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE status = :completedStatus AND mediaType = :mediaType ORDER BY completedAt DESC, createdAt DESC")
    fun getCompletedTasksByMediaType(completedStatus: DownloadStatus = DownloadStatus.COMPLETED, mediaType: MediaType): Flow<List<DownloadTask>>

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getTasksByStatus(status: DownloadStatus): List<DownloadTask>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTask?

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    fun observeTaskById(id: String): Flow<DownloadTask?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: DownloadTask)

    @Update
    suspend fun update(task: DownloadTask)

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM download_tasks WHERE status = :status")
    suspend fun deleteByStatus(status: DownloadStatus)

    @Query("UPDATE download_tasks SET status = :newStatus WHERE status = :currentStatus")
    suspend fun updateStatusForBatch(currentStatus: DownloadStatus, newStatus: DownloadStatus)

    @Query("SELECT COUNT(*) FROM download_tasks WHERE status = :status")
    fun getTaskCountByStatus(status: DownloadStatus): Flow<Int>

    @Query("SELECT SUM(downloadedBytes) FROM download_tasks WHERE status = 'COMPLETED'")
    fun getTotalDownloadedBytes(): Flow<Long?>
}

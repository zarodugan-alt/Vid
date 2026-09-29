package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.BrowserBookmark
import com.example.data.model.BrowserHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface BrowserDao {
    @Query("SELECT * FROM browser_bookmarks ORDER BY createdAt DESC")
    fun getAllBookmarks(): Flow<List<BrowserBookmark>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BrowserBookmark)

    @Query("DELETE FROM browser_bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: Long)

    @Query("SELECT * FROM browser_history ORDER BY visitedAt DESC LIMIT 100")
    fun getRecentHistory(): Flow<List<BrowserHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: BrowserHistory)

    @Query("DELETE FROM browser_history")
    suspend fun clearHistory()
}

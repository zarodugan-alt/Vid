package com.example.data.repository

import com.example.data.db.BrowserDao
import com.example.data.model.BrowserBookmark
import com.example.data.model.BrowserHistory
import kotlinx.coroutines.flow.Flow

class BrowserRepository(private val browserDao: BrowserDao) {
    val bookmarks: Flow<List<BrowserBookmark>> = browserDao.getAllBookmarks()
    val history: Flow<List<BrowserHistory>> = browserDao.getRecentHistory()

    suspend fun addBookmark(title: String, url: String, favicon: String? = null) {
        browserDao.insertBookmark(
            BrowserBookmark(
                title = title,
                url = url,
                favicon = favicon
            )
        )
    }

    suspend fun removeBookmark(id: Long) {
        browserDao.deleteBookmarkById(id)
    }

    suspend fun recordHistory(title: String, url: String) {
        browserDao.insertHistory(
            BrowserHistory(
                title = title.ifBlank { url },
                url = url
            )
        )
    }

    suspend fun clearHistory() {
        browserDao.clearHistory()
    }
}

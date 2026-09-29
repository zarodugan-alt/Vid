package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.model.BrowserBookmark
import com.example.data.model.BrowserHistory
import com.example.data.model.DownloadEngine
import com.example.data.model.DownloadStatus
import com.example.data.model.DownloadTask
import com.example.data.model.MediaType

class Converters {
    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = try {
        DownloadStatus.valueOf(value)
    } catch (_: Exception) {
        DownloadStatus.PENDING
    }

    @TypeConverter
    fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter
    fun toMediaType(value: String): MediaType = try {
        MediaType.valueOf(value)
    } catch (_: Exception) {
        MediaType.VIDEO
    }

    @TypeConverter
    fun fromDownloadEngine(value: DownloadEngine): String = value.name

    @TypeConverter
    fun toDownloadEngine(value: String): DownloadEngine = try {
        DownloadEngine.valueOf(value)
    } catch (_: Exception) {
        DownloadEngine.HTTP
    }
}

@Database(
    entities = [
        DownloadTask::class,
        BrowserBookmark::class,
        BrowserHistory::class
    ],
    // v2 adds engine/formatSelector/sourcePageUrl to download_tasks.
    // v3 adds referer to download_tasks.
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun browserDao(): BrowserDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "viddownloader_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

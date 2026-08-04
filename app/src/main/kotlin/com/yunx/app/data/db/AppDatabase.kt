package com.yunx.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [QuarkAccountEntity::class, DownloadTaskEntity::class, UCAccountEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun quarkAccountDao(): QuarkAccountDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun ucAccountDao(): UCAccountDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yunx.db"
                )
                    // 开发期：结构变更直接重建，避免迁移崩溃
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
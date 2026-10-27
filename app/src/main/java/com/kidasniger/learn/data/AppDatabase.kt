package com.kidasniger.learn.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptionEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE transcriptions ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transcriptions ADD COLUMN folder TEXT NOT NULL DEFAULT 'Général'")
                database.execSQL("ALTER TABLE transcriptions ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transcriptions ADD COLUMN segments TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "whisper_learn_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

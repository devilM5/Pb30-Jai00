package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LearningNoteEntity::class,
        ChatMessageEntity::class,
        TerminalLogEntity::class,
        LearningRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun fridayDao(): FridayDao

    companion object {
        @Volatile
        private var instance: FridayDatabase? = null

        fun getInstance(context: Context): FridayDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FridayDatabase::class.java,
                    "friday_local_vault.db"
                ).build().also { instance = it }
            }
        }
    }
}

package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LearningNoteEntity::class,
        ChatMessageEntity::class,
        TerminalLogEntity::class,
        LearningRecordEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun fridayDao(): FridayDao

    companion object {
        @Volatile
        private var instance: FridayDatabase? = null

        /**
         * Non-destructive 1 -> 2 migration:
         * - Deduplicates any legacy `(userId, recordType, itemId)` rows before adding the unique index.
         * - Adds metadata columns (`title`, `sourceUrl`, `summary`) to `learning_records` for YouTube/lab logs.
         * - Adds composite `(userId, agentType, pendingDelete, createdAtMillis)` index on `chat_messages`.
         * Never drops or clears user tables (`fallbackToDestructiveMigration` is intentionally NEVER enabled).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE learning_records ADD COLUMN title TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE learning_records ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE learning_records ADD COLUMN summary TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "DELETE FROM learning_records WHERE rowid NOT IN (" +
                        "SELECT MAX(rowid) FROM learning_records GROUP BY userId, recordType, itemId" +
                        ")"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_learning_records_userId_recordType_itemId " +
                        "ON learning_records (userId, recordType, itemId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_chat_messages_userId_agentType_pendingDelete_createdAtMillis " +
                        "ON chat_messages (userId, agentType, pendingDelete, createdAtMillis)"
                )
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun getInstance(context: Context): FridayDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FridayDatabase::class.java,
                    "friday_local_vault.db"
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

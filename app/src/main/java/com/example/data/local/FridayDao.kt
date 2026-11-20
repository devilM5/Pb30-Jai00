package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FridayDao {

    // --- Notes ---
    @Query(
        "SELECT * FROM notes WHERE userId = :userId AND pendingDelete = 0 " +
            "ORDER BY pinned DESC, updatedAtMillis DESC"
    )
    fun observeNotes(userId: String): Flow<List<LearningNoteEntity>>

    @Query("SELECT * FROM notes WHERE userId = :userId AND pendingDelete = 0 ORDER BY updatedAtMillis DESC LIMIT :limit")
    suspend fun getRecentNotes(userId: String, limit: Int = 20): List<LearningNoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: String): LearningNoteEntity?

    @Query("SELECT * FROM notes WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingSyncNotes(userId: String): List<LearningNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: LearningNoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(notes: List<LearningNoteEntity>)

    @Query("UPDATE notes SET pendingSync = 0 WHERE id = :id")
    suspend fun markNoteSynced(id: String)

    @Query("UPDATE notes SET pendingDelete = 1, pendingSync = 1 WHERE id = :id")
    suspend fun markNotePendingDelete(id: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteHard(id: String)

    // --- Chat Messages ---
    @Query(
        "SELECT * FROM chat_messages WHERE userId = :userId AND pendingDelete = 0 " +
            "ORDER BY createdAtMillis ASC"
    )
    fun observeChatMessages(userId: String): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE userId = :userId AND pendingDelete = 0 " +
            "ORDER BY createdAtMillis DESC LIMIT :limit"
    )
    suspend fun getRecentChatMessages(userId: String, limit: Int = 25): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE userId = :userId AND pendingSync = 1")
    suspend fun getPendingSyncMessages(userId: String): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChatMessage(message: ChatMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChatMessages(messages: List<ChatMessageEntity>)

    @Query("UPDATE chat_messages SET pendingSync = 0 WHERE id = :id")
    suspend fun markChatMessageSynced(id: String)

    @Query("UPDATE chat_messages SET pendingDelete = 1, pendingSync = 1 WHERE id = :id")
    suspend fun markChatMessagePendingDelete(id: String)

    @Query("UPDATE chat_messages SET pendingDelete = 1, pendingSync = 1 WHERE userId = :userId")
    suspend fun markAllChatMessagesPendingDelete(userId: String)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteChatMessageHard(id: String)

    // --- Terminal Logs (LOCAL-ONLY by default) ---
    @Query("SELECT * FROM terminal_logs WHERE userId = :userId ORDER BY createdAtMillis DESC")
    fun observeTerminalLogs(userId: String): Flow<List<TerminalLogEntity>>

    @Query("SELECT * FROM terminal_logs WHERE userId = :userId ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun getRecentTerminalLogs(userId: String, limit: Int = 15): List<TerminalLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTerminalLog(log: TerminalLogEntity)

    @Query("DELETE FROM terminal_logs WHERE id = :id")
    suspend fun deleteTerminalLog(id: String)

    // --- Learning Records (Checklists & Quizzes) ---
    @Query(
        "SELECT itemId FROM learning_records " +
            "WHERE userId = :userId AND recordType = :recordType AND completed = 1"
    )
    fun observeCompletedRecordIds(userId: String, recordType: String): Flow<List<String>>

    @Query("SELECT * FROM learning_records WHERE id = :id LIMIT 1")
    suspend fun getLearningRecord(id: String): LearningRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLearningRecord(record: LearningRecordEntity)
}

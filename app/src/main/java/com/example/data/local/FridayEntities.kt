package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.model.ChatMessage
import com.example.data.model.LearningNote
import com.example.data.model.TerminalLog
import com.google.firebase.Timestamp
import java.util.Date

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["userId", "pendingDelete", "pinned", "updatedAtMillis"]),
        Index(value = ["userId", "pendingSync"])
    ]
)
data class LearningNoteEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val category: String,
    val pinned: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val pendingSync: Boolean = true,
    val pendingDelete: Boolean = false
) {
    fun toModel(): LearningNote = LearningNote(
        id = id,
        userId = userId,
        title = title,
        content = content,
        category = category,
        pinned = pinned,
        createdAt = Timestamp(Date(createdAtMillis)),
        updatedAt = Timestamp(Date(updatedAtMillis))
    )
}

@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["userId", "pendingDelete", "createdAtMillis"]),
        Index(value = ["userId", "pendingSync"])
    ]
)
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val role: String,
    val content: String,
    val languageMode: String,
    val agentType: String,
    val createdAtMillis: Long,
    val pendingSync: Boolean = true,
    val pendingDelete: Boolean = false
) {
    fun toModel(): ChatMessage = ChatMessage(
        id = id,
        userId = userId,
        role = role,
        content = content,
        languageMode = languageMode,
        agentType = agentType,
        createdAt = Timestamp(Date(createdAtMillis))
    )
}

/**
 * Local-only execution audit log. `localOnly` stays `true` by default so shell/SSH
 * activity never leaves the device unless explicitly audited locally.
 */
@Entity(
    tableName = "terminal_logs",
    indices = [Index(value = ["userId", "createdAtMillis"])]
)
data class TerminalLogEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val command: String,
    val environment: String,
    val status: String,
    val output: String,
    val exitCode: Int,
    val approvedByUser: Boolean,
    val localOnly: Boolean = true,
    val createdAtMillis: Long
) {
    fun toModel(): TerminalLog = TerminalLog(
        id = id,
        userId = userId,
        command = command,
        environment = environment,
        status = status,
        output = output,
        exitCode = exitCode,
        approvedByUser = approvedByUser,
        createdAt = Timestamp(Date(createdAtMillis))
    )
}

@Entity(
    tableName = "learning_records",
    indices = [Index(value = ["userId", "recordType"])]
)
data class LearningRecordEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val recordType: String,
    val itemId: String,
    val completed: Boolean,
    val updatedAtMillis: Long
) {
    companion object {
        const val TYPE_CHECKLIST = "CHECKLIST"
        const val TYPE_QUIZ = "QUIZ"

        fun key(userId: String, type: String, itemId: String): String = "$userId::$type::$itemId"
    }
}

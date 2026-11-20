package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.CredentialRedactor
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.model.AgentType
import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import com.example.data.model.LearningNote
import com.example.data.model.NoteCategory
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TerminalLog
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class MemoryRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Please sign in with Google to access Memory.")

    fun observeNotes(userId: String): Flow<List<LearningNote>> = flow {
        val path = "users/$userId/notes"
        emitAll(
            db.collection("users").document(userId).collection("notes")
                .orderBy("updatedAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        LearningNote::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeChatMessages(userId: String): Flow<List<ChatMessage>> = flow {
        val path = "users/$userId/chat_messages"
        emitAll(
            db.collection("users").document(userId).collection("chat_messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeTerminalLogs(userId: String): Flow<List<TerminalLog>> = flow {
        val path = "users/$userId/terminal_logs"
        emitAll(
            db.collection("users").document(userId).collection("terminal_logs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        TerminalLog::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveNote(
        title: String,
        content: String,
        category: NoteCategory,
        pinned: Boolean = false
    ): Result<Unit> {
        val cleanTitle = CredentialRedactor.redact(title.trim()).take(120)
        val cleanContent = CredentialRedactor.redact(content.trim()).take(8000)
        if (cleanTitle.isEmpty() || cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Title and note content cannot be empty."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/notes"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("notes").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "content" to cleanContent,
                "category" to category.name,
                "pinned" to pinned,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateNote(
        noteId: String,
        title: String,
        content: String,
        category: NoteCategory,
        pinned: Boolean
    ): Result<Unit> {
        val cleanTitle = CredentialRedactor.redact(title.trim()).take(120)
        val cleanContent = CredentialRedactor.redact(content.trim()).take(8000)
        if (noteId.isBlank() || cleanTitle.isEmpty() || cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Note ID, title, and content are required."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/notes/$noteId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("notes").document(noteId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "title" to cleanTitle,
                    "content" to cleanContent,
                    "category" to category.name,
                    "pinned" to pinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun togglePin(noteId: String, currentPinned: Boolean): Result<Unit> {
        if (noteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Note ID must not be blank."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/notes/$noteId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("notes").document(noteId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "pinned" to !currentPinned,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteNote(noteId: String): Result<Unit> {
        if (noteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Note ID must not be blank."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/notes/$noteId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("notes").document(noteId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveChatMessage(
        role: String,
        content: String,
        languageMode: LanguageMode,
        agentType: AgentType
    ): Result<Unit> {
        val cleanContent = CredentialRedactor.redact(content.trim()).take(15000)
        if (cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val safeRole = if (role == "FRIDAY") "FRIDAY" else "USER"
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/chat_messages"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("chat_messages").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "role" to safeRole,
                "content" to cleanContent,
                "languageMode" to languageMode.name,
                "agentType" to agentType.name,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteChatMessage(messageId: String): Result<Unit> {
        if (messageId.isBlank()) {
            return Result.failure(IllegalArgumentException("Message ID must not be blank."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/chat_messages/$messageId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("chat_messages").document(messageId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun clearChatHistory(messages: List<ChatMessage>): Result<Unit> {
        var path = "users/${auth.currentUser?.uid ?: "unauthenticated"}/chat_messages"
        return try {
            val uid = requireUserId()
            val collection = db.collection("users").document(uid).collection("chat_messages")
            path = collection.path
            messages.filter { it.id.isNotBlank() }.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { msg -> batch.delete(collection.document(msg.id)) }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    suspend fun saveTerminalLog(
        command: String,
        environment: TerminalEnvironment,
        status: TerminalExecutionStatus,
        output: String,
        exitCode: Int,
        approvedByUser: Boolean
    ): Result<Unit> {
        val safeCommand = CredentialRedactor.redact(command.trim()).take(1000)
        if (safeCommand.isEmpty()) {
            return Result.failure(IllegalArgumentException("Command cannot be empty for log entry."))
        }
        val safeOutput = CredentialRedactor.redact(output.trim()).take(8000)
        val clampedExitCode = exitCode.coerceIn(-1, 255)
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/terminal_logs"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("terminal_logs").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "command" to safeCommand,
                "environment" to environment.name,
                "status" to status.name,
                "output" to safeOutput,
                "exitCode" to clampedExitCode,
                "approvedByUser" to approvedByUser,
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteTerminalLog(logId: String): Result<Unit> {
        if (logId.isBlank()) {
            return Result.failure(IllegalArgumentException("Log ID must not be blank."))
        }
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/terminal_logs/$logId"
        return try {
            val uid = requireUserId()
            val docRef = db.collection("users").document(uid).collection("terminal_logs").document(logId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}

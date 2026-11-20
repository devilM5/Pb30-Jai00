package com.example.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.R
import com.example.core.CredentialRedactor
import com.example.core.OperationType
import com.example.core.handleFirestoreError
import com.example.data.local.ChatMessageEntity
import com.example.data.local.FridayDao
import com.example.data.local.FridayDatabase
import com.example.data.local.LearningNoteEntity
import com.example.data.local.LearningRecordEntity
import com.example.data.local.TerminalLogEntity
import com.example.data.model.AgentType
import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import com.example.data.model.LearningNote
import com.example.data.model.NoteCategory
import com.example.data.model.SyncStatusState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Local-first Memory & Audit repository backed by Room (`FridayDatabase`).
 *
 * Rules enforced:
 * - Phone-local Room database is ALWAYS the primary source of truth (works 100% offline).
 * - Notes & chat messages sync automatically to Firebase Firestore when signed in & online,
 *   preserving existing document IDs (`users/{uid}/notes/{id}`, `users/{uid}/chat_messages/{id}`)
 *   and `firestore.rules` constraints.
 * - Terminal execution logs are stored LOCAL-ONLY (`localOnly = true`) in Room by default
 *   and are never uploaded to cloud Firestore.
 */
class MemoryRepository(
    private val dao: FridayDao,
    private val db: FirebaseFirestore? = null,
    private val auth: FirebaseAuth? = null,
    private val context: Context? = null
) {
    constructor(context: Context) : this(
        dao = FridayDatabase.getInstance(context).fridayDao(),
        db = runCatching {
            FirebaseFirestore.getInstance(
                context.applicationContext.getString(R.string.firestore_database_id)
            )
        }.getOrNull(),
        auth = runCatching { Firebase.auth }.getOrNull(),
        context = context.applicationContext
    )

    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncedUserId: String? = null
    private var networkCallbackRegistered = false

    private val _syncStatus = MutableStateFlow(
        SyncStatusState(
            isOnline = checkOnline(),
            isSignedIn = canCloudSync(auth?.currentUser?.uid),
            lastSyncSummary = "Local Room database ready."
        )
    )
    val syncStatus: StateFlow<SyncStatusState> = _syncStatus.asStateFlow()

    init {
        registerNetworkMonitor()
    }

    private fun registerNetworkMonitor() {
        val appCtx = context ?: return
        if (networkCallbackRegistered) return
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _syncStatus.value = _syncStatus.value.copy(isOnline = true)
                    val uid = auth?.currentUser?.uid
                    if (canCloudSync(uid)) {
                        syncScope.launch { uid?.let { syncPendingChanges(it) } }
                    }
                }

                override fun onLost(network: Network) {
                    _syncStatus.value = _syncStatus.value.copy(
                        isOnline = checkOnline(),
                        lastSyncSummary = "Offline • changes saved safely in local Room database."
                    )
                }
            })
            networkCallbackRegistered = true
        }
    }

    private fun checkOnline(): Boolean {
        val appCtx = context ?: return false
        return runCatching {
            val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    private fun canCloudSync(userId: String?): Boolean {
        if (db == null || auth == null || userId.isNullOrBlank() || userId == LOCAL_OFFLINE_USER_ID) {
            return false
        }
        return auth.currentUser?.uid == userId
    }

    fun startCloudSyncIfEligible(userId: String) {
        if (syncedUserId == userId) return
        syncedUserId = userId
        val eligible = canCloudSync(userId)
        _syncStatus.value = _syncStatus.value.copy(
            isOnline = checkOnline(),
            isSignedIn = eligible,
            lastSyncSummary = if (eligible) {
                "Local Room active • Firebase cloud sync armed."
            } else {
                "Offline Local Mode • saved on this phone only."
            }
        )
        if (!eligible || db == null) return

        syncScope.launch { syncPendingChanges(userId) }

        db.collection("users").document(userId).collection("notes")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .onEach { snapshot ->
                val remoteNotes = snapshot.toObjects(
                    LearningNote::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                remoteNotes.forEach { remote ->
                    val existing = dao.getNoteById(remote.id)
                    if (existing == null || !existing.pendingSync) {
                        dao.upsertNote(
                            LearningNoteEntity(
                                id = remote.id,
                                userId = userId,
                                title = remote.title,
                                content = remote.content,
                                category = remote.category,
                                pinned = remote.pinned,
                                createdAtMillis = remote.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                updatedAtMillis = remote.updatedAt?.toDate()?.time ?: System.currentTimeMillis(),
                                pendingSync = false,
                                pendingDelete = false
                            )
                        )
                    }
                }
                refreshPendingCount(userId)
            }
            .catch { err ->
                if (err is CancellationException) throw err
                val fe = err as? FirebaseFirestoreException ?: (err as? Exception)
                if (fe != null) handleFirestoreError(fe, OperationType.LIST, "users/$userId/notes")
            }
            .launchIn(syncScope)

        db.collection("users").document(userId).collection("chat_messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .onEach { snapshot ->
                val remoteMsgs = snapshot.toObjects(
                    ChatMessage::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                val entities = remoteMsgs.map { remote ->
                    ChatMessageEntity(
                        id = remote.id,
                        userId = userId,
                        role = remote.role,
                        content = remote.content,
                        languageMode = remote.languageMode,
                        agentType = remote.agentType,
                        createdAtMillis = remote.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                        pendingSync = false,
                        pendingDelete = false
                    )
                }
                val pendingIds = dao.getPendingSyncMessages(userId).map { it.id }.toSet()
                val safeToInsert = entities.filterNot { it.id in pendingIds }
                if (safeToInsert.isNotEmpty()) dao.upsertChatMessages(safeToInsert)
                refreshPendingCount(userId)
            }
            .catch { err ->
                if (err is CancellationException) throw err
                val fe = err as? FirebaseFirestoreException ?: (err as? Exception)
                if (fe != null) handleFirestoreError(fe, OperationType.LIST, "users/$userId/chat_messages")
            }
            .launchIn(syncScope)
    }

    fun observeNotes(userId: String): Flow<List<LearningNote>> {
        startCloudSyncIfEligible(userId)
        return dao.observeNotes(userId).map { entities -> entities.map { it.toModel() } }
    }

    fun observeChatMessages(userId: String): Flow<List<ChatMessage>> {
        startCloudSyncIfEligible(userId)
        return dao.observeChatMessages(userId).map { entities -> entities.map { it.toModel() } }
    }

    /**
     * Terminal logs are read exclusively from the local Room database (`localOnly = true`).
     */
    fun observeTerminalLogs(userId: String): Flow<List<TerminalLog>> =
        dao.observeTerminalLogs(userId).map { entities -> entities.map { it.toModel() } }

    fun observeCompletedChecklistIds(userId: String): Flow<Set<String>> =
        dao.observeCompletedRecordIds(userId, LearningRecordEntity.TYPE_CHECKLIST).map { it.toSet() }

    fun observeCompletedQuizModuleIds(userId: String): Flow<Set<String>> =
        dao.observeCompletedRecordIds(userId, LearningRecordEntity.TYPE_QUIZ).map { it.toSet() }

    suspend fun toggleChecklistItem(userId: String, itemId: String) {
        val key = LearningRecordEntity.key(userId, LearningRecordEntity.TYPE_CHECKLIST, itemId)
        val existing = dao.getLearningRecord(key)
        val nextCompleted = !(existing?.completed ?: false)
        dao.upsertLearningRecord(
            LearningRecordEntity(
                id = key,
                userId = userId,
                recordType = LearningRecordEntity.TYPE_CHECKLIST,
                itemId = itemId,
                completed = nextCompleted,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun markQuizCompleted(userId: String, moduleId: String) {
        val key = LearningRecordEntity.key(userId, LearningRecordEntity.TYPE_QUIZ, moduleId)
        dao.upsertLearningRecord(
            LearningRecordEntity(
                id = key,
                userId = userId,
                recordType = LearningRecordEntity.TYPE_QUIZ,
                itemId = moduleId,
                completed = true,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveNote(
        title: String,
        content: String,
        category: NoteCategory,
        pinned: Boolean = false,
        userIdOverride: String? = null
    ): Result<Unit> {
        val cleanTitle = CredentialRedactor.redact(title.trim()).take(120)
        val cleanContent = CredentialRedactor.redact(content.trim()).take(8000)
        if (cleanTitle.isEmpty() || cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Title and note content cannot be empty."))
        }
        val uid = resolveActiveUserId(userIdOverride)
        val now = System.currentTimeMillis()
        val entity = LearningNoteEntity(
            id = newValidDocId(),
            userId = uid,
            title = cleanTitle,
            content = cleanContent,
            category = category.name,
            pinned = pinned,
            createdAtMillis = now,
            updatedAtMillis = now,
            pendingSync = canCloudSync(uid),
            pendingDelete = false
        )
        dao.upsertNote(entity)
        triggerBackgroundSync(uid)
        return Result.success(Unit)
    }

    suspend fun updateNote(
        noteId: String,
        title: String,
        content: String,
        category: NoteCategory,
        pinned: Boolean,
        userIdOverride: String? = null
    ): Result<Unit> {
        val cleanTitle = CredentialRedactor.redact(title.trim()).take(120)
        val cleanContent = CredentialRedactor.redact(content.trim()).take(8000)
        if (noteId.isBlank() || cleanTitle.isEmpty() || cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Note ID, title, and content are required."))
        }
        val uid = resolveActiveUserId(userIdOverride)
        val existing = dao.getNoteById(noteId)
        val now = System.currentTimeMillis()
        val updated = LearningNoteEntity(
            id = noteId,
            userId = existing?.userId ?: uid,
            title = cleanTitle,
            content = cleanContent,
            category = category.name,
            pinned = pinned,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now,
            pendingSync = canCloudSync(uid),
            pendingDelete = false
        )
        dao.upsertNote(updated)
        triggerBackgroundSync(uid)
        return Result.success(Unit)
    }

    suspend fun togglePin(noteId: String, currentPinned: Boolean, userIdOverride: String? = null): Result<Unit> {
        if (noteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Note ID must not be blank."))
        }
        val existing = dao.getNoteById(noteId)
            ?: return Result.failure(IllegalStateException("Note not found locally."))
        val uid = resolveActiveUserId(userIdOverride)
        dao.upsertNote(
            existing.copy(
                pinned = !currentPinned,
                updatedAtMillis = System.currentTimeMillis(),
                pendingSync = canCloudSync(uid)
            )
        )
        triggerBackgroundSync(uid)
        return Result.success(Unit)
    }

    suspend fun deleteNote(noteId: String, userIdOverride: String? = null): Result<Unit> {
        if (noteId.isBlank()) {
            return Result.failure(IllegalArgumentException("Note ID must not be blank."))
        }
        val uid = resolveActiveUserId(userIdOverride)
        if (canCloudSync(uid)) {
            dao.markNotePendingDelete(noteId)
            triggerBackgroundSync(uid)
        } else {
            dao.deleteNoteHard(noteId)
        }
        return Result.success(Unit)
    }

    suspend fun saveChatMessage(
        role: String,
        content: String,
        languageMode: LanguageMode,
        agentType: AgentType,
        userIdOverride: String? = null
    ): Result<Unit> {
        val cleanContent = CredentialRedactor.redact(content.trim()).take(15000)
        if (cleanContent.isEmpty()) {
            return Result.failure(IllegalArgumentException("Message cannot be empty."))
        }
        val safeRole = if (role == "FRIDAY") "FRIDAY" else "USER"
        val uid = resolveActiveUserId(userIdOverride)
        val entity = ChatMessageEntity(
            id = newValidDocId(),
            userId = uid,
            role = safeRole,
            content = cleanContent,
            languageMode = languageMode.name,
            agentType = agentType.name,
            createdAtMillis = System.currentTimeMillis(),
            pendingSync = canCloudSync(uid),
            pendingDelete = false
        )
        dao.upsertChatMessage(entity)
        triggerBackgroundSync(uid)
        return Result.success(Unit)
    }

    suspend fun deleteChatMessage(messageId: String, userIdOverride: String? = null): Result<Unit> {
        if (messageId.isBlank()) {
            return Result.failure(IllegalArgumentException("Message ID must not be blank."))
        }
        val uid = resolveActiveUserId(userIdOverride)
        if (canCloudSync(uid)) {
            dao.markChatMessagePendingDelete(messageId)
            triggerBackgroundSync(uid)
        } else {
            dao.deleteChatMessageHard(messageId)
        }
        return Result.success(Unit)
    }

    suspend fun clearChatHistory(messages: List<ChatMessage>, userIdOverride: String? = null): Result<Unit> {
        val uid = resolveActiveUserId(userIdOverride)
        if (canCloudSync(uid)) {
            dao.markAllChatMessagesPendingDelete(uid)
            triggerBackgroundSync(uid)
        } else {
            messages.forEach { dao.deleteChatMessageHard(it.id) }
        }
        return Result.success(Unit)
    }

    /**
     * Saves terminal execution log STRICTLY LOCAL-ONLY in Room by default (`localOnly = true`).
     * Never pushes terminal logs or shell outputs to cloud Firestore.
     */
    suspend fun saveTerminalLog(
        command: String,
        environment: TerminalEnvironment,
        status: TerminalExecutionStatus,
        output: String,
        exitCode: Int,
        approvedByUser: Boolean,
        userIdOverride: String? = null
    ): Result<Unit> {
        val safeCommand = CredentialRedactor.redact(command.trim()).take(1000)
        if (safeCommand.isEmpty()) {
            return Result.failure(IllegalArgumentException("Command cannot be empty for log entry."))
        }
        val safeOutput = CredentialRedactor.redact(output.trim()).take(8000)
        val clampedExitCode = exitCode.coerceIn(-1, 255)
        val uid = resolveActiveUserId(userIdOverride)
        dao.insertTerminalLog(
            TerminalLogEntity(
                id = newValidDocId(),
                userId = uid,
                command = safeCommand,
                environment = environment.name,
                status = status.name,
                output = safeOutput,
                exitCode = clampedExitCode,
                approvedByUser = approvedByUser,
                localOnly = true,
                createdAtMillis = System.currentTimeMillis()
            )
        )
        return Result.success(Unit)
    }

    suspend fun deleteTerminalLog(logId: String): Result<Unit> {
        if (logId.isBlank()) {
            return Result.failure(IllegalArgumentException("Log ID must not be blank."))
        }
        dao.deleteTerminalLog(logId)
        return Result.success(Unit)
    }

    suspend fun searchLocalKnowledge(userId: String, query: String): String {
        val keywords = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val notes = dao.getRecentNotes(userId, limit = 30).filterNot { it.pendingDelete }
        val chats = dao.getRecentChatMessages(userId, limit = 30).filterNot { it.pendingDelete }

        val matchedNotes = if (keywords.isEmpty()) notes.take(5) else {
            notes.filter { note ->
                val hay = "${note.title} ${note.content} ${note.category}".lowercase()
                keywords.any { hay.contains(it) }
            }.take(5)
        }
        val matchedChats = if (keywords.isEmpty()) chats.takeLast(5) else {
            chats.filter { msg ->
                val hay = msg.content.lowercase()
                keywords.any { hay.contains(it) }
            }.takeLast(5)
        }

        if (matchedNotes.isEmpty() && matchedChats.isEmpty()) {
            return "No matching local notes or recent chat records found for '$query'."
        }
        return buildString {
            if (matchedNotes.isNotEmpty()) {
                appendLine("=== Local FRIDAY Notes ===")
                matchedNotes.forEach { n ->
                    appendLine("- [${n.category}] ${n.title}: ${n.content.take(350)}")
                }
            }
            if (matchedChats.isNotEmpty()) {
                appendLine("=== Recent Local Chat History ===")
                matchedChats.forEach { c ->
                    appendLine("- ${c.role}: ${c.content.take(280)}")
                }
            }
        }.trim()
    }

    private fun triggerBackgroundSync(userId: String) {
        syncScope.launch {
            refreshPendingCount(userId)
            if (canCloudSync(userId) && checkOnline()) {
                syncPendingChanges(userId)
            }
        }
    }

    suspend fun syncPendingChanges(userId: String) {
        val firestore = db ?: return
        if (!canCloudSync(userId)) return
        if (!checkOnline()) {
            refreshPendingCount(userId)
            return
        }

        _syncStatus.value = _syncStatus.value.copy(
            isOnline = true,
            isSignedIn = true,
            isSyncing = true,
            lastSyncSummary = "Syncing offline notes & chats to Firestore…"
        )

        var syncedCount = 0
        try {
            val pendingNotes = dao.getPendingSyncNotes(userId)
            for (note in pendingNotes) {
                val docRef = firestore.collection("users").document(userId)
                    .collection("notes").document(note.id)
                if (note.pendingDelete) {
                    docRef.delete().await()
                    dao.deleteNoteHard(note.id)
                    syncedCount++
                } else {
                    val existingSnapshot = docRef.get().await()
                    if (existingSnapshot.exists()) {
                        docRef.update(
                            mapOf(
                                "title" to note.title,
                                "content" to note.content,
                                "category" to note.category,
                                "pinned" to note.pinned,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    } else {
                        docRef.set(
                            mapOf(
                                "userId" to userId,
                                "title" to note.title,
                                "content" to note.content,
                                "category" to note.category,
                                "pinned" to note.pinned,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                    dao.markNoteSynced(note.id)
                    syncedCount++
                }
            }

            val pendingChats = dao.getPendingSyncMessages(userId)
            for (msg in pendingChats) {
                val docRef = firestore.collection("users").document(userId)
                    .collection("chat_messages").document(msg.id)
                if (msg.pendingDelete) {
                    docRef.delete().await()
                    dao.deleteChatMessageHard(msg.id)
                    syncedCount++
                } else {
                    docRef.set(
                        mapOf(
                            "userId" to userId,
                            "role" to msg.role,
                            "content" to msg.content,
                            "languageMode" to msg.languageMode,
                            "agentType" to msg.agentType,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    dao.markChatMessageSynced(msg.id)
                    syncedCount++
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background cloud sync deferred (local Room preserved): ${e.message}")
            handleFirestoreError(e, OperationType.WRITE, "users/$userId")
        } finally {
            val remaining = refreshPendingCount(userId)
            _syncStatus.value = _syncStatus.value.copy(
                isSyncing = false,
                pendingChangesCount = remaining,
                lastSyncSummary = when {
                    remaining > 0 -> "Saved locally ($remaining pending cloud sync)."
                    syncedCount > 0 -> "Synced $syncedCount local changes to Firestore."
                    else -> "Local Room & Firestore in sync."
                }
            )
        }
    }

    private suspend fun refreshPendingCount(userId: String): Int {
        val pending = dao.getPendingSyncNotes(userId).size + dao.getPendingSyncMessages(userId).size
        _syncStatus.value = _syncStatus.value.copy(
            isOnline = checkOnline(),
            isSignedIn = canCloudSync(userId),
            pendingChangesCount = pending
        )
        return pending
    }

    private fun resolveActiveUserId(override: String?): String =
        override?.takeIf { it.isNotBlank() }
            ?: syncedUserId
            ?: auth?.currentUser?.uid
            ?: LOCAL_OFFLINE_USER_ID

    companion object {
        private const val TAG = "MemoryRepository"
        const val LOCAL_OFFLINE_USER_ID = "local_offline_operator"

        fun newValidDocId(): String = UUID.randomUUID().toString()
    }
}

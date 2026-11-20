package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.ExecutionEnvFilter
import com.example.data.model.ExecutionStatusFilter
import com.example.data.model.LearningNote
import com.example.data.model.NoteCategory
import com.example.data.model.TerminalLog
import com.example.data.model.UiState
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge

@Composable
fun MemoryScreen(
    searchQuery: String,
    notesState: UiState<List<LearningNote>>,
    chatHistoryState: UiState<List<ChatMessage>>,
    terminalLogsState: UiState<List<TerminalLog>> = UiState.Success(emptyList()),
    onSearchChange: (String) -> Unit,
    onCreateNote: (String, String, NoteCategory, Boolean) -> Unit,
    onUpdateNote: (String, String, String, NoteCategory, Boolean) -> Unit,
    onTogglePin: (LearningNote) -> Unit,
    onDeleteNote: (String) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onDeleteTerminalLog: (String) -> Unit = {},
    onClearHistory: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var envFilter by rememberSaveable { mutableStateOf(ExecutionEnvFilter.ALL) }
    var statusFilter by rememberSaveable { mutableStateOf(ExecutionStatusFilter.ALL) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<LearningNote?>(null) }

    val notes = (notesState as? UiState.Success)?.data.orEmpty()
    val messages = (chatHistoryState as? UiState.Success)?.data.orEmpty()
    val terminalLogs = (terminalLogsState as? UiState.Success)?.data.orEmpty()

    val filteredNotes = remember(notes, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) notes
        else notes.filter {
            it.title.lowercase().contains(q) ||
                it.content.lowercase().contains(q) ||
                it.categoryEnum.label.lowercase().contains(q)
        }
    }

    val filteredMessages = remember(messages, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) messages.reversed()
        else messages.filter {
            it.content.lowercase().contains(q) ||
                it.agentType.lowercase().contains(q)
        }.reversed()
    }

    val filteredTerminalLogs = remember(terminalLogs, searchQuery, envFilter, statusFilter) {
        filterTerminalLogs(terminalLogs, searchQuery, envFilter, statusFilter)
    }

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        editingNote = null
                        showNoteDialog = true
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("add_memory_note_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add learning note")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "FRIDAY KNOWLEDGE MEMORY",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_knowledge_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("memory_search_input")
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("memory_tab_notes"),
                    text = { Text("Notes (${filteredNotes.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("memory_tab_history"),
                    text = { Text("History (${filteredMessages.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("memory_tab_exec_logs"),
                    text = { Text("Exec Logs (${filteredTerminalLogs.size})") }
                )
            }

            if (selectedTab == 0) {
                when (notesState) {
                    is UiState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    is UiState.Error -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CyberHudCard(accentColor = MaterialTheme.colorScheme.secondary) {
                            Text(
                                text = notesState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (filteredNotes.isEmpty()) {
                                item {
                                    CyberHudCard(accentColor = MaterialTheme.colorScheme.outline) {
                                        Icon(
                                            imageVector = Icons.Default.NoteAlt,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = if (searchQuery.isBlank()) {
                                                "No saved learning notes yet. Tap + to create one, or bookmark answers from FRIDAY Chat & Learning Lab."
                                            } else {
                                                "No notes match \"$searchQuery\"."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                items(filteredNotes, key = { it.id }) { note ->
                                    CyberHudCard(
                                        accentColor = if (note.pinned) CyberEmerald else MaterialTheme.colorScheme.primary
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CyberTagBadge(
                                                text = note.categoryEnum.label.uppercase(),
                                                accentColor = if (note.pinned) CyberEmerald else MaterialTheme.colorScheme.primary
                                            )
                                            Row {
                                                IconButton(onClick = { onTogglePin(note) }) {
                                                    Icon(
                                                        imageVector = if (note.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                                        contentDescription = "Pin note",
                                                        tint = if (note.pinned) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        editingNote = note
                                                        showNoteDialog = true
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Edit,
                                                        contentDescription = "Edit note",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                                IconButton(onClick = { onDeleteNote(note.id) }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Delete,
                                                        contentDescription = "Delete note",
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = note.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = note.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(64.dp)) }
                        }
                    }
                }
            } else if (selectedTab == 1) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (messages.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = onClearHistory,
                                    modifier = Modifier.testTag("clear_chat_history_button")
                                ) {
                                    Text(
                                        text = "Clear All History",
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                    if (filteredMessages.isEmpty()) {
                        item {
                            CyberHudCard(accentColor = MaterialTheme.colorScheme.outline) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        "No conversation history recorded yet. Start chatting with FRIDAY or run an AI Agent."
                                    } else {
                                        "No conversation logs match \"$searchQuery\"."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        items(filteredMessages, key = { it.id }) { msg ->
                            CyberHudCard(
                                accentColor = if (msg.isFromFriday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CyberTagBadge(
                                        text = "${msg.role} // ${msg.agentType}",
                                        accentColor = if (msg.isFromFriday) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary
                                    )
                                    IconButton(onClick = { onDeleteMessage(msg.id) }) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Delete message",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = msg.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        ExecutionHistoryFilterBar(
                            searchQuery = searchQuery,
                            envFilter = envFilter,
                            statusFilter = statusFilter,
                            totalCount = terminalLogs.size,
                            filteredCount = filteredTerminalLogs.size,
                            onSearchQueryChange = onSearchChange,
                            onEnvFilterChange = { envFilter = it },
                            onStatusFilterChange = { statusFilter = it }
                        )
                    }
                    when (terminalLogsState) {
                        is UiState.Loading -> item { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
                        is UiState.Error -> item {
                            CyberHudCard(accentColor = MaterialTheme.colorScheme.secondary) {
                                Text(
                                    text = terminalLogsState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        else -> {
                            if (filteredTerminalLogs.isEmpty()) {
                                item {
                                    CyberHudCard(accentColor = MaterialTheme.colorScheme.outline) {
                                        Icon(
                                            imageVector = Icons.Default.Terminal,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = if (terminalLogs.isEmpty()) {
                                                "No terminal or SSH execution logs yet."
                                            } else {
                                                "No execution logs match your current search/filter."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                items(filteredTerminalLogs, key = { it.id.ifBlank { it.hashCode().toString() } }) { log ->
                                    ExecutionLogItemCard(
                                        log = log,
                                        onDeleteLog = onDeleteTerminalLog
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNoteDialog) {
        NoteEditorDialog(
            existing = editingNote,
            onDismiss = { showNoteDialog = false },
            onSave = { title, content, category, pinned ->
                val current = editingNote
                if (current == null) {
                    onCreateNote(title, content, category, pinned)
                } else {
                    onUpdateNote(current.id, title, content, category, pinned)
                }
                showNoteDialog = false
            }
        )
    }
}

@Composable
private fun NoteEditorDialog(
    existing: LearningNote?,
    onDismiss: () -> Unit,
    onSave: (String, String, NoteCategory, Boolean) -> Unit
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var category by remember { mutableStateOf(existing?.categoryEnum ?: NoteCategory.GENERAL) }
    var pinned by remember { mutableStateOf(existing?.pinned ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        title = {
            Text(
                text = if (existing == null) "NEW LEARNING NOTE" else "EDIT LEARNING NOTE",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_title_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it.take(8000) },
                    label = { Text("Cybersecurity Note / Commands") },
                    minLines = 4,
                    maxLines = 7,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("note_content_input")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NoteCategory.entries.forEach { cat ->
                        val selected = cat == category
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(CircleShape)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    1.dp,
                                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape
                                )
                                .clickable { category = cat }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = cat.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, content, category, pinned) },
                enabled = title.isNotBlank() && content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("save_note_dialog_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

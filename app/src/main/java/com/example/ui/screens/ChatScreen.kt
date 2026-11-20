package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.VoiceUiState
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.ChatMessage
import com.example.data.model.CodeLanguage
import com.example.data.model.ConversationalAction
import com.example.data.model.LanguageMode
import com.example.data.model.UiState
import com.example.ui.theme.AiModelSelectorRow
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.FridayArcCore
import com.example.ui.theme.LanguagePillSelector
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan

@Composable
fun ChatScreen(
    languageMode: LanguageMode,
    selectedModel: AiModelOption,
    chatHistoryState: UiState<List<ChatMessage>>,
    streamState: UiState<String>,
    pendingAction: ConversationalAction,
    voiceState: VoiceUiState,
    prefillPrompt: String?,
    toolTraces: List<com.example.data.model.ToolExecutionTrace> = emptyList(),
    latestCitations: List<com.example.data.model.WebCitation> = emptyList(),
    dhirModelState: com.example.data.model.DhirModelState? = null,
    onConsumePrefill: () -> Unit,
    onSelectLanguage: (LanguageMode) -> Unit,
    onSelectModel: (AiModelOption) -> Unit,
    onSendPrompt: (String, Boolean) -> Unit,
    onDismissAction: () -> Unit,
    onLaunchCodingTool: (CodeLanguage, String) -> Unit,
    onLaunchResearchTool: (String, Boolean) -> Unit,
    onLaunchFileTool: () -> Unit,
    onOpenTerminalBridge: () -> Unit,
    onStartVoiceInput: ((String) -> Unit) -> Unit,
    onStopVoiceInput: () -> Unit,
    onSpeakMessage: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onSaveMessageToMemory: (String) -> Unit,
    onNotifyCopied: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var inputText by rememberSaveable { mutableStateOf("") }
    var autoSpeakVoiceReply by rememberSaveable { mutableStateOf(true) }
    var micPermissionNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(prefillPrompt) {
        if (!prefillPrompt.isNullOrBlank()) {
            inputText = prefillPrompt
            onConsumePrefill()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micPermissionNotice = null
            onStartVoiceInput { spoken ->
                inputText = spoken
                onSendPrompt(spoken, autoSpeakVoiceReply)
            }
        } else {
            micPermissionNotice = "Microphone permission is required for Hindi/English voice input."
        }
    }

    val allMessages = (chatHistoryState as? UiState.Success)?.data.orEmpty()
    val chatMessages = remember(allMessages) {
        allMessages.filter { it.agentType == AgentType.CHAT.name }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size, streamState) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header Bar with FRIDAY Core + Language + AI Model Switcher
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FridayArcCore(
                            size = 38.dp,
                            isActive = streamState is UiState.Loading || streamState is UiState.Streaming || voiceState.isListening
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "FRIDAY COMMAND & CHAT",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${selectedModel.displayName} • ${
                                    dhirModelState?.status?.label ?: "Local"
                                } • ${
                                    if (voiceState.hindiTtsAvailable) "hi-IN Voice" else "Voice"
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (voiceState.isSpeaking) {
                        IconButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier.testTag("chat_stop_tts_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = stringResource(R.string.voice_stop_cd),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                LanguagePillSelector(
                    selected = languageMode,
                    onSelect = onSelectLanguage
                )
                Spacer(Modifier.height(6.dp))
                AiModelSelectorRow(
                    selected = selectedModel,
                    onSelect = onSelectModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                )
            }
        }

        // Conversational Command Quick Chips
        val commandChips = listOf(
            "FRIDAY, write Python code.",
            "FRIDAY, explain this error.",
            "FRIDAY, run this approved terminal command: `uname -a`",
            "FRIDAY, scan my authorized test network using Nmap.",
            "FRIDAY, explain the result in Hindi."
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            commandChips.forEachIndexed { idx, prompt ->
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .clickable { onSendPrompt(prompt, false) }
                        .testTag("quick_prompt_$idx")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Conversational Command Router Banner (One-tap bridge into Tools)
        AnimatedVisibility(visible = pendingAction != ConversationalAction.None) {
            CyberHudCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                accentColor = NeonCrimson
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CyberTagBadge(text = "COMMAND DETECTED", accentColor = NeonCrimson)
                    TextButton(onClick = onDismissAction) { Text("Dismiss") }
                }
                Spacer(Modifier.height(4.dp))
                when (pendingAction) {
                    is ConversationalAction.OpenCoding -> OutlinedButton(
                        onClick = { onLaunchCodingTool(pendingAction.language, pendingAction.prompt) },
                        modifier = Modifier.fillMaxWidth().testTag("action_open_coding_button")
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open ${pendingAction.language.displayName} Coding Studio")
                    }
                    is ConversationalAction.RequestTerminalApproval -> OutlinedButton(
                        onClick = onOpenTerminalBridge,
                        modifier = Modifier.fillMaxWidth().testTag("action_open_terminal_button")
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Review & Approve Command in Terminal Bridge")
                    }
                    is ConversationalAction.RequestAuthorizedNmap -> OutlinedButton(
                        onClick = onOpenTerminalBridge,
                        modifier = Modifier.fillMaxWidth().testTag("action_open_nmap_terminal_button")
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Inspect Nmap Approval (${pendingAction.target})")
                    }
                    is ConversationalAction.RunWebResearch -> OutlinedButton(
                        onClick = { onLaunchResearchTool(pendingAction.query, pendingAction.explainInHindi) },
                        modifier = Modifier.fillMaxWidth().testTag("action_open_research_button")
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Run Grounded Web Search in Research Studio")
                    }
                    is ConversationalAction.OpenFileInspector -> OutlinedButton(
                        onClick = onLaunchFileTool,
                        modifier = Modifier.fillMaxWidth().testTag("action_open_file_button")
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open File Agent Picker")
                    }
                    ConversationalAction.None -> Unit
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (chatMessages.isEmpty() && streamState is UiState.Idle) {
                item {
                    CyberHudCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentColor = MaterialTheme.colorScheme.primary
                    ) {
                        CyberTagBadge(text = "FRIDAY ONLINE", accentColor = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.friday_greeting),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Try conversational commands like \"FRIDAY, write Python code\", \"FRIDAY, scan my authorized test network using Nmap\", or switch AI models above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (chatHistoryState is UiState.Error) {
                item {
                    CyberHudCard(accentColor = MaterialTheme.colorScheme.secondary) {
                        Text(
                            text = chatHistoryState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            items(chatMessages, key = { it.id.ifBlank { it.hashCode().toString() } }) { message ->
                ChatMessageBubble(
                    message = message,
                    isSpeakingThis = voiceState.isSpeaking && voiceState.speakingMessageId == message.id,
                    onSpeak = { onSpeakMessage(message.content, message.id) },
                    onStopSpeak = onStopSpeaking,
                    onCopy = {
                        clipboard.setText(AnnotatedString(message.content))
                        onNotifyCopied()
                    },
                    onSaveNote = { onSaveMessageToMemory(message.content) }
                )
            }

            if (streamState is UiState.Loading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "${selectedModel.displayName} processing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (toolTraces.isNotEmpty() || latestCitations.isNotEmpty()) {
                item {
                    CyberHudCard(
                        modifier = Modifier.fillMaxWidth().testTag("chat_orchestrator_traces_card"),
                        accentColor = com.example.ui.theme.CyberEmerald
                    ) {
                        if (toolTraces.isNotEmpty()) {
                            CyberTagBadge(text = "ORCHESTRATOR TOOLS (${toolTraces.size})", accentColor = com.example.ui.theme.CyberEmerald)
                            Spacer(Modifier.height(6.dp))
                            toolTraces.forEach { trace ->
                                Text(
                                    text = "• ${trace.toolName}: ${trace.summary}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (latestCitations.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("SOURCES", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                            latestCitations.forEach { cite ->
                                Text(
                                    text = "• ${cite.title} (${cite.url})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NeonCyan,
                                    modifier = Modifier
                                        .clickable {
                                            runCatching {
                                                context.startActivity(
                                                    android.content.Intent(
                                                        android.content.Intent.ACTION_VIEW,
                                                        android.net.Uri.parse(cite.url)
                                                    )
                                                )
                                            }
                                        }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (streamState is UiState.Streaming) {
                item {
                    CyberHudCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentColor = MaterialTheme.colorScheme.primary
                    ) {
                        CyberTagBadge(text = "FRIDAY // STREAMING", accentColor = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = streamState.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            if (streamState is UiState.Error) {
                item {
                    CyberHudCard(
                        modifier = Modifier.fillMaxWidth(),
                        accentColor = MaterialTheme.colorScheme.secondary
                    ) {
                        Text(
                            text = streamState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = voiceState.isListening || micPermissionNotice != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = when {
                        voiceState.isListening && voiceState.partialTranscript.isNotBlank() ->
                            "Heard: ${voiceState.partialTranscript}"
                        voiceState.isListening ->
                            voiceState.statusMessage ?: "Listening in ${languageMode.displayLabel}…"
                        else -> micPermissionNotice.orEmpty()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        if (voiceState.isListening) {
                            onStopVoiceInput()
                        } else {
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                micPermissionNotice = null
                                onStartVoiceInput { spoken ->
                                    inputText = spoken
                                    onSendPrompt(spoken, autoSpeakVoiceReply)
                                }
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceState.isListening) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .testTag("chat_voice_mic_button")
                ) {
                    Icon(
                        imageVector = if (voiceState.isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = stringResource(R.string.voice_mic_cd),
                        tint = if (voiceState.isListening) MaterialTheme.colorScheme.onSecondary
                        else MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = "Command or ask FRIDAY (${languageMode.displayLabel})…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_message_input")
                )

                IconButton(
                    onClick = {
                        val toSend = inputText.trim()
                        if (toSend.isNotEmpty()) {
                            inputText = ""
                            onSendPrompt(toSend, false)
                        }
                    },
                    enabled = inputText.isNotBlank() && streamState !is UiState.Loading,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag("chat_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send to FRIDAY",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessage,
    isSpeakingThis: Boolean,
    onSpeak: () -> Unit,
    onStopSpeak: () -> Unit,
    onCopy: () -> Unit,
    onSaveNote: () -> Unit
) {
    val isFriday = message.isFromFriday
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isFriday) Arrangement.Start else Arrangement.End
    ) {
        CyberHudCard(
            modifier = Modifier.widthIn(max = 520.dp),
            accentColor = if (isFriday) NeonCyan else NeonCrimson
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CyberTagBadge(
                    text = if (isFriday) "FRIDAY // ${message.languageMode}" else "OPERATOR",
                    accentColor = if (isFriday) NeonCyan else NeonCrimson
                )
                if (isFriday) {
                    Row {
                        IconButton(
                            onClick = onCopy,
                            modifier = Modifier.testTag("msg_copy_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy response",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { if (isSpeakingThis) onStopSpeak() else onSpeak() },
                            modifier = Modifier.testTag("msg_speak_${message.id}")
                        ) {
                            Icon(
                                imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.Default.VolumeUp,
                                contentDescription = stringResource(R.string.voice_speak_cd),
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onSaveNote,
                            modifier = Modifier.testTag("msg_save_${message.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BookmarkAdd,
                                contentDescription = stringResource(R.string.save_to_memory),
                                tint = NeonCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

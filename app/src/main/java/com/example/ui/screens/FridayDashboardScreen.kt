package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.VoiceUiState
import com.example.data.curriculum.CyberCurriculumData
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.ChatMessage
import com.example.data.model.LanguageMode
import com.example.data.model.LearningNote
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.UiState
import com.example.ui.theme.AiModelSelectorRow
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.FridayArcCore
import com.example.ui.theme.LanguagePillSelector
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun FridayDashboardScreen(
    operatorName: String,
    languageMode: LanguageMode,
    selectedModel: AiModelOption = AiModelOption.DHIR_2B_BALANCED,
    activeAgent: AgentType,
    agentPromptInput: String,
    agentStreamState: UiState<String>,
    notesState: UiState<List<LearningNote>>,
    chatHistoryState: UiState<List<ChatMessage>>,
    completedChecklistCount: Int,
    voiceState: VoiceUiState,
    sshState: SshConnectionState = SshConnectionState(),
    sshProfile: SshConnectionProfile = SshConnectionProfile(),
    dhirModelState: com.example.data.model.DhirModelState = com.example.data.model.DhirModelState(),
    syncStatus: com.example.data.model.SyncStatusState = com.example.data.model.SyncStatusState(),
    isOfflineSession: Boolean = false,
    onSelectLanguage: (LanguageMode) -> Unit,
    onSelectModel: (AiModelOption) -> Unit = {},
    onSelectAgent: (AgentType) -> Unit,
    onAgentInputChanged: (String) -> Unit,
    onRunAgent: (AgentType, String) -> Unit,
    onSaveAgentBrief: (String, String) -> Unit,
    onSpeakText: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSecurityAgent: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenStudioTab: (StudioToolTab) -> Unit = {},
    onOpenRemoteSettings: () -> Unit = {},
    onImportDhirModel: (android.net.Uri) -> Unit = {},
    onDownloadDhirModel: () -> Unit = {},
    onCancelDhirDownload: () -> Unit = {},
    onLoadDhirModel: () -> Unit = {},
    onUnloadDhirModel: () -> Unit = {},
    onDeleteDhirModel: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onSignOut: () -> Unit
) {
    val notesCount = (notesState as? UiState.Success)?.data?.size ?: 0
    val chatCount = (chatHistoryState as? UiState.Success)?.data?.size ?: 0
    val totalChecklistItems = CyberCurriculumData.checklists.sumOf { it.items.size }
    val specialistAgents = listOf(
        AgentType.RESEARCH,
        AgentType.LEARNING,
        AgentType.CODE_ANALYSIS,
        AgentType.REPORT_ANALYSIS
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("friday_dashboard_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Iron Man FRIDAY Hero HUD Card (Black, Cyan & Crimson)
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = CyberObsidian,
                border = BorderStroke(1.5.dp, Brush.linearGradient(listOf(NeonCyan, NeonCrimson)))
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_friday_core_1795056702374),
                        contentDescription = "FRIDAY Command Center HUD",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        CyberObsidian.copy(alpha = 0.45f),
                                        CyberObsidian.copy(alpha = 0.94f)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                FridayArcCore(
                                    size = 62.dp,
                                    isActive = agentStreamState is UiState.Loading || agentStreamState is UiState.Streaming
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CyberTagBadge(text = "PB-30 JAI", accentColor = NeonCyan)
                                        CyberTagBadge(text = selectedModel.tierBadge, accentColor = NeonCrimson)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "FRIDAY AI HUB",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Operator: $operatorName • ${selectedModel.displayName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NeonCyan.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = onSignOut,
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = stringResource(R.string.sign_out),
                                    tint = NeonCrimson
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            HudTelemetryChip("NOTES", notesCount.toString(), NeonCyan, Modifier.weight(1f))
                            HudTelemetryChip("LOGS", chatCount.toString(), NeonCrimson, Modifier.weight(1f))
                            HudTelemetryChip("AUDITS", "$completedChecklistCount/$totalChecklistItems", NeonCyan, Modifier.weight(1f))
                        }

                        Spacer(Modifier.height(10.dp))
                        DashboardSshStatusIndicator(
                            sshProfile = sshProfile,
                            sshState = sshState,
                            onClick = onOpenRemoteSettings
                        )
                        Spacer(Modifier.height(8.dp))
                        DashboardSyncStatusBar(
                            syncStatus = syncStatus,
                            isOfflineSession = isOfflineSession,
                            onSyncClick = onSyncNow
                        )
                    }
                }
            }
        }

        // 2. Primary Command Modules
        item {
            Text("PRIMARY COMMAND MODULES", style = MaterialTheme.typography.titleSmall, color = NeonCyan)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubLaunchCard("AI Chat", "Hindi, English, Hinglish & Voice", "CHAT", Icons.Default.Psychology, NeonCyan, "hub_card_chat", onOpenChat, Modifier.weight(1f))
                    HubLaunchCard("Security Agent", "Nmap audit, Kali & checklists", "DEFENSE", Icons.Default.Security, NeonCrimson, "hub_card_sec_agent", onOpenSecurityAgent, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubLaunchCard("Coding Studio", "Python, Java, Kotlin, JS & Bash", "CODE", Icons.Default.Code, NeonCyan, "hub_card_coding", { onOpenStudioTab(StudioToolTab.CODING) }, Modifier.weight(1f))
                    HubLaunchCard("Terminal Bridge", "Termux / Linux approval gate", "TERM", Icons.Default.Terminal, NeonCrimson, "hub_card_terminal", { onOpenStudioTab(StudioToolTab.TERMINAL) }, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubLaunchCard("Web Research", "Live search & Hindi summaries", "WEB", Icons.Default.Language, NeonCyan, "hub_card_research", { onOpenStudioTab(StudioToolTab.RESEARCH) }, Modifier.weight(1f))
                    HubLaunchCard("File Inspector", "Inspect, debug & patch files", "FILES", Icons.Default.FolderOpen, NeonCrimson, "hub_card_files", { onOpenStudioTab(StudioToolTab.FILES) }, Modifier.weight(1f))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HubLaunchCard("Learning Lab", "6 cyber tracks & quizzes", "LABS", Icons.Default.School, NeonCyan, "hub_card_lab", onOpenLab, Modifier.weight(1f))
                    HubLaunchCard("Memory Vault", "Notes & searchable history", "CLOUD", Icons.Default.Bookmarks, NeonCrimson, "hub_card_memory", onOpenMemory, Modifier.weight(1f))
                }
            }
        }

        // 3. AI Model & Language Matrix
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AI AGENT MATRIX", style = MaterialTheme.typography.titleSmall, color = NeonCrimson)
                    LanguagePillSelector(selected = languageMode, onSelect = onSelectLanguage)
                }
                AiModelSelectorRow(
                    selected = selectedModel,
                    onSelect = onSelectModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                )
                com.example.ui.theme.DhirModelStatusCard(
                    modelState = dhirModelState,
                    onImportModelUri = onImportDhirModel,
                    onDownloadOfficialModel = onDownloadDhirModel,
                    onCancelDownload = onCancelDhirDownload,
                    onLoadModel = onLoadDhirModel,
                    onUnloadModel = onUnloadDhirModel,
                    onDeleteModel = onDeleteDhirModel
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                specialistAgents.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { agent ->
                            val selected = agent == activeAgent
                            val accent = if (agent == AgentType.CODE_ANALYSIS || agent == AgentType.REPORT_ANALYSIS) NeonCrimson else NeonCyan
                            CyberHudCard(
                                modifier = Modifier.weight(1f).testTag("agent_card_${agent.name.lowercase()}"),
                                accentColor = if (selected) accent else MaterialTheme.colorScheme.outline,
                                onClick = { onSelectAgent(agent) }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(agentIcon(agent), contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    if (selected) CyberTagBadge(text = "ARMED", accentColor = accent)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(agent.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.height(2.dp))
                                Text(agent.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // 4. Active Agent Console
        item {
            CyberHudCard(modifier = Modifier.fillMaxWidth(), accentColor = NeonCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(activeAgent.title.uppercase(), style = MaterialTheme.typography.titleSmall, color = NeonCyan)
                    CyberTagBadge(text = selectedModel.displayName.uppercase(), accentColor = NeonCrimson)
                }

                val samplePrompt = when (activeAgent) {
                    AgentType.RESEARCH -> "Explain Zero Trust Architecture & MITRE ATT&CK basics step by step."
                    AgentType.LEARNING -> "Teach me OWASP Broken Access Control with a beginner analogy."
                    AgentType.CODE_ANALYSIS -> "Review this Kotlin SQL snippet:\nval q = \"SELECT * FROM users WHERE name='\" + input + \"'\""
                    AgentType.REPORT_ANALYSIS -> "Analyze: Port 22 SSH PasswordAuth=yes, Port 3306 MySQL open to 0.0.0.0, missing CSP header."
                    else -> "Summarize defensive security controls for REST APIs."
                }
                TextButton(
                    onClick = { onAgentInputChanged(samplePrompt) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.testTag("agent_sample_prompt_button")
                ) {
                    Text("Load Example Prompt", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
                }

                OutlinedTextField(
                    value = agentPromptInput,
                    onValueChange = onAgentInputChanged,
                    placeholder = { Text(activeAgent.promptHint, style = MaterialTheme.typography.bodySmall) },
                    minLines = 3,
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NeonCyan, unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().testTag("agent_prompt_input")
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onRunAgent(activeAgent, agentPromptInput) },
                    enabled = agentPromptInput.isNotBlank() && agentStreamState !is UiState.Loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("run_agent_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCrimson, contentColor = Color.White)
                ) {
                    if (agentStreamState is UiState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("FRIDAY Agent Analyzing…")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Deploy ${activeAgent.title}")
                    }
                }

                AnimatedVisibility(visible = agentStreamState !is UiState.Idle && agentStreamState !is UiState.Loading) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        when (agentStreamState) {
                            is UiState.Streaming -> {
                                Text("LIVE STREAMING RESPONSE…", style = MaterialTheme.typography.labelSmall, color = NeonCyan)
                                Spacer(Modifier.height(6.dp))
                                Text(agentStreamState.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            is UiState.Success -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CyberTagBadge(text = "AGENT BRIEF READY", accentColor = NeonCyan)
                                    Row {
                                        val isSpeakingThis = voiceState.isSpeaking && voiceState.speakingMessageId == "agent_brief"
                                        IconButton(
                                            onClick = {
                                                if (isSpeakingThis) onStopSpeaking()
                                                else onSpeakText(agentStreamState.data, "agent_brief")
                                            },
                                            modifier = Modifier.testTag("agent_speak_button")
                                        ) {
                                            Icon(
                                                imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = stringResource(R.string.voice_speak_cd),
                                                tint = NeonCyan
                                            )
                                        }
                                        IconButton(
                                            onClick = { onSaveAgentBrief("${activeAgent.title} Brief", agentStreamState.data) },
                                            modifier = Modifier.testTag("agent_save_memory_button")
                                        ) {
                                            Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.save_to_memory), tint = NeonCrimson)
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(agentStreamState.data, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            is UiState.Error -> Text(agentStreamState.message, style = MaterialTheme.typography.bodyMedium, color = NeonCrimson)
                            else -> Unit
                        }
                    }
                }
            }
        }

        item {
            SecurityDisclaimerBanner(text = stringResource(R.string.ethical_notice_short), isSimulated = false)
        }
    }
}

@Composable
private fun DashboardSshStatusIndicator(
    sshProfile: SshConnectionProfile,
    sshState: SshConnectionState,
    onClick: () -> Unit
) {
    val (statusLabel, accent, icon) = when {
        sshState.isConnecting -> Triple("SSH: CONNECTING", CyberAmber, Icons.Default.Sync)
        sshState.isConnected -> Triple("SSH: ONLINE", CyberEmerald, Icons.Default.CloudDone)
        else -> Triple("SSH: OFFLINE", NeonCrimson, Icons.Default.CloudOff)
    }
    val endpoint = if (sshProfile.host.isNotBlank()) {
        "${sshProfile.username.ifBlank { "operator" }}@${sshProfile.host}:${sshProfile.port}"
    } else {
        "Remote Linux Server unconfigured • Tap to set up"
    }
    val subline = when {
        sshState.isConnecting -> "Verifying SSH handshake on $endpoint…"
        sshState.isConnected -> "$endpoint • ${sshState.serverInfo.ifBlank { "Authenticated" }}"
        else -> endpoint
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_ssh_status_indicator"),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = statusLabel,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (sshState.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = CyberAmber,
                    strokeWidth = 2.dp
                )
            } else {
                CyberTagBadge(
                    text = if (sshState.isConnected) "LIVE" else "SSH",
                    accentColor = accent
                )
            }
        }
    }
}

@Composable
private fun HudTelemetryChip(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.titleLarge, color = accent)
        }
    }
}

@Composable
private fun HubLaunchCard(
    title: String,
    subtitle: String,
    badge: String,
    icon: ImageVector,
    accent: Color,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CyberHudCard(modifier = modifier.testTag(tag), accentColor = accent, onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(22.dp))
            CyberTagBadge(text = badge, accentColor = accent)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun agentIcon(agent: AgentType): ImageVector = when (agent) {
    AgentType.RESEARCH -> Icons.Default.Search
    AgentType.LEARNING -> Icons.Default.School
    AgentType.CODE_ANALYSIS -> Icons.Default.Code
    AgentType.REPORT_ANALYSIS -> Icons.Default.Analytics
    else -> Icons.Default.Psychology
}

@Composable
private fun DashboardSyncStatusBar(
    syncStatus: com.example.data.model.SyncStatusState,
    isOfflineSession: Boolean,
    onSyncClick: () -> Unit
) {
    val label = when {
        isOfflineSession -> "ROOM LOCAL-ONLY"
        syncStatus.isSyncing -> "SYNCING…"
        syncStatus.isOnline && syncStatus.pendingChangesCount == 0 -> "ROOM + CLOUD SYNCED"
        syncStatus.pendingChangesCount > 0 -> "OFFLINE QUEUE (${syncStatus.pendingChangesCount})"
        else -> "LOCAL VAULT READY"
    }
    val accent = if (syncStatus.isOnline && !isOfflineSession) CyberEmerald else CyberAmber
    Surface(
        onClick = onSyncClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_sync_status_bar"),
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = syncStatus.lastSyncSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            CyberTagBadge(text = if (isOfflineSession) "SIGN IN" else label, accentColor = accent)
        }
    }
}

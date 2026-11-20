package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.QuickCommandStore
import com.example.core.VoiceUiState
import com.example.data.model.AiModelOption
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.CodeLanguage
import com.example.data.model.LoadedProjectFile
import com.example.data.model.NoteCategory
import com.example.data.model.ProposedFilePatch
import com.example.data.model.QuickCommandItem
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalLog
import com.example.data.model.TermuxConnectionMode
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.UiState
import com.example.data.model.WebResearchResult
import com.example.ui.theme.AiModelSelectorRow
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan

enum class StudioToolTab(val label: String, val tag: String) {
    CODING("Coding", "studio_tab_coding"),
    TERMINAL("Terminal", "studio_tab_terminal"),
    RESEARCH("Research", "studio_tab_research"),
    FILES("Files", "studio_tab_files")
}

@Composable
fun ToolsStudioScreen(
    selectedTab: StudioToolTab,
    selectedModel: AiModelOption,
    codeLanguage: CodeLanguage,
    codeEditorText: String,
    codeTaskPrompt: String,
    codingAiState: UiState<String>,
    sandboxResultState: UiState<BridgeExecutionResult>,
    selectedExecutionEnvironment: TerminalEnvironment,
    termuxState: TermuxEnvironmentState,
    sshProfile: SshConnectionProfile,
    sshState: SshConnectionState,
    commandTimeoutSeconds: Int,
    isCommandRunning: Boolean,
    terminalExecState: UiState<BridgeExecutionResult>,
    terminalLogsState: UiState<List<TerminalLog>>,
    webResearchState: UiState<WebResearchResult>,
    loadedFile: LoadedProjectFile?,
    fileAnalysisState: UiState<String>,
    proposedPatch: ProposedFilePatch?,
    voiceState: VoiceUiState,
    onSelectTab: (StudioToolTab) -> Unit,
    onSelectModel: (AiModelOption) -> Unit,
    onSelectCodeLanguage: (CodeLanguage) -> Unit,
    onCodeChanged: (String) -> Unit,
    onCodePromptChanged: (String) -> Unit,
    onRunCodingAction: (String) -> Unit,
    onRunInSandbox: () -> Unit,
    onSelectExecutionEnvironment: (TerminalEnvironment) -> Unit,
    onSelectTermuxMode: (TermuxConnectionMode) -> Unit,
    onUpdateTermuxHttpConfig: (String, String) -> Unit,
    onRefreshTermuxStatus: () -> Unit,
    onVerifyAndConnectTermux: () -> Unit,
    onDisconnectTermux: () -> Unit,
    onStageTermuxToolProbe: () -> Unit,
    onUpdateSshProfile: (SshConnectionProfile, Boolean) -> Unit,
    onConnectAndTestSsh: () -> Unit,
    onDisconnectSsh: () -> Unit,
    onClearSavedSshCredentials: () -> Unit,
    onSelectTimeoutSeconds: (Int) -> Unit,
    onSubmitTerminalApproval: (String, TerminalEnvironment) -> Unit,
    onCancelRunningCommand: () -> Unit,
    onDeleteTerminalLog: (String) -> Unit,
    onRunWebResearch: (String, Boolean) -> Unit,
    onFilePicked: (Uri) -> Unit,
    onAnalyzeFile: (String) -> Unit,
    onSendToCodingEditor: (String) -> Unit,
    onStageEditProposal: (String, String) -> Unit,
    onApproveAndSavePatch: (Uri?) -> Unit,
    onSpeakText: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit,
    onNotifyCopied: () -> Unit,
    onOpenRemoteSettings: () -> Unit = {},
    quickCommands: List<QuickCommandItem> = QuickCommandStore.defaultCommands(),
    onSaveQuickCommand: (String, String) -> Unit = { _, _ -> },
    onDeleteQuickCommand: (String) -> Unit = {},
    onSelectFileChunk: (Int) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tools_studio_screen")
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.tools_hub_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NeonCyan
                    )
                    Text(
                        text = stringResource(R.string.tools_hub_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CyberTagBadge(text = selectedModel.tierBadge, accentColor = NeonCrimson)
            }
            Spacer(Modifier.height(8.dp))
            AiModelSelectorRow(
                selected = selectedModel,
                onSelect = onSelectModel,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            )
        }

        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = NeonCyan
        ) {
            StudioToolTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { onSelectTab(tab) },
                    modifier = Modifier.testTag(tab.tag),
                    text = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            StudioToolTab.CODING -> CodingAgentTab(
                selectedLanguage = codeLanguage,
                codeEditorText = codeEditorText,
                taskPrompt = codeTaskPrompt,
                codingAiState = codingAiState,
                sandboxResultState = sandboxResultState,
                onSelectLanguage = onSelectCodeLanguage,
                onCodeChanged = onCodeChanged,
                onTaskPromptChanged = onCodePromptChanged,
                onRunCodingAction = onRunCodingAction,
                onRunInSandbox = onRunInSandbox,
                onSaveNote = onSaveNote,
                onNotifyCopied = onNotifyCopied
            )

            StudioToolTab.TERMINAL -> TerminalAgentTab(
                selectedEnvironment = selectedExecutionEnvironment,
                termuxState = termuxState,
                sshProfile = sshProfile,
                sshState = sshState,
                timeoutSeconds = commandTimeoutSeconds,
                isCommandRunning = isCommandRunning,
                execState = terminalExecState,
                logsState = terminalLogsState,
                onSelectEnvironment = onSelectExecutionEnvironment,
                onSelectTermuxMode = onSelectTermuxMode,
                onUpdateTermuxHttpConfig = onUpdateTermuxHttpConfig,
                onRefreshTermuxStatus = onRefreshTermuxStatus,
                onVerifyAndConnectTermux = onVerifyAndConnectTermux,
                onDisconnectTermux = onDisconnectTermux,
                onStageTermuxToolProbe = onStageTermuxToolProbe,
                onUpdateSshProfile = onUpdateSshProfile,
                onConnectAndTestSsh = onConnectAndTestSsh,
                onDisconnectSsh = onDisconnectSsh,
                onClearSavedSshCredentials = onClearSavedSshCredentials,
                onSelectTimeoutSeconds = onSelectTimeoutSeconds,
                onSubmitForApproval = onSubmitTerminalApproval,
                onCancelRunningCommand = onCancelRunningCommand,
                onDeleteLog = onDeleteTerminalLog,
                onOpenRemoteSettings = onOpenRemoteSettings,
                quickCommands = quickCommands,
                onSaveQuickCommand = onSaveQuickCommand,
                onDeleteQuickCommand = onDeleteQuickCommand
            )

            StudioToolTab.RESEARCH -> WebResearchTab(
                researchState = webResearchState,
                voiceState = voiceState,
                onRunResearch = onRunWebResearch,
                onSpeakText = onSpeakText,
                onStopSpeaking = onStopSpeaking,
                onSaveNote = onSaveNote
            )

            StudioToolTab.FILES -> FileAgentTab(
                loadedFile = loadedFile,
                fileAnalysisState = fileAnalysisState,
                proposedPatch = proposedPatch,
                onFilePicked = onFilePicked,
                onSelectChunk = onSelectFileChunk,
                onAnalyzeFile = onAnalyzeFile,
                onSendToCodingEditor = { code ->
                    onSendToCodingEditor(code)
                    onSelectTab(StudioToolTab.CODING)
                },
                onStageEditProposal = onStageEditProposal,
                onApproveAndSavePatch = onApproveAndSavePatch,
                onSaveNote = onSaveNote
            )
        }
    }
}

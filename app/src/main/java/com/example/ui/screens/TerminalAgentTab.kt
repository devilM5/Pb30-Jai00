package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.core.QuickCommandStore
import com.example.core.VaultLifecycleGuard
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.ExecutionEnvFilter
import com.example.data.model.ExecutionStatusFilter
import com.example.data.model.QuickCommandItem
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TerminalLog
import com.example.data.model.TermuxConnectionMode
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.UiState
import com.example.data.repository.TermuxCompanionClient
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun TerminalAgentTab(
    selectedEnvironment: TerminalEnvironment,
    termuxState: TermuxEnvironmentState,
    sshProfile: SshConnectionProfile,
    sshState: SshConnectionState,
    timeoutSeconds: Int,
    isCommandRunning: Boolean,
    execState: UiState<BridgeExecutionResult>,
    logsState: UiState<List<TerminalLog>>,
    onSelectEnvironment: (TerminalEnvironment) -> Unit,
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
    onSubmitForApproval: (String, TerminalEnvironment) -> Unit,
    onCancelRunningCommand: () -> Unit,
    onDeleteLog: (String) -> Unit,
    onOpenRemoteSettings: () -> Unit = {},
    quickCommands: List<QuickCommandItem> = QuickCommandStore.defaultCommands(),
    onSaveQuickCommand: (String, String) -> Unit = { _, _ -> },
    onDeleteQuickCommand: (String) -> Unit = {}
) {
    var commandInput by rememberSaveable { mutableStateOf("") }
    var historySearchQuery by rememberSaveable { mutableStateOf("") }
    var envFilter by rememberSaveable { mutableStateOf(ExecutionEnvFilter.ALL) }
    var statusFilter by rememberSaveable { mutableStateOf(ExecutionStatusFilter.ALL) }

    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        VaultLifecycleGuard.clearExternalFlow()
        onRefreshTermuxStatus()
    }

    val timeoutOptions = listOf(15, 30, 60, 120)

    val activeConnected = when (selectedEnvironment) {
        TerminalEnvironment.LINUX_SERVER_BRIDGE -> sshState.isConnected
        else -> termuxState.isConnected
    }
    val activeTargetBanner = when (selectedEnvironment) {
        TerminalEnvironment.LINUX_SERVER_BRIDGE ->
            if (sshProfile.host.isNotBlank()) "REMOTE SSH • ${sshProfile.username}@${sshProfile.host}:${sshProfile.port}"
            else "REMOTE SSH • Unconfigured host"
        else ->
            "LOCAL TERMUX • ${termuxState.companionMode.displayName}"
    }

    val allLogs = (logsState as? UiState.Success)?.data.orEmpty()
    val filteredLogs = remember(allLogs, historySearchQuery, envFilter, statusFilter) {
        filterTerminalLogs(allLogs, historySearchQuery, envFilter, statusFilter)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("terminal_agent_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = stringResource(R.string.terminal_approval_warning),
                isSimulated = false
            )
        }

        // Execution Environment Switcher
        item {
            ExecutionEnvironmentSwitcher(
                selectedEnvironment = selectedEnvironment,
                termuxConnected = termuxState.isConnected,
                sshConnected = sshState.isConnected,
                onSelectEnvironment = onSelectEnvironment
            )
        }

        // Active Environment Setup & Real Connection Card
        item {
            if (selectedEnvironment == TerminalEnvironment.LINUX_SERVER_BRIDGE) {
                SshConnectionCard(
                    profile = sshProfile,
                    sshState = sshState,
                    onProfileChanged = onUpdateSshProfile,
                    onConnectAndTest = onConnectAndTestSsh,
                    onDisconnect = onDisconnectSsh,
                    onClearSavedCredentials = onClearSavedSshCredentials,
                    onOpenRemoteSettings = onOpenRemoteSettings
                )
            } else {
                TermuxEnvironmentCard(
                    termuxState = termuxState,
                    onSelectMode = onSelectTermuxMode,
                    onUpdateCompanionHttp = onUpdateTermuxHttpConfig,
                    onRequestRunCommandPermission = {
                        VaultLifecycleGuard.markExternalFlowLaunched()
                        termuxPermissionLauncher.launch(TermuxCompanionClient.TERMUX_PERMISSION)
                    },
                    onVerifyAndConnect = onVerifyAndConnectTermux,
                    onDisconnect = onDisconnectTermux,
                    onStageToolProbe = onStageTermuxToolProbe
                )
            }
        }

        // Quick Command Panel (Save frequent commands + 1-tap approval execute)
        item {
            QuickCommandPanel(
                quickCommands = quickCommands,
                selectedEnvironment = selectedEnvironment,
                isCommandRunning = isCommandRunning,
                onOneTapExecute = { item ->
                    onSubmitForApproval(item.command, selectedEnvironment)
                },
                onLoadIntoEditor = { cmd -> commandInput = cmd },
                onSaveQuickCommand = onSaveQuickCommand,
                onDeleteQuickCommand = onDeleteQuickCommand
            )
        }

        // Command Editor, Timeout Control & Execution Control Card
        item {
            CyberHudCard(accentColor = NeonCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "COMMAND CONTROL",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonCyan
                    )
                    CyberTagBadge(
                        text = if (activeConnected) "TARGET READY" else "DISCONNECTED",
                        accentColor = if (activeConnected) CyberEmerald else CyberAmber
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = activeTargetBanner,
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = { commandInput = it },
                    textStyle = MonoCodeStyle,
                    placeholder = { Text("Enter Linux, Python, Bash, or authorized Nmap command…") },
                    minLines = 2,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("terminal_command_input")
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Timeout:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    timeoutOptions.forEach { sec ->
                        val selected = timeoutSeconds == sec
                        Box(
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) NeonCyan.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                                .border(
                                    1.dp,
                                    if (selected) NeonCyan else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectTimeoutSeconds(sec) }
                                .testTag("timeout_chip_$sec")
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${sec}s", style = MaterialTheme.typography.labelSmall, color = if (selected) NeonCyan else Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (isCommandRunning) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonCyan, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Running (${timeoutSeconds}s limit)…")
                        }
                        OutlinedButton(
                            onClick = onCancelRunningCommand,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCrimson),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("terminal_cancel_execution_button")
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                } else {
                    Button(
                        onClick = { onSubmitForApproval(commandInput, selectedEnvironment) },
                        enabled = commandInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCrimson, contentColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("terminal_submit_approval_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Review & Request Approval")
                    }
                }
            }
        }

        // Latest Real Execution Result Console
        if (execState is UiState.Success) {
            val res = execState.data
            item {
                val badgeColor = when (res.status) {
                    TerminalExecutionStatus.EXECUTED -> CyberEmerald
                    TerminalExecutionStatus.TIMEOUT,
                    TerminalExecutionStatus.BRIDGE_UNAVAILABLE -> CyberAmber
                    else -> NeonCrimson
                }
                CyberHudCard(accentColor = badgeColor) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CyberTagBadge(
                            text = "${res.environment.badge} • ${res.status.label.uppercase()}",
                            accentColor = badgeColor
                        )
                        Text(
                            text = "EXIT ${res.exitCode} • ${res.executionTimeMillis}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .padding(10.dp)
                    ) {
                        val combined = buildString {
                            if (res.stdout.isNotBlank()) append(res.stdout)
                            if (res.stderr.isNotBlank()) {
                                if (isNotEmpty()) append("\n")
                                append("STDERR: ").append(res.stderr)
                            }
                        }.ifBlank { "No stdout/stderr returned." }
                        Text(
                            text = combined,
                            style = MonoCodeStyle,
                            color = badgeColor
                        )
                    }
                }
            }
        }

        // Audited & Filterable Execution History from Firestore
        item {
            ExecutionHistoryFilterBar(
                searchQuery = historySearchQuery,
                envFilter = envFilter,
                statusFilter = statusFilter,
                totalCount = allLogs.size,
                filteredCount = filteredLogs.size,
                onSearchQueryChange = { historySearchQuery = it },
                onEnvFilterChange = { envFilter = it },
                onStatusFilterChange = { statusFilter = it }
            )
        }

        when (logsState) {
            is UiState.Loading -> item { CircularProgressIndicator(color = NeonCyan) }
            is UiState.Error -> item {
                Text(logsState.message, style = MaterialTheme.typography.bodySmall, color = NeonCrimson)
            }
            is UiState.Success -> {
                if (filteredLogs.isEmpty()) {
                    item {
                        CyberHudCard(accentColor = MaterialTheme.colorScheme.outline) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = NeonCyan)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (allLogs.isEmpty()) {
                                        "No command history recorded yet. Every approved, cancelled, or timed-out run is logged cleanly here."
                                    } else {
                                        "No terminal or SSH execution logs match your current search/filter."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(filteredLogs, key = { it.id.ifBlank { it.hashCode().toString() } }) { log ->
                        ExecutionLogItemCard(
                            log = log,
                            onDeleteLog = onDeleteLog
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

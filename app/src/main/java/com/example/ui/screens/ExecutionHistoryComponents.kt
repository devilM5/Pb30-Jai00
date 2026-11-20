package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ExecutionEnvFilter
import com.example.data.model.ExecutionStatusFilter
import com.example.data.model.QuickCommandItem
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TerminalLog
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberObsidian
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan

@Composable
fun QuickCommandPanel(
    quickCommands: List<QuickCommandItem>,
    selectedEnvironment: TerminalEnvironment,
    isCommandRunning: Boolean,
    onOneTapExecute: (QuickCommandItem) -> Unit,
    onLoadIntoEditor: (String) -> Unit,
    onSaveQuickCommand: (String, String) -> Unit,
    onDeleteQuickCommand: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }

    CyberHudCard(accentColor = NeonCrimson) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = NeonCrimson,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "QUICK COMMANDS (${selectedEnvironment.badge})",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
            }
            TextButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("quick_cmd_add_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save New", color = NeonCyan, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = "Tap Execute to open mandatory approval dialog on ${selectedEnvironment.displayName}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (quickCommands.isEmpty()) {
            Text(
                text = "No quick commands saved. Tap 'Save New' to pin frequent commands like nmap -sV.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quickCommands.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                            Text(
                                text = item.command,
                                style = MonoCodeStyle,
                                color = NeonCyan
                            )
                        }
                        IconButton(
                            onClick = { onLoadIntoEditor(item.command) },
                            modifier = Modifier.testTag("quick_cmd_edit_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Load ${item.label} into editor",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onOneTapExecute(item) },
                            enabled = !isCommandRunning,
                            modifier = Modifier.testTag("quick_cmd_run_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Approve and execute ${item.label}",
                                tint = if (isCommandRunning) MaterialTheme.colorScheme.onSurfaceVariant else CyberEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteQuickCommand(item.id) },
                            modifier = Modifier.testTag("quick_cmd_delete_${item.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Delete ${item.label}",
                                tint = NeonCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var labelInput by remember { mutableStateOf("") }
        var commandInput by remember { mutableStateOf("nmap -sV 192.168.1.1") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = CyberObsidian,
            title = {
                Text(
                    text = "SAVE QUICK COMMAND",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it.take(48) },
                        label = { Text("Command Name / Label") },
                        placeholder = { Text("e.g. Service Version Audit") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_cmd_dialog_label")
                    )
                    OutlinedTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it.take(400) },
                        label = { Text("Shell / Security Command") },
                        textStyle = MonoCodeStyle,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_cmd_dialog_command")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveQuickCommand(labelInput, commandInput)
                        showAddDialog = false
                    },
                    enabled = labelInput.isNotBlank() && commandInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier.testTag("quick_cmd_dialog_save")
                ) {
                    Text("Save Command")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ExecutionHistoryFilterBar(
    searchQuery: String,
    envFilter: ExecutionEnvFilter,
    statusFilter: ExecutionStatusFilter,
    totalCount: Int,
    filteredCount: Int,
    onSearchQueryChange: (String) -> Unit,
    onEnvFilterChange: (ExecutionEnvFilter) -> Unit,
    onStatusFilterChange: (ExecutionStatusFilter) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EXECUTION HISTORY ($filteredCount/$totalCount)",
                style = MaterialTheme.typography.titleSmall,
                color = NeonCyan
            )
            CyberTagBadge(text = "REDACTED AUDIT LOGS", accentColor = CyberEmerald)
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = NeonCyan)
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            placeholder = { Text("Search command, output, nmap, ssh, termux…") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("terminal_history_search_input")
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExecutionEnvFilter.entries.forEach { env ->
                val selected = env == envFilter
                FilterPillChip(
                    text = env.label,
                    selected = selected,
                    accent = NeonCyan,
                    testTag = "history_filter_env_${env.name.lowercase()}",
                    onClick = { onEnvFilterChange(env) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExecutionStatusFilter.entries.forEach { st ->
                val selected = st == statusFilter
                FilterPillChip(
                    text = st.label,
                    selected = selected,
                    accent = NeonCrimson,
                    testTag = "history_filter_status_${st.name.lowercase()}",
                    onClick = { onStatusFilterChange(st) }
                )
            }
        }
    }
}

@Composable
private fun FilterPillChip(
    text: String,
    selected: Boolean,
    accent: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (selected) accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else Color.White
        )
    }
}

fun filterTerminalLogs(
    logs: List<TerminalLog>,
    query: String,
    envFilter: ExecutionEnvFilter,
    statusFilter: ExecutionStatusFilter
): List<TerminalLog> {
    val cleanQuery = query.trim().lowercase()
    return logs.filter { log ->
        val envMatches = when (envFilter) {
            ExecutionEnvFilter.ALL -> true
            ExecutionEnvFilter.TERMUX -> log.environmentEnum == TerminalEnvironment.TERMUX_BRIDGE
            ExecutionEnvFilter.SSH -> log.environmentEnum == TerminalEnvironment.LINUX_SERVER_BRIDGE
            ExecutionEnvFilter.SANDBOX -> log.environmentEnum == TerminalEnvironment.CODE_SANDBOX
        }
        val statusMatches = when (statusFilter) {
            ExecutionStatusFilter.ALL -> true
            ExecutionStatusFilter.EXECUTED -> log.statusEnum == TerminalExecutionStatus.EXECUTED
            ExecutionStatusFilter.ERROR -> log.statusEnum == TerminalExecutionStatus.ERROR
            ExecutionStatusFilter.TIMEOUT -> log.statusEnum == TerminalExecutionStatus.TIMEOUT
            ExecutionStatusFilter.CANCELLED -> log.statusEnum == TerminalExecutionStatus.CANCELLED
            ExecutionStatusFilter.BLOCKED -> log.statusEnum == TerminalExecutionStatus.BLOCKED
            ExecutionStatusFilter.OFFLINE -> log.statusEnum == TerminalExecutionStatus.BRIDGE_UNAVAILABLE
        }
        val textMatches = cleanQuery.isEmpty() ||
            log.command.lowercase().contains(cleanQuery) ||
            log.output.lowercase().contains(cleanQuery) ||
            log.environmentEnum.displayName.lowercase().contains(cleanQuery) ||
            log.statusEnum.label.lowercase().contains(cleanQuery)
        envMatches && statusMatches && textMatches
    }
}

@Composable
fun ExecutionLogItemCard(
    log: TerminalLog,
    onDeleteLog: (String) -> Unit
) {
    val badgeColor = when (log.statusEnum) {
        TerminalExecutionStatus.EXECUTED -> CyberEmerald
        TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
        TerminalExecutionStatus.TIMEOUT -> CyberAmber
        else -> NeonCrimson
    }
    CyberHudCard(accentColor = badgeColor) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CyberTagBadge(
                    text = "${log.environmentEnum.badge} • ${log.statusEnum.label}",
                    accentColor = badgeColor
                )
                CyberTagBadge(
                    text = if (log.approvedByUser) "APPROVED • EXIT ${log.exitCode}" else "NOT EXECUTED",
                    accentColor = if (log.approvedByUser) NeonCyan else CyberAmber
                )
            }
            IconButton(onClick = { onDeleteLog(log.id) }) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete log",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(text = "$ ${log.command}", style = MonoCodeStyle, color = NeonCyan)
        if (log.output.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = log.output, style = MonoCodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.example.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.CodeLanguage
import com.example.data.model.NoteCategory
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.UiState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun CodingAgentTab(
    selectedLanguage: CodeLanguage,
    codeEditorText: String,
    taskPrompt: String,
    codingAiState: UiState<String>,
    sandboxResultState: UiState<BridgeExecutionResult>,
    onSelectLanguage: (CodeLanguage) -> Unit,
    onCodeChanged: (String) -> Unit,
    onTaskPromptChanged: (String) -> Unit,
    onRunCodingAction: (String) -> Unit,
    onRunInSandbox: () -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit,
    onNotifyCopied: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val lineCount = codeEditorText.lines().size

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("coding_agent_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = stringResource(R.string.sandbox_disclaimer),
                isSimulated = false
            )
        }

        // Language Pills
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CodeLanguage.entries.forEach { lang ->
                    val selected = lang == selectedLanguage
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) NeonCyan.copy(alpha = 0.18f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .border(
                                1.dp,
                                if (selected) NeonCyan else MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectLanguage(lang) }
                            .testTag("code_lang_${lang.name.lowercase()}")
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = lang.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Prompt & Code Generation Actions
        item {
            CyberHudCard(accentColor = NeonCyan) {
                Text(
                    text = "PROMPT OR ERROR TRACE (${selectedLanguage.displayName.uppercase()})",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = taskPrompt,
                    onValueChange = onTaskPromptChanged,
                    placeholder = {
                        Text("Describe code to generate, or paste an error/stack trace to debug…")
                    },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("coding_prompt_input")
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onRunCodingAction("GENERATE") },
                        enabled = codingAiState !is UiState.Loading,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("coding_generate_button")
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Generate")
                    }
                    OutlinedButton(
                        onClick = { onRunCodingAction("EXPLAIN") },
                        enabled = codeEditorText.isNotBlank() && codingAiState !is UiState.Loading,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("coding_explain_button")
                    ) {
                        Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Line-by-Line")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { onRunCodingAction("DEBUG") },
                    enabled = (codeEditorText.isNotBlank() || taskPrompt.isNotBlank()) && codingAiState !is UiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("coding_debug_button")
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = NeonCrimson, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Debug Errors & Suggest Fixes", color = NeonCrimson)
                }
            }
        }

        // Code Editor Card
        item {
            CyberHudCard(accentColor = MaterialTheme.colorScheme.outline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CyberTagBadge(
                        text = "${selectedLanguage.displayName.uppercase()} EDITOR • $lineCount LINES",
                        accentColor = NeonCyan
                    )
                    Row {
                        IconButton(
                            onClick = { onSelectLanguage(selectedLanguage) },
                            modifier = Modifier.testTag("coding_reset_template_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reset template", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(codeEditorText))
                                onNotifyCopied()
                            },
                            modifier = Modifier.testTag("coding_copy_button")
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy code", tint = NeonCyan)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = codeEditorText,
                    onValueChange = onCodeChanged,
                    textStyle = MonoCodeStyle.copy(color = Color(0xFFE6F4FF)),
                    minLines = 8,
                    maxLines = 16,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF040711),
                        unfocusedContainerColor = Color(0xFF040711),
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("coding_editor_input")
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onRunInSandbox,
                    enabled = codeEditorText.isNotBlank() && sandboxResultState !is UiState.Loading,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCrimson, contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("coding_sandbox_run_button")
                ) {
                    if (sandboxResultState is UiState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Executing in Isolated Sandbox…")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Execute in Isolated Sandbox")
                    }
                }
            }
        }

        // Real Sandbox Execution Output Card (no fake execution!)
        if (sandboxResultState is UiState.Success) {
            val res = sandboxResultState.data
            item {
                CyberHudCard(
                    accentColor = when (res.status) {
                        TerminalExecutionStatus.EXECUTED -> CyberEmerald
                        TerminalExecutionStatus.BRIDGE_UNAVAILABLE -> CyberAmber
                        else -> NeonCrimson
                    }
                ) {
                    CyberTagBadge(
                        text = "SANDBOX: ${res.status.label.uppercase()} (EXIT ${res.exitCode})",
                        accentColor = if (res.executed) CyberEmerald else CyberAmber
                    )
                    Spacer(Modifier.height(8.dp))
                    if (res.stdout.isNotBlank()) {
                        Text(text = res.stdout, style = MonoCodeStyle, color = CyberEmerald)
                    }
                    if (res.stderr.isNotBlank()) {
                        Text(text = res.stderr, style = MonoCodeStyle, color = CyberAmber)
                    }
                }
            }
        }

        // AI Explanation / Debug Output Card
        if (codingAiState !is UiState.Idle) {
            item {
                AnimatedVisibility(visible = true) {
                    CyberHudCard(accentColor = NeonCyan) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CyberTagBadge(text = "CODING AGENT OUTPUT", accentColor = NeonCyan)
                            if (codingAiState is UiState.Success) {
                                Row {
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(codingAiState.data))
                                            onNotifyCopied()
                                        }
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy response", tint = NeonCyan)
                                    }
                                    IconButton(
                                        onClick = {
                                            onSaveNote("${selectedLanguage.displayName} Code Brief", codingAiState.data, NoteCategory.AGENT_BRIEF)
                                        }
                                    ) {
                                        Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.save_to_memory), tint = NeonCrimson)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        when (codingAiState) {
                            is UiState.Loading -> CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(22.dp))
                            is UiState.Streaming -> Text(codingAiState.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            is UiState.Success -> Text(codingAiState.data, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            is UiState.Error -> Text(codingAiState.message, style = MaterialTheme.typography.bodyMedium, color = NeonCrimson)
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

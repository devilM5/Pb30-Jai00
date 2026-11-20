package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.LoadedProjectFile
import com.example.data.model.NoteCategory
import com.example.data.model.ProposedFilePatch
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
fun FileAgentTab(
    loadedFile: LoadedProjectFile?,
    fileAnalysisState: UiState<String>,
    proposedPatch: ProposedFilePatch?,
    onFilePicked: (Uri) -> Unit,
    onAnalyzeFile: (String) -> Unit,
    onSendToCodingEditor: (String) -> Unit,
    onStageEditProposal: (String, String) -> Unit,
    onApproveAndSavePatch: (Uri?) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    val context = LocalContext.current
    var instruction by rememberSaveable { mutableStateOf("") }
    var editableDraft by rememberSaveable(loadedFile?.uriString, loadedFile?.content) {
        mutableStateOf(loadedFile?.content.orEmpty())
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(loadedFile?.mimeType ?: "text/plain")
    ) { uri ->
        if (uri != null) onApproveAndSavePatch(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("file_agent_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = "Zero-permission Storage Access Framework: Pick code, configs, or logs safely. File edits require explicit approval.",
                isSimulated = false
            )
        }

        item {
            CyberHudCard(accentColor = NeonCyan) {
                Button(
                    onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("file_open_picker_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (loadedFile == null) "Open Code or Document" else "Open Another File")
                }

                if (loadedFile != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = NeonCyan)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(loadedFile.fileName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Text(
                                text = "${loadedFile.mimeType} • ${loadedFile.sizeBytes} bytes" +
                                    if (loadedFile.truncated) " (Truncated preview)" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (loadedFile.truncated) CyberAmber else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editableDraft,
                        onValueChange = { editableDraft = it },
                        textStyle = MonoCodeStyle,
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_content_editor")
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = instruction,
                        onValueChange = { instruction = it },
                        placeholder = { Text("Instruction (e.g., Audit for bugs, suggest refactor, explain…)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_instruction_input")
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onAnalyzeFile(instruction) },
                            enabled = fileAnalysisState !is UiState.Loading,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonCrimson, contentColor = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("file_analyze_button")
                        ) {
                            Text("Analyze File")
                        }
                        OutlinedButton(
                            onClick = { onSendToCodingEditor(editableDraft) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Edit in Coding")
                        }
                    }

                    if (editableDraft != loadedFile.content) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                onStageEditProposal(editableDraft, "Operator edited ${loadedFile.fileName}")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("file_stage_patch_button")
                        ) {
                            Text("Review Edit Before Saving", color = CyberAmber)
                        }
                    }
                }
            }
        }

        if (proposedPatch != null) {
            item {
                CyberHudCard(accentColor = if (proposedPatch.applied) CyberEmerald else CyberAmber) {
                    CyberTagBadge(
                        text = if (proposedPatch.applied) "APPROVED & SAVED" else "PENDING FILE SAVE APPROVAL",
                        accentColor = if (proposedPatch.applied) CyberEmerald else CyberAmber
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(proposedPatch.summary, style = MaterialTheme.typography.bodySmall, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApproveAndSavePatch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("file_approve_overwrite_button")
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Approve & Save")
                        }
                        OutlinedButton(
                            onClick = { saveCopyLauncher.launch("edited_${proposedPatch.fileName}") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Save As Copy")
                        }
                    }
                }
            }
        }

        if (fileAnalysisState !is UiState.Idle) {
            item {
                CyberHudCard(accentColor = NeonCyan) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CyberTagBadge(text = "FILE AGENT REPORT", accentColor = NeonCyan)
                        if (fileAnalysisState is UiState.Success && loadedFile != null) {
                            IconButton(
                                onClick = {
                                    onSaveNote("File Audit: ${loadedFile.fileName}", fileAnalysisState.data, NoteCategory.AGENT_BRIEF)
                                }
                            ) {
                                Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.save_to_memory), tint = NeonCrimson)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (fileAnalysisState) {
                        is UiState.Loading -> CircularProgressIndicator(color = NeonCyan)
                        is UiState.Streaming -> Text(fileAnalysisState.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        is UiState.Success -> Text(fileAnalysisState.data, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        is UiState.Error -> Text(fileAnalysisState.message, style = MaterialTheme.typography.bodyMedium, color = NeonCrimson)
                        else -> Unit
                    }
                }
            }
        }
    }
}

package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.curriculum.CyberCurriculumData
import com.example.data.model.NoteCategory
import com.example.data.model.UiState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun SecurityAgentScreen(
    checkedChecklistIds: Set<String>,
    reportAnalysisState: UiState<String>,
    onToggleChecklistItem: (String) -> Unit,
    onAnalyzeReport: (String, Boolean) -> Unit,
    onAskFridayAboutTool: (String) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit,
    onStageNmapCommand: (String) -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Ethics", "Nmap", "Kali", "Audit", "Reports")

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SECURITY AGENT",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                CyberTagBadge(
                    text = "ETHICAL ONLY",
                    accentColor = CyberEmerald
                )
            }
            Spacer(Modifier.height(6.dp))
            SecurityDisclaimerBanner(
                text = "Educational & Authorized Defense Only. Any live Nmap audit requires a connected controlled Linux bridge and explicit approval.",
                isSimulated = false
            )
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                val tag = when (index) {
                    1 -> "sec_tab_nmap"
                    2 -> "sec_tab_kali_tools"
                    3 -> "sec_tab_checklists"
                    else -> "sec_tab_${title.lowercase()}"
                }
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    modifier = Modifier.testTag(tag),
                    text = {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> EthicalHackingGuideTab(onSaveNote = onSaveNote)
            1 -> AuthorizedNmapTab(
                onStageCommandForApproval = onStageNmapCommand,
                onAskFridayAboutTool = onAskFridayAboutTool
            )
            2 -> KaliToolsTab(onAskFridayAboutTool = onAskFridayAboutTool)
            3 -> ChecklistsTab(
                checkedIds = checkedChecklistIds,
                onToggleItem = onToggleChecklistItem
            )
            4 -> ReportAnalyzerTab(
                reportAnalysisState = reportAnalysisState,
                onAnalyzeReport = onAnalyzeReport,
                onSaveNote = onSaveNote
            )
        }
    }
}

@Composable
private fun AuthorizedNmapTab(
    onStageCommandForApproval: (String) -> Unit,
    onAskFridayAboutTool: (String) -> Unit
) {
    var targetInput by rememberSaveable { mutableStateOf("192.168.1.0/24") }
    var selectedProfileIndex by rememberSaveable { mutableIntStateOf(0) }
    var explicitAuthChecked by rememberSaveable { mutableStateOf(false) }

    val profiles = listOf(
        Triple("Host Discovery (-sn)", "-sn", "Safe ping/ARP discovery without probing service ports."),
        Triple("Fast Port Audit (-F)", "-F -sT", "Checks top 100 well-known TCP ports on owned lab targets."),
        Triple("Service Fingerprint (-sV)", "-sV --top-ports 50", "Identifies open service versions for patch auditing."),
        Triple("Safe Script Check", "-sV --script=safe -p 22,80,443", "Runs non-intrusive Nmap NSE safety checks only.")
    )

    val sanitizedTarget = targetInput.trim().replace(Regex("[^a-zA-Z0-9.:/\\-]"), "")
    val isPrivateTarget = com.example.core.CommandPolicyValidator.isPrivateOrLabTarget(sanitizedTarget)
    val builtCommand = "nmap ${profiles[selectedProfileIndex].second} $sanitizedTarget".trim()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("authorized_nmap_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = stringResource(R.string.nmap_authorization_warning),
                isSimulated = false
            )
        }
        item {
            CyberHudCard(accentColor = MaterialTheme.colorScheme.primary) {
                Text(
                    text = "AUTHORIZED NMAP AUDIT BUILDER",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = targetInput,
                    onValueChange = { targetInput = it },
                    label = { Text("Authorized Lab IP / Subnet / Hostname") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("nmap_target_input")
                )
                Spacer(Modifier.height(6.dp))
                CyberTagBadge(
                    text = if (isPrivateTarget) "PRIVATE / LOOPBACK LAB SUBNET" else "EXTERNAL TARGET • WRITTEN AUTH REQUIRED",
                    accentColor = if (isPrivateTarget) CyberEmerald else CyberAmber
                )
                Spacer(Modifier.height(10.dp))
                profiles.forEachIndexed { idx, (label, _, desc) ->
                    val chosen = idx == selectedProfileIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedProfileIndex = idx }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (chosen) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = label,
                            tint = if (chosen) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(builtCommand, style = MonoCodeStyle, color = CyberEmerald)
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { explicitAuthChecked = !explicitAuthChecked }
                        .testTag("nmap_auth_confirm_row")
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (explicitAuthChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = "Confirm authorization",
                        tint = if (explicitAuthChecked) CyberEmerald else MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "I own or hold explicit written authorization to audit this target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onStageCommandForApproval(builtCommand) },
                    enabled = explicitAuthChecked && sanitizedTarget.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("nmap_stage_approval_button")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stage for Controlled Linux Approval")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        onAskFridayAboutTool("Explain what `$builtCommand` checks defensively and how to remediate open ports.")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask FRIDAY What This Audit Checks")
                }
            }
        }
    }
}

@Composable
private fun EthicalHackingGuideTab(
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CyberHudCard(accentColor = MaterialTheme.colorScheme.secondary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Gavel,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "CORE RULE: PERMISSION FIRST",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Ethical hacking (authorized security testing) improves defense with explicit written permission from the system owner. Never scan, probe, or test systems you do not own or have signed authorization for.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        items(CyberCurriculumData.ethicalHackingPhases) { (phaseTitle, detail) ->
            CyberHudCard(accentColor = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = phaseTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { onSaveNote(phaseTitle, detail, NoteCategory.GENERAL) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.BookmarkAdd,
                            contentDescription = stringResource(R.string.save_to_memory),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KaliToolsTab(
    onAskFridayAboutTool: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = "Simulated Lab Previews below are static educational examples — no real port scan or packet capture is executed.",
                isSimulated = true
            )
        }

        items(CyberCurriculumData.kaliTools) { tool ->
            CyberHudCard(accentColor = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tool.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    CyberTagBadge(
                        text = stringResource(R.string.sim_lab_badge),
                        accentColor = CyberAmber
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = tool.purpose,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Safe Lab Syntax:",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberEmerald
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = tool.safeSyntax,
                        style = MonoCodeStyle,
                        color = CyberEmerald
                    )
                }

                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberAmber.copy(alpha = 0.08f))
                        .border(1.dp, CyberAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = tool.simulatedLabOutput,
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Authorization Rule: ${tool.authorizationRule}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onAskFridayAboutTool("Explain ${tool.name} (${tool.safeSyntax}) step-by-step for defensive authorized lab testing.")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask FRIDAY to Explain Flags")
                }
            }
        }
    }
}

@Composable
private fun ChecklistsTab(
    checkedIds: Set<String>,
    onToggleItem: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(CyberCurriculumData.checklists) { checklist ->
            val doneCount = checklist.items.count { it.id in checkedIds }
            val total = checklist.items.size
            val progress = if (total > 0) doneCount.toFloat() / total else 0f

            CyberHudCard(accentColor = CyberEmerald) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = checklist.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${checklist.domain} • $doneCount/$total verified",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberEmerald
                        )
                    }
                    CyberTagBadge(
                        text = "${(progress * 100).toInt()}%",
                        accentColor = CyberEmerald
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = CyberEmerald,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
                Spacer(Modifier.height(10.dp))

                checklist.items.forEach { item ->
                    val checked = item.id in checkedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onToggleItem(item.id) }
                            .testTag("checklist_item_${item.id}")
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = item.title,
                            tint = if (checked) CyberEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (checked) CyberEmerald else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = item.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportAnalyzerTab(
    reportAnalysisState: UiState<String>,
    onAnalyzeReport: (String, Boolean) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    var reportInput by rememberSaveable { mutableStateOf("") }
    var isSimulatedSample by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = if (isSimulatedSample) {
                    "Current input is a SIMULATED LAB SAMPLE for learning. Real reports must belong to systems you are authorized to audit."
                } else {
                    "Authorized Scan Report Analyzer: Paste your own Nmap, OWASP ZAP, or HTTP header output for AI remediation triage."
                },
                isSimulated = isSimulatedSample
            )
        }

        item {
            CyberHudCard(accentColor = MaterialTheme.colorScheme.primary) {
                Text(
                    text = "LOAD SIMULATED LAB SAMPLES OR PASTE AUTHORIZED REPORT",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                CyberCurriculumData.sampleReports.forEach { sample ->
                    TextButton(
                        onClick = {
                            reportInput = sample.rawContent
                            isSimulatedSample = true
                        },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("load_${sample.id}")
                    ) {
                        Text(
                            text = "• ${sample.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberAmber
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = reportInput,
                    onValueChange = {
                        reportInput = it
                        isSimulatedSample = it.contains("SIMULATED", ignoreCase = true)
                    },
                    placeholder = {
                        Text("Paste authorized Nmap, Nikto, ZAP, or HTTP header logs here…")
                    },
                    minLines = 5,
                    maxLines = 10,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("report_input_field")
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onAnalyzeReport(reportInput, isSimulatedSample) },
                    enabled = reportInput.isNotBlank() && reportAnalysisState !is UiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("analyze_report_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary
                    )
                ) {
                    if (reportAnalysisState is UiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("FRIDAY Analyzing Report…")
                    } else {
                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Analyze with FRIDAY")
                    }
                }
            }
        }

        if (reportAnalysisState !is UiState.Idle && reportAnalysisState !is UiState.Loading) {
            item {
                CyberHudCard(
                    accentColor = if (isSimulatedSample) CyberAmber else CyberEmerald
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CyberTagBadge(
                            text = if (isSimulatedSample) stringResource(R.string.sim_lab_badge)
                            else stringResource(R.string.real_ai_badge),
                            accentColor = if (isSimulatedSample) CyberAmber else CyberEmerald
                        )
                        if (reportAnalysisState is UiState.Success) {
                            IconButton(
                                onClick = {
                                    onSaveNote(
                                        "Security Report Analysis",
                                        reportAnalysisState.data,
                                        NoteCategory.AGENT_BRIEF
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkAdd,
                                    contentDescription = stringResource(R.string.save_to_memory),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (reportAnalysisState) {
                        is UiState.Streaming -> Text(
                            text = reportAnalysisState.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        is UiState.Success -> Text(
                            text = reportAnalysisState.data,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        is UiState.Error -> Text(
                            text = reportAnalysisState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        else -> Unit
                    }
                }
            }
        }
    }
}

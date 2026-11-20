package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.VoiceUiState
import com.example.data.model.NoteCategory
import com.example.data.model.UiState
import com.example.data.model.WebResearchResult
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun WebResearchTab(
    researchState: UiState<WebResearchResult>,
    voiceState: VoiceUiState,
    onRunResearch: (String, Boolean) -> Unit,
    onSpeakText: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var explainInHindi by rememberSaveable { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("web_research_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SecurityDisclaimerBanner(
                text = "Live Web Research uses Firebase AI Logic with Google Search Grounding & URL Context to summarize websites and documentation.",
                isSimulated = false
            )
        }

        item {
            CyberHudCard(accentColor = NeonCyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = NeonCyan)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "WEB RESEARCH & URL SUMMARIZER",
                        style = MaterialTheme.typography.titleSmall,
                        color = NeonCyan
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Enter a topic, CVE, or paste https:// documentation URL…") },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("research_query_input")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Explain findings in Hindi (हिन्दी)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(
                        checked = explainInHindi,
                        onCheckedChange = { explainInHindi = it },
                        modifier = Modifier.testTag("research_hindi_switch")
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onRunResearch(query, explainInHindi) },
                    enabled = query.isNotBlank() && researchState !is UiState.Loading,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("research_run_button")
                ) {
                    if (researchState is UiState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Searching Web & Grounding…")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Search & Summarize")
                    }
                }
            }
        }

        when (researchState) {
            is UiState.Error -> item {
                CyberHudCard(accentColor = NeonCrimson) {
                    Text(researchState.message, style = MaterialTheme.typography.bodyMedium, color = NeonCrimson)
                }
            }
            is UiState.Success -> {
                val res = researchState.data
                item {
                    CyberHudCard(accentColor = CyberEmerald) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CyberTagBadge(
                                text = if (res.explainedInHindi) "GROUNDED BRIEF • हिन्दी" else "GROUNDED WEB BRIEF",
                                accentColor = CyberEmerald
                            )
                            Row {
                                val speaking = voiceState.isSpeaking && voiceState.speakingMessageId == "web_research_reply"
                                IconButton(
                                    onClick = {
                                        if (speaking) onStopSpeaking()
                                        else onSpeakText(res.answer, "web_research_reply")
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (speaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                        contentDescription = stringResource(R.string.voice_speak_cd),
                                        tint = NeonCyan
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        onSaveNote("Research: ${res.query.take(60)}", res.answer, NoteCategory.AGENT_BRIEF)
                                    }
                                ) {
                                    Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.save_to_memory), tint = NeonCrimson)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(res.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)

                        if (res.citations.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text("VERIFIED WEB SOURCES", style = MaterialTheme.typography.labelMedium, color = NeonCyan)
                            Spacer(Modifier.height(6.dp))
                            res.citations.forEach { cite ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cite.url)))
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = cite.title,
                                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                                        color = NeonCyan
                                    )
                                }
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }
}

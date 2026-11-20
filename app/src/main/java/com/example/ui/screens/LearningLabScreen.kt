package com.example.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Api
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.curriculum.CyberCurriculumData
import com.example.data.model.LabModule
import com.example.data.model.LabTrack
import com.example.data.model.NoteCategory
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle

@Composable
fun LearningLabScreen(
    completedModuleIds: Set<String>,
    onMarkQuizCompleted: (String) -> Unit,
    onAskFriday: (String) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    var selectedTrackId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTrack = remember(selectedTrackId) {
        CyberCurriculumData.labTracks.firstOrNull { it.id == selectedTrackId }
    }

    if (selectedTrack != null) {
        BackHandler { selectedTrackId = null }
        LabTrackDetailView(
            track = selectedTrack,
            completedModuleIds = completedModuleIds,
            onBack = { selectedTrackId = null },
            onMarkQuizCompleted = onMarkQuizCompleted,
            onAskFriday = onAskFriday,
            onSaveNote = onSaveNote
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "CYBER LEARNING LAB",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Interactive defensive cybersecurity curriculum covering networking, Linux, OWASP Top 10, web, APIs, and CVE analysis.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(CyberCurriculumData.labTracks, key = { it.id }) { track ->
                val doneInTrack = track.modules.count { it.id in completedModuleIds }
                val totalInTrack = track.modules.size
                val accent = if (doneInTrack == totalInTrack && totalInTrack > 0) CyberEmerald
                else MaterialTheme.colorScheme.primary

                CyberHudCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("lab_track_${track.id}"),
                    accentColor = accent,
                    onClick = { selectedTrackId = track.id }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = trackIcon(track.id),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = track.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        CyberTagBadge(
                            text = "$doneInTrack/$totalInTrack",
                            accentColor = accent
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CyberTagBadge(
                            text = track.difficulty.uppercase(),
                            accentColor = MaterialTheme.colorScheme.secondary
                        )
                        CyberTagBadge(
                            text = "${track.modules.size} MODULES",
                            accentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabTrackDetailView(
    track: LabTrack,
    completedModuleIds: Set<String>,
    onBack: () -> Unit,
    onMarkQuizCompleted: (String) -> Unit,
    onAskFriday: (String) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("lab_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to Learning Lab",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = track.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(track.modules, key = { it.id }) { module ->
            LabModuleCard(
                module = module,
                category = track.category,
                isCompleted = module.id in completedModuleIds,
                onMarkCompleted = { onMarkQuizCompleted(module.id) },
                onAskFriday = onAskFriday,
                onSaveNote = onSaveNote
            )
        }
    }
}

@Composable
private fun LabModuleCard(
    module: LabModule,
    category: NoteCategory,
    isCompleted: Boolean,
    onMarkCompleted: () -> Unit,
    onAskFriday: (String) -> Unit,
    onSaveNote: (String, String, NoteCategory) -> Unit
) {
    var selectedAnswer by rememberSaveable(module.id) { mutableIntStateOf(-1) }
    var quizSubmitted by rememberSaveable(module.id) { mutableStateOf(false) }

    CyberHudCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = if (isCompleted) CyberEmerald else MaterialTheme.colorScheme.primary
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = module.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (isCompleted) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Completed",
                    tint = CyberEmerald,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = {
                    val noteBody = buildString {
                        appendLine(module.summary)
                        appendLine()
                        module.keyConcepts.forEach { appendLine("• $it") }
                        appendLine()
                        appendLine("Defense: ${module.defensiveTakeaway}")
                    }
                    onSaveNote(module.title, noteBody, category)
                },
                modifier = Modifier.testTag("lab_save_${module.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.BookmarkAdd,
                    contentDescription = stringResource(R.string.save_to_memory),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = module.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = "KEY CONCEPTS",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        module.keyConcepts.forEach { point ->
            Text(
                text = "• $point",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Text(
                text = module.commandOrCodeSnippet,
                style = MonoCodeStyle,
                color = CyberEmerald
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Defense Rule: ${module.defensiveTakeaway}",
            style = MaterialTheme.typography.bodySmall,
            color = CyberEmerald
        )

        Spacer(Modifier.height(12.dp))
        Text(
            text = "QUICK CHECK: ${module.quiz.question}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        module.quiz.options.forEachIndexed { idx, option ->
            val isChosen = selectedAnswer == idx
            val isCorrect = idx == module.quiz.correctIndex
            val borderColor = when {
                quizSubmitted && isCorrect -> CyberEmerald
                quizSubmitted && isChosen && !isCorrect -> MaterialTheme.colorScheme.secondary
                isChosen -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .background(
                        if (isChosen) borderColor.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable {
                        selectedAnswer = idx
                        quizSubmitted = false
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    quizSubmitted = true
                    if (selectedAnswer == module.quiz.correctIndex) {
                        onMarkCompleted()
                    }
                },
                enabled = selectedAnswer >= 0,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("Verify Answer")
            }
            OutlinedButton(
                onClick = {
                    onAskFriday("Explain '${module.title}' step by step in simple terms with a real-world defensive example.")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ask FRIDAY")
            }
        }

        if (quizSubmitted) {
            Spacer(Modifier.height(8.dp))
            val passed = selectedAnswer == module.quiz.correctIndex
            Text(
                text = if (passed) "Correct! ${module.quiz.explanation}" else "Not quite. ${module.quiz.explanation}",
                style = MaterialTheme.typography.bodySmall,
                color = if (passed) CyberEmerald else MaterialTheme.colorScheme.secondary
            )
        }
    }
}

private fun trackIcon(id: String): ImageVector = when (id) {
    "networking" -> Icons.Default.Lan
    "linux" -> Icons.Default.Terminal
    "owasp" -> Icons.Default.Security
    "web" -> Icons.Default.Language
    "api" -> Icons.Default.Api
    else -> Icons.Default.BugReport
}

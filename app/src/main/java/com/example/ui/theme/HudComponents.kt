package com.example.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.data.model.LanguageMode

@Composable
fun CyberGridBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val cyanGlow = MaterialTheme.colorScheme.primary
    val redGlow = MaterialTheme.colorScheme.secondary
    val baseBg = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(baseBg)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(cyanGlow.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.10f),
                    radius = size.maxDimension * 0.6f
                )
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(redGlow.copy(alpha = 0.09f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.85f),
                    radius = size.maxDimension * 0.55f
                )
            )
            val step = 56.dp.toPx()
            val gridColor = cyanGlow.copy(alpha = 0.04f)
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        content()
    }
}

@Composable
fun CyberHudCard(
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            accentColor.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
        )
    )
    Surface(
        modifier = modifier
            .clip(shape)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, borderBrush),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun FridayArcCore(
    modifier: Modifier = Modifier,
    size: Dp = 76.dp,
    isActive: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "friday_core")
    val outerAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 3200 else 9000, easing = LinearEasing)
        ),
        label = "outer_angle"
    )
    val innerAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 2200 else 6500, easing = LinearEasing)
        ),
        label = "inner_angle"
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val cyan = MaterialTheme.colorScheme.primary
    val crimson = MaterialTheme.colorScheme.secondary

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeOuter = 2.5.dp.toPx()
            val strokeInner = 2.dp.toPx()
            val pad = 4.dp.toPx()
            val diameter = this.size.minDimension - pad * 2f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cyan.copy(alpha = 0.25f * pulse),
                        crimson.copy(alpha = 0.12f * pulse),
                        Color.Transparent
                    )
                ),
                radius = this.size.minDimension / 2f
            )

            rotate(outerAngle) {
                drawArc(
                    color = cyan.copy(alpha = 0.85f),
                    startAngle = 0f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
                )
                drawArc(
                    color = crimson.copy(alpha = 0.85f),
                    startAngle = 180f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = Offset(pad, pad),
                    size = Size(diameter, diameter),
                    style = Stroke(width = strokeOuter, cap = StrokeCap.Round)
                )
            }

            val innerPad = pad + 8.dp.toPx()
            val innerDia = this.size.minDimension - innerPad * 2f
            rotate(innerAngle) {
                drawArc(
                    color = cyan.copy(alpha = 0.6f),
                    startAngle = 45f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(innerPad, innerPad),
                    size = Size(innerDia, innerDia),
                    style = Stroke(width = strokeInner, cap = StrokeCap.Round)
                )
                drawArc(
                    color = crimson.copy(alpha = 0.7f),
                    startAngle = 225f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(innerPad, innerPad),
                    size = Size(innerDia, innerDia),
                    style = Stroke(width = strokeInner, cap = StrokeCap.Round)
                )
            }

            drawCircle(
                color = if (isActive) crimson else cyan,
                radius = (this.size.minDimension * 0.14f) * pulse
            )
        }
    }
}

@Composable
fun CyberTagBadge(
    text: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SecurityDisclaimerBanner(
    text: String,
    isSimulated: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isSimulated) CyberAmber else MaterialTheme.colorScheme.tertiary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun LanguagePillSelector(
    selected: LanguageMode,
    onSelect: (LanguageMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LanguageMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val bg = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
            val border = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
            val textColor = if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(bg)
                    .border(1.dp, border, CircleShape)
                    .clickable { onSelect(mode) }
                    .testTag("lang_mode_${mode.name.lowercase()}")
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun AiModelSelectorRow(
    selected: com.example.data.model.AiModelOption,
    onSelect: (com.example.data.model.AiModelOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.example.data.model.AiModelOption.entries.forEach { option ->
            val isSelected = option == selected
            val bg = if (isSelected) NeonCrimson.copy(alpha = 0.20f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
            val border = if (isSelected) NeonCrimson else MaterialTheme.colorScheme.outline
            val textColor = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(10.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(10.dp))
                    .clickable { onSelect(option) }
                    .testTag("model_option_${option.name.lowercase()}")
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun CommandApprovalDialog(
    pending: com.example.data.model.PendingCommandApproval,
    onApprove: () -> Unit,
    onCancel: () -> Unit
) {
    var authorizedConfirmed by remember(pending.command) {
        mutableStateOf(!pending.requiresAuthorizationConfirmation)
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CyberObsidian,
        titleContentColor = if (pending.isBlocked) NeonCrimson else NeonCyan,
        textContentColor = Color.White,
        title = {
            Text(
                text = if (pending.isBlocked) "COMMAND BLOCKED BY POLICY"
                else "EXPLICIT COMMAND APPROVAL",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CyberTagBadge(
                    text = "${pending.environment.badge} • ${pending.riskLevel.label}",
                    accentColor = if (pending.isBlocked) NeonCrimson else CyberAmber
                )
                if (pending.targetHostSummary.isNotBlank()) {
                    Text(
                        text = "Target: ${pending.targetHostSummary} • Timeout: ${pending.timeoutSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberEmerald
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = pending.command,
                        style = MonoCodeStyle,
                        color = NeonCyan
                    )
                }
                Text(
                    text = pending.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!pending.isBlocked && pending.requiresAuthorizationConfirmation) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { authorizedConfirmed = !authorizedConfirmed }
                            .testTag("approval_authorization_checkbox")
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Checkbox(
                            checked = authorizedConfirmed,
                            onCheckedChange = { authorizedConfirmed = it }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "I confirm explicit authorization for this target & command.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!pending.isBlocked) {
                androidx.compose.material3.Button(
                    onClick = onApprove,
                    enabled = authorizedConfirmed,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = NeonCrimson,
                        contentColor = Color.White
                    ),
                    modifier = Modifier.testTag("confirm_execute_command_button")
                ) {
                    Text("Approve & Execute")
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag("cancel_execute_command_button")
            ) {
                Text(if (pending.isBlocked) "Dismiss" else "Cancel")
            }
        }
    )
}


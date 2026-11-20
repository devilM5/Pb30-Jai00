package com.example.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.core.BiometricGateController
import com.example.core.BiometricSupportStatus
import com.example.core.DefaultBiometricGateController
import com.example.core.buildBiometricEnrollIntent
import com.example.core.findFragmentActivity
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberGridBackdrop
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.FridayArcCore
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan

@Composable
fun BiometricLockScreen(
    controller: BiometricGateController = DefaultBiometricGateController,
    autoPromptOnStart: Boolean = true,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findFragmentActivity() }

    var supportResult by remember { mutableStateOf(controller.checkSupport(context)) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var isAuthenticating by remember { mutableStateOf(false) }
    var hasAutoPrompted by remember { mutableStateOf(false) }

    val promptTitle = stringResource(R.string.biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.biometric_prompt_subtitle)

    fun triggerBiometricPrompt() {
        supportResult = controller.checkSupport(context)
        if (supportResult.status != BiometricSupportStatus.READY) {
            feedbackMessage = supportResult.detail
            return
        }
        if (activity == null) {
            feedbackMessage = "Host FragmentActivity unavailable for BiometricPrompt."
            return
        }
        isAuthenticating = true
        feedbackMessage = null
        controller.authenticate(
            activity = activity,
            title = promptTitle,
            subtitle = promptSubtitle,
            onSuccess = {
                isAuthenticating = false
                feedbackMessage = null
                onUnlocked()
            },
            onFailureMessage = { message ->
                isAuthenticating = false
                feedbackMessage = message
            }
        )
    }

    // Refresh hardware / lock-screen enrollment status if operator returns from Android Security Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                supportResult = controller.checkSupport(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(autoPromptOnStart, supportResult.status) {
        if (autoPromptOnStart &&
            !hasAutoPrompted &&
            supportResult.status == BiometricSupportStatus.READY &&
            activity != null
        ) {
            hasAutoPrompted = true
            triggerBiometricPrompt()
        }
    }

    CyberGridBackdrop {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp)
                    .widthIn(max = 520.dp)
                    .testTag("biometric_lock_screen"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
            FridayArcCore(size = 86.dp, isActive = isAuthenticating)
            Spacer(Modifier.height(16.dp))

            CyberTagBadge(text = "PB-30 JAI // VAULT SEALED", accentColor = NeonCrimson)
            Spacer(Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.biometric_lock_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NeonCyan,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.biometric_lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            CyberHudCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = NeonCyan
            ) {
                Text(
                    text = "PROTECTED SECURITY ASSETS",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
                Spacer(Modifier.height(10.dp))
                ProtectedAssetRow(
                    icon = Icons.Default.VpnKey,
                    title = "Encrypted SSH Server Profiles",
                    subtitle = "AES-256-GCM host, port & operator metadata"
                )
                Spacer(Modifier.height(8.dp))
                ProtectedAssetRow(
                    icon = Icons.Default.History,
                    title = "Audited Terminal & SSH History",
                    subtitle = "Redacted command logs & scan outputs"
                )
                Spacer(Modifier.height(8.dp))
                ProtectedAssetRow(
                    icon = Icons.Default.Lock,
                    title = "Termux & Command Approval Gate",
                    subtitle = "Zero silent command execution"
                )
            }

            Spacer(Modifier.height(14.dp))

            val statusAccent = when (supportResult.status) {
                BiometricSupportStatus.READY -> CyberEmerald
                BiometricSupportStatus.NONE_ENROLLED -> CyberAmber
                BiometricSupportStatus.UNAVAILABLE -> NeonCrimson
            }
            CyberHudCard(
                modifier = Modifier.fillMaxWidth(),
                accentColor = if (feedbackMessage != null) NeonCrimson else statusAccent
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = statusAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when (supportResult.status) {
                            BiometricSupportStatus.READY -> "BIOMETRIC SECURITY READY"
                            BiometricSupportStatus.NONE_ENROLLED -> "NO DEVICE LOCK ENROLLED"
                            BiometricSupportStatus.UNAVAILABLE -> "BIOMETRIC SENSOR UNAVAILABLE"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = statusAccent
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = feedbackMessage ?: supportResult.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedbackMessage != null) NeonCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("biometric_status_message")
                )
            }

            Spacer(Modifier.height(20.dp))

            if (supportResult.status == BiometricSupportStatus.READY) {
                Button(
                    onClick = { triggerBiometricPrompt() },
                    enabled = !isAuthenticating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("biometric_unlock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.biometric_unlock_button),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                if (supportResult.status == BiometricSupportStatus.NONE_ENROLLED) {
                    Button(
                        onClick = { context.startActivity(buildBiometricEnrollIntent(context)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = Color.Black
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("biometric_enroll_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.biometric_enroll_button),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Only shown when the device/emulator genuinely has no enrolled biometrics or lock screen.
                OutlinedButton(
                    onClick = onUnlocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("biometric_fallback_bypass_button")
                ) {
                    Text(
                        text = "Continue Without Hardware Lock (Unprotected)",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberAmber
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun ProtectedAssetRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NeonCyan,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

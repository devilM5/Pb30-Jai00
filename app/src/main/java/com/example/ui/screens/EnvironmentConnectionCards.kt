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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TermuxConnectionMode
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.ToolAvailability
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan

@Composable
fun ExecutionEnvironmentSwitcher(
    selectedEnvironment: TerminalEnvironment,
    termuxConnected: Boolean,
    sshConnected: Boolean,
    onSelectEnvironment: (TerminalEnvironment) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        EnvironmentOptionChip(
            title = "Local Android Termux",
            badge = if (termuxConnected) "CONNECTED" else "DISCONNECTED",
            icon = Icons.Default.PhoneAndroid,
            selected = selectedEnvironment == TerminalEnvironment.TERMUX_BRIDGE,
            connected = termuxConnected,
            modifier = Modifier
                .weight(1f)
                .testTag("env_switch_termux"),
            onClick = { onSelectEnvironment(TerminalEnvironment.TERMUX_BRIDGE) }
        )
        EnvironmentOptionChip(
            title = "Remote Linux SSH",
            badge = if (sshConnected) "CONNECTED" else "DISCONNECTED",
            icon = Icons.Default.Dns,
            selected = selectedEnvironment == TerminalEnvironment.LINUX_SERVER_BRIDGE,
            connected = sshConnected,
            modifier = Modifier
                .weight(1f)
                .testTag("env_switch_ssh"),
            onClick = { onSelectEnvironment(TerminalEnvironment.LINUX_SERVER_BRIDGE) }
        )
    }
}

@Composable
private fun EnvironmentOptionChip(
    title: String,
    badge: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    connected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = when {
        selected -> NeonCyan
        connected -> CyberEmerald.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outline
    }
    val bgColor = if (selected) NeonCyan.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer
    Column(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CyberTagBadge(
            text = badge,
            accentColor = if (connected) CyberEmerald else CyberAmber
        )
    }
}

@Composable
fun TermuxEnvironmentCard(
    termuxState: TermuxEnvironmentState,
    onSelectMode: (TermuxConnectionMode) -> Unit,
    onUpdateCompanionHttp: (String, String) -> Unit,
    onRequestRunCommandPermission: () -> Unit,
    onVerifyAndConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStageToolProbe: () -> Unit
) {
    CyberHudCard(
        accentColor = if (termuxState.isConnected) CyberEmerald else CyberAmber
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LOCAL ANDROID TERMUX",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
                Text(
                    text = "APK: ${if (termuxState.apkInstalled) "Installed (${termuxState.apkVersion.ifBlank { "detected" }})" else "Not Installed"} • Perm: ${if (termuxState.runCommandPermissionGranted) "Granted" else "Not Granted"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CyberTagBadge(
                text = if (termuxState.isConnected) "CONNECTED" else "DISCONNECTED",
                accentColor = if (termuxState.isConnected) CyberEmerald else CyberAmber
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TermuxConnectionMode.entries.forEach { mode ->
                val selected = termuxState.companionMode == mode
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) NeonCyan.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .border(
                            1.dp,
                            if (selected) NeonCyan else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onSelectMode(mode) }
                        .testTag("termux_mode_${mode.name.lowercase()}")
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.displayName,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) NeonCyan else Color.White
                    )
                }
            }
        }

        if (termuxState.companionMode == TermuxConnectionMode.COMPANION_HTTP) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = termuxState.companionUrl,
                onValueChange = { onUpdateCompanionHttp(it, termuxState.companionToken) },
                label = { Text("Local Companion URL (e.g. http://127.0.0.1:8022)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_http_url_input")
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = termuxState.companionToken,
                onValueChange = { onUpdateCompanionHttp(termuxState.companionUrl, it) },
                label = { Text("Companion Bearer Token (in-memory only)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_http_token_input")
            )
        } else if (!termuxState.runCommandPermissionGranted) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRequestRunCommandPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("termux_grant_permission_button")
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Request Termux RUN_COMMAND Permission")
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = termuxState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = if (termuxState.isConnected) CyberEmerald else CyberAmber
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onVerifyAndConnect,
                enabled = !termuxState.isChecking,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("termux_connect_button")
            ) {
                if (termuxState.isChecking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Checking…")
                } else {
                    Text("Verify & Connect")
                }
            }
            if (termuxState.isConnected) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("termux_disconnect_button")
                ) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "DETECTED TERMUX TOOLS (VERIFIED VIA REAL RUNS)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            termuxState.toolStatuses.forEach { (tool, availability) ->
                val color = when (availability) {
                    ToolAvailability.INSTALLED -> CyberEmerald
                    ToolAvailability.UNAVAILABLE -> NeonCrimson
                    ToolAvailability.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                CyberTagBadge(
                    text = "$tool: ${availability.label}",
                    accentColor = color
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onStageToolProbe,
            enabled = termuxState.isConnected,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("termux_probe_tools_button")
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Approve & Probe Installed Tools (python3, bash, nmap)")
        }
    }
}

@Composable
fun SshConnectionCard(
    profile: SshConnectionProfile,
    sshState: SshConnectionState,
    onProfileChanged: (SshConnectionProfile, Boolean) -> Unit,
    onConnectAndTest: () -> Unit,
    onDisconnect: () -> Unit,
    onClearSavedCredentials: () -> Unit,
    onOpenRemoteSettings: () -> Unit = {}
) {
    CyberHudCard(
        accentColor = if (sshState.isConnected) CyberEmerald else CyberAmber
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "REMOTE LINUX SERVER (SSH)",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCyan
                )
                Text(
                    text = if (profile.host.isNotBlank()) "${profile.username.ifBlank { "user" }}@${profile.host}:${profile.port}"
                    else "Configure your authorized Linux server",
                    style = MonoCodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CyberTagBadge(
                text = if (sshState.isConnected) "SSH ONLINE" else "DISCONNECTED",
                accentColor = if (sshState.isConnected) CyberEmerald else CyberAmber
            )
        }

        if (sshState.serverInfo.isNotBlank() || sshState.hostFingerprint.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Server: ${sshState.serverInfo} • Key: ${sshState.hostFingerprint.ifBlank { "verified" }}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberEmerald
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = sshState.statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = if (sshState.isConnected) CyberEmerald else CyberAmber
        )

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = profile.host,
                onValueChange = { onProfileChanged(profile.copy(host = it), profile.persistSecretsEncrypted) },
                label = { Text("Host / IP") },
                singleLine = true,
                modifier = Modifier
                    .weight(2f)
                    .testTag("ssh_host_input")
            )
            OutlinedTextField(
                value = profile.port.toString(),
                onValueChange = { raw ->
                    val parsed = raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                    onProfileChanged(profile.copy(port = parsed), profile.persistSecretsEncrypted)
                },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ssh_port_input")
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = profile.username,
            onValueChange = { onProfileChanged(profile.copy(username = it), profile.persistSecretsEncrypted) },
            label = { Text("SSH Username") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ssh_username_input")
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SshAuthMethod.entries.forEach { method ->
                val selected = profile.authMethod == method
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selected) NeonCyan.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .border(
                            1.dp,
                            if (selected) NeonCyan else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onProfileChanged(profile.copy(authMethod = method), profile.persistSecretsEncrypted)
                        }
                        .testTag("ssh_auth_${method.name.lowercase()}")
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (method == SshAuthMethod.PASSWORD) Icons.Default.Lock else Icons.Default.Key,
                            contentDescription = null,
                            tint = if (selected) NeonCyan else Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(method.displayName, style = MaterialTheme.typography.labelSmall, color = if (selected) NeonCyan else Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (profile.authMethod == SshAuthMethod.PASSWORD) {
            OutlinedTextField(
                value = profile.password,
                onValueChange = { onProfileChanged(profile.copy(password = it), profile.persistSecretsEncrypted) },
                label = { Text("Session Password (in-memory only • never saved)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ssh_password_input")
            )
        } else {
            OutlinedTextField(
                value = profile.privateKeyPem,
                onValueChange = { onProfileChanged(profile.copy(privateKeyPem = it), profile.persistSecretsEncrypted) },
                label = { Text("OpenSSH / PEM Private Key (in-memory only)") },
                minLines = 3,
                maxLines = 5,
                textStyle = MonoCodeStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ssh_private_key_input")
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = profile.keyPassphrase,
                onValueChange = { onProfileChanged(profile.copy(keyPassphrase = it), profile.persistSecretsEncrypted) },
                label = { Text("Key Passphrase (optional • never saved)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ssh_passphrase_input")
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onProfileChanged(profile, !profile.persistSecretsEncrypted)
                }
                .testTag("ssh_persist_secrets_toggle")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = profile.persistSecretsEncrypted,
                onCheckedChange = { checked -> onProfileChanged(profile, checked) }
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Save Host & Username via EncryptedSharedPreferences (passwords never saved)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onConnectAndTest,
                enabled = !sshState.isConnecting,
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("ssh_connect_button")
            ) {
                if (sshState.isConnecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Connecting…")
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Connect & Test SSH")
                }
            }
            OutlinedButton(
                onClick = onDisconnect,
                enabled = sshState.isConnected,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("ssh_disconnect_button")
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Disconnect")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenRemoteSettings,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ssh_open_settings_button")
            ) {
                Text("Remote Server Settings")
            }
            OutlinedButton(
                onClick = onClearSavedCredentials,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ssh_clear_credentials_button")
            ) {
                Text("Clear Saved Details")
            }
        }
    }
}

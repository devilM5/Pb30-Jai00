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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.ui.theme.CyberAmber
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberHudCard
import com.example.ui.theme.CyberTagBadge
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.NeonCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SecurityDisclaimerBanner

@Composable
fun RemoteServerSettingsScreen(
    profile: SshConnectionProfile,
    sshState: SshConnectionState,
    onUpdateProfileInMemory: (SshConnectionProfile) -> Unit,
    onSaveConnectionDetails: (String, Int, String, SshAuthMethod) -> Unit,
    onTestConnection: () -> Unit,
    onDisconnect: () -> Unit,
    onClearSavedDetails: () -> Unit,
    onNavigateBack: () -> Unit
) {
    BackHandler(onBack = onNavigateBack)

    var showPassword by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("remote_server_settings_screen"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.remote_server_settings_title).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = NeonCyan
                    )
                    CyberTagBadge(
                        text = if (sshState.isConnected) "SSH CONNECTED" else "DISCONNECTED",
                        accentColor = if (sshState.isConnected) CyberEmerald else CyberAmber
                    )
                }
                Text(
                    text = stringResource(R.string.remote_server_settings_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SecurityDisclaimerBanner(
                text = "Host, Port & Username are saved locally via EncryptedSharedPreferences. Passwords are used in-memory only and are NEVER stored on disk.",
                isSimulated = false
            )
        }

        // Card 1: Encrypted SSH Connection Details (Host, Port, Username)
        item {
            CyberHudCard(accentColor = NeonCyan) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "SSH CONNECTION DETAILS",
                            style = MaterialTheme.typography.titleSmall,
                            color = NeonCyan
                        )
                    }
                    CyberTagBadge(text = "ENCRYPTED PREFS", accentColor = CyberEmerald)
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = profile.host,
                        onValueChange = { onUpdateProfileInMemory(profile.copy(host = it)) },
                        label = { Text("SSH Host / IP") },
                        placeholder = { Text("e.g. 192.168.1.50 or server.example.com") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(2.2f)
                            .testTag("remote_settings_host_input")
                    )
                    OutlinedTextField(
                        value = profile.port.toString(),
                        onValueChange = { raw ->
                            val parsed = raw.filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 65535) ?: 22
                            onUpdateProfileInMemory(profile.copy(port = parsed))
                        },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("remote_settings_port_input")
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = profile.username,
                    onValueChange = { onUpdateProfileInMemory(profile.copy(username = it)) },
                    label = { Text("SSH Username") },
                    placeholder = { Text("e.g. ubuntu or kali") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_settings_username_input")
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            onSaveConnectionDetails(
                                profile.host,
                                profile.port,
                                profile.username,
                                profile.authMethod
                            )
                        },
                        enabled = profile.host.isNotBlank() && profile.username.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("remote_settings_save_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save Details")
                    }
                    OutlinedButton(
                        onClick = onClearSavedDetails,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("remote_settings_clear_button")
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Clear stored SSH settings", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear")
                    }
                }
            }
        }

        // Card 2: Connectivity Verification (Ephemeral Password / Private Key — Never Stored)
        item {
            CyberHudCard(accentColor = if (sshState.isConnected) CyberEmerald else NeonCrimson) {
                Text(
                    text = "VERIFY CONNECTIVITY (PASSWORD NOT STORED)",
                    style = MaterialTheme.typography.titleSmall,
                    color = NeonCrimson
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
                                .clickable { onUpdateProfileInMemory(profile.copy(authMethod = method)) }
                                .testTag("remote_settings_auth_${method.name.lowercase()}")
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (method == SshAuthMethod.PASSWORD) Icons.Default.Lock else Icons.Default.Key,
                                    contentDescription = null,
                                    tint = if (selected) NeonCyan else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = method.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) NeonCyan else Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                if (profile.authMethod == SshAuthMethod.PASSWORD) {
                    OutlinedTextField(
                        value = profile.password,
                        onValueChange = { onUpdateProfileInMemory(profile.copy(password = it)) },
                        label = { Text("Session Password (ephemeral • never saved)") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_settings_password_input")
                    )
                } else {
                    OutlinedTextField(
                        value = profile.privateKeyPem,
                        onValueChange = { onUpdateProfileInMemory(profile.copy(privateKeyPem = it)) },
                        label = { Text("OpenSSH / PEM Private Key (in-memory only)") },
                        minLines = 3,
                        maxLines = 5,
                        textStyle = MonoCodeStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_settings_private_key_input")
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = profile.keyPassphrase,
                        onValueChange = { onUpdateProfileInMemory(profile.copy(keyPassphrase = it)) },
                        label = { Text("Key Passphrase (optional • never saved)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_settings_passphrase_input")
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onTestConnection,
                        enabled = !sshState.isConnecting && profile.host.isNotBlank() && profile.username.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCrimson, contentColor = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("remote_settings_test_connection_button")
                    ) {
                        if (sshState.isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Verifying SSH…")
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Test Connection")
                        }
                    }

                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = sshState.isConnected,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("remote_settings_disconnect_button")
                    ) {
                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Disconnect")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(
                            1.dp,
                            if (sshState.isConnected) CyberEmerald.copy(alpha = 0.6f) else CyberAmber.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = sshState.statusDetail,
                            style = MonoCodeStyle,
                            color = if (sshState.isConnected) CyberEmerald else CyberAmber
                        )
                        if (sshState.serverInfo.isNotBlank() || sshState.hostFingerprint.isNotBlank()) {
                            Text(
                                text = "Banner: ${sshState.serverInfo} | Fingerprint: ${sshState.hostFingerprint}",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.example.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.VaultLifecycleGuard
import com.example.data.model.DhirModelState
import com.example.data.model.DhirModelStatus

@Composable
fun DhirModelStatusCard(
    modelState: DhirModelState,
    onImportModelUri: (android.net.Uri) -> Unit,
    onDownloadOfficialModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onDeleteModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        VaultLifecycleGuard.clearExternalFlow()
        if (uri != null) onImportModelUri(uri)
    }

    val statusColor = when (modelState.status) {
        DhirModelStatus.LOADED -> CyberEmerald
        DhirModelStatus.READY_UNLOADED -> NeonCyan
        DhirModelStatus.DOWNLOADING,
        DhirModelStatus.IMPORTING,
        DhirModelStatus.LOADING,
        DhirModelStatus.VALIDATING -> CyberAmber
        DhirModelStatus.NOT_INSTALLED,
        DhirModelStatus.ERROR -> NeonCrimson
    }

    CyberHudCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("dhir_model_status_card"),
        accentColor = statusColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = "Dhīr 2B Local AI Engine",
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "DHĪR 2B LOCAL AI CORE",
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColor
                    )
                    Text(
                        text = modelState.activeFileName.ifBlank { modelState.expectedFilename },
                        style = MonoCodeStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            CyberTagBadge(text = modelState.status.label.uppercase(), accentColor = statusColor)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = modelState.statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (modelState.totalDeviceRamMb > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "RAM: ${modelState.availableDeviceRamMb} MB free / ${modelState.totalDeviceRamMb} MB total" +
                    (if (modelState.effectiveContextSize > 0) " • Ctx: ${modelState.effectiveContextSize}" else "") +
                    " • JNI: ${if (modelState.nativeBackendReady) "Ready" else "JVM/Unavailable"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (modelState.status == DhirModelStatus.DOWNLOADING || modelState.status == DhirModelStatus.IMPORTING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (modelState.downloadProgressPercent.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = CyberAmber
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.fillMaxWidth().testTag("dhir_cancel_transfer_button")
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Cancel Transfer (${modelState.downloadProgressPercent}%)")
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        VaultLifecycleGuard.markExternalFlowLaunched()
                        filePicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.weight(1f).testTag("dhir_import_gguf_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import GGUF")
                }

                if (modelState.status == DhirModelStatus.NOT_INSTALLED || modelState.status == DhirModelStatus.ERROR) {
                    Button(
                        onClick = onDownloadOfficialModel,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black),
                        modifier = Modifier.weight(1f).testTag("dhir_download_gguf_button")
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download")
                    }
                } else if (modelState.status == DhirModelStatus.READY_UNLOADED) {
                    Button(
                        onClick = onLoadModel,
                        enabled = modelState.nativeBackendReady,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberEmerald, contentColor = Color.Black),
                        modifier = Modifier.weight(1f).testTag("dhir_load_model_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Load RAM")
                    }
                } else if (modelState.status == DhirModelStatus.LOADED) {
                    OutlinedButton(
                        onClick = onUnloadModel,
                        modifier = Modifier.weight(1f).testTag("dhir_unload_model_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Unload")
                    }
                }
            }

            if (modelState.activeFilePath.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onDeleteModel,
                    modifier = Modifier.fillMaxWidth().testTag("dhir_delete_model_button")
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove Stored GGUF (${modelState.activeFileName})")
                }
            }
        }
    }
}

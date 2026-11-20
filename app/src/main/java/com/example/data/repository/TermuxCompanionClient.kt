package com.example.data.repository

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.core.CredentialRedactor
import com.example.core.TermuxCommandReceiver
import com.example.core.TermuxExecutionCallbacks
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TermuxConnectionMode
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.ToolAvailability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

object ToolAvailabilityParser {
    val TRACKED_TOOLS = listOf("python3", "bash", "nmap")

    const val TOOL_PROBE_COMMAND =
        "for t in python3 bash nmap; do command -v \"\$t\" >/dev/null 2>&1 && echo \"FRIDAY_TOOL:\$t=AVAILABLE\" || echo \"FRIDAY_TOOL:\$t=UNAVAILABLE\"; done"

    fun updateFromExecution(
        current: Map<String, ToolAvailability>,
        command: String,
        stdout: String,
        stderr: String,
        exitCode: Int
    ): Map<String, ToolAvailability> {
        val updated = current.toMutableMap()
        val combined = "$stdout\n$stderr"

        // Explicit diagnostic probe markers
        TRACKED_TOOLS.forEach { tool ->
            when {
                combined.contains("FRIDAY_TOOL:$tool=AVAILABLE") ->
                    updated[tool] = ToolAvailability.INSTALLED
                combined.contains("FRIDAY_TOOL:$tool=UNAVAILABLE") ->
                    updated[tool] = ToolAvailability.UNAVAILABLE
            }
        }

        // Real runtime failure inference (exit code 127 or "not found")
        val lowerCmd = command.lowercase()
        val lowerErr = stderr.lowercase()
        if (exitCode == 127 || lowerErr.contains("not found") || lowerErr.contains("no such file")) {
            TRACKED_TOOLS.forEach { tool ->
                if (lowerErr.contains(tool) || lowerCmd.startsWith(tool) || lowerCmd.contains(" $tool ")) {
                    updated[tool] = ToolAvailability.UNAVAILABLE
                }
            }
        } else if (exitCode == 0) {
            TRACKED_TOOLS.forEach { tool ->
                if (lowerCmd == tool || lowerCmd.startsWith("$tool ")) {
                    updated[tool] = ToolAvailability.INSTALLED
                }
            }
        }
        return updated
    }
}

/**
 * Manages real Termux companion verification and approved command execution via either:
 * 1) Termux's RunCommandService PendingIntent callback architecture, or
 * 2) Authenticated Local HTTP Companion bridge (http://127.0.0.1:8022).
 *
 * Never fabricates Termux connection state or runs silent commands.
 */
class TermuxCompanionClient(
    private val context: Context,
    private val httpBridgeClient: TerminalBridgeClient = TerminalBridgeClient(context)
) {
    private val nextExecId = AtomicInteger(4000)

    fun inspectEnvironment(
        previous: TermuxEnvironmentState = TermuxEnvironmentState()
    ): TermuxEnvironmentState {
        val pm = context.packageManager
        val pkgInfo = runCatching { pm.getPackageInfo(TERMUX_PACKAGE, 0) }.getOrNull()
        val installed = pkgInfo != null
        val version = pkgInfo?.versionName.orEmpty()
        val permissionGranted = ContextCompat.checkSelfPermission(
            context,
            TERMUX_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        val stillConnected = when (previous.companionMode) {
            TermuxConnectionMode.RUN_COMMAND_SERVICE -> installed && permissionGranted && previous.isConnected
            TermuxConnectionMode.COMPANION_HTTP -> previous.isConnected
        }

        val defaultMessage = when {
            previous.companionMode == TermuxConnectionMode.COMPANION_HTTP && !stillConnected ->
                "Local HTTP Companion not connected. Start your local Termux HTTP server on ${previous.companionUrl} and tap Connect."
            !installed ->
                "Termux app (com.termux) is not installed on this device."
            !permissionGranted ->
                "Termux detected (v${version.ifBlank { "?" }}), but RUN_COMMAND permission is not granted yet."
            stillConnected ->
                previous.statusMessage
            else ->
                "Termux prerequisites verified (APK + RUN_COMMAND). Tap Connect Termux to arm execution."
        }

        return previous.copy(
            apkInstalled = installed,
            apkVersion = version,
            runCommandPermissionGranted = permissionGranted,
            isConnected = stillConnected,
            statusMessage = defaultMessage
        )
    }

    suspend fun verifyAndConnect(current: TermuxEnvironmentState): TermuxEnvironmentState {
        val refreshed = inspectEnvironment(current)
        return when (refreshed.companionMode) {
            TermuxConnectionMode.RUN_COMMAND_SERVICE -> {
                when {
                    !refreshed.apkInstalled -> refreshed.copy(
                        isConnected = false,
                        isChecking = false,
                        statusMessage = "Connection failed: Termux (com.termux) is not installed on this Android device."
                    )
                    !refreshed.runCommandPermissionGranted -> refreshed.copy(
                        isConnected = false,
                        isChecking = false,
                        statusMessage = "Connection blocked: Grant 'Run commands in Termux environment' permission first."
                    )
                    else -> {
                        val hasService = isRunCommandServiceResolvable()
                        if (hasService) {
                            refreshed.copy(
                                isConnected = true,
                                isChecking = false,
                                statusMessage = "Termux RunCommandService ready (v${refreshed.apkVersion.ifBlank { "installed" }}). Note: ~/.termux/termux.properties must enable allow-external-apps=true."
                            )
                        } else {
                            refreshed.copy(
                                isConnected = false,
                                isChecking = false,
                                statusMessage = "Termux RunCommandService component not exposed by installed Termux build."
                            )
                        }
                    }
                }
            }
            TermuxConnectionMode.COMPANION_HTTP -> {
                val health = httpBridgeClient.testConnection(refreshed.companionUrl, refreshed.companionToken)
                if (health.isSuccess) {
                    refreshed.copy(
                        isConnected = true,
                        isChecking = false,
                        statusMessage = health.getOrThrow()
                    )
                } else {
                    refreshed.copy(
                        isConnected = false,
                        isChecking = false,
                        statusMessage = health.exceptionOrNull()?.localizedMessage ?: "Termux HTTP Companion unreachable."
                    )
                }
            }
        }
    }

    fun disconnect(current: TermuxEnvironmentState): TermuxEnvironmentState =
        current.copy(
            isConnected = false,
            isChecking = false,
            statusMessage = "Termux companion disconnected by operator."
        )

    private fun isRunCommandServiceResolvable(): Boolean {
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
        }
        val resolved = context.packageManager.resolveService(intent, 0)
        return resolved != null
    }

    suspend fun executeApprovedCommand(
        state: TermuxEnvironmentState,
        command: String,
        timeoutSeconds: Int
    ): BridgeExecutionResult {
        val started = System.currentTimeMillis()
        if (!state.isConnected) {
            return BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = "Termux companion is not connected. Verify & connect Termux before running commands.",
                environment = TerminalEnvironment.TERMUX_BRIDGE
            )
        }

        return when (state.companionMode) {
            TermuxConnectionMode.COMPANION_HTTP -> {
                try {
                    withTimeout(timeoutSeconds.coerceIn(5, 300) * 1000L) {
                        httpBridgeClient.executeApprovedCommand(
                            command = command,
                            environment = TerminalEnvironment.TERMUX_BRIDGE,
                            endpointUrl = state.companionUrl,
                            sessionToken = state.companionToken
                        ).copy(executionTimeMillis = System.currentTimeMillis() - started)
                    }
                } catch (_: TimeoutCancellationException) {
                    BridgeExecutionResult(
                        executed = false,
                        status = TerminalExecutionStatus.TIMEOUT,
                        exitCode = -1,
                        stdout = "",
                        stderr = "Termux HTTP companion timed out after ${timeoutSeconds}s.",
                        environment = TerminalEnvironment.TERMUX_BRIDGE,
                        executionTimeMillis = System.currentTimeMillis() - started
                    )
                }
            }
            TermuxConnectionMode.RUN_COMMAND_SERVICE -> executeViaRunCommandService(command, timeoutSeconds, started)
        }
    }

    private suspend fun executeViaRunCommandService(
        command: String,
        timeoutSeconds: Int,
        startedAt: Long
    ): BridgeExecutionResult {
        val currentCheck = inspectEnvironment()
        if (!currentCheck.apkInstalled || !currentCheck.runCommandPermissionGranted) {
            return BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = "Termux APK or RUN_COMMAND permission is missing.",
                environment = TerminalEnvironment.TERMUX_BRIDGE
            )
        }

        val execId = nextExecId.incrementAndGet()
        val deferred = TermuxExecutionCallbacks.register(execId)
        try {
            val callbackIntent = Intent(context, TermuxCommandReceiver::class.java).apply {
                putExtra(TermuxCommandReceiver.EXTRA_EXECUTION_ID, execId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, execId, callbackIntent, flags)

            val serviceIntent = Intent(ACTION_RUN_COMMAND).apply {
                component = ComponentName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH_PATH)
                putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
                putExtra(EXTRA_WORKDIR, TERMUX_HOME_PATH)
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val result = withTimeout(timeoutSeconds.coerceIn(5, 300) * 1000L) {
                deferred.await()
            }

            val safeStdout = CredentialRedactor.redact(result.stdout).take(6000)
            val errCombined = listOf(result.stderr, result.internalErrMsg)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            val safeStderr = CredentialRedactor.redact(errCombined).take(2500)
            val ok = result.internalErrCode == 0 && result.exitCode == 0
            return BridgeExecutionResult(
                executed = result.internalErrCode == 0,
                status = if (ok) TerminalExecutionStatus.EXECUTED else TerminalExecutionStatus.ERROR,
                exitCode = result.exitCode,
                stdout = safeStdout,
                stderr = safeStderr,
                environment = TerminalEnvironment.TERMUX_BRIDGE,
                executionTimeMillis = System.currentTimeMillis() - startedAt
            )
        } catch (_: TimeoutCancellationException) {
            return BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.TIMEOUT,
                exitCode = -1,
                stdout = "",
                stderr = "Termux RunCommandService timed out after ${timeoutSeconds}s. Verify allow-external-apps=true in ~/.termux/termux.properties.",
                environment = TerminalEnvironment.TERMUX_BRIDGE,
                executionTimeMillis = System.currentTimeMillis() - startedAt
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            return BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.ERROR,
                exitCode = -1,
                stdout = "",
                stderr = CredentialRedactor.redact(
                    "Failed to dispatch command to Termux (${e.javaClass.simpleName}): ${e.localizedMessage ?: "security/service error"}"
                ),
                environment = TerminalEnvironment.TERMUX_BRIDGE,
                executionTimeMillis = System.currentTimeMillis() - startedAt
            )
        } finally {
            TermuxExecutionCallbacks.cancel(execId)
        }
    }

    companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"
    }
}

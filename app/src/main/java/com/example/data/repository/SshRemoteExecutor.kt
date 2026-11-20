package com.example.data.repository

import android.content.Context
import com.example.core.CredentialRedactor
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshConnectionProfile
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

data class SshConnectionSummary(
    val serverBanner: String,
    val hostFingerprint: String
)

/**
 * Real SSH remote Linux server executor backed by mwiede/JSch.
 * Never fabricates output or reports connected before a live SSH Session completes handshake.
 */
class SshRemoteExecutor(private val context: Context) {

    private val sessionMutex = Mutex()
    private val knownHostsFile by lazy { File(context.filesDir, "friday_ssh_known_hosts") }

    @Volatile
    private var activeSession: Session? = null

    @Volatile
    private var activeProfileKey: String? = null

    @Volatile
    private var activeChannel: ChannelExec? = null

    fun validateProfile(profile: SshConnectionProfile): String? {
        if (profile.host.isBlank()) return "SSH host IP or domain is required."
        if (profile.port !in 1..65535) return "SSH port must be between 1 and 65535."
        if (profile.username.isBlank()) return "SSH username is required."
        return when (profile.authMethod) {
            SshAuthMethod.PASSWORD ->
                if (profile.password.isBlank()) "SSH password is required for Password auth." else null
            SshAuthMethod.PRIVATE_KEY ->
                if (profile.privateKeyPem.isBlank()) "PEM / OpenSSH private key is required for Key auth." else null
        }
    }

    fun isConnected(): Boolean = activeSession?.isConnected == true

    private fun profileSignature(profile: SshConnectionProfile): String =
        "${profile.username.trim()}@${profile.host.trim()}:${profile.port}:${profile.authMethod.name}"

    private fun createConfiguredJsch(profile: SshConnectionProfile): JSch {
        if (!knownHostsFile.exists()) {
            knownHostsFile.createNewFile()
        }
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        if (profile.authMethod == SshAuthMethod.PRIVATE_KEY && profile.privateKeyPem.isNotBlank()) {
            val passphraseBytes = profile.keyPassphrase
                .takeIf { it.isNotEmpty() }
                ?.toByteArray(StandardCharsets.UTF_8)
            jsch.addIdentity(
                "friday_operator_key",
                profile.privateKeyPem.trim().toByteArray(StandardCharsets.UTF_8),
                null,
                passphraseBytes
            )
        }
        return jsch
    }

    private fun openSession(profile: SshConnectionProfile): Pair<Session, SshConnectionSummary> {
        val jsch = createConfiguredJsch(profile)
        val cleanHost = profile.host.trim()
        val hostLookupKey = if (profile.port == 22) cleanHost else "[$cleanHost]:${profile.port}"
        val alreadyTrusted = jsch.hostKeyRepository
            .getHostKey(hostLookupKey, null)
            ?.isNotEmpty() == true

        val session = jsch.getSession(profile.username.trim(), cleanHost, profile.port)
        if (profile.authMethod == SshAuthMethod.PASSWORD) {
            session.setPassword(profile.password)
        }
        // Enforce strict key verification once host is in known_hosts; TOFU on initial connect
        session.setConfig("StrictHostKeyChecking", if (alreadyTrusted) "yes" else "no")
        session.setConfig(
            "PreferredAuthentications",
            if (profile.authMethod == SshAuthMethod.PRIVATE_KEY) "publickey,keyboard-interactive,password"
            else "password,keyboard-interactive,publickey"
        )
        session.connect(10_000)

        val hostKey: HostKey? = session.hostKey
        if (hostKey != null && !alreadyTrusted) {
            runCatching {
                jsch.hostKeyRepository.add(hostKey, null)
                val repo: HostKeyRepository = jsch.hostKeyRepository
                if (repo.getHostKey(hostLookupKey, null)?.isNotEmpty() == true) {
                    // known_hosts updated automatically by JSch when setKnownHosts was configured
                }
            }
        }
        val fingerprint = hostKey?.getFingerPrint(jsch).orEmpty()
        val banner = session.serverVersion ?: "SSH-2.0 Remote Linux"
        return session to SshConnectionSummary(serverBanner = banner, hostFingerprint = fingerprint)
    }

    suspend fun connectAndTest(profile: SshConnectionProfile): Result<SshConnectionSummary> =
        withContext(Dispatchers.IO) {
            val validationError = validateProfile(profile)
            if (validationError != null) {
                return@withContext Result.failure(IllegalArgumentException(validationError))
            }
            sessionMutex.withLock {
                disconnectInternal()
                try {
                    val (session, summary) = openSession(profile)
                    activeSession = session
                    activeProfileKey = profileSignature(profile)
                    Result.success(summary)
                } catch (e: Exception) {
                    disconnectInternal()
                    val msg = CredentialRedactor.redact(
                        "SSH connection failed (${e.javaClass.simpleName}): ${e.localizedMessage ?: "unable to authenticate or reach host"}."
                    )
                    Result.failure(IllegalStateException(msg, e))
                }
            }
        }

    suspend fun disconnect(): Unit = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            disconnectInternal()
        }
    }

    fun abortRunningChannel() {
        runCatching { activeChannel?.disconnect() }
        activeChannel = null
    }

    private fun disconnectInternal() {
        runCatching { activeChannel?.disconnect() }
        activeChannel = null
        runCatching { activeSession?.disconnect() }
        activeSession = null
        activeProfileKey = null
    }

    suspend fun executeCommand(
        profile: SshConnectionProfile,
        command: String,
        timeoutSeconds: Int
    ): BridgeExecutionResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val validationError = validateProfile(profile)
        if (validationError != null) {
            return@withContext BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = validationError,
                environment = TerminalEnvironment.LINUX_SERVER_BRIDGE
            )
        }

        val session = sessionMutex.withLock {
            val sig = profileSignature(profile)
            val current = activeSession
            if (current != null && current.isConnected && activeProfileKey == sig) {
                current
            } else {
                null
            }
        } ?: return@withContext BridgeExecutionResult(
            executed = false,
            status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
            exitCode = -1,
            stdout = "",
            stderr = "Remote SSH session is disconnected. Tap 'Connect & Test SSH' before running commands.",
            environment = TerminalEnvironment.LINUX_SERVER_BRIDGE
        )

        var channel: ChannelExec? = null
        try {
            withTimeout(timeoutSeconds.coerceIn(5, 300) * 1000L) {
                channel = (session.openChannel("exec") as ChannelExec).apply {
                    setCommand(command)
                    setInputStream(null)
                }
                activeChannel = channel

                val stdoutStream = ByteArrayOutputStream()
                val stderrStream = ByteArrayOutputStream()
                val execChannel = channel!!
                val inStream = execChannel.inputStream
                val errStream = execChannel.errStream
                execChannel.connect(10_000)

                val buffer = ByteArray(2048)
                while (true) {
                    while (inStream.available() > 0) {
                        val read = inStream.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        stdoutStream.write(buffer, 0, read)
                    }
                    while (errStream.available() > 0) {
                        val read = errStream.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        stderrStream.write(buffer, 0, read)
                    }
                    if (execChannel.isClosed) {
                        if (inStream.available() == 0 && errStream.available() == 0) break
                    }
                    delay(40)
                }

                val exitCode = execChannel.exitStatus
                val rawOut = stdoutStream.toString(StandardCharsets.UTF_8.name())
                val rawErr = stderrStream.toString(StandardCharsets.UTF_8.name())
                val safeOut = scrubSecrets(rawOut, profile).take(6000)
                val safeErr = scrubSecrets(rawErr, profile).take(2500)

                BridgeExecutionResult(
                    executed = true,
                    status = if (exitCode == 0) TerminalExecutionStatus.EXECUTED else TerminalExecutionStatus.ERROR,
                    exitCode = exitCode,
                    stdout = safeOut,
                    stderr = safeErr,
                    environment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
                    executionTimeMillis = System.currentTimeMillis() - started
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            runCatching { channel?.disconnect() }
            BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.TIMEOUT,
                exitCode = -1,
                stdout = "",
                stderr = "SSH command timed out after ${timeoutSeconds}s and channel was closed.",
                environment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
                executionTimeMillis = System.currentTimeMillis() - started
            )
        } catch (cancel: CancellationException) {
            runCatching { channel?.disconnect() }
            throw cancel
        } catch (e: Exception) {
            BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.ERROR,
                exitCode = -1,
                stdout = "",
                stderr = scrubSecrets(
                    "SSH execution error (${e.javaClass.simpleName}): ${e.localizedMessage ?: "channel error"}",
                    profile
                ),
                environment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
                executionTimeMillis = System.currentTimeMillis() - started
            )
        } finally {
            activeChannel = null
            runCatching { channel?.disconnect() }
        }
    }

    private fun scrubSecrets(raw: String, profile: SshConnectionProfile): String {
        var cleaned = CredentialRedactor.redact(raw)
        if (profile.password.length >= 3) {
            cleaned = cleaned.replace(profile.password, "[REDACTED_SSH_PASSWORD]")
        }
        if (profile.keyPassphrase.length >= 3) {
            cleaned = cleaned.replace(profile.keyPassphrase, "[REDACTED_SSH_PASSPHRASE]")
        }
        return cleaned
    }
}

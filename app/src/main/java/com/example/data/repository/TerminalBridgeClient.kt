package com.example.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.example.core.CommandPolicyValidator
import com.example.core.CredentialRedactor
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.CodeLanguage
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class TerminalBridgeClient(
    private val context: Context? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    fun isTermuxAppInstalled(): Boolean {
        val pm = context?.packageManager ?: return false
        return try {
            pm.getPackageInfo("com.termux", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun validateEndpointSecurity(endpointUrl: String): String? {
        val trimmed = endpointUrl.trim()
        if (trimmed.isEmpty()) return "Bridge endpoint URL is required."
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "Invalid URL syntax."
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (host.isBlank()) return "Endpoint URL must include a valid host."
        if (scheme == "https") return null
        if (scheme == "http" && CommandPolicyValidator.isPrivateOrLabTarget(host)) {
            return null
        }
        return "Remote servers require HTTPS to protect authentication tokens and terminal output."
    }

    suspend fun testConnection(endpointUrl: String, sessionToken: String): Result<String> =
        withContext(Dispatchers.IO) {
            val securityError = validateEndpointSecurity(endpointUrl)
            if (securityError != null) {
                return@withContext Result.failure(IllegalArgumentException(securityError))
            }
            val cleanBase = endpointUrl.trim().trimEnd('/')
            val requestBuilder = Request.Builder()
                .url("$cleanBase/health")
                .get()
            if (sessionToken.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer ${sessionToken.trim()}")
            }
            try {
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = CredentialRedactor.redact(response.body?.string().orEmpty().take(500))
                    if (response.isSuccessful) {
                        Result.success("Connected (HTTP ${response.code}): ${body.ifBlank { "Bridge ready" }}")
                    } else {
                        Result.failure(IllegalStateException("Bridge responded HTTP ${response.code}: $body"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(
                    IllegalStateException(
                        "Could not reach bridge at $cleanBase (${e.javaClass.simpleName}: ${e.localizedMessage ?: "connection refused"})."
                    )
                )
            }
        }

    suspend fun executeApprovedCommand(
        command: String,
        environment: TerminalEnvironment,
        endpointUrl: String,
        sessionToken: String
    ): BridgeExecutionResult = withContext(Dispatchers.IO) {
        val securityError = validateEndpointSecurity(endpointUrl)
        if (securityError != null) {
            return@withContext BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = securityError,
                environment = environment
            )
        }

        val cleanBase = endpointUrl.trim().trimEnd('/')
        val payload = JSONObject().apply {
            put("command", command)
            put("environment", environment.name)
        }
        val requestBuilder = Request.Builder()
            .url("$cleanBase/execute")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (sessionToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${sessionToken.trim()}")
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext BridgeExecutionResult(
                        executed = false,
                        status = TerminalExecutionStatus.ERROR,
                        exitCode = response.code,
                        stdout = "",
                        stderr = CredentialRedactor.redact(
                            "Bridge returned HTTP ${response.code}: ${rawBody.take(1500)}"
                        ),
                        environment = environment
                    )
                }

                val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
                val exitCode = parsed?.optInt("exitCode", 0) ?: 0
                val stdout = CredentialRedactor.redact(
                    parsed?.optString("stdout", "") ?: rawBody
                )
                val stderr = CredentialRedactor.redact(
                    parsed?.optString("stderr", "").orEmpty()
                )
                BridgeExecutionResult(
                    executed = true,
                    status = if (exitCode == 0) TerminalExecutionStatus.EXECUTED else TerminalExecutionStatus.ERROR,
                    exitCode = exitCode.coerceIn(-1, 255),
                    stdout = stdout.take(6000),
                    stderr = stderr.take(2000),
                    environment = environment
                )
            }
        } catch (e: Exception) {
            BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = CredentialRedactor.redact(
                    "Bridge unreachable at $cleanBase (${e.localizedMessage ?: "connection refused"}). Verify your Termux/Linux bridge daemon is running. No fake output generated."
                ),
                environment = environment
            )
        }
    }

    suspend fun executeInIsolatedSandbox(
        language: CodeLanguage,
        code: String,
        endpointUrl: String,
        sessionToken: String
    ): BridgeExecutionResult = withContext(Dispatchers.IO) {
        val securityError = validateEndpointSecurity(endpointUrl)
        if (securityError != null) {
            return@withContext BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = securityError,
                environment = TerminalEnvironment.CODE_SANDBOX
            )
        }

        val cleanBase = endpointUrl.trim().trimEnd('/')
        val payload = JSONObject().apply {
            put("language", language.name.lowercase())
            put("code", code)
        }
        val requestBuilder = Request.Builder()
            .url("$cleanBase/sandbox/run")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (sessionToken.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${sessionToken.trim()}")
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext BridgeExecutionResult(
                        executed = false,
                        status = TerminalExecutionStatus.ERROR,
                        exitCode = response.code,
                        stdout = "",
                        stderr = CredentialRedactor.redact("Sandbox HTTP ${response.code}: ${rawBody.take(1500)}"),
                        environment = TerminalEnvironment.CODE_SANDBOX
                    )
                }
                val parsed = runCatching { JSONObject(rawBody) }.getOrNull()
                val exitCode = parsed?.optInt("exitCode", 0) ?: 0
                val stdout = CredentialRedactor.redact(parsed?.optString("stdout", "") ?: rawBody)
                val stderr = CredentialRedactor.redact(parsed?.optString("stderr", "").orEmpty())
                BridgeExecutionResult(
                    executed = true,
                    status = if (exitCode == 0) TerminalExecutionStatus.EXECUTED else TerminalExecutionStatus.ERROR,
                    exitCode = exitCode.coerceIn(-1, 255),
                    stdout = stdout.take(6000),
                    stderr = stderr.take(2000),
                    environment = TerminalEnvironment.CODE_SANDBOX
                )
            }
        } catch (e: Exception) {
            BridgeExecutionResult(
                executed = false,
                status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                exitCode = -1,
                stdout = "",
                stderr = CredentialRedactor.redact(
                    "Isolated Sandbox not reachable at $cleanBase (${e.localizedMessage ?: "connection refused"}). Start your isolated Docker/Termux runner to execute ${language.displayName} safely."
                ),
                environment = TerminalEnvironment.CODE_SANDBOX
            )
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class LanguageMode(val displayLabel: String, val speechLocaleTag: String) {
    HINGLISH("Hinglish", "hi-IN"),
    HINDI("हिन्दी", "hi-IN"),
    ENGLISH("English", "en-IN");

    companion object {
        fun fromRaw(raw: String): LanguageMode =
            entries.firstOrNull { it.name == raw } ?: HINGLISH
    }
}

enum class AiModelOption(
    val modelId: String,
    val displayName: String,
    val tierBadge: String,
    val description: String,
    val contextSize: Int,
    val maxTokens: Int,
    val temperature: Float
) {
    DHIR_2B_BALANCED(
        modelId = "dhir-2b-Q4_K_M.gguf",
        displayName = "Dhīr 2B • Balanced",
        tierBadge = "LOCAL Q4_K_M",
        description = "On-device Dhīr 2B GGUF (llama.cpp • 4K ctx • balanced)",
        contextSize = 4096,
        maxTokens = 768,
        temperature = 0.35f
    ),
    DHIR_2B_FAST(
        modelId = "dhir-2b-Q4_K_M.gguf#fast",
        displayName = "Dhīr 2B • Fast",
        tierBadge = "LOW RAM",
        description = "Low-RAM fast mobile preset (2K ctx • crisp replies)",
        contextSize = 2048,
        maxTokens = 384,
        temperature = 0.25f
    ),
    DHIR_2B_DEEP(
        modelId = "dhir-2b-Q4_K_M.gguf#deep",
        displayName = "Dhīr 2B • Deep",
        tierBadge = "4K RESEARCH",
        description = "Extended reasoning & tool synthesis with Dhīr 2B",
        contextSize = 4096,
        maxTokens = 1024,
        temperature = 0.45f
    );

    companion object {
        fun fromModelId(id: String): AiModelOption =
            entries.firstOrNull { it.modelId == id } ?: DHIR_2B_BALANCED
    }
}

enum class DhirModelStatus(val label: String) {
    NOT_INSTALLED("Model Missing"),
    VALIDATING("Validating GGUF…"),
    DOWNLOADING("Downloading GGUF…"),
    IMPORTING("Importing GGUF…"),
    READY_UNLOADED("GGUF Ready (Unloaded)"),
    LOADING("Loading into RAM…"),
    LOADED("Active in RAM"),
    ERROR("Model Error")
}

data class DhirModelState(
    val status: DhirModelStatus = DhirModelStatus.NOT_INSTALLED,
    val expectedFilename: String = "dhir-2b-Q4_K_M.gguf",
    val activeFileName: String = "",
    val activeFilePath: String = "",
    val fileSizeBytes: Long = 0L,
    val downloadProgressPercent: Int = 0,
    val detectedArchitecture: String = "",
    val effectiveContextSize: Int = 0,
    val totalDeviceRamMb: Long = 0L,
    val availableDeviceRamMb: Long = 0L,
    val nativeBackendReady: Boolean = false,
    val statusDetail: String = "Import or download dhir-2b-Q4_K_M.gguf to enable offline on-device Dhīr 2B inference."
)

data class SyncStatusState(
    val isOnline: Boolean = false,
    val isSignedIn: Boolean = false,
    val pendingChangesCount: Int = 0,
    val isSyncing: Boolean = false,
    val lastSyncSummary: String = "Local Room vault active."
)

data class ToolExecutionTrace(
    val toolName: String,
    val summary: String,
    val sourceUrls: List<String> = emptyList()
)

data class YouTubeVideoSummary(
    val videoId: String,
    val title: String,
    val channel: String,
    val url: String,
    val durationLabel: String = "",
    val transcriptExcerpt: String = "",
    val transcriptAvailable: Boolean = false
)

enum class AgentType(
    val title: String,
    val subtitle: String,
    val promptHint: String
) {
    CHAT(
        title = "FRIDAY Core Chat",
        subtitle = "Local Dhīr 2B AI, coding & cyber mentor",
        promptHint = "Ask anything or say \"FRIDAY, write Python code\" / \"explain in Hindi\"…"
    ),
    RESEARCH(
        title = "Research Agent",
        subtitle = "CVEs, frameworks & threat intel",
        promptHint = "e.g. Explain CVE lifecycle, Zero Trust, or MITRE ATT&CK…"
    ),
    LEARNING(
        title = "Security Learning Agent",
        subtitle = "Step-by-step study plans & analogies",
        promptHint = "e.g. Teach me Linux privilege escalation concepts safely…"
    ),
    CODE_ANALYSIS(
        title = "Code Analysis Agent",
        subtitle = "Static defensive code & config review",
        promptHint = "Paste Kotlin, Python, SQL, or config code for security review…"
    ),
    REPORT_ANALYSIS(
        title = "Report Analysis Agent",
        subtitle = "Authorized scan & log triage",
        promptHint = "Paste authorized Nmap/ZAP/HTTP header output for remediation…"
    ),
    CODING(
        title = "Coding Agent",
        subtitle = "Python, Java, Kotlin, JS & Bash studio",
        promptHint = "Describe the script, function, or error you want debugged…"
    ),
    FILE_ANALYSIS(
        title = "File Agent",
        subtitle = "Inspect, debug & patch uploaded files",
        promptHint = "Ask FRIDAY to analyze or propose an approved fix for the open file…"
    ),
    WEB_RESEARCH(
        title = "Web Research Agent",
        subtitle = "Live web search, URL & YouTube research",
        promptHint = "Search the web, YouTube transcripts, or paste an https:// URL…"
    ),
    TERMINAL_REVIEW(
        title = "Terminal Agent",
        subtitle = "Safe command explanation & review",
        promptHint = "Enter a Linux/Termux/Nmap command for safety check before approval…"
    );

    companion object {
        fun fromRaw(raw: String): AgentType =
            entries.firstOrNull { it.name == raw } ?: CHAT
    }
}

enum class CodeLanguage(
    val displayName: String,
    val extension: String,
    val starterTemplate: String
) {
    PYTHON(
        displayName = "Python",
        extension = "py",
        starterTemplate = """# Authorized Port Header Checker (Educational)
import socket

def check_banner(host: str, port: int = 80) -> str:
    with socket.create_connection((host, port), timeout=3.0) as sock:
        sock.sendall(b"HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n")
        return sock.recv(512).decode("utf-8", errors="replace")

print(check_banner("127.0.0.1", 80))
"""
    ),
    JAVA(
        displayName = "Java",
        extension = "java",
        starterTemplate = """import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public class HashCheck {
    public static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
"""
    ),
    KOTLIN(
        displayName = "Kotlin",
        extension = "kt",
        starterTemplate = """fun sanitizeTargetInput(raw: String): String {
    val allowed = Regex("^[a-zA-Z0-9.:/\\-]+$")
    val trimmed = raw.trim()
    require(allowed.matches(trimmed)) { "Invalid target format" }
    return trimmed
}
"""
    ),
    JAVASCRIPT(
        displayName = "JavaScript",
        extension = "js",
        starterTemplate = """// Validate CORS origin allowlist safely
const ALLOWED_ORIGINS = new Set(["https://app.example.com"]);

export function isOriginAllowed(origin) {
  return typeof origin === "string" && ALLOWED_ORIGINS.has(origin);
}
"""
    ),
    BASH(
        displayName = "Bash",
        extension = "sh",
        starterTemplate = """#!/usr/bin/env bash
set -euo pipefail

# Audit listening TCP sockets locally on authorized host
ss -tulnp | head -n 20
"""
    );
}

enum class NoteCategory(val label: String) {
    NETWORKING("Networking"),
    LINUX("Linux"),
    OWASP("OWASP"),
    WEB_API("Web & API"),
    AGENT_BRIEF("Agent Brief"),
    GENERAL("General");

    companion object {
        fun fromRaw(raw: String): NoteCategory =
            entries.firstOrNull { it.name == raw } ?: GENERAL
    }
}

enum class TerminalEnvironment(val displayName: String, val badge: String) {
    TERMUX_BRIDGE("Termux Bridge", "TERMUX"),
    LINUX_SERVER_BRIDGE("Controlled Linux Server", "LINUX SSH-API"),
    CODE_SANDBOX("Isolated Code Sandbox", "SANDBOX");

    companion object {
        fun fromRaw(raw: String): TerminalEnvironment =
            entries.firstOrNull { it.name == raw } ?: TERMUX_BRIDGE
    }
}

enum class TerminalExecutionStatus(val label: String) {
    EXECUTED("Executed (Approved)"),
    CANCELLED("Cancelled by Operator"),
    BLOCKED("Blocked by Policy"),
    BRIDGE_UNAVAILABLE("Bridge Disconnected"),
    TIMEOUT("Timed Out"),
    ERROR("Execution Error");

    companion object {
        fun fromRaw(raw: String): TerminalExecutionStatus =
            entries.firstOrNull { it.name == raw } ?: ERROR
    }
}

enum class TermuxConnectionMode(val displayName: String) {
    RUN_COMMAND_SERVICE("Termux RunCommand"),
    COMPANION_HTTP("Local HTTP Companion")
}

enum class ToolAvailability(val label: String) {
    UNKNOWN("Not Checked"),
    INSTALLED("Installed"),
    UNAVAILABLE("Unavailable")
}

data class TermuxEnvironmentState(
    val apkInstalled: Boolean = false,
    val apkVersion: String = "",
    val runCommandPermissionGranted: Boolean = false,
    val companionMode: TermuxConnectionMode = TermuxConnectionMode.RUN_COMMAND_SERVICE,
    val companionUrl: String = "http://127.0.0.1:8022",
    val companionToken: String = "",
    val isConnected: Boolean = false,
    val isChecking: Boolean = false,
    val statusMessage: String = "Not connected. Verify Termux APK & RUN_COMMAND permission or Local Companion.",
    val toolStatuses: Map<String, ToolAvailability> = mapOf(
        "python3" to ToolAvailability.UNKNOWN,
        "bash" to ToolAvailability.UNKNOWN,
        "nmap" to ToolAvailability.UNKNOWN
    )
)

enum class SshAuthMethod(val displayName: String) {
    PASSWORD("Password"),
    PRIVATE_KEY("Private Key");

    companion object {
        fun fromRaw(raw: String): SshAuthMethod =
            entries.firstOrNull { it.name == raw } ?: PASSWORD
    }
}

data class SshConnectionProfile(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: SshAuthMethod = SshAuthMethod.PASSWORD,
    val password: String = "",
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    val persistSecretsEncrypted: Boolean = false
)

data class SshConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val serverInfo: String = "",
    val hostFingerprint: String = "",
    val statusDetail: String = "SSH disconnected. Enter server details and run Connect & Test."
)

enum class CommandRiskLevel(val label: String) {
    SAFE_READONLY("Low Risk • Inspection"),
    NETWORK_AUDIT_AUTHORIZED("Medium Risk • Authorized Network Audit"),
    ELEVATED_MODIFY("High Attention • System / File Action"),
    BLOCKED_DANGEROUS("Blocked • Destructive or Unauthorized")
}

@Keep
@IgnoreExtraProperties
data class LearningNote(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val content: String = "",
    val category: String = NoteCategory.GENERAL.name,
    val pinned: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val categoryEnum: NoteCategory
        get() = NoteCategory.fromRaw(category)
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "USER",
    val content: String = "",
    val languageMode: String = LanguageMode.HINGLISH.name,
    val agentType: String = AgentType.CHAT.name,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    val isFromFriday: Boolean
        get() = role == "FRIDAY"
}

@Keep
@IgnoreExtraProperties
data class TerminalLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val command: String = "",
    val environment: String = TerminalEnvironment.TERMUX_BRIDGE.name,
    val status: String = TerminalExecutionStatus.BRIDGE_UNAVAILABLE.name,
    val output: String = "",
    val exitCode: Int = -1,
    val approvedByUser: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
) {
    val environmentEnum: TerminalEnvironment
        get() = TerminalEnvironment.fromRaw(environment)

    val statusEnum: TerminalExecutionStatus
        get() = TerminalExecutionStatus.fromRaw(status)
}

data class PendingCommandApproval(
    val command: String,
    val environment: TerminalEnvironment,
    val riskLevel: CommandRiskLevel,
    val reason: String,
    val targetLabel: String = "",
    val targetHostSummary: String = "",
    val timeoutSeconds: Int = 30,
    val requiresAuthorizationConfirmation: Boolean = false,
    val isBlocked: Boolean = false
)

data class BridgeConnectionConfig(
    val environment: TerminalEnvironment = TerminalEnvironment.TERMUX_BRIDGE,
    val endpointUrl: String = "http://127.0.0.1:8022",
    val sessionToken: String = "",
    val termuxInstalledOnDevice: Boolean = false,
    val bridgeReachable: Boolean = false,
    val bridgeStatusDetail: String = "Not tested yet. Configure your local Termux/Linux bridge URL and test connection."
)

data class BridgeExecutionResult(
    val executed: Boolean,
    val status: TerminalExecutionStatus,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val environment: TerminalEnvironment,
    val executionTimeMillis: Long = 0L
)

data class QuickCommandItem(
    val id: String,
    val label: String,
    val command: String,
    val isBuiltIn: Boolean = false
)

enum class ExecutionEnvFilter(val label: String) {
    ALL("All Envs"),
    TERMUX("Termux"),
    SSH("Remote SSH"),
    SANDBOX("Sandbox")
}

enum class ExecutionStatusFilter(val label: String) {
    ALL("All Status"),
    EXECUTED("Executed"),
    ERROR("Errors"),
    TIMEOUT("Timed Out"),
    CANCELLED("Cancelled"),
    BLOCKED("Blocked"),
    OFFLINE("Offline")
}

data class LoadedProjectFile(
    val uriString: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val content: String,
    val truncated: Boolean = false
)

data class ProposedFilePatch(
    val fileName: String,
    val originalContent: String,
    val proposedContent: String,
    val summary: String,
    val applied: Boolean = false
)

data class WebCitation(
    val title: String,
    val url: String
)

data class WebResearchResult(
    val query: String,
    val answer: String,
    val citations: List<WebCitation>,
    val searchQueriesUsed: List<String>,
    val explainedInHindi: Boolean,
    val youtubeResults: List<YouTubeVideoSummary> = emptyList(),
    val modeNote: String = ""
)

sealed interface ConversationalAction {
    data object None : ConversationalAction
    data class OpenCoding(val language: CodeLanguage, val prompt: String) : ConversationalAction
    data class RequestTerminalApproval(val command: String) : ConversationalAction
    data class RequestAuthorizedNmap(val target: String, val suggestedCommand: String) : ConversationalAction
    data class RunWebResearch(val query: String, val explainInHindi: Boolean) : ConversationalAction
    data object OpenFileInspector : ConversationalAction
}

sealed interface UiState<out T> {
    data object Idle : UiState<Nothing>
    data object Loading : UiState<Nothing>
    data class Streaming(val text: String) : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

// Learning Lab & Security Agent Models
data class LabQuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

data class LabModule(
    val id: String,
    val title: String,
    val summary: String,
    val keyConcepts: List<String>,
    val commandOrCodeSnippet: String,
    val defensiveTakeaway: String,
    val quiz: LabQuizQuestion
)

data class LabTrack(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: NoteCategory,
    val difficulty: String,
    val modules: List<LabModule>
)

data class KaliToolDoc(
    val name: String,
    val category: String,
    val purpose: String,
    val safeSyntax: String,
    val simulatedLabOutput: String,
    val authorizationRule: String
)

data class ChecklistItem(
    val id: String,
    val title: String,
    val detail: String
)

data class SecurityChecklist(
    val id: String,
    val title: String,
    val domain: String,
    val items: List<ChecklistItem>
)

data class SampleLabReport(
    val id: String,
    val title: String,
    val label: String,
    val rawContent: String
)

package com.example.core

import com.example.data.model.CodeLanguage
import com.example.data.model.CommandRiskLevel
import com.example.data.model.ConversationalAction
import com.example.data.model.PendingCommandApproval
import com.example.data.model.TerminalEnvironment

/**
 * Redacts passwords, bearer tokens, API keys, and private key blocks before
 * displaying execution traces or persisting logs to Firestore.
 */
object CredentialRedactor {
    private val patterns = listOf(
        // Key=Value / Header style credentials
        Regex(
            """(?i)\b(password|passwd|pwd|api[_-]?key|secret|token|bearer|auth[_-]?token|private[_-]?key)\s*[:=]\s*(['"]?)[^\s'"]{3,}\2"""
        ) to "$1=[REDACTED]",
        // Authorization header
        Regex("""(?i)(Authorization\s*:\s*(?:Bearer|Basic)\s+)[^\s\r\n]+""") to "$1[REDACTED]",
        // CLI flags carrying credentials (avoid false positive on nmap -p / ssh -p port flags)
        Regex("""(?i)(--password|--passwd|--passphrase|--token|--api-key|sshpass\s+-p)\s+(['"]?)[^\s'"]+\2""") to "$1 [REDACTED]",
        // URLs with embedded userinfo: https://user:pass@host
        Regex("""(https?://)([^:\s/@]+):([^@\s/]+)@""") to "$1$2:[REDACTED]@",
        // Common API key prefixes
        Regex("""\b(AIza[0-9A-Za-z\-_]{20,}|sk-[0-9A-Za-z]{16,}|ghp_[0-9A-Za-z]{20,}|xoxb-[0-9A-Za-z\-]{16,})\b""") to "[REDACTED_KEY]",
        // PEM Private key blocks
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""") to "[REDACTED_PRIVATE_KEY]"
    )

    fun redact(raw: String): String {
        if (raw.isBlank()) return raw
        var sanitized = raw
        patterns.forEach { (regex, replacement) ->
            sanitized = sanitized.replace(regex, replacement)
        }
        return sanitized
    }

    fun redactWithSecrets(raw: String, extraSecrets: List<String> = emptyList()): String {
        var sanitized = redact(raw)
        extraSecrets
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .forEach { secret ->
                sanitized = sanitized.replace(secret, "[REDACTED_SECRET]")
            }
        return sanitized
    }
}

/**
 * Validates terminal and Nmap commands against FRIDAY's safety and authorization rules.
 */
object CommandPolicyValidator {

    private val dangerousPatterns = listOf(
        Regex("""(?i)\brm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+/(\s|$)"""),
        Regex("""(?i):\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;"""),
        Regex("""(?i)\b(mkfs|wipefs|fdisk|parted)\b"""),
        Regex("""(?i)\bdd\s+.*\bof=/dev/(sd|nvme|mmcblk|vd)"""),
        Regex("""(?i)/etc/shadow"""),
        Regex("""(?i)\bnc\b.*-e\s+/bin/(ba)?sh"""),
        Regex("""(?i)/dev/tcp/"""),
        Regex("""(?i)\b(sqlmap|hydra|medusa|metasploit|msfconsole|msfvenom)\b"""),
        Regex("""(?i)--script\s*=?\s*(exploit|brute|dos|intrusive|malware|vuln)""")
    )

    private val readOnlyPrefixes = listOf(
        "ls", "pwd", "whoami", "id", "uname", "date", "uptime", "df", "free",
        "ps", "ss", "ip ", "ifconfig", "ping ", "dig ", "nslookup", "cat ",
        "head ", "tail ", "grep ", "which ", "command -v ", "for t in python3 bash nmap",
        "python3 --version", "bash --version", "nmap --version", "node -v",
        "java -version", "kotlinc -version"
    )

    fun isPrivateOrLabTarget(target: String): Boolean {
        val clean = target.trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "::1" || clean.endsWith(".local")) {
            return true
        }
        val bareIp = clean.substringBefore("/")
        val parts = bareIp.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            val first = parts[0].toInt()
            val second = parts[1].toInt()
            return when {
                first == 127 -> true
                first == 10 -> true
                first == 192 && second == 168 -> true
                first == 172 && second in 16..31 -> true
                else -> false
            }
        }
        return false
    }

    fun evaluateCommand(
        rawCommand: String,
        environment: TerminalEnvironment,
        targetHostSummary: String = "",
        timeoutSeconds: Int = 30
    ): PendingCommandApproval {
        val cmd = rawCommand.trim()
        val safeTimeout = timeoutSeconds.coerceIn(5, 300)
        if (cmd.isEmpty()) {
            return PendingCommandApproval(
                command = "",
                environment = environment,
                riskLevel = CommandRiskLevel.BLOCKED_DANGEROUS,
                reason = "Command cannot be empty.",
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                isBlocked = true
            )
        }

        val blockedMatch = dangerousPatterns.firstOrNull { it.containsMatchIn(cmd) }
        if (blockedMatch != null) {
            return PendingCommandApproval(
                command = CredentialRedactor.redact(cmd),
                environment = environment,
                riskLevel = CommandRiskLevel.BLOCKED_DANGEROUS,
                reason = "Blocked by FRIDAY Security Policy: destructive, credential-dumping, or unauthorized exploitation command detected.",
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                isBlocked = true
            )
        }

        val lower = cmd.lowercase()
        if (lower.startsWith("nmap ") || lower == "nmap") {
            val tokens = cmd.split(Regex("\\s+")).filter { it.isNotBlank() && !it.startsWith("-") }
            val targetToken = tokens.drop(1).lastOrNull().orEmpty()
            val isVersionProbe = lower.contains("--version") || lower == "nmap -v"
            val isPrivateLab = isPrivateOrLabTarget(targetToken)
            val reason = when {
                isVersionProbe -> "Nmap version inspection queued for operator approval."
                targetToken.isBlank() -> "Nmap audit requires an explicit target host or subnet."
                isPrivateLab -> "Authorized private/lab subnet detected ($targetToken). Explicit operator approval required."
                else -> "Target ($targetToken) is outside RFC1918/loopback ranges. Confirm explicit written authorization before scanning."
            }
            return PendingCommandApproval(
                command = CredentialRedactor.redact(cmd),
                environment = environment,
                riskLevel = if (isVersionProbe) CommandRiskLevel.SAFE_READONLY else CommandRiskLevel.NETWORK_AUDIT_AUTHORIZED,
                reason = reason,
                targetLabel = targetToken,
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                requiresAuthorizationConfirmation = !isVersionProbe,
                isBlocked = !isVersionProbe && targetToken.isBlank()
            )
        }

        val isReadOnly = readOnlyPrefixes.any { lower == it.trim() || lower.startsWith(it) }
        return PendingCommandApproval(
            command = CredentialRedactor.redact(cmd),
            environment = environment,
            riskLevel = if (isReadOnly) CommandRiskLevel.SAFE_READONLY else CommandRiskLevel.ELEVATED_MODIFY,
            reason = if (isReadOnly) {
                "Inspection command queued for your explicit approval before execution."
            } else {
                "Custom shell command queued. Verify flags carefully before approving execution."
            },
            targetHostSummary = targetHostSummary,
            timeoutSeconds = safeTimeout,
            requiresAuthorizationConfirmation = !isReadOnly,
            isBlocked = false
        )
    }
}

/**
 * Parses natural-language FRIDAY conversational commands across English, Hindi & Hinglish
 * and maps them cleanly to actionable workflows without ever executing commands silently.
 */
object FridayCommandRouter {

    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val ipOrCidrRegex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?:/\d{1,2})?\b""")

    fun requestsHindiResponse(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return lower.contains("in hindi") ||
            lower.contains("hindi mein") ||
            lower.contains("hindi me ") ||
            lower.endsWith("hindi me") ||
            prompt.contains("हिन्दी में") ||
            prompt.contains("हिंदी में")
    }

    fun detectAction(prompt: String): ConversationalAction {
        val trimmed = prompt.trim()
        val lower = trimmed.lowercase()
        if (trimmed.isEmpty()) return ConversationalAction.None

        // 1. Authorized Nmap request
        if (lower.contains("nmap") || (lower.contains("scan") && (lower.contains("network") || lower.contains("port") || ipOrCidrRegex.containsMatchIn(trimmed)))) {
            val target = ipOrCidrRegex.find(trimmed)?.value ?: "192.168.1.0/24"
            val cmd = "nmap -sn $target"
            return ConversationalAction.RequestAuthorizedNmap(
                target = target,
                suggestedCommand = cmd
            )
        }

        // 2. Explicit terminal command request (never runs silently!)
        if (lower.contains("terminal command") ||
            lower.contains("run command") ||
            lower.startsWith("friday, run ") ||
            lower.startsWith("run `") ||
            lower.startsWith("execute `")
        ) {
            val backtickCmd = Regex("""`([^`]+)`""").find(trimmed)?.groupValues?.getOrNull(1)
            val colonCmd = trimmed.substringAfter(":", "").trim().takeIf { it.isNotBlank() }
            val extracted = backtickCmd ?: colonCmd ?: "uname -a"
            return ConversationalAction.RequestTerminalApproval(command = extracted)
        }

        // 3. Web search / URL summary
        val url = urlRegex.find(trimmed)?.value
        if (url != null ||
            lower.contains("search the internet") ||
            lower.contains("search the web") ||
            lower.contains("search web") ||
            lower.contains("summarize website") ||
            lower.contains("summarize this url") ||
            lower.contains("latest cve")
        ) {
            return ConversationalAction.RunWebResearch(
                query = trimmed,
                explainInHindi = requestsHindiResponse(trimmed)
            )
        }

        // 4. File inspection request
        if (lower.contains("open file") ||
            lower.contains("upload file") ||
            lower.contains("read file") ||
            lower.contains("analyze project file") ||
            lower.contains("read document")
        ) {
            return ConversationalAction.OpenFileInspector
        }

        // 5. Coding request
        val requestedLang = when {
            lower.contains("python") -> CodeLanguage.PYTHON
            lower.contains("kotlin") -> CodeLanguage.KOTLIN
            lower.contains("java") && !lower.contains("javascript") -> CodeLanguage.JAVA
            lower.contains("javascript") || lower.contains("node.js") -> CodeLanguage.JAVASCRIPT
            lower.contains("bash") || lower.contains("shell script") -> CodeLanguage.BASH
            else -> null
        }
        if (requestedLang != null || lower.contains("write code") || lower.contains("debug code")) {
            return ConversationalAction.OpenCoding(
                language = requestedLang ?: CodeLanguage.PYTHON,
                prompt = trimmed
            )
        }

        return ConversationalAction.None
    }
}

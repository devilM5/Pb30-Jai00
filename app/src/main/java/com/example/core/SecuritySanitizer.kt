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
 * Validates terminal and Nmap commands against FRIDAY's safety and authorization rules
 * using structural quote-aware shell tokenization rather than naive prefix checks.
 */
object CommandPolicyValidator {

    private data class ShellParseResult(
        val segments: List<String>,
        val hasChainingOrBackground: Boolean,
        val hasPipes: Boolean,
        val hasCommandSubstitution: Boolean,
        val hasRedirection: Boolean
    )

    private val dangerousCommandNames = setOf(
        "mkfs", "mkfs.ext4", "mkfs.ext3", "mkfs.vfat", "mkfs.xfs",
        "wipefs", "fdisk", "parted", "gdisk", "sfdisk", "shred",
        "sudo", "su", "doas", "pkexec",
        "sqlmap", "hydra", "medusa", "metasploit", "msfconsole", "msfvenom",
        "ettercap", "responder", "mimikatz"
    )

    private val fullCommandDangerousPatterns = listOf(
        Regex("""(?i):\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;?"""),
        Regex("""(?i)/etc/shadow"""),
        Regex("""(?i)/etc/sudoers"""),
        Regex("""(?i)/dev/tcp/"""),
        Regex("""(?i)/dev/udp/"""),
        Regex("""(?i)\bnc(?:at)?\b.*(?:-e|-c)\s+/bin/(?:ba|z|da)?sh"""),
        Regex("""(?i)\b(curl|wget|fetch)\b[\s\S]*\|\s*(?:sudo\s+)?(?:ba|z|da)?sh\b"""),
        Regex("""(?i)\bdd\b.*\bof=/dev/(?:sd|nvme|mmcblk|vd|hd|loop)"""),
        Regex("""(?i)--script\s*=?\s*(?:exploit|brute|dos|intrusive|malware|vuln)\b"""),
        Regex("""(?i)>\s*>?\s*/(?:etc|boot|dev|sys|proc|root/\.ssh)/""")
    )

    private val readOnlyCommandAllowlist = setOf(
        "ls", "pwd", "whoami", "id", "uname", "date", "uptime", "df", "free",
        "ps", "ss", "ip", "ifconfig", "ping", "dig", "nslookup", "cat",
        "head", "tail", "grep", "egrep", "wc", "sort", "uniq", "which", "echo"
    )

    private val diagnosticProbeExact =
        "for t in python3 bash nmap; do command -v \"\$t\" >/dev/null 2>&1 && echo \"FRIDAY_TOOL:\$t=AVAILABLE\" || echo \"FRIDAY_TOOL:\$t=UNAVAILABLE\"; done"

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

    /**
     * Execution-layer mandatory defense-in-depth gate called inside Termux, SSH, and HTTP bridge executors.
     * Prevents unapproved callers or bypassed UI flows from executing blocked or unapproved commands.
     */
    fun verifyExecutionAuthorization(
        rawCommand: String,
        environment: TerminalEnvironment,
        approvedByUser: Boolean
    ): String? {
        if (!approvedByUser) {
            return "Execution denied: explicit operator approval is required at the execution layer."
        }
        val check = evaluateCommand(rawCommand, environment)
        if (check.isBlocked) {
            return check.reason
        }
        return null
    }

    private fun parseShellStructure(command: String): ShellParseResult {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var escaped = false
        var hasChaining = false
        var hasPipes = false
        var hasSubst = false
        var hasRedir = false

        var i = 0
        while (i < command.length) {
            val ch = command[i]
            if (escaped) {
                current.append(ch)
                escaped = false
                i++
                continue
            }
            if (ch == '\\' && !inSingleQuote) {
                current.append(ch)
                escaped = true
                i++
                continue
            }
            if (ch == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote
                current.append(ch)
                i++
                continue
            }
            if (ch == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote
                current.append(ch)
                i++
                continue
            }
            if (ch == '`' && !inSingleQuote) {
                inBacktick = !inBacktick
                hasSubst = true
                current.append(ch)
                i++
                continue
            }

            if (!inSingleQuote) {
                if ((ch == '$' && i + 1 < command.length && command[i + 1] == '(') ||
                    ((ch == '<' || ch == '>') && i + 1 < command.length && command[i + 1] == '(')
                ) {
                    hasSubst = true
                }
            }

            if (!inSingleQuote && !inDoubleQuote && !inBacktick) {
                when {
                    ch == ';' || ch == '\n' || ch == '\r' -> {
                        hasChaining = true
                        if (current.isNotBlank()) segments += current.toString().trim()
                        current.clear()
                        i++
                        continue
                    }
                    ch == '&' && i + 1 < command.length && command[i + 1] == '&' -> {
                        hasChaining = true
                        if (current.isNotBlank()) segments += current.toString().trim()
                        current.clear()
                        i += 2
                        continue
                    }
                    ch == '|' && i + 1 < command.length && command[i + 1] == '|' -> {
                        hasChaining = true
                        if (current.isNotBlank()) segments += current.toString().trim()
                        current.clear()
                        i += 2
                        continue
                    }
                    ch == '|' -> {
                        hasPipes = true
                        if (current.isNotBlank()) segments += current.toString().trim()
                        current.clear()
                        i++
                        continue
                    }
                    ch == '&' -> {
                        val isFdRedir = i > 0 && (command[i - 1] == '>' || command[i - 1] == '<')
                        if (!isFdRedir) {
                            hasChaining = true
                            if (current.isNotBlank()) segments += current.toString().trim()
                            current.clear()
                            i++
                            continue
                        }
                    }
                    ch == '>' -> hasRedir = true
                }
            }
            current.append(ch)
            i++
        }
        if (current.isNotBlank()) {
            segments += current.toString().trim()
        }
        return ShellParseResult(
            segments = segments,
            hasChainingOrBackground = hasChaining,
            hasPipes = hasPipes,
            hasCommandSubstitution = hasSubst,
            hasRedirection = hasRedir
        )
    }

    private fun tokenizeSegment(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escaped = false
        for (ch in segment) {
            if (escaped) {
                cur.append(ch)
                escaped = false
                continue
            }
            if (ch == '\\' && !inSingle) {
                escaped = true
                continue
            }
            if (ch == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (ch == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (ch.isWhitespace() && !inSingle && !inDouble) {
                if (cur.isNotEmpty()) {
                    tokens += cur.toString()
                    cur.clear()
                }
            } else {
                cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens
    }

    private fun normalizeExecutableName(rawToken: String): String {
        val clean = rawToken.replace("\\", "").replace("\"", "").replace("'", "").trim()
        return clean.substringAfterLast('/').lowercase()
    }

    private fun isDangerousRmSegment(tokens: List<String>): Boolean {
        if (tokens.isEmpty() || normalizeExecutableName(tokens[0]) != "rm") return false
        var hasRecursive = false
        var hasForce = false
        val targets = mutableListOf<String>()
        for (t in tokens.drop(1)) {
            if (t.startsWith("--")) {
                when (t.lowercase()) {
                    "--recursive" -> hasRecursive = true
                    "--force" -> hasForce = true
                    "--no-preserve-root" -> return true
                }
            } else if (t.startsWith("-") && t.length > 1) {
                val flags = t.drop(1).lowercase()
                if ('r' in flags) hasRecursive = true
                if ('f' in flags) hasForce = true
            } else {
                targets += t.trim()
            }
        }
        if (hasRecursive && hasForce && targets.isNotEmpty()) {
            val criticalTargets = targets.any { tgt ->
                tgt == "/" || tgt == "/*" || tgt == "~" || tgt == "~/" || tgt == ".." ||
                    tgt.startsWith("/etc") || tgt.startsWith("/bin") || tgt.startsWith("/usr") ||
                    tgt.startsWith("/system") || tgt.startsWith("/data") || tgt.startsWith("/boot")
            }
            if (criticalTargets || targets.any { it.contains("*") }) return true
        }
        return targets.any { it == "/" || it == "/*" }
    }

    private fun isDangerousSegment(tokens: List<String>): String? {
        if (tokens.isEmpty()) return null
        // Skip leading VAR=val env assignments
        var idx = 0
        while (idx < tokens.size && Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*$""").matches(tokens[idx])) {
            idx++
        }
        if (idx >= tokens.size) return null
        val activeTokens = tokens.subList(idx, tokens.size)
        val exe = normalizeExecutableName(activeTokens[0])

        if (exe in dangerousCommandNames) {
            return "Blocked by FRIDAY Security Policy: privileged, disk-wiping, or exploitation tool '$exe' is prohibited."
        }
        if (isDangerousRmSegment(activeTokens)) {
            return "Blocked by FRIDAY Security Policy: destructive 'rm' targeting system/critical paths is prohibited."
        }
        if ((exe == "chmod" || exe == "chown") && activeTokens.any { it == "/" || it == "/*" || it == "777" || it == "+s" }) {
            return "Blocked by FRIDAY Security Policy: unsafe recursive or world-writable permission change detected."
        }
        if (exe == "eval" || ((exe == "bash" || exe == "sh" || exe == "zsh" || exe == "dash") &&
            activeTokens.any { it == "-c" || it == "-i" || it.startsWith("-c") })
        ) {
            return "Blocked by FRIDAY Security Policy: inline shell execution ('$exe -c' / 'eval') is prohibited."
        }
        if ((exe == "python" || exe == "python3" || exe == "perl") &&
            activeTokens.any { it == "-c" || it == "-e" || it == "-i" }
        ) {
            val joined = activeTokens.joinToString(" ").lowercase()
            if (joined.contains("socket") || joined.contains("/dev/tcp") || joined.contains("rm ") ||
                joined.contains("subprocess") || joined.contains("pty.spawn")
            ) {
                return "Blocked by FRIDAY Security Policy: inline shell/interpreter payload blocked."
            }
        }
        return null
    }

    private fun isSafeVersionProbe(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val exe = normalizeExecutableName(tokens[0])
        val rest = tokens.drop(1)
        return exe in setOf("python3", "bash", "nmap", "node", "java", "kotlinc", "git") &&
            rest.size == 1 && (rest[0] == "--version" || rest[0] == "-v" || rest[0] == "-version")
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

        // Check full-command dangerous signatures across newlines/pipes/subshells
        if (fullCommandDangerousPatterns.any { it.containsMatchIn(cmd) }) {
            return PendingCommandApproval(
                command = CredentialRedactor.redact(cmd),
                environment = environment,
                riskLevel = CommandRiskLevel.BLOCKED_DANGEROUS,
                reason = "Blocked by FRIDAY Security Policy: destructive, credential-dumping, remote-pipe-to-shell, or reverse-shell pattern detected.",
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                isBlocked = true
            )
        }

        // Exact allowlisted read-only Termux diagnostic tool probe
        if (cmd == diagnosticProbeExact) {
            return PendingCommandApproval(
                command = cmd,
                environment = environment,
                riskLevel = CommandRiskLevel.SAFE_READONLY,
                reason = "Termux diagnostic tool availability check queued for approval.",
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                requiresAuthorizationConfirmation = false,
                isBlocked = false
            )
        }

        val parsed = parseShellStructure(cmd)
        val tokenizedSegments = parsed.segments.map { tokenizeSegment(it) }

        // Check EVERY segment individually (chaining/subshells cannot bypass inspection)
        for (segTokens in tokenizedSegments) {
            val dangerMsg = isDangerousSegment(segTokens)
            if (dangerMsg != null) {
                return PendingCommandApproval(
                    command = CredentialRedactor.redact(cmd),
                    environment = environment,
                    riskLevel = CommandRiskLevel.BLOCKED_DANGEROUS,
                    reason = dangerMsg,
                    targetHostSummary = targetHostSummary,
                    timeoutSeconds = safeTimeout,
                    isBlocked = true
                )
            }
        }

        // Inspect embedded $(...) or `...` subshell fragments as well
        val embeddedMatches = Regex("""\$\(([^)]+)\)|`([^`]+)`""").findAll(cmd)
        for (m in embeddedMatches) {
            val inner = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim()
            if (inner.isNotBlank()) {
                val innerEval = evaluateCommand(inner, environment, targetHostSummary, safeTimeout)
                if (innerEval.isBlocked) {
                    return innerEval.copy(command = CredentialRedactor.redact(cmd))
                }
            }
        }

        // Block shell statement chaining (;, &&, ||, &, newlines) and command substitution ($(...), backticks)
        if (parsed.hasChainingOrBackground || parsed.hasCommandSubstitution) {
            return PendingCommandApproval(
                command = CredentialRedactor.redact(cmd),
                environment = environment,
                riskLevel = CommandRiskLevel.BLOCKED_DANGEROUS,
                reason = "Blocked by FRIDAY Security Policy: shell command chaining (;, &&, ||, newline) and command substitution (\$(...), backticks) are prohibited.",
                targetHostSummary = targetHostSummary,
                timeoutSeconds = safeTimeout,
                isBlocked = true
            )
        }

        // Check standalone single-segment Nmap invocation ONLY when there is no chaining/subshell
        if (tokenizedSegments.size == 1 &&
            normalizeExecutableName(tokenizedSegments[0].firstOrNull().orEmpty()) == "nmap"
        ) {
            val segTokens = tokenizedSegments[0]
            val nonFlagArgs = segTokens.drop(1).filter { !it.startsWith("-") }
            val targetToken = nonFlagArgs.lastOrNull().orEmpty()
            val isVersionProbe = segTokens.any { it.equals("--version", true) || it == "-V" }
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

        // SAFE_READONLY allowed ONLY for strictly single simple command (no chaining, no redirection, no subshell)
        val isSimpleReadOnly = !parsed.hasChainingOrBackground &&
            !parsed.hasPipes &&
            !parsed.hasCommandSubstitution &&
            !parsed.hasRedirection &&
            tokenizedSegments.size == 1 &&
            tokenizedSegments[0].isNotEmpty() &&
            (normalizeExecutableName(tokenizedSegments[0][0]) in readOnlyCommandAllowlist ||
                isSafeVersionProbe(tokenizedSegments[0]))

        val elevationNote = when {
            parsed.hasRedirection ->
                "Output redirection detected. Verify target path carefully before approving."
            parsed.hasPipes ->
                "Piped shell command queued. Confirm authorization before executing."
            isSimpleReadOnly ->
                "Inspection command queued for your explicit approval before execution."
            else ->
                "Custom shell command queued. Verify flags carefully before approving execution."
        }

        return PendingCommandApproval(
            command = CredentialRedactor.redact(cmd),
            environment = environment,
            riskLevel = if (isSimpleReadOnly) CommandRiskLevel.SAFE_READONLY else CommandRiskLevel.ELEVATED_MODIFY,
            reason = elevationNote,
            targetHostSummary = targetHostSummary,
            timeoutSeconds = safeTimeout,
            requiresAuthorizationConfirmation = !isSimpleReadOnly,
            isBlocked = false
        )
    }
}

/**
 * Parses natural-language FRIDAY conversational commands across English, Hindi & Hinglish
 * and maps them cleanly to actionable workflows without ever executing commands silently
 * or silently guessing default network scan targets.
 */
object FridayCommandRouter {

    private val urlRegex = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private val ipOrCidrRegex = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?:/\d{1,2})?\b""")
    private val hostTargetAfterPrepRegex = Regex("""(?i)\b(?:scan|on|target|host|nmap)\s+([a-zA-Z0-9][a-zA-Z0-9.\-]{1,62}(?:\.[a-zA-Z]{2,}|localhost))""")

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

        // 1. Authorized Nmap request — NEVER invent a default subnet like 192.168.1.0/24!
        val isScanIntent = lower.contains("nmap") ||
            (lower.contains("scan") && (lower.contains("network") || lower.contains("port") || lower.contains("host") || ipOrCidrRegex.containsMatchIn(trimmed)))
        if (isScanIntent) {
            val ipTarget = ipOrCidrRegex.find(trimmed)?.value
            val explicitHost = when {
                lower.contains("localhost") -> "localhost"
                else -> hostTargetAfterPrepRegex.find(trimmed)?.groupValues?.getOrNull(1)
                    ?.takeUnless { it.equals("network", true) || it.equals("port", true) || it.equals("my", true) || it.equals("target", true) }
            }
            val target = ipTarget ?: explicitHost
            if (target.isNullOrBlank()) {
                return ConversationalAction.AskScanTarget(
                    "Please specify the authorized IP address, CIDR subnet (for example 127.0.0.1 or your lab LAN range), or hostname to scan with Nmap."
                )
            }
            return ConversationalAction.RequestAuthorizedNmap(
                target = target,
                suggestedCommand = "nmap -sn $target"
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

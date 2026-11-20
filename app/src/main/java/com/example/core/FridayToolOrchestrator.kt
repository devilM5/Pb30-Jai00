package com.example.core

import com.example.ai.DhirModelManager
import com.example.ai.DhirPromptFormatter
import com.example.ai.ParsedToolCall
import com.example.data.curriculum.CyberCurriculumData
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.LanguageMode
import com.example.data.model.NoteCategory
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.ToolExecutionTrace
import com.example.data.model.WebCitation
import com.example.data.repository.DhirFridayAiRepository
import com.example.data.repository.FridayAiRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.WebToolsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class OrchestratedReplyChunk(
    val streamingText: String,
    val toolTraces: List<ToolExecutionTrace> = emptyList(),
    val citations: List<WebCitation> = emptyList(),
    val isFinished: Boolean = false
)

/**
 * Unified FRIDAY Tool Orchestrator connecting:
 * 1. Web Search & NVD CVE lookup
 * 2. Website URL content retrieval (SSRF-safe)
 * 3. YouTube search & available transcript extraction
 * 4. Room local Notes, Chat History & Learning Lab knowledge
 * 5. Termux / SSH / Model / Sync environment status inspection
 * 6. Terminal/SSH command staging (ALWAYS gated behind CommandApprovalDialog — never auto-runs!)
 *
 * Uses local Dhīr 2B (`dhir-2b-Q4_K_M.gguf`) for reasoning and response synthesis.
 */
class FridayToolOrchestrator(
    private val aiRepository: FridayAiRepository,
    private val memoryRepository: MemoryRepository,
    private val webToolsRepository: WebToolsRepository,
    private val modelManager: DhirModelManager?,
    private val currentUserId: String,
    private val termuxStateProvider: () -> TermuxEnvironmentState,
    private val sshProfileProvider: () -> SshConnectionProfile,
    private val sshStateProvider: () -> SshConnectionState,
    private val onStageTerminalApproval: (String) -> Unit
) {

    fun orchestrateChatTurn(
        userPrompt: String,
        languageMode: LanguageMode,
        modelOption: AiModelOption,
        history: List<Pair<String, String>>
    ): Flow<OrchestratedReplyChunk> = flow {
        val traces = mutableListOf<ToolExecutionTrace>()
        val citations = mutableListOf<WebCitation>()
        val preObservations = mutableListOf<String>()

        // 1. Pre-flight deterministic tool routing for crisp mobile UX & low-latency 2B accuracy
        val lower = userPrompt.lowercase()
        val detectedUrl = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE).find(userPrompt)?.value

        if (detectedUrl != null && webToolsRepository.extractYouTubeVideoId(detectedUrl) != null) {
            val yt = webToolsRepository.searchYouTubeAndTranscripts(detectedUrl, maxVideos = 1)
            yt.onSuccess { videos ->
                videos.forEach { v ->
                    memoryRepository.saveYouTubeLearningRecord(currentUserId, v)
                    citations += WebCitation("YouTube: ${v.title}", v.url)
                    traces += ToolExecutionTrace("youtube_transcript", v.title, listOf(v.url))
                    preObservations += "YouTube Video '${v.title}' by ${v.channel}\nURL: ${v.url}\nTranscript (${if (v.transcriptAvailable) "Available" else "Unavailable"}):\n${v.transcriptExcerpt.take(2200)}"
                }
            }.onFailure { err ->
                traces += ToolExecutionTrace("youtube_transcript", "Error: ${err.localizedMessage}")
                preObservations += "YouTube fetch error: ${err.localizedMessage}"
            }
        } else if (detectedUrl != null) {
            webToolsRepository.fetchWebsiteContent(detectedUrl)
                .onSuccess { page ->
                    citations += WebCitation(page.title, page.url)
                    traces += ToolExecutionTrace("fetch_website", page.title, listOf(page.url))
                    preObservations += "Fetched Webpage '${page.title}' (${page.url}):\n${page.textContent.take(2400)}"
                }
                .onFailure { err ->
                    traces += ToolExecutionTrace("fetch_website", "Blocked/Failed: ${err.localizedMessage}")
                    preObservations += "Website fetch failed: ${err.localizedMessage}"
                }
        } else if (shouldTriggerYouTubeSearch(lower)) {
            val ytQuery = extractCleanYouTubeQuery(userPrompt)
            webToolsRepository.searchYouTubeAndTranscripts(ytQuery, maxVideos = 3)
                .onSuccess { videos ->
                    videos.forEach {
                        memoryRepository.saveYouTubeLearningRecord(currentUserId, it)
                        citations += WebCitation("YouTube: ${it.title}", it.url)
                    }
                    if (videos.isNotEmpty()) {
                        traces += ToolExecutionTrace("youtube_search", "${videos.size} video(s) retrieved", videos.map { it.url })
                        preObservations += videos.joinToString("\n") { v ->
                            "- ${v.title} (${v.channel}) ${v.url}\n  Transcript: ${v.transcriptExcerpt.take(1000)}"
                        }
                    }
                }
                .onFailure { err ->
                    traces += ToolExecutionTrace("youtube_search", "Offline/Failed: ${err.localizedMessage}")
                    preObservations += "YouTube search failed: ${err.localizedMessage}"
                }
        } else if (shouldTriggerWebSearch(lower)) {
            webToolsRepository.searchWeb(userPrompt, maxResults = 4)
                .onSuccess { hits ->
                    if (hits.isNotEmpty()) {
                        citations += webToolsRepository.hitsToCitations(hits)
                        traces += ToolExecutionTrace("web_search", "${hits.size} source(s) retrieved", hits.map { it.url })
                        preObservations += hits.joinToString("\n") { h ->
                            "- ${h.title} (${h.url}): ${h.snippet}"
                        }
                    }
                }
                .onFailure { err ->
                    traces += ToolExecutionTrace("web_search", "Offline/Unavailable: ${err.localizedMessage}")
                    preObservations += "Live web search failed: ${err.localizedMessage}"
                }
        }

        if (shouldLookupMemory(lower)) {
            val localContext = memoryRepository.searchLocalKnowledge(currentUserId, userPrompt)
            traces += ToolExecutionTrace("search_memory", "Searched local Room notes & chat history")
            preObservations += localContext
        }

        if (shouldInspectEnvironments(lower)) {
            val envSummary = buildEnvironmentSnapshot()
            traces += ToolExecutionTrace("check_environment_status", "Checked Termux, SSH, Dhīr, Sync")
            preObservations += envSummary
        }

        // 2. Check if Dhīr model weights are installed & native lib ready
        val modelInstalled = modelManager?.findInstalledDhirFile() != null
        val nativeReady = modelManager?.state?.value?.nativeBackendReady ?: false

        if (!modelInstalled || !nativeReady) {
            val honestFallback = buildUnavailableModelResponse(
                userPrompt = userPrompt,
                languageMode = languageMode,
                modelInstalled = modelInstalled,
                nativeReady = nativeReady,
                observations = preObservations,
                citations = citations
            )
            emit(
                OrchestratedReplyChunk(
                    streamingText = honestFallback,
                    toolTraces = traces,
                    citations = citations.distinctBy { it.url },
                    isFinished = true
                )
            )
            return@flow
        }

        // 3. Call Dhīr 2B with tool declarations + any pre-gathered observations
        val promptWithObs = if (preObservations.isEmpty()) {
            userPrompt
        } else {
            """
            $userPrompt

            [Retrieved Tool Context — Ground your answer strictly in these observations and cite URLs if present]
            ${preObservations.joinToString("\n\n")}
            """.trimIndent()
        }

        val toolsJson = DhirPromptFormatter.buildToolDeclarationsJson()
        val rawFirstPass = StringBuilder()

        aiRepository.streamResponse(
            prompt = promptWithObs,
            languageMode = languageMode,
            agentType = AgentType.CHAT,
            modelOption = modelOption,
            history = history,
            toolsJson = toolsJson
        ).collect { partial ->
            rawFirstPass.clear()
            rawFirstPass.append(partial)
            val displayText = DhirPromptFormatter.cleanVisibleText(partial)
            if (displayText.isNotBlank()) {
                emit(
                    OrchestratedReplyChunk(
                        streamingText = displayText,
                        toolTraces = traces,
                        citations = citations.distinctBy { it.url },
                        isFinished = false
                    )
                )
            }
        }

        val fullFirstPass = rawFirstPass.toString()
        val modelToolCalls = DhirPromptFormatter.parseToolCalls(fullFirstPass)

        if (modelToolCalls.isEmpty()) {
            val cleaned = DhirPromptFormatter.cleanVisibleText(fullFirstPass)
                .ifBlank { fullFirstPass.trim() }
            val withSources = appendSourcesFooter(cleaned, citations)
            emit(
                OrchestratedReplyChunk(
                    streamingText = withSources,
                    toolTraces = traces,
                    citations = citations.distinctBy { it.url },
                    isFinished = true
                )
            )
            return@flow
        }

        // 4. Execute model-emitted <tool_call> entries safely, then run synthesis pass
        val dynamicObservations = mutableListOf<String>()
        for (call in modelToolCalls.take(3)) {
            val obs = executeToolCallSafely(call, traces, citations)
            dynamicObservations += "[${call.name}] $obs"
        }

        val languageReminder = when (languageMode) {
            LanguageMode.HINDI ->
                "Synthesize a clear, accurate final answer in natural Hindi (Devanagari script) grounded in the tool observations above."
            LanguageMode.HINGLISH ->
                "Synthesize a clear, accurate final answer in crisp Roman Hinglish (Hindi + English tech terms) grounded in the tool observations above."
            LanguageMode.ENGLISH ->
                "Synthesize a complete, accurate final answer in clean English grounded in the tool observations above."
        }

        val secondPassPrompt = """
            $userPrompt

            Tool Observations:
            ${(preObservations + dynamicObservations).joinToString("\n\n")}

            $languageReminder
        """.trimIndent()

        val rawSecondPass = StringBuilder()
        aiRepository.streamResponse(
            prompt = secondPassPrompt,
            languageMode = languageMode,
            agentType = AgentType.CHAT,
            modelOption = modelOption,
            history = history,
            toolsJson = null
        ).collect { partial ->
            rawSecondPass.clear()
            rawSecondPass.append(partial)
            val display = DhirPromptFormatter.cleanVisibleText(partial)
            if (display.isNotBlank()) {
                emit(
                    OrchestratedReplyChunk(
                        streamingText = display,
                        toolTraces = traces,
                        citations = citations.distinctBy { it.url },
                        isFinished = false
                    )
                )
            }
        }

        val finalAnswer = appendSourcesFooter(
            DhirPromptFormatter.cleanVisibleText(rawSecondPass.toString()),
            citations
        )
        emit(
            OrchestratedReplyChunk(
                streamingText = finalAnswer,
                toolTraces = traces,
                citations = citations.distinctBy { it.url },
                isFinished = true
            )
        )
    }

    internal suspend fun executeToolCallSafely(
        call: ParsedToolCall,
        traces: MutableList<ToolExecutionTrace>,
        citations: MutableList<WebCitation>
    ): String {
        return when (call.name.lowercase()) {
            "web_search" -> {
                val q = call.arguments["query"].orEmpty()
                webToolsRepository.searchWeb(q, maxResults = 4).fold(
                    onSuccess = { hits ->
                        citations += webToolsRepository.hitsToCitations(hits)
                        traces += ToolExecutionTrace("web_search", "Searched '$q' (${hits.size} hits)", hits.map { it.url })
                        hits.joinToString("\n") { "${it.title} (${it.url}): ${it.snippet}" }
                            .ifBlank { "No web results found for $q." }
                    },
                    onFailure = { err ->
                        traces += ToolExecutionTrace("web_search", "Failed: ${err.localizedMessage}")
                        "Search error: ${err.localizedMessage}"
                    }
                )
            }
            "fetch_website" -> {
                val url = call.arguments["url"].orEmpty()
                webToolsRepository.fetchWebsiteContent(url).fold(
                    onSuccess = { page ->
                        citations += WebCitation(page.title, page.url)
                        traces += ToolExecutionTrace("fetch_website", page.title, listOf(page.url))
                        "${page.title} (${page.url}):\n${page.textContent.take(2500)}"
                    },
                    onFailure = { err ->
                        traces += ToolExecutionTrace("fetch_website", "Error: ${err.localizedMessage}")
                        "Fetch error: ${err.localizedMessage}"
                    }
                )
            }
            "youtube_search" -> {
                val q = call.arguments["query"].orEmpty()
                webToolsRepository.searchYouTubeAndTranscripts(q, maxVideos = 2).fold(
                    onSuccess = { videos ->
                        videos.forEach {
                            memoryRepository.saveYouTubeLearningRecord(currentUserId, it)
                            citations += WebCitation("YouTube: ${it.title}", it.url)
                        }
                        traces += ToolExecutionTrace("youtube_search", "${videos.size} video(s)", videos.map { it.url })
                        videos.joinToString("\n") {
                            "${it.title} (${it.url}) — Transcript: ${it.transcriptExcerpt.take(1200)}"
                        }.ifBlank { "No YouTube videos found." }
                    },
                    onFailure = { err ->
                        traces += ToolExecutionTrace("youtube_search", "Error: ${err.localizedMessage}")
                        "YouTube error: ${err.localizedMessage}"
                    }
                )
            }
            "search_memory" -> {
                val q = call.arguments["query"].orEmpty()
                traces += ToolExecutionTrace("search_memory", "Query: $q")
                memoryRepository.searchLocalKnowledge(currentUserId, q)
            }
            "save_note" -> {
                val content = call.arguments["content"].orEmpty()
                val title = content.lineSequence().firstOrNull()?.take(70).orEmpty().ifBlank { "FRIDAY Brief" }
                val res = memoryRepository.saveNote(title, content, NoteCategory.AGENT_BRIEF, userIdOverride = currentUserId)
                traces += ToolExecutionTrace("save_note", if (res.isSuccess) "Saved '$title'" else "Failed")
                if (res.isSuccess) "Note saved locally in FRIDAY Memory ('$title')."
                else "Failed to save note: ${res.exceptionOrNull()?.localizedMessage}"
            }
            "prepare_terminal_command" -> {
                val cmd = call.arguments["command"].orEmpty().trim()
                if (cmd.isNotEmpty()) {
                    // NEVER executes directly — always routes through mandatory CommandApprovalDialog!
                    onStageTerminalApproval(cmd)
                    traces += ToolExecutionTrace("prepare_terminal_command", "Staged in approval dialog: $cmd")
                    "Command '$cmd' has been staged in the mandatory operator approval dialog. Explain what it does and wait for user approval."
                } else {
                    "No command supplied."
                }
            }
            "check_environment_status" -> {
                traces += ToolExecutionTrace("check_environment_status", "Environment status checked")
                buildEnvironmentSnapshot()
            }
            else -> {
                // Check Kali/Lab curriculum match cleanly
                val toolMatch = CyberCurriculumData.kaliTools.firstOrNull {
                    it.name.equals(call.name, ignoreCase = true)
                }
                toolMatch?.let { "${it.name}: ${it.purpose}. Safe syntax: ${it.safeSyntax}" }
                    ?: "Unknown tool '${call.name}'."
            }
        }
    }

    private fun buildEnvironmentSnapshot(): String {
        val termux = termuxStateProvider()
        val sshProf = sshProfileProvider()
        val ssh = sshStateProvider()
        val model = modelManager?.state?.value
        val sync = memoryRepository.syncStatus.value
        return buildString {
            appendLine("=== FRIDAY Subsystem Status ===")
            appendLine("Dhīr Local AI: ${model?.status?.label ?: "Unknown"} (${model?.activeFileName?.ifBlank { "not imported" } ?: "unconfigured"})")
            appendLine("Device RAM: ${model?.totalDeviceRamMb ?: 0} MB total, ${model?.availableDeviceRamMb ?: 0} MB free")
            appendLine("Termux Bridge: ${if (termux.isConnected) "CONNECTED" else "DISCONNECTED"} | APK installed=${termux.apkInstalled} | Tools=${termux.toolStatuses}")
            appendLine("Remote Linux SSH: ${if (ssh.isConnected) "CONNECTED" else "DISCONNECTED"} (${sshProf.username.ifBlank { "unset" }}@${sshProf.host.ifBlank { "unset" }}:${sshProf.port})")
            appendLine("Room Local DB & Sync: online=${sync.isOnline}, signedIn=${sync.isSignedIn}, pendingSync=${sync.pendingChangesCount} — ${sync.lastSyncSummary}")
        }.trim()
    }

    private fun buildUnavailableModelResponse(
        userPrompt: String,
        languageMode: LanguageMode,
        modelInstalled: Boolean,
        nativeReady: Boolean,
        observations: List<String>,
        citations: List<WebCitation>
    ): String = buildString {
        when (languageMode) {
            LanguageMode.HINDI -> {
                if (!modelInstalled) {
                    appendLine("स्थानीय AI मॉडल `dhir-2b-Q4_K_M.gguf` अभी इंस्टॉल नहीं है।")
                    appendLine("ऑफ़लाइन उत्तरों के लिए Dashboard या Tools Studio में Dhīr Engine Manager खोलें और फोन से `dhir-2b-Q4_K_M.gguf` इम्पोर्ट या HTTPS से डाउनलोड करें।")
                } else if (!nativeReady) {
                    appendLine("`dhir-2b-Q4_K_M.gguf` इंस्टॉल है, लेकिन इस डिवाइस/रनटाइम पर नेटिव `libfriday_llama.so` उपलब्ध नहीं है।")
                }
                if (observations.isNotEmpty()) {
                    appendLine()
                    appendLine("इसी बीच FRIDAY ने आपके अनुरोध \"$userPrompt\" के लिए वास्तविक टूल्स से यह जानकारी प्राप्त की:")
                    appendLine(observations.joinToString("\n\n"))
                }
            }
            LanguageMode.HINGLISH -> {
                if (!modelInstalled) {
                    appendLine("Local AI model `dhir-2b-Q4_K_M.gguf` abhi phone par installed nahi hai.")
                    appendLine("Offline on-device answers ke liye Dashboard ya Tools Studio mein Dhīr Engine Manager khol kar `dhir-2b-Q4_K_M.gguf` import ya HTTPS se download karein.")
                } else if (!nativeReady) {
                    appendLine("`dhir-2b-Q4_K_M.gguf` installed hai, lekin is runtime par native `libfriday_llama.so` available nahi hai.")
                }
                if (observations.isNotEmpty()) {
                    appendLine()
                    appendLine("Is dauran FRIDAY ne \"$userPrompt\" ke liye real tools se ye output nikala hai:")
                    appendLine(observations.joinToString("\n\n"))
                }
            }
            LanguageMode.ENGLISH -> {
                if (!modelInstalled) {
                    appendLine("Local AI model `dhir-2b-Q4_K_M.gguf` is not installed yet.")
                    appendLine("Open the Dhīr Engine Manager (Dashboard or Tools Studio) to import `dhir-2b-Q4_K_M.gguf` from phone storage or download it via HTTPS.")
                } else if (!nativeReady) {
                    appendLine("`dhir-2b-Q4_K_M.gguf` is installed, but native `libfriday_llama.so` is unavailable on this runtime.")
                }
                if (observations.isNotEmpty()) {
                    appendLine()
                    appendLine("Meanwhile, FRIDAY executed the requested real tools for \"$userPrompt\":")
                    appendLine(observations.joinToString("\n\n"))
                }
            }
        }
        val cleanCites = citations.distinctBy { it.url }
        if (cleanCites.isNotEmpty()) {
            appendLine()
            appendLine(if (languageMode == LanguageMode.HINDI) "स्रोत:" else "Sources:")
            cleanCites.forEach { appendLine("- ${it.title}: ${it.url}") }
        }
    }.trim()

    private fun appendSourcesFooter(text: String, citations: List<WebCitation>): String {
        val unique = citations.distinctBy { it.url }
        if (unique.isEmpty()) return text
        if (unique.any { text.contains(it.url) }) return text
        return buildString {
            appendLine(text)
            appendLine()
            appendLine("Sources:")
            unique.forEach { appendLine("• ${it.title} — ${it.url}") }
        }.trim()
    }

    internal fun shouldTriggerYouTubeSearch(lower: String): Boolean =
        lower.contains("youtube") ||
            lower.contains("transcript") ||
            lower.contains("यूट्यूब") ||
            lower.contains("ट्रांसक्रिप्ट") ||
            Regex("""\byt\s+""").containsMatchIn(lower)

    internal fun extractCleanYouTubeQuery(rawPrompt: String): String =
        rawPrompt
            .replace(Regex("""(?i)\b(youtube|yt|search|transcript|find|video|videos|karo|dhundo|nikalo|batao|dikhao|par|pe|mein|me)\b"""), "")
            .replace(Regex("""(यूट्यूब|वीडियो|खोजो|ट्रांसक्रिप्ट|पर|में)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { rawPrompt.trim() }

    internal fun shouldTriggerWebSearch(lower: String): Boolean =
        lower.contains("search the web") ||
            lower.contains("search the internet") ||
            lower.contains("search online") ||
            lower.contains("latest cve") ||
            lower.contains("cve-20") ||
            lower.startsWith("search ") ||
            lower.contains("google ") ||
            lower.contains("web research") ||
            lower.contains("search karo") ||
            lower.contains("online khojo") ||
            lower.contains("internet par") ||
            lower.contains("web par") ||
            lower.contains("khajo") ||
            lower.contains("khojo") ||
            lower.contains("इंटरनेट") ||
            lower.contains("वेब सर्च") ||
            lower.contains("ऑनलाइन खोज")

    internal fun shouldLookupMemory(lower: String): Boolean =
        lower.contains("my note") ||
            lower.contains("saved note") ||
            lower.contains("remember") ||
            lower.contains("chat history") ||
            lower.contains("previous note") ||
            lower.contains("memory") ||
            lower.contains("mere notes") ||
            lower.contains("saved notes") ||
            lower.contains("purani chat") ||
            lower.contains("yaad") ||
            lower.contains("नोट्स") ||
            lower.contains("मेमोरी") ||
            lower.contains("पिछली चैट") ||
            lower.contains("याद")

    internal fun shouldInspectEnvironments(lower: String): Boolean =
        lower.contains("termux status") ||
            lower.contains("ssh status") ||
            lower.contains("environment status") ||
            lower.contains("system status") ||
            lower.contains("sync status") ||
            lower.contains("model status") ||
            lower.contains("tools installed") ||
            lower.contains("termux connected") ||
            lower.contains("ssh connected") ||
            lower.contains("termux connect") ||
            lower.contains("ssh connect") ||
            lower.contains("टर्मक्स स्टेटस") ||
            lower.contains("सिंक स्टेटस") ||
            lower.contains("मॉडल स्टेटस")
}

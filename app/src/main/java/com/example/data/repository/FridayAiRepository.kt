package com.example.data.repository

import android.util.Log
import com.example.ai.DhirModelManager
import com.example.ai.DhirPromptFormatter
import com.example.ai.DhirTurn
import com.example.ai.LlamaInferenceEngine
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.DhirModelStatus
import com.example.data.model.LanguageMode
import com.example.data.model.WebCitation
import com.example.data.model.WebResearchResult
import com.example.data.model.YouTubeVideoSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.io.File

interface FridayAiRepository {
    fun streamResponse(
        prompt: String,
        languageMode: LanguageMode,
        agentType: AgentType,
        modelOption: AiModelOption = AiModelOption.DHIR_2B_BALANCED,
        history: List<Pair<String, String>> = emptyList(),
        toolsJson: String? = null
    ): Flow<String>

    suspend fun performWebResearch(
        query: String,
        languageMode: LanguageMode,
        explainInHindi: Boolean,
        modelOption: AiModelOption = AiModelOption.DHIR_2B_BALANCED
    ): WebResearchResult

    suspend fun ensureDhirLoaded(modelOption: AiModelOption): Result<Unit>
    fun unloadDhir()
    fun abortGeneration()
}

/**
 * Local Dhīr 2B (`dhir-2b-Q4_K_M.gguf`) AI engine backed by `llama.cpp` JNI (`dhir-support`)
 * plus `WebToolsRepository` for grounded internet, website, and YouTube research.
 *
 * Strictly zero Gemini, ChatGPT, or cloud LLM calls.
 */
class DhirFridayAiRepository(
    private val modelManager: DhirModelManager,
    private val llamaBridge: LlamaInferenceEngine,
    private val webToolsRepository: WebToolsRepository = WebToolsRepository()
) : FridayAiRepository {

    fun buildSystemPrompt(
        languageMode: LanguageMode,
        agentType: AgentType
    ): String {
        val languageInstruction = when (languageMode) {
            LanguageMode.HINGLISH ->
                "Respond primarily in natural Hinglish (Hindi in Roman script mixed with clear English technical terms) unless the user writes purely in English or Devanagari Hindi."
            LanguageMode.HINDI ->
                "Respond in clear, natural Hindi (Devanagari script: हिन्दी) while keeping exact code syntax, CLI flags, CVE IDs, and technical terms in English."
            LanguageMode.ENGLISH ->
                "Respond in clear, concise, authoritative English."
        }

        val roleInstruction = when (agentType) {
            AgentType.CHAT ->
                "You are FRIDAY, the on-device AI assistant, coding mentor, and authorized cybersecurity guide inside PB-30 JAI - FRIDAY Security AI, powered locally by Dhīr 2B."
            AgentType.RESEARCH ->
                "You are FRIDAY's Research Agent. Organize threat intelligence and technical findings (OWASP, NIST, MITRE ATT&CK, CVE/CWE) into Overview, Technical Vector, Defensive Controls, and Key Takeaways."
            AgentType.LEARNING ->
                "You are FRIDAY's Security Learning Agent. Explain concepts step by step using clear analogies, safe lab examples, and one quick self-check question."
            AgentType.CODE_ANALYSIS ->
                "You are FRIDAY's Code Analysis Agent. Review code defensively: (1) Identified Risks & Severity, (2) Impact, and (3) Secure Fixed Code."
            AgentType.REPORT_ANALYSIS ->
                "You are FRIDAY's Report Analysis Agent. Analyze authorized scan/log findings by severity, root cause, and remediation steps."
            AgentType.CODING ->
                "You are FRIDAY's Coding Agent for Python, Java, Kotlin, JavaScript, and Bash. Give clean, safe code blocks and debugging fixes."
            AgentType.FILE_ANALYSIS ->
                "You are FRIDAY's File Agent. Summarize architecture, find bugs or risks with specifics, and suggest safe edits marked for operator approval."
            AgentType.WEB_RESEARCH ->
                "You are FRIDAY's Web Research Agent. Synthesize only facts present in the retrieved web, website, CVE, or YouTube transcript observations and cite source links accurately."
            AgentType.TERMINAL_REVIEW ->
                "You are FRIDAY's Terminal Agent safety reviewer. Explain commands and flags clearly and recommend safe alternatives. Never claim a command executed unless tool output confirms it."
        }

        val safetyGuardrail = """
            STRICT SECURITY & HONESTY RULES:
            - Teach and assist ONLY with ethical, authorized, defensive cybersecurity and safe software engineering.
            - Never generate exploit payloads, malware, credential-stuffing scripts, or unauthorized attack steps.
            - Never claim a web search, file edit, or terminal scan happened unless an observation block confirms it.
            - Terminal/SSH commands must always go through prepare_terminal_command for explicit user approval.
        """.trimIndent()

        return "$roleInstruction\n\n$languageInstruction\n\n$safetyGuardrail"
    }

    override suspend fun ensureDhirLoaded(modelOption: AiModelOption): Result<Unit> {
        val modelFile: File = modelManager.findInstalledDhirFile()
            ?: return Result.failure(
                IllegalStateException(
                    "Local AI model 'dhir-2b-Q4_K_M.gguf' is not installed yet. Open Model Manager to import or download it."
                )
            )

        val currentState = modelManager.inspectModelState(
            loadedFilePath = llamaBridge.getLoadedModelPath(),
            effectiveCtx = llamaBridge.getLoadedContextSize(),
            arch = llamaBridge.getModelArchitecture()
        )
        val recommendedCtx = DhirModelManager.recommendContextSize(
            totalRamMb = currentState.totalDeviceRamMb,
            availableRamMb = currentState.availableDeviceRamMb,
            modelSizeBytes = modelFile.length(),
            requestedCtx = modelOption.contextSize
        )

        if (llamaBridge.getLoadedModelPath() == modelFile.absolutePath &&
            llamaBridge.getLoadedContextSize() == recommendedCtx
        ) {
            modelManager.updateRuntimeLoadedStatus(
                loadedPath = modelFile.absolutePath,
                effectiveCtx = recommendedCtx,
                arch = llamaBridge.getModelArchitecture()
            )
            return Result.success(Unit)
        }

        modelManager.updateRuntimeLoadedStatus(loadedPath = null, isLoading = true)
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val ctxCandidates = listOf(recommendedCtx, 1024, 512).filter { it <= recommendedCtx }.distinct()
        var chosenCtx = recommendedCtx
        var lastResult: Result<String> = Result.failure(IllegalStateException("Unable to load Dhīr 2B."))

        for (candidateCtx in ctxCandidates) {
            chosenCtx = candidateCtx
            lastResult = llamaBridge.loadModel(modelFile.absolutePath, candidateCtx, cpuThreads)
            if (lastResult.isSuccess) break
            val msg = lastResult.exceptionOrNull()?.message.orEmpty()
            // Only retry smaller context on context allocation / insufficient RAM errors
            if (!msg.contains("context", ignoreCase = true) && !msg.contains("RAM", ignoreCase = true)) {
                break
            }
        }

        return lastResult.fold(
            onSuccess = { arch ->
                modelManager.updateRuntimeLoadedStatus(
                    loadedPath = modelFile.absolutePath,
                    effectiveCtx = chosenCtx,
                    arch = arch
                )
                Result.success(Unit)
            },
            onFailure = { err ->
                modelManager.updateRuntimeLoadedStatus(loadedPath = null, errorMessage = err.message)
                Result.failure(err)
            }
        )
    }

    override fun unloadDhir() {
        llamaBridge.unloadModel()
        modelManager.refreshState()
    }

    override fun abortGeneration() {
        llamaBridge.abortGeneration()
    }

    override fun streamResponse(
        prompt: String,
        languageMode: LanguageMode,
        agentType: AgentType,
        modelOption: AiModelOption,
        history: List<Pair<String, String>>,
        toolsJson: String?
    ): Flow<String> = flow {
        ensureDhirLoaded(modelOption).getOrThrow()

        val activeCtx = llamaBridge.getLoadedContextSize().takeIf { it > 0 } ?: modelOption.contextSize
        val systemPrompt = buildSystemPrompt(languageMode, agentType)
        val historyTurns = history.takeLast(10).map { (role, content) ->
            val normalized = when {
                role.equals("FRIDAY", ignoreCase = true) || role.equals("assistant", ignoreCase = true) -> "assistant"
                role.equals("observation", ignoreCase = true) -> "observation"
                else -> "user"
            }
            DhirTurn(role = normalized, content = content.take(1200))
        }
        val turns = DhirPromptFormatter.trimTurnsToBudget(
            systemPrompt = systemPrompt,
            historyTurns = historyTurns,
            currentUserTurn = DhirTurn(role = "user", content = prompt),
            toolsJson = toolsJson,
            contextSizeTokens = activeCtx,
            reserveOutputTokens = modelOption.maxTokens.coerceIn(192, activeCtx / 2)
        )

        val formattedPrompt = DhirPromptFormatter.formatPrompt(
            systemPrompt = systemPrompt,
            turns = turns,
            toolsJson = toolsJson
        )

        var latest = ""
        try {
            withTimeout(LOCAL_INFERENCE_TIMEOUT_MS) {
                llamaBridge.streamCompletion(
                    formattedPrompt = formattedPrompt,
                    maxTokens = modelOption.maxTokens,
                    temperature = modelOption.temperature
                ).collect { partial ->
                    latest = partial
                    emit(partial)
                }
            }
            if (latest.isBlank()) {
                throw IllegalStateException("Dhīr 2B produced an empty response.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Dhīr local inference timed out", e)
            llamaBridge.abortGeneration()
            throw IllegalStateException(
                "Dhīr local inference timed out after 120s before finishing; incomplete output was discarded.",
                e
            )
        } catch (e: CancellationException) {
            llamaBridge.abortGeneration()
            throw e
        }
    }

    override suspend fun performWebResearch(
        query: String,
        languageMode: LanguageMode,
        explainInHindi: Boolean,
        modelOption: AiModelOption
    ): WebResearchResult {
        val cleanQuery = query.trim()
        val effectiveLanguage = if (explainInHindi) LanguageMode.HINDI else languageMode
        val citations = mutableListOf<WebCitation>()
        val queriesUsed = mutableListOf<String>()
        var youtubeResults = emptyList<YouTubeVideoSummary>()
        val evidenceSections = mutableListOf<String>()

        // 1. Check direct URL extraction (Webpage or YouTube URL)
        val directUrl = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE).find(cleanQuery)?.value
        val isYouTubeIntent = cleanQuery.contains("youtube", ignoreCase = true) ||
            cleanQuery.contains("youtu.be", ignoreCase = true) ||
            cleanQuery.contains("transcript", ignoreCase = true) ||
            cleanQuery.contains("video", ignoreCase = true)

        if (directUrl != null && webToolsRepository.extractYouTubeVideoId(directUrl) != null) {
            queriesUsed += directUrl
            val ytRes = webToolsRepository.searchYouTubeAndTranscripts(directUrl, maxVideos = 1)
            ytRes.onSuccess { videos ->
                youtubeResults = videos
                videos.forEach { v ->
                    citations += WebCitation(title = "YouTube: ${v.title}", url = v.url)
                    evidenceSections += "YouTube Video: ${v.title} (${v.channel})\nURL: ${v.url}\nTranscript (${if (v.transcriptAvailable) "Available" else "Unavailable"}):\n${v.transcriptExcerpt}"
                }
            }.onFailure { err ->
                evidenceSections += "YouTube retrieval failed: ${err.localizedMessage}"
            }
        } else {
            if (directUrl != null) {
                queriesUsed += directUrl
                webToolsRepository.fetchWebsiteContent(directUrl)
                    .onSuccess { page ->
                        citations += WebCitation(title = page.title, url = page.url)
                        evidenceSections += "Fetched Website: ${page.title}\nURL: ${page.url}\nExtracted Content:\n${page.textContent.take(3000)}"
                    }
                    .onFailure { err ->
                        evidenceSections += "Website fetch failed ($directUrl): ${err.localizedMessage}"
                    }
            }

            if (isYouTubeIntent) {
                val ytQuery = cleanQuery.replace(Regex("""(?i)\b(youtube|video|transcript)\b"""), "").trim().ifBlank { cleanQuery }
                queriesUsed += "youtube:$ytQuery"
                webToolsRepository.searchYouTubeAndTranscripts(ytQuery, maxVideos = 3)
                    .onSuccess { videos ->
                        youtubeResults = videos
                        videos.forEach { v ->
                            citations += WebCitation(title = "YouTube: ${v.title}", url = v.url)
                        }
                        if (videos.isNotEmpty()) {
                            val top = videos.first()
                            evidenceSections += buildString {
                                appendLine("Top YouTube Match: ${top.title} (${top.channel}) ${top.durationLabel}")
                                appendLine("URL: ${top.url}")
                                appendLine("Transcript: ${top.transcriptExcerpt.take(2200)}")
                            }
                        }
                    }
            }

            // General live web search (always run unless user only pasted an exact URL and got clean page)
            if (directUrl == null || evidenceSections.isEmpty()) {
                queriesUsed += cleanQuery
                val searchRes = webToolsRepository.searchWeb(cleanQuery, maxResults = 5)
                searchRes.onSuccess { hits ->
                    citations += webToolsRepository.hitsToCitations(hits)
                    if (hits.isNotEmpty()) {
                        evidenceSections += buildString {
                            appendLine("Live Web Search Results:")
                            hits.forEachIndexed { idx, h ->
                                appendLine("${idx + 1}. ${h.title} (${h.source})")
                                appendLine("   URL: ${h.url}")
                                if (h.snippet.isNotBlank()) appendLine("   Summary: ${h.snippet}")
                            }
                        }
                    }
                }.onFailure { err ->
                    if (evidenceSections.isEmpty()) {
                        throw IllegalStateException(err.localizedMessage ?: "Live search failed. Check internet connectivity.")
                    }
                }
            }
        }

        val distinctCitations = citations.distinctBy { it.url }
        if (evidenceSections.isEmpty()) {
            throw IllegalStateException("No search results or readable page content found for '$cleanQuery'.")
        }

        val combinedEvidence = evidenceSections.joinToString("\n\n---\n\n")

        // 2. Synthesize retrieved real sources using local Dhīr 2B if installed, else return honest retrieved digest
        val modelReady = modelManager.findInstalledDhirFile() != null &&
            modelManager.state.value.nativeBackendReady

        val synthesizedAnswer = if (modelReady) {
            runCatching {
                val synthPrompt = """
                    User Query: $cleanQuery

                    Retrieved Real Evidence:
                    $combinedEvidence

                    Task: Synthesize an accurate, clear answer grounded strictly in the retrieved evidence above. List key takeaways and cite source URLs where relevant.
                """.trimIndent()
                val rawOut = StringBuilder()
                streamResponse(
                    prompt = synthPrompt,
                    languageMode = effectiveLanguage,
                    agentType = AgentType.WEB_RESEARCH,
                    modelOption = modelOption
                ).collect { chunk ->
                    rawOut.clear()
                    rawOut.append(chunk)
                }
                DhirPromptFormatter.cleanVisibleText(rawOut.toString())
            }.getOrElse { err ->
                buildRawRetrievalFallback(
                    cleanQuery = cleanQuery,
                    evidence = combinedEvidence,
                    notice = "Local Dhīr synthesis failed (${err.localizedMessage}); showing retrieved sources directly."
                )
            }
        } else {
            buildRawRetrievalFallback(
                cleanQuery = cleanQuery,
                evidence = combinedEvidence,
                notice = "Retrieved real web/YouTube results directly. Import dhir-2b-Q4_K_M.gguf to enable on-device AI summarization in ${effectiveLanguage.displayLabel}."
            )
        }

        return WebResearchResult(
            query = cleanQuery,
            answer = synthesizedAnswer,
            citations = distinctCitations,
            searchQueriesUsed = queriesUsed.distinct(),
            explainedInHindi = explainInHindi || effectiveLanguage == LanguageMode.HINDI,
            youtubeResults = youtubeResults,
            modeNote = if (modelManager.findInstalledDhirFile() != null) {
                "Synthesized by local Dhīr 2B from real HTTP sources"
            } else {
                "Direct HTTP retrieval (import dhir-2b-Q4_K_M.gguf for AI synthesis)"
            }
        )
    }

    private fun buildRawRetrievalFallback(
        cleanQuery: String,
        evidence: String,
        notice: String
    ): String = buildString {
        appendLine("[$notice]")
        appendLine()
        appendLine("Query: $cleanQuery")
        appendLine()
        append(evidence)
    }

    private companion object {
        const val TAG = "DhirFridayAiRepo"
        const val LOCAL_INFERENCE_TIMEOUT_MS = 120_000L
    }
}

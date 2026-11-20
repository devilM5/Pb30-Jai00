package com.example.data.repository

import android.util.Log
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.LanguageMode
import com.example.data.model.WebCitation
import com.example.data.model.WebResearchResult
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

interface FridayAiRepository {
    fun streamResponse(
        prompt: String,
        languageMode: LanguageMode,
        agentType: AgentType,
        modelOption: AiModelOption = AiModelOption.FLASH_3_8,
        history: List<Pair<String, String>> = emptyList()
    ): Flow<String>

    suspend fun performWebResearch(
        query: String,
        languageMode: LanguageMode,
        explainInHindi: Boolean,
        modelOption: AiModelOption = AiModelOption.FLASH_3_8
    ): WebResearchResult
}

class FirebaseFridayAiRepository : FridayAiRepository {

    private fun buildSystemPrompt(
        languageMode: LanguageMode,
        agentType: AgentType
    ): String {
        val languageInstruction = when (languageMode) {
            LanguageMode.HINGLISH ->
                "Respond primarily in clear, natural Hinglish (Hindi written in Roman script mixed with accurate English technical terms) unless the user writes purely in English or Devanagari Hindi."
            LanguageMode.HINDI ->
                "Respond in clear, natural Hindi (Devanagari script: हिन्दी) while keeping exact code syntax, CLI flags, CVE IDs, and technical terms in English for precision."
            LanguageMode.ENGLISH ->
                "Respond in crisp, authoritative, professional English."
        }

        val roleInstruction = when (agentType) {
            AgentType.CHAT ->
                "You are FRIDAY, the conversational personal AI assistant, software engineer mentor, and authorized cybersecurity guide inside PB-30 JAI - FRIDAY Security AI. Answer general knowledge, coding, Linux, and defensive security questions clearly and step-by-step."
            AgentType.RESEARCH ->
                "You are FRIDAY's Research Agent. Provide structured threat-intelligence and technical research (OWASP, NIST, MITRE ATT&CK, CVE/CWE concepts, cloud/software architecture) with clear sections: Overview, Threat/Technical Vector, Defensive Controls, and Key Takeaways."
            AgentType.LEARNING ->
                "You are FRIDAY's Security Learning Agent. Teach concepts step by step using beginner-friendly analogies, safe lab command explanations, and a quick self-check question at the end."
            AgentType.CODE_ANALYSIS ->
                "You are FRIDAY's Code Analysis Agent. Perform a defensive static code/configuration review on the snippet provided. Output: (1) Identified Risks & Severity, (2) Why It Matters, and (3) Secure Fixed Code."
            AgentType.REPORT_ANALYSIS ->
                "You are FRIDAY's Report Analysis Agent. Analyze authorized Nmap/ZAP/HTTP/system scan logs. Categorize findings by Severity (Critical/High/Medium/Low), explain root cause, and give concrete remediation commands/configs."
            AgentType.CODING ->
                "You are FRIDAY's Coding Agent expert in Python, Java, Kotlin, JavaScript, and Bash. Provide clean, production-safe code blocks, clear line-by-line explanations when requested, root-cause debugging for stack traces/errors, and exact fixes."
            AgentType.FILE_ANALYSIS ->
                "You are FRIDAY's File Agent. Review the uploaded source code or document carefully. Summarize architecture/purpose, identify bugs or security flaws with line references, and when asked to propose an edit, provide clear updated code clearly marked as a proposed patch awaiting operator approval."
            AgentType.WEB_RESEARCH ->
                "You are FRIDAY's Web Research Agent. Synthesize accurate, up-to-date answers grounded in web search and provided URLs, highlighting authoritative sources and practical takeaways."
            AgentType.TERMINAL_REVIEW ->
                "You are FRIDAY's Terminal Agent safety reviewer. Explain what each command and flag does, highlight potential side effects, and recommend safer read-only or scoped alternatives if needed. Never claim a command executed unless bridge output was provided."
        }

        val safetyGuardrail = """
            STRICT ETHICAL & SAFETY POLICY:
            - You ONLY teach ethical, authorized, and defensive cybersecurity and safe software engineering.
            - NEVER generate exploit payloads, malware, ransomware, credential-stuffing scripts, or instructions to attack unauthorized systems.
            - Never claim to have scanned a live target or executed a shell command yourself — terminal/sandbox execution always requires explicit operator approval through the app bridge.
            - Never expose secrets or encourage hardcoding passwords/API keys.
        """.trimIndent()

        return "$roleInstruction\n\n$languageInstruction\n\n$safetyGuardrail"
    }

    private fun createModel(
        languageMode: LanguageMode,
        agentType: AgentType,
        modelOption: AiModelOption
    ) = Firebase.ai.generativeModel(
        modelName = modelOption.modelId,
        systemInstruction = content {
            text(buildSystemPrompt(languageMode, agentType))
        }
    )

    override fun streamResponse(
        prompt: String,
        languageMode: LanguageMode,
        agentType: AgentType,
        modelOption: AiModelOption,
        history: List<Pair<String, String>>
    ): Flow<String> = flow {
        val model = createModel(languageMode, agentType, modelOption)
        val accumulated = StringBuilder()

        try {
            withTimeout(AI_TIMEOUT_MS) {
                val stream = if (agentType == AgentType.CHAT && history.isNotEmpty()) {
                    val chatHistory: List<Content> = history.takeLast(16).map { (role, msg) ->
                        content(role = if (role == "FRIDAY") "model" else "user") {
                            text(msg)
                        }
                    }
                    val chat = model.startChat(history = chatHistory)
                    chat.sendMessageStream(prompt)
                } else {
                    model.generateContentStream(prompt)
                }

                stream.collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulated.append(chunkText)
                        emit(accumulated.toString())
                    }
                }
            }
            if (accumulated.isBlank()) {
                throw IllegalStateException("FRIDAY returned an empty response. Please try again.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI generation timed out", e)
            throw IllegalStateException("FRIDAY request timed out after 60 seconds. Please check network and retry.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI generation failed", e)
            throw e
        }
    }

    override suspend fun performWebResearch(
        query: String,
        languageMode: LanguageMode,
        explainInHindi: Boolean,
        modelOption: AiModelOption
    ): WebResearchResult {
        val effectiveLanguage = if (explainInHindi) LanguageMode.HINDI else languageMode
        val hasUrl = Regex("""https?://\S+""").containsMatchIn(query)
        val tools = if (hasUrl) {
            listOf(Tool.googleSearch(), Tool.urlContext())
        } else {
            listOf(Tool.googleSearch())
        }

        val groundedModel = Firebase.ai.generativeModel(
            modelName = modelOption.modelId,
            tools = tools,
            systemInstruction = content {
                text(buildSystemPrompt(effectiveLanguage, AgentType.WEB_RESEARCH))
            }
        )

        return try {
            val response = withTimeout(AI_TIMEOUT_MS) {
                groundedModel.generateContent(query)
            }
            val answer = response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Web Research returned an empty response.")

            val metadata = response.candidates.firstOrNull()?.groundingMetadata
            val searchQueries = metadata?.webSearchQueries.orEmpty()
            val citations = metadata?.groundingChunks
                ?.mapNotNull { chunk ->
                    val web = chunk.web
                    val uri = web?.uri
                    if (!uri.isNullOrBlank()) {
                        WebCitation(
                            title = web.title?.takeIf { it.isNotBlank() } ?: uri,
                            url = uri
                        )
                    } else null
                }
                ?.distinctBy { it.url }
                .orEmpty()

            WebResearchResult(
                query = query,
                answer = answer,
                citations = citations,
                searchQueriesUsed = searchQueries,
                explainedInHindi = explainInHindi || effectiveLanguage == LanguageMode.HINDI
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Web research timed out", e)
            throw IllegalStateException("Web research timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Web research failed", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "FridayAiRepo"
        const val AI_TIMEOUT_MS = 60_000L
    }
}

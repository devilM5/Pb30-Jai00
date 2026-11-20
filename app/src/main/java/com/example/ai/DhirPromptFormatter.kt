package com.example.ai

import org.json.JSONArray
import org.json.JSONObject

data class DhirTurn(
    val role: String, // "user", "assistant", "observation"
    val content: String
)

data class ParsedToolCall(
    val name: String,
    val arguments: Map<String, String>
)

/**
 * Exact prompt formatter and parser for BrahmAI Dhīr 2B (`zenteiq-ai/Dhir-2B-GGUF`)
 * implementing its `BrahmAI Dhir v5 nothink` Jinja chat + tool-calling format:
 *
 * `<bos><|system|>{sys}[<|tool_declare|><tools>{tools_json}</tools>]<|end_of_turn|>`
 * `<|user|>{msg}<|end_of_turn|>`
 * `<|assistant|><|nothink|>{reply}<|end_of_turn|>`
 * `<|observation|><tool_response>{obs}</tool_response><|end_of_turn|>`
 * `<|assistant|><|nothink|>`
 */
object DhirPromptFormatter {

    const val BOS = "<bos>"
    const val EOS = "<eos>"
    const val END_OF_TURN = "<|end_of_turn|>"
    const val SYSTEM_TAG = "<|system|>"
    const val USER_TAG = "<|user|>"
    const val ASSISTANT_TAG = "<|assistant|>"
    const val OBSERVATION_TAG = "<|observation|>"
    const val NOTHINK_TAG = "<|nothink|>"
    const val TOOL_DECLARE_TAG = "<|tool_declare|>"

    val STOP_SEQUENCES = listOf(END_OF_TURN, EOS, "<|user|>", "<|system|>")

    private val toolCallRegex = Regex("""<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""")

    fun formatPrompt(
        systemPrompt: String,
        turns: List<DhirTurn>,
        toolsJson: String? = null,
        includeBos: Boolean = true
    ): String = buildString {
        if (includeBos) append(BOS)
        append(SYSTEM_TAG)
        append(systemPrompt.trim())
        if (!toolsJson.isNullOrBlank()) {
            append(TOOL_DECLARE_TAG)
            append("<tools>")
            append(toolsJson.trim())
            append("</tools>")
        }
        append(END_OF_TURN)
        append('\n')

        turns.forEach { turn ->
            when (turn.role.lowercase()) {
                "assistant", "friday", "model" -> {
                    append(ASSISTANT_TAG)
                    append(NOTHINK_TAG)
                    append(turn.content.trim())
                    append(END_OF_TURN)
                    append('\n')
                }
                "observation", "tool" -> {
                    append(OBSERVATION_TAG)
                    append("<tool_response>")
                    append(turn.content.trim())
                    append("</tool_response>")
                    append(END_OF_TURN)
                    append('\n')
                }
                else -> {
                    append(USER_TAG)
                    append(turn.content.trim())
                    append(END_OF_TURN)
                    append('\n')
                }
            }
        }

        append(ASSISTANT_TAG)
        append(NOTHINK_TAG)
    }

    fun parseToolCalls(rawOutput: String): List<ParsedToolCall> {
        return toolCallRegex.findAll(rawOutput).mapNotNull { match ->
            val jsonText = match.groupValues.getOrNull(1)?.trim().orEmpty()
            runCatching {
                val obj = JSONObject(jsonText)
                val name = obj.optString("name").trim()
                if (name.isEmpty()) return@runCatching null
                val argsObj = obj.optJSONObject("arguments") ?: JSONObject()
                val argsMap = buildMap {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, argsObj.opt(k)?.toString().orEmpty())
                    }
                }
                ParsedToolCall(name = name, arguments = argsMap)
            }.getOrNull()
        }.toList()
    }

    fun cleanVisibleText(rawOutput: String): String {
        return rawOutput
            .replace(toolCallRegex, "")
            .replace(NOTHINK_TAG, "")
            .replace(END_OF_TURN, "")
            .replace(BOS, "")
            .replace(EOS, "")
            .replace("<|think|>", "")
            .trim()
    }

    fun buildToolDeclarationsJson(): String {
        val tools = JSONArray()
        fun addTool(name: String, description: String, paramName: String, paramDesc: String) {
            tools.put(
                JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", name)
                        put("description", description)
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put(paramName, JSONObject().apply {
                                    put("type", "string")
                                    put("description", paramDesc)
                                })
                            })
                            put("required", JSONArray().put(paramName))
                        })
                    })
                }
            )
        }
        addTool("web_search", "Search the live internet for technical, CVE, or general knowledge articles with source links.", "query", "Search keywords")
        addTool("fetch_website", "Retrieve and extract readable content from an https:// webpage URL.", "url", "Target HTTPS URL")
        addTool("youtube_search", "Search YouTube videos and extract available captions/transcripts.", "query", "Topic or YouTube video URL")
        addTool("search_memory", "Search user's saved local notes and recent chat history.", "query", "Keyword to search in local notes and chat logs")
        addTool("save_note", "Save an important summary or study brief to local FRIDAY Memory.", "content", "Note body to save")
        addTool("prepare_terminal_command", "Stage a Termux or Linux SSH command in the mandatory user approval dialog. Never executes without approval.", "command", "Shell command to propose for operator approval")
        addTool("check_environment_status", "Inspect current Termux, SSH, Dhīr model, voice, and offline/online sync readiness.", "scope", "all, termux, ssh, model, or sync")
        return tools.toString()
    }
}

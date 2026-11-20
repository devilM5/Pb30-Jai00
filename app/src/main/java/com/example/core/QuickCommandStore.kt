package com.example.core

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.QuickCommandItem
import com.example.data.model.TerminalEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persists operator-saved Quick Commands for one-tap approval-gated execution across
 * Local Android Termux and Remote Linux SSH environments.
 */
class QuickCommandStore internal constructor(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun loadCommands(): List<QuickCommandItem> {
        val raw = prefs.getString(KEY_COMMANDS_JSON, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultCommands()
            persist(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                    val label = obj.optString("label").trim()
                    val command = obj.optString("command").trim()
                    val isBuiltIn = obj.optBoolean("isBuiltIn", false)
                    if (label.isNotEmpty() && command.isNotEmpty()) {
                        add(QuickCommandItem(id = id, label = label, command = command, isBuiltIn = isBuiltIn))
                    }
                }
            }.ifEmpty { defaultCommands() }
        } catch (_: Exception) {
            defaultCommands()
        }
    }

    fun addCommand(rawLabel: String, rawCommand: String): Result<List<QuickCommandItem>> {
        val label = CredentialRedactor.redact(rawLabel.trim()).take(48)
        val command = CredentialRedactor.redact(rawCommand.trim()).take(400)
        if (label.isEmpty() || command.isEmpty()) {
            return Result.failure(IllegalArgumentException("Both command label and shell command are required."))
        }
        val policyCheck = CommandPolicyValidator.evaluateCommand(command, TerminalEnvironment.TERMUX_BRIDGE)
        if (policyCheck.isBlocked) {
            return Result.failure(IllegalArgumentException(policyCheck.reason))
        }
        val current = loadCommands().toMutableList()
        val updatedItem = QuickCommandItem(
            id = UUID.randomUUID().toString().take(8),
            label = label,
            command = command,
            isBuiltIn = false
        )
        current.add(0, updatedItem)
        persist(current)
        return Result.success(current)
    }

    fun deleteCommand(id: String): List<QuickCommandItem> {
        val next = loadCommands().filterNot { it.id == id }
        persist(next)
        return next
    }

    private fun persist(items: List<QuickCommandItem>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("label", item.label)
                    put("command", item.command)
                    put("isBuiltIn", item.isBuiltIn)
                }
            )
        }
        prefs.edit().putString(KEY_COMMANDS_JSON, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "friday_quick_commands_prefs"
        private const val KEY_COMMANDS_JSON = "quick_commands_v1"

        fun defaultCommands(): List<QuickCommandItem> = listOf(
            QuickCommandItem("qc_nmap_sv", "Nmap Service Scan", "nmap -sV 127.0.0.1", isBuiltIn = true),
            QuickCommandItem("qc_nmap_sn", "Lab Host Discovery", "nmap -sn 192.168.1.0/24", isBuiltIn = true),
            QuickCommandItem("qc_uname", "Kernel & Arch", "uname -a", isBuiltIn = true),
            QuickCommandItem("qc_sockets", "Listening Ports", "ss -tulnp", isBuiltIn = true),
            QuickCommandItem("qc_py_ver", "Python Check", "python3 --version", isBuiltIn = true)
        )
    }
}

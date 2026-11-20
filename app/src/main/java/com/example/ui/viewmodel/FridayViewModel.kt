package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.CommandPolicyValidator
import com.example.core.CredentialRedactor
import com.example.core.FridayCommandRouter
import com.example.core.ProjectFileHelper
import com.example.core.QuickCommandStore
import com.example.core.SecureSshCredentialStore
import com.example.core.VoiceController
import com.example.core.VoiceUiState
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.BridgeExecutionResult
import com.example.data.model.ChatMessage
import com.example.data.model.CodeLanguage
import com.example.data.model.ConversationalAction
import com.example.data.model.LanguageMode
import com.example.data.model.LearningNote
import com.example.data.model.LoadedProjectFile
import com.example.data.model.NoteCategory
import com.example.data.model.PendingCommandApproval
import com.example.data.model.ProposedFilePatch
import com.example.data.model.QuickCommandItem
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TerminalLog
import com.example.data.model.TermuxConnectionMode
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.UiState
import com.example.data.model.WebResearchResult
import com.example.data.repository.FridayAiRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.SshRemoteExecutor
import com.example.data.repository.TerminalBridgeClient
import com.example.data.repository.TermuxCompanionClient
import com.example.data.repository.ToolAvailabilityParser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FridayViewModel(
    private val memoryRepository: MemoryRepository,
    private val aiRepository: FridayAiRepository,
    private val voiceController: VoiceController,
    private val currentUserId: String,
    private val bridgeClient: TerminalBridgeClient = TerminalBridgeClient(),
    private val termuxClient: TermuxCompanionClient? = null,
    private val sshExecutor: SshRemoteExecutor? = null,
    private val sshCredentialStore: SecureSshCredentialStore? = null,
    private val quickCommandStore: QuickCommandStore? = null
) : ViewModel() {

    private fun <T> Flow<List<T>>.toUiStateFlow(errorLabel: String): StateFlow<UiState<List<T>>> =
        map<List<T>, UiState<List<T>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val root = firestoreError ?: error
                Log.w(TAG, errorLabel, root)
                emit(UiState.Error(root.message ?: errorLabel))
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState.Loading)

    val notesState: StateFlow<UiState<List<LearningNote>>> =
        memoryRepository.observeNotes(currentUserId)
            .map { list ->
                list.sortedWith(
                    compareByDescending<LearningNote> { it.pinned }
                        .thenByDescending { it.updatedAt?.seconds ?: 0L }
                )
            }
            .toUiStateFlow("Failed to load saved notes.")

    val chatHistoryState: StateFlow<UiState<List<ChatMessage>>> =
        memoryRepository.observeChatMessages(currentUserId)
            .toUiStateFlow("Failed to load chat history.")

    val terminalLogsState: StateFlow<UiState<List<TerminalLog>>> =
        memoryRepository.observeTerminalLogs(currentUserId)
            .toUiStateFlow("Failed to load terminal logs.")

    val voiceState: StateFlow<VoiceUiState> = voiceController.state

    private val _languageMode = MutableStateFlow(LanguageMode.HINGLISH)
    val languageMode: StateFlow<LanguageMode> = _languageMode.asStateFlow()

    private val _selectedModel = MutableStateFlow(AiModelOption.FLASH_3_8)
    val selectedModel: StateFlow<AiModelOption> = _selectedModel.asStateFlow()

    private val _chatStreamState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val chatStreamState: StateFlow<UiState<String>> = _chatStreamState.asStateFlow()

    private val _pendingChatAction = MutableStateFlow<ConversationalAction>(ConversationalAction.None)
    val pendingChatAction: StateFlow<ConversationalAction> = _pendingChatAction.asStateFlow()

    private val _activeAgent = MutableStateFlow(AgentType.RESEARCH)
    val activeAgent: StateFlow<AgentType> = _activeAgent.asStateFlow()

    private val _agentPromptInput = MutableStateFlow("")
    val agentPromptInput: StateFlow<String> = _agentPromptInput.asStateFlow()

    private val _agentStreamState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val agentStreamState: StateFlow<UiState<String>> = _agentStreamState.asStateFlow()

    private val _reportAnalysisState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val reportAnalysisState: StateFlow<UiState<String>> = _reportAnalysisState.asStateFlow()

    // Coding Agent
    private val _codeLanguage = MutableStateFlow(CodeLanguage.PYTHON)
    val codeLanguage: StateFlow<CodeLanguage> = _codeLanguage.asStateFlow()

    private val _codeEditorText = MutableStateFlow(CodeLanguage.PYTHON.starterTemplate)
    val codeEditorText: StateFlow<String> = _codeEditorText.asStateFlow()

    private val _codeTaskPrompt = MutableStateFlow("")
    val codeTaskPrompt: StateFlow<String> = _codeTaskPrompt.asStateFlow()

    private val _codingAiState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val codingAiState: StateFlow<UiState<String>> = _codingAiState.asStateFlow()

    private val _sandboxResultState = MutableStateFlow<UiState<BridgeExecutionResult>>(UiState.Idle)
    val sandboxResultState: StateFlow<UiState<BridgeExecutionResult>> = _sandboxResultState.asStateFlow()

    // Dual Execution Environments (Local Android Termux & Remote Linux SSH)
    private val _selectedExecutionEnv = MutableStateFlow(TerminalEnvironment.TERMUX_BRIDGE)
    val selectedExecutionEnv: StateFlow<TerminalEnvironment> = _selectedExecutionEnv.asStateFlow()

    private val _termuxState = MutableStateFlow(
        termuxClient?.inspectEnvironment()
            ?: TermuxEnvironmentState(apkInstalled = bridgeClient.isTermuxAppInstalled())
    )
    val termuxState: StateFlow<TermuxEnvironmentState> = _termuxState.asStateFlow()

    private val _sshProfile = MutableStateFlow(sshCredentialStore?.loadProfile() ?: SshConnectionProfile())
    val sshProfile: StateFlow<SshConnectionProfile> = _sshProfile.asStateFlow()

    private val _sshState = MutableStateFlow(SshConnectionState())
    val sshState: StateFlow<SshConnectionState> = _sshState.asStateFlow()

    private val _commandTimeoutSeconds = MutableStateFlow(30)
    val commandTimeoutSeconds: StateFlow<Int> = _commandTimeoutSeconds.asStateFlow()

    private val _isCommandRunning = MutableStateFlow(false)
    val isCommandRunning: StateFlow<Boolean> = _isCommandRunning.asStateFlow()

    private val _pendingApproval = MutableStateFlow<PendingCommandApproval?>(null)
    val pendingApproval: StateFlow<PendingCommandApproval?> = _pendingApproval.asStateFlow()

    private val _terminalExecState = MutableStateFlow<UiState<BridgeExecutionResult>>(UiState.Idle)
    val terminalExecState: StateFlow<UiState<BridgeExecutionResult>> = _terminalExecState.asStateFlow()

    private val _quickCommands = MutableStateFlow(quickCommandStore?.loadCommands() ?: QuickCommandStore.defaultCommands())
    val quickCommands: StateFlow<List<QuickCommandItem>> = _quickCommands.asStateFlow()

    // Web Research & File Agent
    private val _webResearchState = MutableStateFlow<UiState<WebResearchResult>>(UiState.Idle)
    val webResearchState: StateFlow<UiState<WebResearchResult>> = _webResearchState.asStateFlow()

    private val _loadedFile = MutableStateFlow<LoadedProjectFile?>(null)
    val loadedFile: StateFlow<LoadedProjectFile?> = _loadedFile.asStateFlow()

    private val _fileAnalysisState = MutableStateFlow<UiState<String>>(UiState.Idle)
    val fileAnalysisState: StateFlow<UiState<String>> = _fileAnalysisState.asStateFlow()

    private val _proposedFilePatch = MutableStateFlow<ProposedFilePatch?>(null)
    val proposedFilePatch: StateFlow<ProposedFilePatch?> = _proposedFilePatch.asStateFlow()

    private val _checkedChecklistIds = MutableStateFlow<Set<String>>(emptySet())
    val checkedChecklistIds: StateFlow<Set<String>> = _checkedChecklistIds.asStateFlow()

    private val _completedQuizModuleIds = MutableStateFlow<Set<String>>(emptySet())
    val completedQuizModuleIds: StateFlow<Set<String>> = _completedQuizModuleIds.asStateFlow()

    private val _memorySearchQuery = MutableStateFlow("")
    val memorySearchQuery: StateFlow<String> = _memorySearchQuery.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var chatJob: Job? = null
    private var agentJob: Job? = null
    private var reportJob: Job? = null
    private var codingJob: Job? = null
    private var researchJob: Job? = null
    private var fileJob: Job? = null
    private var activeCommandJob: Job? = null

    fun setLanguageMode(mode: LanguageMode) { _languageMode.value = mode }
    fun setSelectedModel(option: AiModelOption) { _selectedModel.value = option }
    fun selectAgent(agent: AgentType) { _activeAgent.value = agent; _agentStreamState.value = UiState.Idle }
    fun setAgentPromptInput(text: String) { _agentPromptInput.value = text }
    fun updateMemorySearch(query: String) { _memorySearchQuery.value = query }
    fun clearStatusBanner() { _statusBanner.value = null }
    fun dismissChatAction() { _pendingChatAction.value = ConversationalAction.None }

    fun toggleChecklistItem(itemId: String) {
        val current = _checkedChecklistIds.value
        _checkedChecklistIds.value = if (itemId in current) current - itemId else current + itemId
    }

    fun markQuizCompleted(moduleId: String) {
        _completedQuizModuleIds.value = _completedQuizModuleIds.value + moduleId
    }

    fun sendChatPrompt(rawPrompt: String, autoSpeakResponse: Boolean = false) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return

        val effectiveLanguage = if (FridayCommandRouter.requestsHindiResponse(prompt)) {
            _languageMode.value = LanguageMode.HINDI
            LanguageMode.HINDI
        } else {
            _languageMode.value
        }

        val routedAction = FridayCommandRouter.detectAction(prompt)
        _pendingChatAction.value = routedAction
        if (routedAction is ConversationalAction.RequestTerminalApproval) {
            requestCommandExecution(routedAction.command, _selectedExecutionEnv.value)
        } else if (routedAction is ConversationalAction.RequestAuthorizedNmap) {
            requestCommandExecution(routedAction.suggestedCommand, _selectedExecutionEnv.value)
        }

        chatJob?.cancel()
        chatJob = viewModelScope.launch {
            val currentMessages = (chatHistoryState.value as? UiState.Success)?.data.orEmpty()
            val historyPairs = currentMessages.filter { it.agentType == AgentType.CHAT.name }
                .takeLast(16).map { it.role to it.content }

            _chatStreamState.value = UiState.Loading
            if (memoryRepository.saveChatMessage("USER", prompt, effectiveLanguage, AgentType.CHAT).isFailure) {
                _statusBanner.value = "Could not sync user message to cloud history."
            }

            var latestText = ""
            try {
                aiRepository.streamResponse(
                    prompt = prompt,
                    languageMode = effectiveLanguage,
                    agentType = AgentType.CHAT,
                    modelOption = _selectedModel.value,
                    history = historyPairs
                ).collect { partial ->
                    latestText = partial
                    _chatStreamState.value = UiState.Streaming(partial)
                }
                if (latestText.isNotBlank()) {
                    memoryRepository.saveChatMessage("FRIDAY", latestText, effectiveLanguage, AgentType.CHAT)
                    _chatStreamState.value = UiState.Idle
                    if (autoSpeakResponse) speakText(latestText, id = "latest_friday_reply")
                } else {
                    _chatStreamState.value = UiState.Error("FRIDAY returned an empty response.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Chat stream failed", e)
                _chatStreamState.value = UiState.Error(e.localizedMessage ?: "FRIDAY AI request failed.")
            }
        }
    }

    fun runSpecializedAgent(agent: AgentType, rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return
        _activeAgent.value = agent
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            val mode = _languageMode.value
            _agentStreamState.value = UiState.Loading
            var latestText = ""
            try {
                aiRepository.streamResponse(input, mode, agent, _selectedModel.value).collect { partial ->
                    latestText = partial
                    _agentStreamState.value = UiState.Streaming(partial)
                }
                if (latestText.isNotBlank()) {
                    _agentStreamState.value = UiState.Success(latestText)
                    memoryRepository.saveChatMessage("USER", "[${agent.title}] $input", mode, agent)
                    memoryRepository.saveChatMessage("FRIDAY", latestText, mode, agent)
                } else {
                    _agentStreamState.value = UiState.Error("Agent returned an empty response.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Agent execution failed", e)
                _agentStreamState.value = UiState.Error(e.localizedMessage ?: "Agent execution failed.")
            }
        }
    }

    fun analyzeAuthorizedReport(rawReport: String, isSimulatedSample: Boolean) {
        val report = rawReport.trim()
        if (report.isEmpty()) return
        reportJob?.cancel()
        reportJob = viewModelScope.launch {
            val mode = _languageMode.value
            _reportAnalysisState.value = UiState.Loading
            val prefix = if (isSimulatedSample) {
                "Analyze this SIMULATED LAB SAMPLE report (label as Educational Lab Review):\n\n"
            } else {
                "Analyze this AUTHORIZED SECURITY REPORT (summarize risk, root causes, and defensive fixes):\n\n"
            }
            var latestText = ""
            try {
                aiRepository.streamResponse(prefix + report, mode, AgentType.REPORT_ANALYSIS, _selectedModel.value).collect { partial ->
                    latestText = partial
                    _reportAnalysisState.value = UiState.Streaming(partial)
                }
                _reportAnalysisState.value = if (latestText.isNotBlank()) UiState.Success(latestText)
                else UiState.Error("Report analysis returned empty output.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _reportAnalysisState.value = UiState.Error(e.localizedMessage ?: "Failed to analyze report.")
            }
        }
    }

    // --- Coding Agent ---
    fun selectCodeLanguage(lang: CodeLanguage, loadTemplateIfEmpty: Boolean = true) {
        _codeLanguage.value = lang
        if (loadTemplateIfEmpty) _codeEditorText.value = lang.starterTemplate
    }

    fun updateCodeEditor(code: String) { _codeEditorText.value = code }
    fun updateCodeTaskPrompt(prompt: String) { _codeTaskPrompt.value = prompt }

    fun runCodingAction(actionLabel: String, customPrompt: String = _codeTaskPrompt.value) {
        val lang = _codeLanguage.value
        val code = _codeEditorText.value
        val task = customPrompt.trim()
        val prompt = when (actionLabel) {
            "GENERATE" -> "Write clean, well-commented ${lang.displayName} code for: ${task.ifBlank { "secure utility function" }}. Return complete runnable code."
            "EXPLAIN" -> "Explain this ${lang.displayName} code line by line clearly:\n\n```${lang.extension}\n$code\n```"
            else -> "Debug and suggest concrete fixes for any bugs, edge cases, or security risks in this ${lang.displayName} code. Context: $task\n\n```${lang.extension}\n$code\n```"
        }
        codingJob?.cancel()
        codingJob = viewModelScope.launch {
            _codingAiState.value = UiState.Loading
            var latest = ""
            try {
                aiRepository.streamResponse(prompt, _languageMode.value, AgentType.CODING, _selectedModel.value).collect {
                    latest = it
                    _codingAiState.value = UiState.Streaming(it)
                }
                _codingAiState.value = if (latest.isNotBlank()) UiState.Success(latest)
                else UiState.Error("Coding Agent returned an empty response.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _codingAiState.value = UiState.Error(e.localizedMessage ?: "Coding Agent failed.")
            }
        }
    }

    fun executeEditorCodeInSandbox() {
        val code = _codeEditorText.value
        if (code.isBlank()) return
        val lang = _codeLanguage.value
        val termux = _termuxState.value
        viewModelScope.launch {
            _sandboxResultState.value = UiState.Loading
            val result = bridgeClient.executeInIsolatedSandbox(lang, code, termux.companionUrl, termux.companionToken)
            _sandboxResultState.value = UiState.Success(result)
            val summary = listOf(result.stdout, result.stderr.takeIf { it.isNotBlank() }?.let { "ERR: $it" }.orEmpty())
                .filter { it.isNotBlank() }.joinToString("\n").ifBlank { "No output" }
            val redactedCmd = CredentialRedactor.redactWithSecrets(
                "[SANDBOX:${lang.name}] ${code.lineSequence().firstOrNull().orEmpty().take(120)}",
                runtimeSecrets()
            )
            memoryRepository.saveTerminalLog(
                command = redactedCmd,
                environment = TerminalEnvironment.CODE_SANDBOX,
                status = result.status,
                output = CredentialRedactor.redactWithSecrets(summary, runtimeSecrets()),
                exitCode = result.exitCode,
                approvedByUser = true
            )
        }
    }

    // --- Dual Execution Environments: Termux & Remote SSH ---
    fun selectExecutionEnvironment(env: TerminalEnvironment) {
        _selectedExecutionEnv.value = env
    }

    fun refreshTermuxStatus() {
        val client = termuxClient ?: return
        _termuxState.value = client.inspectEnvironment(_termuxState.value)
    }

    fun selectTermuxMode(mode: TermuxConnectionMode) {
        _termuxState.value = _termuxState.value.copy(
            companionMode = mode,
            isConnected = false,
            statusMessage = "Mode switched to ${mode.displayName}. Tap Verify & Connect to test."
        )
    }

    fun updateTermuxHttpConfig(url: String, token: String) {
        _termuxState.value = _termuxState.value.copy(
            companionUrl = url,
            companionToken = token,
            isConnected = false
        )
    }

    fun verifyAndConnectTermux() {
        val client = termuxClient ?: return
        viewModelScope.launch {
            _termuxState.value = _termuxState.value.copy(isChecking = true, statusMessage = "Verifying Termux environment…")
            _termuxState.value = client.verifyAndConnect(_termuxState.value)
        }
    }

    fun disconnectTermux() {
        val client = termuxClient
        _termuxState.value = client?.disconnect(_termuxState.value)
            ?: _termuxState.value.copy(isConnected = false, statusMessage = "Disconnected.")
    }

    fun stageTermuxToolProbe() {
        requestCommandExecution(
            rawCommand = ToolAvailabilityParser.TOOL_PROBE_COMMAND,
            environment = TerminalEnvironment.TERMUX_BRIDGE
        )
    }

    fun updateSshProfileInMemory(updated: SshConnectionProfile) {
        val endpointChanged = updated.host != _sshProfile.value.host ||
            updated.port != _sshProfile.value.port ||
            updated.username != _sshProfile.value.username ||
            updated.authMethod != _sshProfile.value.authMethod
        _sshProfile.value = updated
        if (endpointChanged && _sshState.value.isConnected) {
            disconnectSsh("Connection settings changed; reconnect to apply.")
        }
    }

    fun saveRemoteServerDetails(
        host: String,
        port: Int,
        username: String,
        authMethod: com.example.data.model.SshAuthMethod
    ) {
        val cleanHost = host.trim()
        val cleanUser = username.trim()
        val safePort = port.coerceIn(1, 65535)
        if (cleanHost.isEmpty() || cleanUser.isEmpty()) {
            _statusBanner.value = "Host and Username are required to save Remote Server Settings."
            return
        }
        val saved = sshCredentialStore?.saveConnectionDetails(cleanHost, safePort, cleanUser, authMethod) ?: false
        _sshProfile.value = _sshProfile.value.copy(
            host = cleanHost,
            port = safePort,
            username = cleanUser,
            authMethod = authMethod,
            persistSecretsEncrypted = saved
        )
        _statusBanner.value = if (saved) {
            "Host & Username saved via EncryptedSharedPreferences (password not stored)."
        } else {
            "EncryptedSharedPreferences unavailable; details kept in memory only."
        }
    }

    fun updateSshProfile(updated: SshConnectionProfile, persistConnectionDetails: Boolean) {
        val next = updated.copy(persistSecretsEncrypted = persistConnectionDetails)
        updateSshProfileInMemory(next)
        val savedOk = sshCredentialStore?.saveProfile(next, persistConnectionDetails) ?: true
        if (!savedOk && persistConnectionDetails) {
            _sshProfile.value = next.copy(persistSecretsEncrypted = false)
            _statusBanner.value = "EncryptedSharedPreferences unavailable; keeping connection details in memory only."
        }
    }

    fun clearStoredSshCredentials() {
        sshCredentialStore?.clearAll()
        _sshProfile.value = SshConnectionProfile()
        disconnectSsh("Saved SSH connection details cleared from EncryptedSharedPreferences.")
        _statusBanner.value = "Saved SSH details cleared."
    }

    fun connectAndTestSsh() {
        val exec = sshExecutor ?: return
        val profile = _sshProfile.value
        viewModelScope.launch {
            _sshState.value = _sshState.value.copy(
                isConnecting = true,
                isConnected = false,
                statusDetail = "Connecting via SSH to ${profile.username}@${profile.host}:${profile.port}…"
            )
            val result = exec.connectAndTest(profile)
            _sshState.value = if (result.isSuccess) {
                val summary = result.getOrThrow()
                SshConnectionState(
                    isConnected = true,
                    isConnecting = false,
                    serverInfo = summary.serverBanner,
                    hostFingerprint = summary.hostFingerprint,
                    statusDetail = "SSH authenticated & ready on ${profile.username.trim()}@${profile.host.trim()}:${profile.port}."
                )
            } else {
                SshConnectionState(
                    isConnected = false,
                    isConnecting = false,
                    statusDetail = result.exceptionOrNull()?.localizedMessage ?: "SSH connection failed."
                )
            }
        }
    }

    fun disconnectSsh(reason: String = "SSH session disconnected by operator.") {
        viewModelScope.launch {
            sshExecutor?.disconnect()
            _sshState.value = SshConnectionState(isConnected = false, isConnecting = false, statusDetail = reason)
        }
    }

    fun lockVaultSession() {
        sshExecutor?.abortRunningChannel()
        activeCommandJob?.cancel()
        disconnectSsh("Vault locked behind BiometricPrompt.")
        _sshProfile.value = _sshProfile.value.copy(
            password = "",
            privateKeyPem = "",
            keyPassphrase = ""
        )
        _termuxState.value = _termuxState.value.copy(companionToken = "", isConnected = false)
    }

    fun setCommandTimeoutSeconds(seconds: Int) {
        _commandTimeoutSeconds.value = seconds.coerceIn(10, 300)
    }

    private fun runtimeSecrets(): List<String> = listOf(
        _sshProfile.value.password,
        _sshProfile.value.keyPassphrase,
        _sshProfile.value.privateKeyPem,
        _termuxState.value.companionToken
    ).filter { it.isNotBlank() }

    fun saveQuickCommand(label: String, command: String) {
        val store = quickCommandStore
        if (store != null) {
            val res = store.addCommand(label, command)
            if (res.isSuccess) {
                _quickCommands.value = res.getOrThrow()
                _statusBanner.value = "Quick command '${label.trim()}' saved."
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Could not save quick command."
            }
        } else {
            val cleanLabel = CredentialRedactor.redactWithSecrets(label.trim(), runtimeSecrets()).take(48)
            val cleanCmd = CredentialRedactor.redactWithSecrets(command.trim(), runtimeSecrets()).take(400)
            if (cleanLabel.isEmpty() || cleanCmd.isEmpty()) {
                _statusBanner.value = "Both command label and shell command are required."
                return
            }
            val check = CommandPolicyValidator.evaluateCommand(cleanCmd, _selectedExecutionEnv.value)
            if (check.isBlocked) {
                _statusBanner.value = check.reason
                return
            }
            val added = QuickCommandItem(
                id = "qc_${System.currentTimeMillis()}",
                label = cleanLabel,
                command = cleanCmd,
                isBuiltIn = false
            )
            _quickCommands.value = listOf(added) + _quickCommands.value
        }
    }

    fun deleteQuickCommand(id: String) {
        _quickCommands.value = quickCommandStore?.deleteCommand(id)
            ?: _quickCommands.value.filterNot { it.id == id }
    }

    fun requestCommandExecution(rawCommand: String, environment: TerminalEnvironment) {
        val hostSummary = when (environment) {
            TerminalEnvironment.LINUX_SERVER_BRIDGE -> {
                val prof = _sshProfile.value
                val stateLabel = if (_sshState.value.isConnected) "CONNECTED" else "DISCONNECTED"
                "${prof.username.ifBlank { "user" }}@${prof.host.ifBlank { "unconfigured" }}:${prof.port} ($stateLabel)"
            }
            TerminalEnvironment.TERMUX_BRIDGE -> {
                val stateLabel = if (_termuxState.value.isConnected) "CONNECTED" else "DISCONNECTED"
                "Termux ${_termuxState.value.companionMode.displayName} ($stateLabel)"
            }
            TerminalEnvironment.CODE_SANDBOX -> "Isolated Code Sandbox"
        }

        val evaluated = CommandPolicyValidator.evaluateCommand(
            rawCommand = rawCommand,
            environment = environment,
            targetHostSummary = hostSummary,
            timeoutSeconds = _commandTimeoutSeconds.value
        )
        _pendingApproval.value = evaluated
        if (evaluated.isBlocked && evaluated.command.isNotBlank()) {
            val secrets = runtimeSecrets()
            viewModelScope.launch {
                memoryRepository.saveTerminalLog(
                    command = CredentialRedactor.redactWithSecrets(evaluated.command, secrets),
                    environment = environment,
                    status = TerminalExecutionStatus.BLOCKED,
                    output = CredentialRedactor.redactWithSecrets(evaluated.reason, secrets),
                    exitCode = -1,
                    approvedByUser = false
                )
            }
        }
    }

    fun cancelPendingApproval() {
        val pending = _pendingApproval.value ?: return
        _pendingApproval.value = null
        if (!pending.isBlocked && pending.command.isNotBlank()) {
            val secrets = runtimeSecrets()
            viewModelScope.launch {
                memoryRepository.saveTerminalLog(
                    command = CredentialRedactor.redactWithSecrets(pending.command, secrets),
                    environment = pending.environment,
                    status = TerminalExecutionStatus.CANCELLED,
                    output = "Cancelled explicitly by operator prior to execution.",
                    exitCode = -1,
                    approvedByUser = false
                )
            }
        }
    }

    fun approveAndExecutePendingCommand() {
        val pending = _pendingApproval.value ?: return
        if (pending.isBlocked) {
            _statusBanner.value = pending.reason
            return
        }
        _pendingApproval.value = null
        activeCommandJob?.cancel()
        activeCommandJob = viewModelScope.launch {
            _isCommandRunning.value = true
            _terminalExecState.value = UiState.Loading
            try {
                val res = when (pending.environment) {
                    TerminalEnvironment.LINUX_SERVER_BRIDGE -> {
                        sshExecutor?.executeCommand(
                            profile = _sshProfile.value,
                            command = pending.command,
                            timeoutSeconds = pending.timeoutSeconds
                        ) ?: BridgeExecutionResult(
                            executed = false,
                            status = TerminalExecutionStatus.BRIDGE_UNAVAILABLE,
                            exitCode = -1,
                            stdout = "",
                            stderr = "SSH executor not initialized.",
                            environment = TerminalEnvironment.LINUX_SERVER_BRIDGE
                        )
                    }
                    TerminalEnvironment.TERMUX_BRIDGE -> {
                        val termuxRes = termuxClient?.executeApprovedCommand(
                            state = _termuxState.value,
                            command = pending.command,
                            timeoutSeconds = pending.timeoutSeconds
                        ) ?: bridgeClient.executeApprovedCommand(
                            command = pending.command,
                            environment = TerminalEnvironment.TERMUX_BRIDGE,
                            endpointUrl = _termuxState.value.companionUrl,
                            sessionToken = _termuxState.value.companionToken
                        )
                        val nextTools = ToolAvailabilityParser.updateFromExecution(
                            current = _termuxState.value.toolStatuses,
                            command = pending.command,
                            stdout = termuxRes.stdout,
                            stderr = termuxRes.stderr,
                            exitCode = termuxRes.exitCode
                        )
                        _termuxState.value = _termuxState.value.copy(toolStatuses = nextTools)
                        termuxRes
                    }
                    TerminalEnvironment.CODE_SANDBOX -> {
                        bridgeClient.executeApprovedCommand(
                            command = pending.command,
                            environment = TerminalEnvironment.CODE_SANDBOX,
                            endpointUrl = _termuxState.value.companionUrl,
                            sessionToken = _termuxState.value.companionToken
                        )
                    }
                }

                _terminalExecState.value = UiState.Success(res)
                val combinedOutput = buildString {
                    if (res.stdout.isNotBlank()) append(res.stdout)
                    if (res.stderr.isNotBlank()) {
                        if (isNotEmpty()) append("\n")
                        append(res.stderr)
                    }
                }.ifBlank { "Exit ${res.exitCode}" }

                val secrets = runtimeSecrets()
                memoryRepository.saveTerminalLog(
                    command = CredentialRedactor.redactWithSecrets(pending.command, secrets),
                    environment = pending.environment,
                    status = res.status,
                    output = CredentialRedactor.redactWithSecrets(combinedOutput, secrets),
                    exitCode = res.exitCode,
                    approvedByUser = true
                )
            } catch (cancel: CancellationException) {
                val cancelledResult = BridgeExecutionResult(
                    executed = false,
                    status = TerminalExecutionStatus.CANCELLED,
                    exitCode = -1,
                    stdout = "",
                    stderr = "Execution cancelled by operator.",
                    environment = pending.environment
                )
                _terminalExecState.value = UiState.Success(cancelledResult)
                val secrets = runtimeSecrets()
                withContext(NonCancellable) {
                    memoryRepository.saveTerminalLog(
                        command = CredentialRedactor.redactWithSecrets(pending.command, secrets),
                        environment = pending.environment,
                        status = TerminalExecutionStatus.CANCELLED,
                        output = "Active execution cancelled by operator.",
                        exitCode = -1,
                        approvedByUser = true
                    )
                }
                throw cancel
            } finally {
                _isCommandRunning.value = false
            }
        }
    }

    fun cancelActiveCommandExecution() {
        sshExecutor?.abortRunningChannel()
        activeCommandJob?.cancel()
    }

    fun deleteTerminalLog(logId: String) {
        viewModelScope.launch {
            val res = memoryRepository.deleteTerminalLog(logId)
            if (res.isFailure) {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Failed to delete log."
            }
        }
    }

    // --- Web Research Agent ---
    fun runWebResearch(rawQuery: String, explainInHindi: Boolean, autoSpeak: Boolean = false) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return
        researchJob?.cancel()
        researchJob = viewModelScope.launch {
            _webResearchState.value = UiState.Loading
            try {
                val res = aiRepository.performWebResearch(
                    query = query,
                    languageMode = _languageMode.value,
                    explainInHindi = explainInHindi,
                    modelOption = _selectedModel.value
                )
                _webResearchState.value = UiState.Success(res)
                if (autoSpeak) {
                    voiceController.speak(res.answer, "web_research_reply", preferHindi = explainInHindi || _languageMode.value != LanguageMode.ENGLISH)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _webResearchState.value = UiState.Error(e.localizedMessage ?: "Web Research failed.")
            }
        }
    }

    // --- File Agent ---
    fun loadProjectFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val res = ProjectFileHelper.readDocument(context, uri)
            if (res.isSuccess) {
                _loadedFile.value = res.getOrThrow()
                _proposedFilePatch.value = null
                _fileAnalysisState.value = UiState.Idle
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Could not read file."
            }
        }
    }

    fun analyzeLoadedFile(instruction: String) {
        val file = _loadedFile.value ?: return
        fileJob?.cancel()
        fileJob = viewModelScope.launch {
            _fileAnalysisState.value = UiState.Loading
            val prompt = """
                Analyze file '${file.fileName}' (${file.mimeType}, ${file.sizeBytes} bytes):
                Operator request: ${instruction.ifBlank { "Summarize architecture, detect bugs, and suggest secure improvements." }}

                File Content:
                ```
                ${file.content.take(12000)}
                ```
            """.trimIndent()
            var latest = ""
            try {
                aiRepository.streamResponse(prompt, _languageMode.value, AgentType.FILE_ANALYSIS, _selectedModel.value).collect {
                    latest = it
                    _fileAnalysisState.value = UiState.Streaming(it)
                }
                _fileAnalysisState.value = if (latest.isNotBlank()) UiState.Success(latest)
                else UiState.Error("File Agent returned an empty response.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _fileAnalysisState.value = UiState.Error(e.localizedMessage ?: "File analysis failed.")
            }
        }
    }

    fun proposeEditedFileContent(editedContent: String, summary: String) {
        val file = _loadedFile.value ?: return
        _proposedFilePatch.value = ProposedFilePatch(
            fileName = file.fileName,
            originalContent = file.content,
            proposedContent = editedContent,
            summary = summary
        )
    }

    fun approveAndSaveFileEdit(context: Context, targetUri: Uri? = null) {
        val file = _loadedFile.value ?: return
        val patch = _proposedFilePatch.value ?: return
        val uriToWrite = targetUri ?: Uri.parse(file.uriString)
        viewModelScope.launch {
            val res = ProjectFileHelper.writeDocument(context, uriToWrite, patch.proposedContent)
            if (res.isSuccess) {
                _loadedFile.value = file.copy(content = patch.proposedContent, uriString = uriToWrite.toString())
                _proposedFilePatch.value = patch.copy(applied = true)
                _statusBanner.value = "Approved edit saved to ${file.fileName}."
            } else {
                _statusBanner.value = res.exceptionOrNull()?.localizedMessage ?: "Could not save edit. Try 'Save As Copy'."
            }
        }
    }

    // --- Memory & Voice ---
    fun saveNote(title: String, content: String, category: NoteCategory, pinned: Boolean = false) {
        viewModelScope.launch {
            val result = memoryRepository.saveNote(title, content, category, pinned)
            _statusBanner.value = if (result.isSuccess) "Saved to FRIDAY Memory."
            else result.exceptionOrNull()?.localizedMessage ?: "Failed to save note."
        }
    }

    fun updateNote(noteId: String, title: String, content: String, category: NoteCategory, pinned: Boolean) {
        viewModelScope.launch {
            val result = memoryRepository.updateNote(noteId, title, content, category, pinned)
            _statusBanner.value = if (result.isSuccess) "Memory note updated."
            else result.exceptionOrNull()?.localizedMessage ?: "Failed to update note."
        }
    }

    fun togglePin(note: LearningNote) {
        viewModelScope.launch {
            val result = memoryRepository.togglePin(note.id, note.pinned)
            if (result.isFailure) _statusBanner.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to update pin."
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            val result = memoryRepository.deleteNote(noteId)
            _statusBanner.value = if (result.isSuccess) "Note deleted."
            else result.exceptionOrNull()?.localizedMessage ?: "Failed to delete note."
        }
    }

    fun deleteChatMessage(messageId: String) {
        viewModelScope.launch {
            val result = memoryRepository.deleteChatMessage(messageId)
            if (result.isFailure) _statusBanner.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to delete message."
        }
    }

    fun clearChatHistory() {
        val messages = (chatHistoryState.value as? UiState.Success)?.data.orEmpty()
        if (messages.isEmpty()) return
        viewModelScope.launch {
            val result = memoryRepository.clearChatHistory(messages)
            _statusBanner.value = if (result.isSuccess) "Conversation history cleared."
            else result.exceptionOrNull()?.localizedMessage ?: "Failed to clear history."
        }
    }

    fun startHindiVoiceInput(onTranscriptReady: (String) -> Unit) {
        voiceController.startListening(
            languageTag = _languageMode.value.speechLocaleTag,
            onResult = onTranscriptReady,
            onError = { _statusBanner.value = it }
        )
    }

    fun stopVoiceInput() { voiceController.stopListening() }
    fun speakText(text: String, id: String = "tts_msg") {
        voiceController.speak(text, id, preferHindi = _languageMode.value != LanguageMode.ENGLISH)
    }
    fun stopSpeaking() { voiceController.stopSpeaking() }

    override fun onCleared() {
        super.onCleared()
        sshExecutor?.abortRunningChannel()
        voiceController.release()
    }

    private companion object {
        const val TAG = "FridayViewModel"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}

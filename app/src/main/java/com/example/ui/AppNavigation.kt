package com.example.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.AndroidLlamaNativeBridge
import com.example.ai.DhirModelManager
import com.example.core.BiometricGateController
import com.example.core.DefaultBiometricGateController
import com.example.core.FridayToolOrchestrator
import com.example.core.QuickCommandStore
import com.example.core.SecureSshCredentialStore
import com.example.core.VoiceController
import com.example.data.model.NoteCategory
import com.example.data.model.TerminalEnvironment
import com.example.data.repository.DhirFridayAiRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.SshRemoteExecutor
import com.example.data.repository.TerminalBridgeClient
import com.example.data.repository.TermuxCompanionClient
import com.example.data.repository.WebToolsRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.BiometricLockScreen
import com.example.ui.auth.signOut
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.FridayDashboardScreen
import com.example.ui.screens.LearningLabScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.RemoteServerSettingsScreen
import com.example.ui.screens.SecurityAgentScreen
import com.example.ui.screens.StudioToolTab
import com.example.ui.screens.ToolsStudioScreen
import com.example.ui.theme.CommandApprovalDialog
import com.example.ui.viewmodel.FridayViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

@Composable
fun AppNavigation(
    auth: FirebaseAuth = Firebase.auth,
    biometricController: BiometricGateController = DefaultBiometricGateController,
    autoPromptBiometricOnStart: Boolean = true
) {
    var isBiometricUnlocked by rememberSaveable { mutableStateOf(false) }
    var allowOfflineLocalSession by rememberSaveable { mutableStateOf(false) }

    if (!isBiometricUnlocked) {
        BiometricLockScreen(
            controller = biometricController,
            autoPromptOnStart = autoPromptBiometricOnStart,
            onUnlocked = { isBiometricUnlocked = true }
        )
        return
    }

    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null && !allowOfflineLocalSession) {
        AuthScreen(
            onAuthSuccess = { allowOfflineLocalSession = false },
            onContinueOffline = { allowOfflineLocalSession = true }
        )
    } else {
        val activeUserId = user?.uid ?: MemoryRepository.LOCAL_OFFLINE_USER_ID
        val operatorLabel = user?.displayName?.takeIf { it.isNotBlank() }
            ?: user?.email?.substringBefore("@")
            ?: "Local Operator"

        key(activeUserId) {
            AuthenticatedFridayShell(
                currentUserId = activeUserId,
                operatorName = operatorLabel,
                isOfflineSession = user == null,
                onExitOfflineToSignIn = { allowOfflineLocalSession = false },
                onLockVault = { isBiometricUnlocked = false }
            )
        }
    }
}

@Composable
private fun AuthenticatedFridayShell(
    currentUserId: String,
    operatorName: String,
    isOfflineSession: Boolean = false,
    onExitOfflineToSignIn: () -> Unit = {},
    onLockVault: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val viewModel: FridayViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val memoryRepo = MemoryRepository(app)
                val webRepo = WebToolsRepository()
                val llamaBridge = AndroidLlamaNativeBridge()
                val modelManager = DhirModelManager(app) { llamaBridge.isNativeLibraryAvailable() }
                val aiRepo = DhirFridayAiRepository(modelManager, llamaBridge, webRepo)
                val httpBridge = TerminalBridgeClient(app)

                lateinit var vmRef: FridayViewModel
                val orchestrator = FridayToolOrchestrator(
                    aiRepository = aiRepo,
                    memoryRepository = memoryRepo,
                    webToolsRepository = webRepo,
                    modelManager = modelManager,
                    currentUserId = currentUserId,
                    termuxStateProvider = { vmRef.termuxState.value },
                    sshProfileProvider = { vmRef.sshProfile.value },
                    sshStateProvider = { vmRef.sshState.value },
                    onStageTerminalApproval = { cmd ->
                        vmRef.requestCommandExecution(cmd, vmRef.selectedExecutionEnv.value)
                    }
                )
                vmRef = FridayViewModel(
                    memoryRepository = memoryRepo,
                    aiRepository = aiRepo,
                    voiceController = VoiceController(app),
                    currentUserId = currentUserId,
                    bridgeClient = httpBridge,
                    termuxClient = TermuxCompanionClient(app, httpBridge),
                    sshExecutor = SshRemoteExecutor(app),
                    sshCredentialStore = SecureSshCredentialStore(app),
                    quickCommandStore = QuickCommandStore(app),
                    dhirModelManager = modelManager,
                    toolOrchestrator = orchestrator
                )
                vmRef
            }
        }
    )

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: ROUTE_DASHBOARD

    val notesState by viewModel.notesState.collectAsStateWithLifecycle()
    val chatHistoryState by viewModel.chatHistoryState.collectAsStateWithLifecycle()
    val terminalLogsState by viewModel.terminalLogsState.collectAsStateWithLifecycle()
    val languageMode by viewModel.languageMode.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val chatStreamState by viewModel.chatStreamState.collectAsStateWithLifecycle()
    val latestToolTraces by viewModel.latestToolTraces.collectAsStateWithLifecycle()
    val latestChatCitations by viewModel.latestChatCitations.collectAsStateWithLifecycle()
    val pendingChatAction by viewModel.pendingChatAction.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val agentPromptInput by viewModel.agentPromptInput.collectAsStateWithLifecycle()
    val agentStreamState by viewModel.agentStreamState.collectAsStateWithLifecycle()
    val reportAnalysisState by viewModel.reportAnalysisState.collectAsStateWithLifecycle()
    val codeLanguage by viewModel.codeLanguage.collectAsStateWithLifecycle()
    val codeEditorText by viewModel.codeEditorText.collectAsStateWithLifecycle()
    val codeTaskPrompt by viewModel.codeTaskPrompt.collectAsStateWithLifecycle()
    val codingAiState by viewModel.codingAiState.collectAsStateWithLifecycle()
    val sandboxResultState by viewModel.sandboxResultState.collectAsStateWithLifecycle()
    val selectedExecutionEnv by viewModel.selectedExecutionEnv.collectAsStateWithLifecycle()
    val termuxState by viewModel.termuxState.collectAsStateWithLifecycle()
    val sshProfile by viewModel.sshProfile.collectAsStateWithLifecycle()
    val sshState by viewModel.sshState.collectAsStateWithLifecycle()
    val dhirModelState by viewModel.dhirModelState.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val commandTimeoutSeconds by viewModel.commandTimeoutSeconds.collectAsStateWithLifecycle()
    val isCommandRunning by viewModel.isCommandRunning.collectAsStateWithLifecycle()
    val pendingApproval by viewModel.pendingApproval.collectAsStateWithLifecycle()
    val terminalExecState by viewModel.terminalExecState.collectAsStateWithLifecycle()
    val quickCommands by viewModel.quickCommands.collectAsStateWithLifecycle()
    val webResearchState by viewModel.webResearchState.collectAsStateWithLifecycle()
    val loadedFile by viewModel.loadedFile.collectAsStateWithLifecycle()
    val fileAnalysisState by viewModel.fileAnalysisState.collectAsStateWithLifecycle()
    val proposedFilePatch by viewModel.proposedFilePatch.collectAsStateWithLifecycle()
    val checkedChecklistIds by viewModel.checkedChecklistIds.collectAsStateWithLifecycle()
    val completedQuizModuleIds by viewModel.completedQuizModuleIds.collectAsStateWithLifecycle()
    val memorySearchQuery by viewModel.memorySearchQuery.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    var chatPrefillPrompt by remember { mutableStateOf<String?>(null) }
    var selectedStudioTab by remember { mutableStateOf(StudioToolTab.CODING) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Global Explicit Command Approval Dialog (never runs arbitrary commands silently)
    pendingApproval?.let { pending ->
        CommandApprovalDialog(
            pending = pending,
            onApprove = {
                viewModel.approveAndExecutePendingCommand()
                selectedStudioTab = StudioToolTab.TERMINAL
                navigateToTab(ROUTE_TOOLS)
            },
            onCancel = viewModel::cancelPendingApproval
        )
    }

    MainLayout(
        currentRoute = currentRoute,
        snackbarHostState = snackbarHostState,
        onNavigate = { navigateToTab(it) },
        onOpenMemory = { navigateToTab(ROUTE_MEMORY) },
        onOpenRemoteSettings = { navigateToTab(ROUTE_REMOTE_SETTINGS) },
        onLockVault = {
            viewModel.lockVaultSession()
            onLockVault()
        },
        onNavigateBack = { navController.popBackStack() }
    ) {
        NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
            composable(ROUTE_DASHBOARD) {
                FridayDashboardScreen(
                    operatorName = operatorName,
                    languageMode = languageMode,
                    selectedModel = selectedModel,
                    activeAgent = activeAgent,
                    agentPromptInput = agentPromptInput,
                    agentStreamState = agentStreamState,
                    notesState = notesState,
                    chatHistoryState = chatHistoryState,
                    completedChecklistCount = checkedChecklistIds.size,
                    voiceState = voiceState,
                    sshState = sshState,
                    sshProfile = sshProfile,
                    dhirModelState = dhirModelState,
                    syncStatus = syncStatus,
                    isOfflineSession = isOfflineSession,
                    onSelectLanguage = viewModel::setLanguageMode,
                    onSelectModel = viewModel::setSelectedModel,
                    onSelectAgent = viewModel::selectAgent,
                    onAgentInputChanged = viewModel::setAgentPromptInput,
                    onRunAgent = viewModel::runSpecializedAgent,
                    onSaveAgentBrief = { title, body -> viewModel.saveNote(title, body, NoteCategory.AGENT_BRIEF) },
                    onSpeakText = viewModel::speakText,
                    onStopSpeaking = viewModel::stopSpeaking,
                    onOpenChat = { navigateToTab(ROUTE_CHAT) },
                    onOpenSecurityAgent = { navigateToTab(ROUTE_SEC_AGENT) },
                    onOpenLab = { navigateToTab(ROUTE_LAB) },
                    onOpenMemory = { navigateToTab(ROUTE_MEMORY) },
                    onOpenStudioTab = { tab ->
                        selectedStudioTab = tab
                        navigateToTab(ROUTE_TOOLS)
                    },
                    onOpenRemoteSettings = { navigateToTab(ROUTE_REMOTE_SETTINGS) },
                    onImportDhirModel = viewModel::importDhirModelUri,
                    onDownloadDhirModel = viewModel::downloadDhirModel,
                    onCancelDhirDownload = viewModel::cancelDhirModelDownload,
                    onLoadDhirModel = viewModel::loadDhirModel,
                    onUnloadDhirModel = viewModel::unloadDhirModel,
                    onDeleteDhirModel = viewModel::deleteDhirModel,
                    onSyncNow = {
                        if (isOfflineSession) onExitOfflineToSignIn() else viewModel.syncCloudNow()
                    },
                    onSignOut = {
                        viewModel.stopSpeaking()
                        viewModel.stopVoiceInput()
                        if (isOfflineSession) {
                            onExitOfflineToSignIn()
                        } else {
                            signOut(credentialManager, scope)
                        }
                    }
                )
            }

            composable(ROUTE_CHAT) {
                ChatScreen(
                    languageMode = languageMode,
                    selectedModel = selectedModel,
                    chatHistoryState = chatHistoryState,
                    streamState = chatStreamState,
                    pendingAction = pendingChatAction,
                    voiceState = voiceState,
                    prefillPrompt = chatPrefillPrompt,
                    toolTraces = latestToolTraces,
                    latestCitations = latestChatCitations,
                    dhirModelState = dhirModelState,
                    onConsumePrefill = { chatPrefillPrompt = null },
                    onSelectLanguage = viewModel::setLanguageMode,
                    onSelectModel = viewModel::setSelectedModel,
                    onSendPrompt = viewModel::sendChatPrompt,
                    onDismissAction = viewModel::dismissChatAction,
                    onLaunchCodingTool = { lang, prompt ->
                        viewModel.selectCodeLanguage(lang, loadTemplateIfEmpty = false)
                        viewModel.updateCodeTaskPrompt(prompt)
                        selectedStudioTab = StudioToolTab.CODING
                        navigateToTab(ROUTE_TOOLS)
                    },
                    onLaunchResearchTool = { query, hindi ->
                        viewModel.runWebResearch(query, hindi)
                        selectedStudioTab = StudioToolTab.RESEARCH
                        navigateToTab(ROUTE_TOOLS)
                    },
                    onLaunchFileTool = {
                        selectedStudioTab = StudioToolTab.FILES
                        navigateToTab(ROUTE_TOOLS)
                    },
                    onOpenTerminalBridge = {
                        selectedStudioTab = StudioToolTab.TERMINAL
                        navigateToTab(ROUTE_TOOLS)
                    },
                    onStartVoiceInput = viewModel::startHindiVoiceInput,
                    onStopVoiceInput = viewModel::stopVoiceInput,
                    onSpeakMessage = viewModel::speakText,
                    onStopSpeaking = viewModel::stopSpeaking,
                    onSaveMessageToMemory = { text ->
                        val title = text.lineSequence().firstOrNull()?.take(80) ?: "FRIDAY Explanation"
                        viewModel.saveNote(title, text, NoteCategory.GENERAL)
                    },
                    onNotifyCopied = {
                        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard.") }
                    }
                )
            }

            composable(ROUTE_TOOLS) {
                ToolsStudioScreen(
                    selectedTab = selectedStudioTab,
                    selectedModel = selectedModel,
                    codeLanguage = codeLanguage,
                    codeEditorText = codeEditorText,
                    codeTaskPrompt = codeTaskPrompt,
                    codingAiState = codingAiState,
                    sandboxResultState = sandboxResultState,
                    selectedExecutionEnvironment = selectedExecutionEnv,
                    termuxState = termuxState,
                    sshProfile = sshProfile,
                    sshState = sshState,
                    commandTimeoutSeconds = commandTimeoutSeconds,
                    isCommandRunning = isCommandRunning,
                    terminalExecState = terminalExecState,
                    terminalLogsState = terminalLogsState,
                    webResearchState = webResearchState,
                    loadedFile = loadedFile,
                    fileAnalysisState = fileAnalysisState,
                    proposedPatch = proposedFilePatch,
                    voiceState = voiceState,
                    onSelectTab = { selectedStudioTab = it },
                    onSelectModel = viewModel::setSelectedModel,
                    onSelectCodeLanguage = { viewModel.selectCodeLanguage(it) },
                    onCodeChanged = viewModel::updateCodeEditor,
                    onCodePromptChanged = viewModel::updateCodeTaskPrompt,
                    onRunCodingAction = { viewModel.runCodingAction(it) },
                    onRunInSandbox = viewModel::executeEditorCodeInSandbox,
                    onSelectExecutionEnvironment = viewModel::selectExecutionEnvironment,
                    onSelectTermuxMode = viewModel::selectTermuxMode,
                    onUpdateTermuxHttpConfig = viewModel::updateTermuxHttpConfig,
                    onRefreshTermuxStatus = viewModel::refreshTermuxStatus,
                    onVerifyAndConnectTermux = viewModel::verifyAndConnectTermux,
                    onDisconnectTermux = viewModel::disconnectTermux,
                    onStageTermuxToolProbe = viewModel::stageTermuxToolProbe,
                    onUpdateSshProfile = viewModel::updateSshProfile,
                    onConnectAndTestSsh = viewModel::connectAndTestSsh,
                    onDisconnectSsh = { viewModel.disconnectSsh() },
                    onClearSavedSshCredentials = viewModel::clearStoredSshCredentials,
                    onSelectTimeoutSeconds = viewModel::setCommandTimeoutSeconds,
                    onSubmitTerminalApproval = viewModel::requestCommandExecution,
                    onCancelRunningCommand = viewModel::cancelActiveCommandExecution,
                    onDeleteTerminalLog = viewModel::deleteTerminalLog,
                    onRunWebResearch = { q, hindi -> viewModel.runWebResearch(q, hindi) },
                    onFilePicked = { uri -> viewModel.loadProjectFile(context, uri) },
                    onAnalyzeFile = viewModel::analyzeLoadedFile,
                    onSendToCodingEditor = viewModel::updateCodeEditor,
                    onStageEditProposal = viewModel::proposeEditedFileContent,
                    onApproveAndSavePatch = { targetUri -> viewModel.approveAndSaveFileEdit(context, targetUri) },
                    onSpeakText = viewModel::speakText,
                    onStopSpeaking = viewModel::stopSpeaking,
                    onSaveNote = viewModel::saveNote,
                    onNotifyCopied = {
                        scope.launch { snackbarHostState.showSnackbar("Code copied to clipboard.") }
                    },
                    onOpenRemoteSettings = { navigateToTab(ROUTE_REMOTE_SETTINGS) },
                    quickCommands = quickCommands,
                    onSaveQuickCommand = viewModel::saveQuickCommand,
                    onDeleteQuickCommand = viewModel::deleteQuickCommand
                )
            }

            composable(ROUTE_SEC_AGENT) {
                SecurityAgentScreen(
                    checkedChecklistIds = checkedChecklistIds,
                    reportAnalysisState = reportAnalysisState,
                    onToggleChecklistItem = viewModel::toggleChecklistItem,
                    onAnalyzeReport = viewModel::analyzeAuthorizedReport,
                    onAskFridayAboutTool = { prompt ->
                        chatPrefillPrompt = prompt
                        navigateToTab(ROUTE_CHAT)
                    },
                    onSaveNote = viewModel::saveNote,
                    onStageNmapCommand = { nmapCmd ->
                        viewModel.requestCommandExecution(nmapCmd, selectedExecutionEnv)
                    }
                )
            }

            composable(ROUTE_LAB) {
                LearningLabScreen(
                    completedModuleIds = completedQuizModuleIds,
                    onMarkQuizCompleted = viewModel::markQuizCompleted,
                    onAskFriday = { prompt ->
                        chatPrefillPrompt = prompt
                        navigateToTab(ROUTE_CHAT)
                    },
                    onSaveNote = viewModel::saveNote
                )
            }

            composable(ROUTE_MEMORY) {
                MemoryScreen(
                    searchQuery = memorySearchQuery,
                    notesState = notesState,
                    chatHistoryState = chatHistoryState,
                    terminalLogsState = terminalLogsState,
                    onSearchChange = viewModel::updateMemorySearch,
                    onCreateNote = viewModel::saveNote,
                    onUpdateNote = viewModel::updateNote,
                    onTogglePin = viewModel::togglePin,
                    onDeleteNote = viewModel::deleteNote,
                    onDeleteMessage = viewModel::deleteChatMessage,
                    onDeleteTerminalLog = viewModel::deleteTerminalLog,
                    onClearHistory = viewModel::clearChatHistory
                )
            }

            composable(ROUTE_REMOTE_SETTINGS) {
                RemoteServerSettingsScreen(
                    profile = sshProfile,
                    sshState = sshState,
                    onUpdateProfileInMemory = viewModel::updateSshProfileInMemory,
                    onSaveConnectionDetails = viewModel::saveRemoteServerDetails,
                    onTestConnection = viewModel::connectAndTestSsh,
                    onDisconnect = { viewModel.disconnectSsh() },
                    onClearSavedDetails = viewModel::clearStoredSshCredentials,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

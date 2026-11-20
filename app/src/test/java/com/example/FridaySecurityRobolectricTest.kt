package com.example

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.core.BiometricCheckResult
import com.example.core.BiometricGateController
import com.example.core.BiometricSupportStatus
import com.example.core.CommandPolicyValidator
import com.example.core.CredentialRedactor
import com.example.core.DefaultBiometricGateController
import com.example.core.FridayCommandRouter
import com.example.core.QuickCommandStore
import com.example.core.SecureSshCredentialStore
import com.example.core.VoiceUiState
import com.example.data.curriculum.CyberCurriculumData
import com.example.data.model.AgentType
import com.example.data.model.AiModelOption
import com.example.data.model.CodeLanguage
import com.example.data.model.ConversationalAction
import com.example.data.model.ExecutionEnvFilter
import com.example.data.model.ExecutionStatusFilter
import com.example.data.model.LanguageMode
import com.example.data.model.SshAuthMethod
import com.example.data.model.SshConnectionProfile
import com.example.data.model.SshConnectionState
import com.example.data.model.TerminalEnvironment
import com.example.data.model.TerminalExecutionStatus
import com.example.data.model.TerminalLog
import com.example.data.model.TermuxEnvironmentState
import com.example.data.model.ToolAvailability
import com.example.data.model.UiState
import com.example.data.repository.SshRemoteExecutor
import com.example.data.repository.TermuxCompanionClient
import com.example.data.repository.ToolAvailabilityParser
import androidx.room.Room
import com.example.ai.DhirModelManager
import com.example.ai.DhirPromptFormatter
import com.example.ai.DhirTurn
import com.example.core.FridayToolOrchestrator
import com.example.data.local.FridayDatabase
import com.example.data.model.NoteCategory
import com.example.data.repository.FridayAiRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.WebToolsRepository
import com.example.ui.MainLayout
import com.example.ui.ROUTE_CHAT
import com.example.ui.ROUTE_DASHBOARD
import com.example.ui.ROUTE_LAB
import com.example.ui.ROUTE_MEMORY
import com.example.ui.ROUTE_SEC_AGENT
import com.example.ui.ROUTE_TOOLS
import com.example.ui.auth.BiometricLockScreen
import com.example.ui.screens.FridayDashboardScreen
import com.example.ui.screens.LearningLabScreen
import com.example.ui.screens.RemoteServerSettingsScreen
import com.example.ui.screens.SecurityAgentScreen
import com.example.ui.screens.StudioToolTab
import com.example.ui.screens.TerminalAgentTab
import com.example.ui.screens.ToolsStudioScreen
import com.example.ui.screens.filterTerminalLogs
import com.example.ui.theme.FridaySecurityTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.core.TermuxCommandReceiver
import com.example.core.VaultLifecycleGuard
import com.example.core.ProjectFileHelper
import com.example.data.local.LearningRecordEntity
import com.example.data.model.WebResearchResult
import com.example.data.model.YouTubeVideoSummary
import com.example.data.repository.TerminalBridgeClient
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FridaySecurityRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun appNameMatchesRequestedTitle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("PB-30 JAI - FRIDAY Security AI", context.getString(R.string.app_name))
    }

    @Test
    fun curriculumTracksAndKaliToolsAreEthicalAndLabeledSimulated() {
        assertEquals(6, CyberCurriculumData.labTracks.size)
        assertTrue(CyberCurriculumData.kaliTools.all { it.simulatedLabOutput.contains("SIMULATED LAB") })
        assertTrue(CyberCurriculumData.sampleReports.all { it.label.contains("SIMULATED") })
    }

    @Test
    fun credentialRedactorStripsSecretsTokensAndKeys() {
        val raw = "curl -H 'Authorization: Bearer secret_jwt_token_123' https://admin:SuperPass123@host/api --password mySecret --token AIzaSyDummyKeyValue1234567890"
        val cleaned = CredentialRedactor.redact(raw)
        assertFalse(cleaned.contains("secret_jwt_token_123"))
        assertFalse(cleaned.contains("SuperPass123"))
        assertFalse(cleaned.contains("mySecret"))
        assertFalse(cleaned.contains("AIzaSyDummyKeyValue1234567890"))
        assertTrue(cleaned.contains("[REDACTED]"))
    }

    @Test
    fun commandPolicyValidatorBlocksDestructiveAndEnforcesAuthOnNmapAndCarriesTimeout() {
        val destructive = CommandPolicyValidator.evaluateCommand(
            rawCommand = "rm -rf /",
            environment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
            targetHostSummary = "op@10.0.0.5:22 (DISCONNECTED)",
            timeoutSeconds = 60
        )
        assertTrue(destructive.isBlocked)
        assertEquals(60, destructive.timeoutSeconds)

        val exploitTool = CommandPolicyValidator.evaluateCommand("sqlmap -u http://example.com", TerminalEnvironment.TERMUX_BRIDGE)
        assertTrue(exploitTool.isBlocked)

        val safeNmap = CommandPolicyValidator.evaluateCommand("nmap -sn 192.168.1.0/24", TerminalEnvironment.LINUX_SERVER_BRIDGE)
        assertFalse(safeNmap.isBlocked)
        assertTrue(safeNmap.requiresAuthorizationConfirmation)
        assertEquals("192.168.1.0/24", safeNmap.targetLabel)

        val inspect = CommandPolicyValidator.evaluateCommand("uname -a", TerminalEnvironment.TERMUX_BRIDGE)
        assertFalse(inspect.isBlocked)
        assertFalse(inspect.requiresAuthorizationConfirmation)
    }

    @Test
    fun sshExecutorNeverClaimsConnectedBeforeHandshakeAndValidatesCredentials() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = SshRemoteExecutor(context)
        assertFalse(executor.isConnected())

        val invalidProfile = SshConnectionProfile(host = "", username = "root", authMethod = SshAuthMethod.PASSWORD, password = "")
        assertNotNull(executor.validateProfile(invalidProfile))

        val validProfile = SshConnectionProfile(
            host = "192.168.1.50",
            port = 22,
            username = "friday",
            authMethod = SshAuthMethod.PASSWORD,
            password = "test-password"
        )
        val blockedNoApproval = executor.executeCommand(validProfile, "uname -a", timeoutSeconds = 15, approvedByUser = false)
        assertFalse(blockedNoApproval.executed)
        assertEquals(TerminalExecutionStatus.BLOCKED, blockedNoApproval.status)

        val result = executor.executeCommand(validProfile, "uname -a", timeoutSeconds = 15, approvedByUser = true)
        assertFalse(result.executed)
        assertEquals(TerminalExecutionStatus.BRIDGE_UNAVAILABLE, result.status)
        assertTrue(result.stderr.contains("disconnected", ignoreCase = true))
    }

    @Test
    fun termuxClientInspectsHonestlyAndParsesUnavailableTools() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = TermuxCompanionClient(context)
        val state = client.inspectEnvironment()
        assertFalse(state.isConnected)
        assertFalse(state.apkInstalled)

        val blockedNoApproval = client.executeApprovedCommand(state, "python3 --version", 15, approvedByUser = false)
        assertFalse(blockedNoApproval.executed)
        assertEquals(TerminalExecutionStatus.BLOCKED, blockedNoApproval.status)

        val execWhenDisconnected = client.executeApprovedCommand(state, "python3 --version", 15, approvedByUser = true)
        assertFalse(execWhenDisconnected.executed)
        assertEquals(TerminalExecutionStatus.BRIDGE_UNAVAILABLE, execWhenDisconnected.status)

        val probed = ToolAvailabilityParser.updateFromExecution(
            current = state.toolStatuses,
            command = ToolAvailabilityParser.TOOL_PROBE_COMMAND,
            stdout = "FRIDAY_TOOL:python3=AVAILABLE\nFRIDAY_TOOL:bash=AVAILABLE\nFRIDAY_TOOL:nmap=UNAVAILABLE",
            stderr = "",
            exitCode = 0
        )
        assertEquals(ToolAvailability.INSTALLED, probed["python3"])
        assertEquals(ToolAvailability.INSTALLED, probed["bash"])
        assertEquals(ToolAvailability.UNAVAILABLE, probed["nmap"])

        val failedMissing = ToolAvailabilityParser.updateFromExecution(
            current = state.toolStatuses,
            command = "nmap -sn 127.0.0.1",
            stdout = "",
            stderr = "bash: nmap: command not found",
            exitCode = 127
        )
        assertEquals(ToolAvailability.UNAVAILABLE, failedMissing["nmap"])
    }

    @Test
    fun conversationalCommandRouterParsesAllExamples() {
        val coding = FridayCommandRouter.detectAction("FRIDAY, write Python code.")
        assertTrue(coding is ConversationalAction.OpenCoding)
        assertEquals(CodeLanguage.PYTHON, (coding as ConversationalAction.OpenCoding).language)

        val askTarget = FridayCommandRouter.detectAction("FRIDAY, scan my authorized test network using Nmap.")
        assertTrue(askTarget is ConversationalAction.AskScanTarget)

        val nmap = FridayCommandRouter.detectAction("FRIDAY, scan 192.168.56.0/24 using Nmap.")
        assertTrue(nmap is ConversationalAction.RequestAuthorizedNmap)
        assertEquals("192.168.56.0/24", (nmap as ConversationalAction.RequestAuthorizedNmap).target)

        val term = FridayCommandRouter.detectAction("FRIDAY, run this approved terminal command: `uname -a`")
        assertTrue(term is ConversationalAction.RequestTerminalApproval)
        assertEquals("uname -a", (term as ConversationalAction.RequestTerminalApproval).command)

        val research = FridayCommandRouter.detectAction("FRIDAY, search the internet for CVE-2025-0001 in Hindi")
        assertTrue(research is ConversationalAction.RunWebResearch)
        assertTrue((research as ConversationalAction.RunWebResearch).explainInHindi)

        assertTrue(FridayCommandRouter.requestsHindiResponse("FRIDAY, explain the result in Hindi."))
    }

    @Test
    fun learningLabNavigatesIntoTrackAndBack() {
        var askedPrompt = ""
        composeRule.setContent {
            FridaySecurityTheme {
                LearningLabScreen(
                    completedModuleIds = emptySet(),
                    onMarkQuizCompleted = {},
                    onAskFriday = { askedPrompt = it },
                    onSaveNote = { _, _, _ -> }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lab_track_networking").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("OSI vs TCP/IP & Critical Ports").assertIsDisplayed()
        composeRule.onNodeWithTag("lab_back_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("OWASP Top 10").assertIsDisplayed()
        assertEquals("", askedPrompt)
    }

    @Test
    fun securityAgentTabsChecklistsAndNmapAuthorizationWork() {
        val checked = mutableSetOf<String>()
        var stagedNmap = ""
        composeRule.setContent {
            FridaySecurityTheme {
                SecurityAgentScreen(
                    checkedChecklistIds = checked,
                    reportAnalysisState = UiState.Idle,
                    onToggleChecklistItem = { id ->
                        if (id in checked) checked.remove(id) else checked.add(id)
                    },
                    onAnalyzeReport = { _, _ -> },
                    onAskFridayAboutTool = {},
                    onSaveNote = { _, _, _ -> },
                    onStageNmapCommand = { stagedNmap = it }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("CORE RULE: PERMISSION FIRST").assertIsDisplayed()

        // Authorized Nmap Builder tab requires explicit confirmation before staging
        composeRule.onNodeWithTag("sec_tab_nmap").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nmap_stage_approval_button").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("nmap_auth_confirm_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("nmap_stage_approval_button").performScrollTo().assertIsEnabled().performClick()
        assertTrue(stagedNmap.startsWith("nmap "))

        composeRule.onNodeWithTag("sec_tab_kali_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Simulated Lab Previews", substring = true).assertIsDisplayed()

        composeRule.onNodeWithTag("sec_tab_checklists").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("checklist_item_w1").performClick()
        composeRule.waitForIdle()
        assertTrue("w1" in checked)
    }

    @Test
    fun mainLayoutBottomNavigationSwitchesAllDestinations() {
        composeRule.setContent {
            FridaySecurityTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: ROUTE_DASHBOARD
                val snackbarHostState = remember { SnackbarHostState() }

                MainLayout(
                    currentRoute = currentRoute,
                    snackbarHostState = snackbarHostState,
                    onNavigate = { route ->
                        navController.navigate(route) { launchSingleTop = true }
                    },
                    onOpenMemory = { navController.navigate(ROUTE_MEMORY) },
                    onNavigateBack = { navController.popBackStack() }
                ) {
                    NavHost(navController = navController, startDestination = ROUTE_DASHBOARD) {
                        composable(ROUTE_DASHBOARD) { Text("Dashboard Screen Content") }
                        composable(ROUTE_CHAT) { Text("AI Chat Screen Content") }
                        composable(ROUTE_TOOLS) { Text("AI Tools Studio Content") }
                        composable(ROUTE_SEC_AGENT) { Text("Security Agent Screen Content") }
                        composable(ROUTE_LAB) { Text("Learning Lab Screen Content") }
                        composable(ROUTE_MEMORY) { Text("Memory Screen Content") }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dashboard Screen Content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_chat").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI Chat Screen Content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tools").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("AI Tools Studio Content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_sec_agent").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Security Agent Screen Content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_lab").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Learning Lab Screen Content").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_dashboard").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dashboard Screen Content").assertIsDisplayed()
    }

    @Test
    fun toolsStudioSwitchesDualEnvironmentsTermuxSshAndAllTabs() {
        var timeoutPicked = 30
        composeRule.setContent {
            FridaySecurityTheme {
                var selectedTab by remember { mutableStateOf(StudioToolTab.CODING) }
                var model by remember { mutableStateOf(AiModelOption.DHIR_2B_BALANCED) }
                var execEnv by remember { mutableStateOf(TerminalEnvironment.TERMUX_BRIDGE) }
                var timeout by remember { mutableStateOf(30) }
                ToolsStudioScreen(
                    selectedTab = selectedTab,
                    selectedModel = model,
                    codeLanguage = CodeLanguage.PYTHON,
                    codeEditorText = CodeLanguage.PYTHON.starterTemplate,
                    codeTaskPrompt = "",
                    codingAiState = UiState.Idle,
                    sandboxResultState = UiState.Idle,
                    selectedExecutionEnvironment = execEnv,
                    termuxState = TermuxEnvironmentState(),
                    sshProfile = SshConnectionProfile(),
                    sshState = SshConnectionState(),
                    commandTimeoutSeconds = timeout,
                    isCommandRunning = false,
                    terminalExecState = UiState.Idle,
                    terminalLogsState = UiState.Success(emptyList()),
                    webResearchState = UiState.Idle,
                    loadedFile = null,
                    fileAnalysisState = UiState.Idle,
                    proposedPatch = null,
                    voiceState = VoiceUiState(),
                    onSelectTab = { selectedTab = it },
                    onSelectModel = { model = it },
                    onSelectCodeLanguage = {},
                    onCodeChanged = {},
                    onCodePromptChanged = {},
                    onRunCodingAction = {},
                    onRunInSandbox = {},
                    onSelectExecutionEnvironment = { execEnv = it },
                    onSelectTermuxMode = {},
                    onUpdateTermuxHttpConfig = { _, _ -> },
                    onRefreshTermuxStatus = {},
                    onVerifyAndConnectTermux = {},
                    onDisconnectTermux = {},
                    onStageTermuxToolProbe = {},
                    onUpdateSshProfile = { _, _ -> },
                    onConnectAndTestSsh = {},
                    onDisconnectSsh = {},
                    onClearSavedSshCredentials = {},
                    onSelectTimeoutSeconds = { timeout = it; timeoutPicked = it },
                    onSubmitTerminalApproval = { _, _ -> },
                    onCancelRunningCommand = {},
                    onDeleteTerminalLog = {},
                    onRunWebResearch = { _, _ -> },
                    onFilePicked = {},
                    onAnalyzeFile = {},
                    onSendToCodingEditor = {},
                    onStageEditProposal = { _, _ -> },
                    onApproveAndSavePatch = {},
                    onSpeakText = { _, _ -> },
                    onStopSpeaking = {},
                    onSaveNote = { _, _, _ -> },
                    onNotifyCopied = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("coding_generate_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("studio_tab_terminal").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("termux_connect_button"))
        composeRule.onNodeWithTag("termux_connect_button").assertIsDisplayed()

        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("env_switch_ssh"))
        composeRule.onNodeWithTag("env_switch_ssh").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("ssh_connect_button"))
        composeRule.onNodeWithTag("ssh_connect_button").assertExists().assertIsEnabled()
        composeRule.onNodeWithTag("ssh_disconnect_button").assertExists().assertIsNotEnabled()

        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("timeout_chip_60"))
        composeRule.onNodeWithTag("timeout_chip_60").performClick()
        assertEquals(60, timeoutPicked)
        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("terminal_submit_approval_button"))
        composeRule.onNodeWithTag("terminal_submit_approval_button").assertExists()

        composeRule.onNodeWithTag("studio_tab_research").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("research_run_button").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("studio_tab_files").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("file_open_picker_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun secureSshCredentialStoreSavesHostAndUsernameWithoutPersistingPasswords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backingPrefs = context.getSharedPreferences("test_encrypted_ssh_prefs", Context.MODE_PRIVATE)
        backingPrefs.edit().clear().commit()
        val store = SecureSshCredentialStore(context, backingPrefs)

        val saved = store.saveConnectionDetails(
            host = "10.10.0.42",
            port = 2222,
            username = "friday_admin",
            authMethod = SshAuthMethod.PASSWORD
        )
        assertTrue(saved)

        val loaded = store.loadProfile()
        assertEquals("10.10.0.42", loaded.host)
        assertEquals(2222, loaded.port)
        assertEquals("friday_admin", loaded.username)
        assertEquals("", loaded.password)
        assertEquals("", loaded.keyPassphrase)
        assertFalse(backingPrefs.all.values.any { it.toString().contains("SuperSecretPass") })
    }

    @Test
    fun remoteServerSettingsScreenSavesDetailsAndTestsWithoutStoringPassword() {
        var savedHost = ""
        var savedPort = 0
        var savedUser = ""
        var testClicked = false

        composeRule.setContent {
            FridaySecurityTheme {
                var profile by remember { mutableStateOf(SshConnectionProfile()) }
                RemoteServerSettingsScreen(
                    profile = profile,
                    sshState = SshConnectionState(),
                    onUpdateProfileInMemory = { profile = it },
                    onSaveConnectionDetails = { host, port, user, _ ->
                        savedHost = host
                        savedPort = port
                        savedUser = user
                    },
                    onTestConnection = { testClicked = true },
                    onDisconnect = {},
                    onClearSavedDetails = { profile = SshConnectionProfile() },
                    onNavigateBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("remote_server_settings_screen")
            .performScrollToNode(hasTestTag("remote_settings_host_input"))
        composeRule.onNodeWithTag("remote_settings_host_input").performTextInput("192.168.50.10")
        composeRule.onNodeWithTag("remote_settings_username_input").performTextInput("kali_operator")
        composeRule.onNodeWithTag("remote_settings_save_button").performClick()

        assertEquals("192.168.50.10", savedHost)
        assertEquals(22, savedPort)
        assertEquals("kali_operator", savedUser)

        composeRule.onNodeWithTag("remote_server_settings_screen")
            .performScrollToNode(hasTestTag("remote_settings_password_input"))
        composeRule.onNodeWithTag("remote_settings_password_input").performTextInput("ephemeral_pass_only")
        composeRule.onNodeWithTag("remote_server_settings_screen")
            .performScrollToNode(hasTestTag("remote_settings_test_connection_button"))
        composeRule.onNodeWithTag("remote_settings_test_connection_button").performClick()

        assertTrue(testClicked)
    }

    @Test
    fun fridayDashboardScreenRendersHubCardsAndSelectsAgents() {
        var openedTarget = ""
        var selectedAgent = AgentType.RESEARCH
        composeRule.setContent {
            FridaySecurityTheme {
                FridayDashboardScreen(
                    operatorName = "PB-30 JAI",
                    languageMode = LanguageMode.HINGLISH,
                    selectedModel = AiModelOption.DHIR_2B_BALANCED,
                    activeAgent = selectedAgent,
                    agentPromptInput = "",
                    agentStreamState = UiState.Idle,
                    notesState = UiState.Success(emptyList()),
                    chatHistoryState = UiState.Success(emptyList()),
                    completedChecklistCount = 2,
                    voiceState = VoiceUiState(),
                    onSelectLanguage = {},
                    onSelectModel = {},
                    onSelectAgent = { selectedAgent = it },
                    onAgentInputChanged = {},
                    onRunAgent = { _, _ -> },
                    onSaveAgentBrief = { _, _ -> },
                    onSpeakText = { _, _ -> },
                    onStopSpeaking = {},
                    onOpenChat = { openedTarget = "chat" },
                    onOpenSecurityAgent = { openedTarget = "sec_agent" },
                    onOpenLab = { openedTarget = "lab" },
                    onOpenMemory = { openedTarget = "memory" },
                    onOpenStudioTab = { openedTarget = it.name },
                    onSignOut = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("FRIDAY AI HUB").assertIsDisplayed()
        composeRule.onNodeWithTag("friday_dashboard_screen").performScrollToNode(hasTestTag("hub_card_chat"))
        composeRule.onNodeWithTag("hub_card_chat").assertIsDisplayed().performClick()
        assertEquals("chat", openedTarget)
        composeRule.onNodeWithTag("friday_dashboard_screen").performScrollToNode(hasTestTag("hub_card_sec_agent"))
        composeRule.onNodeWithTag("hub_card_sec_agent").assertIsDisplayed().performClick()
        assertEquals("sec_agent", openedTarget)
    }

    @Test
    fun dashboardSshStatusIndicatorReflectsOfflineConnectingAndOnlineStates() {
        val stateHolder = mutableStateOf(SshConnectionState(isConnected = false, isConnecting = false))
        var remoteSettingsClicks = 0
        composeRule.setContent {
            FridaySecurityTheme {
                FridayDashboardScreen(
                    operatorName = "PB-30 JAI",
                    languageMode = LanguageMode.HINGLISH,
                    selectedModel = AiModelOption.DHIR_2B_BALANCED,
                    activeAgent = AgentType.RESEARCH,
                    agentPromptInput = "",
                    agentStreamState = UiState.Idle,
                    notesState = UiState.Success(emptyList()),
                    chatHistoryState = UiState.Success(emptyList()),
                    completedChecklistCount = 0,
                    voiceState = VoiceUiState(),
                    sshState = stateHolder.value,
                    sshProfile = SshConnectionProfile(host = "10.0.0.12", username = "kali", port = 22),
                    onSelectLanguage = {},
                    onSelectAgent = {},
                    onAgentInputChanged = {},
                    onRunAgent = { _, _ -> },
                    onSaveAgentBrief = { _, _ -> },
                    onSpeakText = { _, _ -> },
                    onStopSpeaking = {},
                    onOpenChat = {},
                    onOpenSecurityAgent = {},
                    onOpenLab = {},
                    onOpenMemory = {},
                    onOpenRemoteSettings = { remoteSettingsClicks++ },
                    onSignOut = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dashboard_ssh_status_indicator").assertIsDisplayed()
        composeRule.onNodeWithText("SSH: OFFLINE").assertIsDisplayed()

        stateHolder.value = SshConnectionState(isConnected = false, isConnecting = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SSH: CONNECTING").assertIsDisplayed()

        stateHolder.value = SshConnectionState(isConnected = true, isConnecting = false, serverInfo = "OpenSSH_9.2")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("SSH: ONLINE").assertIsDisplayed()

        composeRule.onNodeWithTag("dashboard_ssh_status_indicator").performClick()
        assertEquals(1, remoteSettingsClicks)
    }

    @Test
    fun quickCommandStorePersistsValidCommandsAndRejectsDangerousOnes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_quick_cmds", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = QuickCommandStore(prefs)

        val initial = store.loadCommands()
        assertTrue(initial.any { it.command.contains("nmap -sV") })

        val added = store.addCommand("LAN Web Scan", "nmap -sV -p 80,443 192.168.1.5")
        assertTrue(added.isSuccess)
        assertTrue(store.loadCommands().any { it.label == "LAN Web Scan" && it.command == "nmap -sV -p 80,443 192.168.1.5" })

        val blocked = store.addCommand("Wipe", "rm -rf /")
        assertTrue(blocked.isFailure)

        val firstId = store.loadCommands().first().id
        val afterDelete = store.deleteCommand(firstId)
        assertFalse(afterDelete.any { it.id == firstId })
    }

    @Test
    fun redactWithSecretsStripsRuntimePasswordsTokensAndPemKeysBeforeLogging() {
        val secretPass = "UltraS3cretSshPass!"
        val companionToken = "cmp_live_token_9988"
        val rawCommand = "echo '$secretPass' | sudo -S nmap -sV 127.0.0.1"
        val rawOutput = "Auth error for token=$companionToken using $secretPass"

        val redactedCmd = CredentialRedactor.redactWithSecrets(rawCommand, listOf(secretPass, companionToken))
        val redactedOut = CredentialRedactor.redactWithSecrets(rawOutput, listOf(secretPass, companionToken))

        assertFalse(redactedCmd.contains(secretPass))
        assertFalse(redactedOut.contains(secretPass))
        assertFalse(redactedOut.contains(companionToken))
        assertTrue(redactedCmd.contains("[REDACTED_SECRET]"))
        assertTrue(redactedOut.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun executionHistorySearchAndFiltersWorkAndQuickCommandTriggersApproval() {
        val logs = listOf(
            TerminalLog(
                id = "l1",
                command = "nmap -sV 192.168.1.1",
                environment = TerminalEnvironment.LINUX_SERVER_BRIDGE.name,
                status = TerminalExecutionStatus.EXECUTED.name,
                output = "22/tcp open ssh OpenSSH",
                exitCode = 0,
                approvedByUser = true
            ),
            TerminalLog(
                id = "l2",
                command = "python3 --version",
                environment = TerminalEnvironment.TERMUX_BRIDGE.name,
                status = TerminalExecutionStatus.TIMEOUT.name,
                output = "Timed out after 15s",
                exitCode = -1,
                approvedByUser = true
            )
        )

        assertEquals(2, filterTerminalLogs(logs, "", ExecutionEnvFilter.ALL, ExecutionStatusFilter.ALL).size)
        assertEquals(1, filterTerminalLogs(logs, "nmap", ExecutionEnvFilter.SSH, ExecutionStatusFilter.EXECUTED).size)
        assertEquals(1, filterTerminalLogs(logs, "", ExecutionEnvFilter.TERMUX, ExecutionStatusFilter.TIMEOUT).size)

        var approvedCmd = ""
        composeRule.setContent {
            FridaySecurityTheme {
                TerminalAgentTab(
                    selectedEnvironment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
                    termuxState = TermuxEnvironmentState(),
                    sshProfile = SshConnectionProfile(host = "192.168.1.10", username = "sec"),
                    sshState = SshConnectionState(),
                    timeoutSeconds = 30,
                    isCommandRunning = false,
                    execState = UiState.Idle,
                    logsState = UiState.Success(logs),
                    onSelectEnvironment = {},
                    onSelectTermuxMode = {},
                    onUpdateTermuxHttpConfig = { _, _ -> },
                    onRefreshTermuxStatus = {},
                    onVerifyAndConnectTermux = {},
                    onDisconnectTermux = {},
                    onStageTermuxToolProbe = {},
                    onUpdateSshProfile = { _, _ -> },
                    onConnectAndTestSsh = {},
                    onDisconnectSsh = {},
                    onClearSavedSshCredentials = {},
                    onSelectTimeoutSeconds = {},
                    onSubmitForApproval = { cmd, _ -> approvedCmd = cmd },
                    onCancelRunningCommand = {},
                    onDeleteLog = {},
                    quickCommands = QuickCommandStore.defaultCommands()
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("quick_cmd_run_qc_nmap_sv"))
        composeRule.onNodeWithTag("quick_cmd_run_qc_nmap_sv").performClick()
        assertEquals("nmap -sV 127.0.0.1", approvedCmd)

        composeRule.onNodeWithTag("terminal_agent_tab").performScrollToNode(hasTestTag("terminal_history_search_input"))
        composeRule.onNodeWithTag("terminal_history_search_input").performTextInput("OpenSSH")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("terminal_agent_tab")
            .performScrollToNode(androidx.compose.ui.test.hasText("$ nmap -sV 192.168.1.1"))
        composeRule.onNodeWithText("$ nmap -sV 192.168.1.1").assertIsDisplayed()
        composeRule.onNodeWithText("$ python3 --version").assertDoesNotExist()
    }

    @Test
    fun mainActivityExtendsFragmentActivityAndDefaultBiometricCheckDoesNotCrash() {
        assertTrue(androidx.fragment.app.FragmentActivity::class.java.isAssignableFrom(MainActivity::class.java))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = DefaultBiometricGateController.checkSupport(context)
        assertNotNull(result.detail)
        assertTrue(result.allowedAuthenticators != 0)
    }

    @Test
    fun biometricLockScreenBlocksBypassWhenReadyAndUnlocksOnAuthenticationSuccess() {
        var shouldSucceed = false
        val fakeController = object : BiometricGateController {
            override fun checkSupport(context: Context) = BiometricCheckResult(
                status = BiometricSupportStatus.READY,
                detail = "Hardware biometric ready.",
                allowedAuthenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
            )

            override fun authenticate(
                activity: androidx.fragment.app.FragmentActivity,
                title: String,
                subtitle: String,
                onSuccess: () -> Unit,
                onFailureMessage: (String) -> Unit
            ) {
                if (shouldSucceed) onSuccess()
                else onFailureMessage("Biometric credential not recognized.")
            }
        }

        // Launch a FragmentActivity so context.findFragmentActivity() resolves inside Compose test
        val fragmentActivity = org.robolectric.Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java)
            .setup()
            .get()

        val unlocked = mutableStateOf(false)
        composeRule.setContent {
            FridaySecurityTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides fragmentActivity
                ) {
                    if (!unlocked.value) {
                        BiometricLockScreen(
                            controller = fakeController,
                            autoPromptOnStart = false,
                            onUnlocked = { unlocked.value = true }
                        )
                    } else {
                        val snackbarHostState = remember { SnackbarHostState() }
                        MainLayout(
                            currentRoute = ROUTE_DASHBOARD,
                            snackbarHostState = snackbarHostState,
                            onNavigate = {},
                            onOpenMemory = {},
                            onLockVault = { unlocked.value = false },
                            onNavigateBack = {}
                        ) {
                            Text("Unlocked Protected Vault")
                        }
                    }
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("biometric_lock_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("biometric_fallback_bypass_button").assertDoesNotExist()

        // First failed attempt keeps vault sealed and displays error feedback
        composeRule.onNodeWithTag("biometric_unlock_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(unlocked.value)
        composeRule.onNodeWithText("Biometric credential not recognized.").performScrollTo().assertIsDisplayed()

        // Second successful attempt unlocks vault
        shouldSucceed = true
        composeRule.onNodeWithTag("biometric_unlock_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(unlocked.value)
        composeRule.onNodeWithText("Unlocked Protected Vault").assertIsDisplayed()

        // Re-locking from TopAppBar returns to BiometricLockScreen
        composeRule.onNodeWithTag("top_bar_lock_vault_button").performClick()
        composeRule.waitForIdle()
        assertFalse(unlocked.value)
        composeRule.onNodeWithTag("biometric_lock_screen").assertIsDisplayed()
    }

    @Test
    fun biometricLockScreenShowsEnrollAndFallbackWhenNoneEnrolled() {
        val noEnrollmentController = object : BiometricGateController {
            override fun checkSupport(context: Context) = BiometricCheckResult(
                status = BiometricSupportStatus.NONE_ENROLLED,
                detail = "No screen lock enrolled.",
                allowedAuthenticators = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
            )

            override fun authenticate(
                activity: androidx.fragment.app.FragmentActivity,
                title: String,
                subtitle: String,
                onSuccess: () -> Unit,
                onFailureMessage: (String) -> Unit
            ) = Unit
        }

        var unlocked = false
        composeRule.setContent {
            FridaySecurityTheme {
                BiometricLockScreen(
                    controller = noEnrollmentController,
                    autoPromptOnStart = false,
                    onUnlocked = { unlocked = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("biometric_enroll_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("biometric_fallback_bypass_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(unlocked)
    }

    @Test
    fun dhirPromptFormatterBuildsV5NothinkPromptAndParsesToolCalls() {
        val toolsJson = DhirPromptFormatter.buildToolDeclarationsJson()
        val prompt = DhirPromptFormatter.formatPrompt(
            systemPrompt = "You are FRIDAY.",
            turns = listOf(
                DhirTurn("user", "नमस्ते FRIDAY, OWASP समझाइए"),
                DhirTurn("assistant", "नमस्ते! OWASP Top 10 वेब सुरक्षा जोखिमों की सूची है।"),
                DhirTurn("observation", "Web hit: OWASP 2021")
            ),
            toolsJson = toolsJson
        )

        assertTrue(prompt.startsWith("<bos><|system|>You are FRIDAY.<|tool_declare|><tools>"))
        assertTrue(prompt.contains("<|user|>नमस्ते FRIDAY, OWASP समझाइए<|end_of_turn|>"))
        assertTrue(prompt.contains("<|assistant|><|nothink|>नमस्ते! OWASP Top 10"))
        assertTrue(prompt.contains("<|observation|><tool_response>Web hit: OWASP 2021</tool_response><|end_of_turn|>"))
        assertTrue(prompt.endsWith("<|assistant|><|nothink|>"))

        val rawModelOutput = """
            <|nothink|>मैं अभी CVE खोज रहा हूँ।
            <tool_call>{"name":"web_search","arguments":{"query":"CVE-2024-3094"}}</tool_call>
            <|end_of_turn|>
        """.trimIndent()
        val calls = DhirPromptFormatter.parseToolCalls(rawModelOutput)
        assertEquals(1, calls.size)
        assertEquals("web_search", calls.first().name)
        assertEquals("CVE-2024-3094", calls.first().arguments["query"])

        val cleaned = DhirPromptFormatter.cleanVisibleText(rawModelOutput)
        assertEquals("मैं अभी CVE खोज रहा हूँ।", cleaned)
    }

    @Test
    fun dhirModelManagerValidatesGgufMagicAndRamContextBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = File(context.cacheDir, "gguf_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val validFile = File(tempDir, "dhir-2b-Q4_K_M.gguf")
            RandomAccessFile(validFile, "rw").use { raf ->
                raf.setLength(1200L * 1024L)
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x46554747) // 'G','G','U','F'
                    .putInt(3)          // GGUF v3
                    .array()
                raf.seek(0)
                raf.write(header)
            }
            val validCheck = DhirModelManager.validateGgufFile(validFile)
            assertTrue(validCheck.isValid)
            assertEquals(3, validCheck.ggufVersion)

            val corruptFile = File(tempDir, "bad-dhir.gguf")
            RandomAccessFile(corruptFile, "rw").use { raf ->
                raf.setLength(1200L * 1024L)
                raf.seek(0)
                raf.write("NOTGGUF!".toByteArray())
            }
            val corruptCheck = DhirModelManager.validateGgufFile(corruptFile)
            assertFalse(corruptCheck.isValid)

            assertEquals(1536, DhirModelManager.recommendContextSize(totalRamMb = 2048))
            assertEquals(2048, DhirModelManager.recommendContextSize(totalRamMb = 4096))
            assertEquals(4096, DhirModelManager.recommendContextSize(totalRamMb = 8192))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun webToolsRepositoryParsesDuckDuckGoYouTubeTranscriptAndBlocksSsrf() {
        val repo = WebToolsRepository()

        val ddgHtml = """
            <div class="result">
              <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fowasp.org%2Fwww-project-top-ten%2F">OWASP Top Ten</a>
              <a class="result__snippet">Official OWASP security standard documentation.</a>
            </div>
        """.trimIndent()
        val hits = repo.parseDuckDuckGoHtml(ddgHtml)
        assertEquals(1, hits.size)
        assertEquals("https://owasp.org/www-project-top-ten/", hits.first().url)
        assertEquals("OWASP Top Ten", hits.first().title)

        val ytHtml = """
            <script>var ytInitialData = {"contents":{"sectionListRenderer":{"itemSectionRenderer":{"videoRenderer":{"videoId":"dQw4w9WgXcQ","title":{"runs":[{"text":"Nmap Ethical Lab Walkthrough"}]},"ownerText":{"runs":[{"text":"SecLab"}]},"lengthText":{"simpleText":"12:34"}}}}}};</script>
        """.trimIndent()
        val videos = repo.parseYouTubeSearchHtml(ytHtml)
        assertEquals(1, videos.size)
        assertEquals("dQw4w9WgXcQ", videos.first().videoId)
        assertEquals("Nmap Ethical Lab Walkthrough", videos.first().title)
        assertEquals("SecLab", videos.first().channel)

        val transcriptXml = """<transcript><text start="0.0">Namaste &amp; welcome to</text><text start="2.0">authorized security testing.</text></transcript>"""
        assertEquals("Namaste & welcome to authorized security testing.", repo.parseYouTubeTimedTextXml(transcriptXml))

        assertNotNull(repo.validateSafePublicHttpUrl("http://localhost:8080/admin"))
        assertNotNull(repo.validateSafePublicHttpUrl("http://127.0.0.1/latest"))
        assertNotNull(repo.validateSafePublicHttpUrl("http://169.254.169.254/latest/meta-data"))
        assertNotNull(repo.validateSafePublicHttpUrl("http://192.168.1.1/"))
        assertEquals(null, repo.validateSafePublicHttpUrl("https://nvd.nist.gov/vuln"))
    }

    @Test
    fun roomMemoryRepositoryPersistsNotesChatLearningRecordsAndLocalOnlyTerminalLogs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FridayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = MemoryRepository(dao = db.fridayDao(), db = null, auth = null, context = context)
            val uid = MemoryRepository.LOCAL_OFFLINE_USER_ID

            val saveRes = repo.saveNote(
                title = "Nmap Verification Brief",
                content = "Checked port 22 on authorized lab host using token=secret_abc123",
                category = NoteCategory.AGENT_BRIEF,
                userIdOverride = uid
            )
            assertTrue(saveRes.isSuccess)
            val notes = repo.observeNotes(uid).first()
            assertEquals(1, notes.size)
            assertTrue(notes.first().id.matches(Regex("^[a-zA-Z0-9_\\-]+$")))
            assertFalse(notes.first().content.contains("secret_abc123"))

            repo.saveChatMessage("USER", "Hinglish mein Nmap flags batao", LanguageMode.HINGLISH, AgentType.CHAT, uid)
            repo.saveChatMessage("FRIDAY", "-sV service version detection ke liye hota hai.", LanguageMode.HINGLISH, AgentType.CHAT, uid)
            val chats = repo.observeChatMessages(uid).first()
            assertEquals(2, chats.size)

            repo.toggleChecklistItem(uid, "w1")
            repo.markQuizCompleted(uid, "networking")
            assertTrue("w1" in repo.observeCompletedChecklistIds(uid).first())
            assertTrue("networking" in repo.observeCompletedQuizModuleIds(uid).first())

            repo.saveTerminalLog(
                command = "sshpass -p MyPass99 ssh kali@10.0.0.5",
                environment = TerminalEnvironment.LINUX_SERVER_BRIDGE,
                status = TerminalExecutionStatus.EXECUTED,
                output = "Linux kali 6.6",
                exitCode = 0,
                approvedByUser = true,
                userIdOverride = uid
            )
            val logEntities = db.fridayDao().observeTerminalLogs(uid).first()
            assertEquals(1, logEntities.size)
            assertTrue(logEntities.first().localOnly)

            val searchHit = repo.searchLocalKnowledge(uid, "Nmap")
            assertTrue(searchHit.contains("Nmap Verification Brief"))
            assertTrue(searchHit.contains("Hinglish mein Nmap flags"))
            val replySearchHit = repo.searchLocalKnowledge(uid, "service version")
            assertTrue(replySearchHit.contains("-sV service version"))
        } finally {
            db.close()
        }
    }

    @Test
    fun fridayToolOrchestratorStagesTerminalCommandsThroughApprovalGateAndHandlesOfflineModelState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FridayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val uid = MemoryRepository.LOCAL_OFFLINE_USER_ID
            val memoryRepo = MemoryRepository(dao = db.fridayDao(), db = null, auth = null, context = context)
            memoryRepo.saveNote("SQLi Prevention", "Use parameterized queries to mitigate SQL injection.", NoteCategory.OWASP, userIdOverride = uid)

            val webRepo = WebToolsRepository()
            val stagedCommands = mutableListOf<String>()
            val stubAiRepo = object : FridayAiRepository {
                override fun streamResponse(
                    prompt: String,
                    languageMode: LanguageMode,
                    agentType: AgentType,
                    modelOption: AiModelOption,
                    history: List<Pair<String, String>>,
                    toolsJson: String?
                ): Flow<String> = flow {
                    emit("Prepared command proposal.")
                }

                override suspend fun performWebResearch(
                    query: String,
                    languageMode: LanguageMode,
                    explainInHindi: Boolean,
                    modelOption: AiModelOption
                ): WebResearchResult = WebResearchResult(
                    query = query,
                    answer = "Prepared research output.",
                    citations = emptyList(),
                    searchQueriesUsed = listOf(query),
                    explainedInHindi = explainInHindi
                )

                override suspend fun ensureDhirLoaded(modelOption: AiModelOption): Result<Unit> = Result.success(Unit)
                override fun unloadDhir() = Unit
                override fun abortGeneration() = Unit
            }

            // 1. Test tool call routing & mandatory CommandApprovalDialog staging
            val orchestrator = FridayToolOrchestrator(
                aiRepository = stubAiRepo,
                memoryRepository = memoryRepo,
                webToolsRepository = webRepo,
                modelManager = null,
                currentUserId = uid,
                termuxStateProvider = { TermuxEnvironmentState() },
                sshProfileProvider = { SshConnectionProfile(host = "10.0.0.9", username = "sec") },
                sshStateProvider = { SshConnectionState() },
                onStageTerminalApproval = { stagedCommands += it }
            )

            val traces = mutableListOf<com.example.data.model.ToolExecutionTrace>()
            val cites = mutableListOf<com.example.data.model.WebCitation>()
            val prepMsg = orchestrator.executeToolCallSafely(
                call = com.example.ai.ParsedToolCall("prepare_terminal_command", mapOf("command" to "nmap -sV 127.0.0.1")),
                traces = traces,
                citations = cites
            )
            assertEquals(listOf("nmap -sV 127.0.0.1"), stagedCommands)
            assertTrue(prepMsg.contains("approval dialog", ignoreCase = true))

            val envStatus = orchestrator.executeToolCallSafely(
                call = com.example.ai.ParsedToolCall("check_environment_status", mapOf("scope" to "all")),
                traces = traces,
                citations = cites
            )
            assertTrue(envStatus.contains("Termux Bridge: DISCONNECTED"))
            assertTrue(envStatus.contains("Remote Linux SSH: DISCONNECTED"))

            // 2. Test honest missing-model response with real local memory tool execution (English & Hindi)
            val chunks = orchestrator.orchestrateChatTurn(
                userPrompt = "Check my saved notes for SQLi prevention",
                languageMode = LanguageMode.ENGLISH,
                modelOption = AiModelOption.DHIR_2B_BALANCED,
                history = emptyList()
            ).toList()

            val finalChunk = chunks.last()
            assertTrue(finalChunk.isFinished)
            assertTrue(finalChunk.streamingText.contains("dhir-2b-Q4_K_M.gguf"))
            assertTrue(finalChunk.streamingText.contains("parameterized queries"))
            assertTrue(finalChunk.toolTraces.any { it.toolName == "search_memory" })

            val hindiChunks = orchestrator.orchestrateChatTurn(
                userPrompt = "mere saved notes mein SQLi batao",
                languageMode = LanguageMode.HINDI,
                modelOption = AiModelOption.DHIR_2B_BALANCED,
                history = emptyList()
            ).toList()
            assertTrue(hindiChunks.last().streamingText.contains("इंस्टॉल नहीं है"))
            assertTrue(hindiChunks.last().toolTraces.any { it.toolName == "search_memory" })

            // 3. Test multilingual Hinglish/Devanagari routing predicates
            assertTrue(orchestrator.shouldTriggerWebSearch("CVE-2025 online khojo"))
            assertTrue(orchestrator.shouldTriggerWebSearch("इंटरनेट परZero Trust खोजो"))
            assertTrue(orchestrator.shouldLookupMemory("mere notes dikhao"))
            assertTrue(orchestrator.shouldLookupMemory("पुरानी चैट याद है?"))
            assertTrue(orchestrator.shouldInspectEnvironments("ssh connect hai kya"))
            assertTrue(orchestrator.shouldTriggerYouTubeSearch("यूट्यूब पर Nmap वीडियो खोजो"))
            assertFalse(orchestrator.extractCleanYouTubeQuery("यूट्यूब पर Nmap वीडियो खोजो").contains("यूट्यूब"))
        } finally {
            db.close()
        }
    }

    @Test
    fun termuxCommandReceiverParsesOfficialResultBundleAndExitCodeVariants() {
        val officialIntent = Intent().apply {
            putExtra(
                TermuxCommandReceiver.TERMUX_RESULT_BUNDLE_KEY,
                Bundle().apply {
                    putString("stdout", "uid=10234(u0_a234)")
                    putString("stderr", "")
                    putInt("exitCode", 0)
                    putInt("err", 0)
                }
            )
        }
        val officialParsed = TermuxCommandReceiver.parseResultIntent(officialIntent, this::class.java.classLoader)
        assertEquals("uid=10234(u0_a234)", officialParsed.stdout)
        assertEquals(0, officialParsed.exitCode)
        assertEquals(0, officialParsed.internalErrCode)

        val snakeCaseIntent = Intent().apply {
            putExtra(
                TermuxCommandReceiver.TERMUX_RESULT_BUNDLE_KEY,
                Bundle().apply {
                    putString("stdout", "nmap 7.95")
                    putInt("exit_code", 2)
                    putInt("err", -1)
                    putString("errmsg", "partial")
                }
            )
        }
        val snakeParsed = TermuxCommandReceiver.parseResultIntent(snakeCaseIntent, null)
        assertEquals("nmap 7.95", snakeParsed.stdout)
        assertEquals(2, snakeParsed.exitCode)
        assertEquals(-1, snakeParsed.internalErrCode)
        assertEquals("partial", snakeParsed.internalErrMsg)

        val missingBundle = TermuxCommandReceiver.parseResultIntent(Intent(), null)
        assertEquals(-1, missingBundle.exitCode)
        assertTrue(missingBundle.stderr.contains("allow-external-apps=true"))
    }

    @Test
    fun biometricAuthHelperSupportsApi24To29DeviceCredentialAndPinFallback() {
        val api28Auths = DefaultBiometricGateController.supportedAuthenticatorsForSdk(Build.VERSION_CODES.P)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            api28Auths
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            api28Auths and BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        assertFalse(
            api28Auths == (BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        )

        val api31Auths = DefaultBiometricGateController.supportedAuthenticatorsForSdk(Build.VERSION_CODES.S)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            api31Auths
        )

        // API 28 device with PIN/Pattern set and no fingerprint hardware/enrollment -> READY via Keyguard fallback
        val pinOnlyApi28 = DefaultBiometricGateController.evaluateSupport(
            sdkInt = Build.VERSION_CODES.P,
            biometricCanAuthCode = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            keyguardDeviceSecure = true,
            hasKeyguardConfirmIntent = true
        )
        assertEquals(BiometricSupportStatus.READY, pinOnlyApi28.status)
        assertTrue(pinOnlyApi28.useKeyguardCredentialFallback)

        // API 26 device with NONE_ENROLLED biometrics but PIN set -> READY via Keyguard fallback
        val noneBioWithPin = DefaultBiometricGateController.evaluateSupport(
            sdkInt = Build.VERSION_CODES.O,
            biometricCanAuthCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            keyguardDeviceSecure = true,
            hasKeyguardConfirmIntent = true
        )
        assertEquals(BiometricSupportStatus.READY, noneBioWithPin.status)
        assertTrue(noneBioWithPin.useKeyguardCredentialFallback)

        // No PIN and no biometrics -> NONE_ENROLLED
        val unlockedDevice = DefaultBiometricGateController.evaluateSupport(
            sdkInt = Build.VERSION_CODES.P,
            biometricCanAuthCode = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            keyguardDeviceSecure = false,
            hasKeyguardConfirmIntent = false
        )
        assertEquals(BiometricSupportStatus.NONE_ENROLLED, unlockedDevice.status)

        // VaultLifecycleGuard tracks transient external SAF/Settings pickers
        VaultLifecycleGuard.clearExternalFlow()
        assertFalse(VaultLifecycleGuard.consumeOrIsWithinExternalFlow())
        VaultLifecycleGuard.markExternalFlowLaunched(10_000L)
        assertTrue(VaultLifecycleGuard.consumeOrIsWithinExternalFlow())
        VaultLifecycleGuard.clearExternalFlow()
    }

    @Test
    fun networkSecurityAndBackupConfigsEnforceLeastPrivilegeAndExcludeSecrets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val parser = context.resources.getXml(R.xml.network_security_config)
        var baseCleartext: Boolean? = null
        val allowedDomains = mutableListOf<String>()
        var insideCleartextDomain = false

        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "base-config" -> {
                        val attr = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        baseCleartext = attr?.toBooleanStrictOrNull()
                    }
                    "domain-config" -> {
                        val attr = parser.getAttributeValue(null, "cleartextTrafficPermitted")
                        insideCleartextDomain = attr == "true"
                    }
                    "domain" -> {
                        if (insideCleartextDomain) {
                            allowedDomains += parser.nextText().trim()
                        }
                    }
                }
            } else if (eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "domain-config") {
                insideCleartextDomain = false
            }
            eventType = parser.next()
        }

        assertEquals(false, baseCleartext)
        assertEquals(listOf("127.0.0.1", "localhost"), allowedDomains)

        // Verify backup_rules.xml & data_extraction_rules.xml exclude sensitive vault assets
        fun readExcludedPaths(xmlResId: Int): Set<String> {
            val xml = context.resources.getXml(xmlResId)
            val paths = mutableSetOf<String>()
            var ev = xml.eventType
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "exclude") {
                    xml.getAttributeValue(null, "path")?.let { paths += it }
                }
                ev = xml.next()
            }
            return paths
        }
        val legacyExcludes = readExcludedPaths(R.xml.backup_rules)
        val cloudExcludes = readExcludedPaths(R.xml.data_extraction_rules)
        assertTrue(legacyExcludes.contains("friday_remote_server_encrypted_prefs.xml"))
        assertTrue(legacyExcludes.contains("friday_local_vault.db"))
        assertTrue(legacyExcludes.contains("friday_ssh_known_hosts"))
        assertTrue(cloudExcludes.contains("friday_remote_server_encrypted_prefs.xml"))
        assertTrue(cloudExcludes.contains("friday_local_vault.db"))
        assertTrue(cloudExcludes.contains("models"))
    }

    @Test
    fun roomDatabaseMigration1To2PreservesAllUserRecordsAndUniqueLearningKeys() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "friday_mig_test_${System.currentTimeMillis()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS notes (" +
                                "id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, title TEXT NOT NULL, " +
                                "content TEXT NOT NULL, category TEXT NOT NULL, pinned INTEGER NOT NULL, " +
                                "createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, " +
                                "pendingSync INTEGER NOT NULL, pendingDelete INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_notes_userId_pendingDelete_pinned_updatedAtMillis " +
                                "ON notes (userId, pendingDelete, pinned, updatedAtMillis)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_notes_userId_pendingSync ON notes (userId, pendingSync)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS chat_messages (" +
                                "id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, role TEXT NOT NULL, " +
                                "content TEXT NOT NULL, languageMode TEXT NOT NULL, agentType TEXT NOT NULL, " +
                                "createdAtMillis INTEGER NOT NULL, pendingSync INTEGER NOT NULL, pendingDelete INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_chat_messages_userId_pendingDelete_createdAtMillis " +
                                "ON chat_messages (userId, pendingDelete, createdAtMillis)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_chat_messages_userId_pendingSync ON chat_messages (userId, pendingSync)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS terminal_logs (" +
                                "id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, command TEXT NOT NULL, " +
                                "environment TEXT NOT NULL, status TEXT NOT NULL, output TEXT NOT NULL, " +
                                "exitCode INTEGER NOT NULL, approvedByUser INTEGER NOT NULL, " +
                                "localOnly INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_terminal_logs_userId_createdAtMillis ON terminal_logs (userId, createdAtMillis)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS learning_records (" +
                                "id TEXT NOT NULL PRIMARY KEY, userId TEXT NOT NULL, recordType TEXT NOT NULL, " +
                                "itemId TEXT NOT NULL, completed INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS index_learning_records_userId_recordType ON learning_records (userId, recordType)"
                        )
                        // Seed v1 rows including duplicate (userId, recordType, itemId)
                        db.execSQL("INSERT INTO notes VALUES ('n1','u1','Keep Me','Safe data','GENERAL',1,100,200,0,0)")
                        db.execSQL("INSERT INTO learning_records VALUES ('u1::QUIZ::mod1','u1','QUIZ','mod1',0,100)")
                        db.execSQL("INSERT INTO learning_records VALUES ('legacy_dup','u1','QUIZ','mod1',1,200)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()

        val roomDb = Room.databaseBuilder(context, FridayDatabase::class.java, dbName)
            .addMigrations(*FridayDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val migratedNote = roomDb.fridayDao().getNoteById("n1")
            assertNotNull(migratedNote)
            assertEquals("Keep Me", migratedNote?.title)

            val records = roomDb.fridayDao().getAllLearningRecordsForUser("u1")
            assertEquals(1, records.size)
            assertTrue(records.first().completed)

            val repo = MemoryRepository(dao = roomDb.fridayDao(), db = null, auth = null, context = context)
            repo.saveYouTubeLearningRecord(
                userId = "u1",
                video = YouTubeVideoSummary(
                    videoId = "abc123",
                    title = "Nmap Basics Hindi",
                    channel = "CyberSec",
                    url = "https://www.youtube.com/watch?v=abc123",
                    transcriptExcerpt = "Use nmap -sV only on authorized systems.",
                    transcriptAvailable = true
                )
            )
            val ytRecords = repo.getRecentYouTubeLearningRecords("u1")
            assertEquals(1, ytRecords.size)
            assertEquals("Nmap Basics Hindi", ytRecords.first().title)
        } finally {
            roomDb.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun securitySanitizerAndBridgeClientBlockBypassOperatorsAndUnapprovedExecution() = runBlocking {
        // Structural shell injection bypasses that previously slipped past simple substring checks
        assertTrue(CommandPolicyValidator.evaluateCommand("ls; id", TerminalEnvironment.TERMUX_BRIDGE).isBlocked)
        assertTrue(CommandPolicyValidator.evaluateCommand("uname $(cat /etc/passwd)", TerminalEnvironment.TERMUX_BRIDGE).isBlocked)
        assertTrue(CommandPolicyValidator.evaluateCommand("echo `id`", TerminalEnvironment.TERMUX_BRIDGE).isBlocked)
        assertTrue(CommandPolicyValidator.evaluateCommand("ls\nrm -rf /", TerminalEnvironment.TERMUX_BRIDGE).isBlocked)
        assertTrue(CommandPolicyValidator.evaluateCommand("bash -c 'uname -a'", TerminalEnvironment.TERMUX_BRIDGE).isBlocked)

        // Ambiguous Nmap prompt without target must ask for target instead of guessing 192.168.1.0/24
        val action = FridayCommandRouter.detectAction("FRIDAY, scan my authorized test network using Nmap")
        assertTrue(action is ConversationalAction.AskScanTarget)

        val explicitTargetAction = FridayCommandRouter.detectAction("FRIDAY, scan 10.10.1.0/24 using Nmap")
        assertTrue(explicitTargetAction is ConversationalAction.RequestAuthorizedNmap)

        // Execution boundary rejects approvedByUser = false even for benign command
        val bridgeClient = TerminalBridgeClient()
        val rejected = bridgeClient.executeApprovedCommand(
            command = "uname -a",
            environment = TerminalEnvironment.TERMUX_BRIDGE,
            endpointUrl = "http://127.0.0.1:8022",
            sessionToken = "tok",
            approvedByUser = false
        )
        assertFalse(rejected.executed)
        assertEquals(TerminalExecutionStatus.BLOCKED, rejected.status)
    }

    @Test
    fun projectFileHelperHandlesUtf16FlatePdfBinaryRejectionAndChunks() {
        // UTF-16LE BOM decoding
        val utf16Body = "नमस्ते FRIDAY\nLine 2".toByteArray(StandardCharsets.UTF_16LE)
        val utf16WithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16Body
        val utf16File = ProjectFileHelper.parseLoadedBytes(
            uriString = "content://test/readme.txt",
            fileName = "readme.txt",
            mimeType = "text/plain",
            reportedSize = utf16WithBom.size.toLong(),
            rawBytes = utf16WithBom
        ).getOrThrow()
        assertEquals("UTF-16LE", utf16File.encodingLabel)
        assertTrue(utf16File.content.contains("नमस्ते FRIDAY"))

        // Binary NUL payload rejection
        val binaryRes = ProjectFileHelper.parseLoadedBytes(
            uriString = "content://test/app.bin",
            fileName = "app.bin",
            mimeType = "application/octet-stream",
            reportedSize = 64,
            rawBytes = ByteArray(64) { if (it % 2 == 0) 0 else 0x41 }
        )
        assertTrue(binaryRes.isFailure)

        // FlateDecode PDF extraction
        val contentStreamText = "BT\n/F1 12 Tf\n(Hello FRIDAY PDF Security) Tj\nET"
        val deflater = Deflater()
        deflater.setInput(contentStreamText.toByteArray(StandardCharsets.ISO_8859_1))
        deflater.finish()
        val compressedOut = ByteArrayOutputStream()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            compressedOut.write(buf, 0, deflater.deflate(buf))
        }
        deflater.end()
        val pdfBytes = buildString {
            append("%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n")
            append("2 0 obj << /Filter /FlateDecode /Length ${compressedOut.size()} >>\nstream\n")
        }.toByteArray(StandardCharsets.ISO_8859_1) +
            compressedOut.toByteArray() +
            "\nendstream\nendobj\n%%EOF".toByteArray(StandardCharsets.ISO_8859_1)

        val pdfLoaded = ProjectFileHelper.parseLoadedBytes(
            uriString = "content://test/audit.pdf",
            fileName = "audit.pdf",
            mimeType = "application/pdf",
            reportedSize = pdfBytes.size.toLong(),
            rawBytes = pdfBytes
        ).getOrThrow()
        assertTrue(pdfLoaded.content.contains("Hello FRIDAY PDF Security"))
        assertTrue(pdfLoaded.encodingLabel.startsWith("PDF Text"))

        // Chunked big file coverage
        val largeContent = "A".repeat(25_000).toByteArray(StandardCharsets.UTF_8)
        val chunked = ProjectFileHelper.parseLoadedBytes(
            uriString = "content://test/large.log",
            fileName = "large.log",
            mimeType = "text/plain",
            reportedSize = largeContent.size.toLong(),
            rawBytes = largeContent
        ).getOrThrow()
        assertEquals(3, chunked.chunksCount)
        assertTrue(chunked.truncated)
        assertNotNull(chunked.partialReadWarning)
        assertEquals(12_000, ProjectFileHelper.getChunk(chunked.content, 0).length)
        assertEquals(1_000, ProjectFileHelper.getChunk(chunked.content, 2).length)
    }

    @Test
    fun offlineMigrationRequiresConsentDeduplicatesAndKeepsAgentHistoriesIsolated() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FridayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = MemoryRepository(dao = db.fridayDao(), db = null, auth = null, context = context)
            val offlineUid = MemoryRepository.LOCAL_OFFLINE_USER_ID
            val targetUid = "firebase_uid_42"

            repo.saveNote("Offline Key Takeaway", "Validate redirects server-side.", NoteCategory.OWASP, userIdOverride = offlineUid)
            repo.saveChatMessage("USER", "Offline chat msg", LanguageMode.ENGLISH, AgentType.CHAT, offlineUid)
            repo.saveTerminalLog("uname -a", TerminalEnvironment.TERMUX_BRIDGE, TerminalExecutionStatus.EXECUTED, "Linux", 0, true, offlineUid)
            repo.markQuizCompleted(offlineUid, "web_basics")

            // 1. Pre-populate duplicate note on target account
            repo.saveNote("Offline Key Takeaway", "Validate redirects server-side.", NoteCategory.OWASP, userIdOverride = targetUid)

            // 2. Reject without consent
            val denied = repo.migrateOfflineDataWithConsent(targetUid, explicitUserConsent = false)
            assertTrue(denied.isFailure)

            // 3. Migrate with consent -> duplicate note skipped, chat/log/quiz migrated, source cleared
            val summaryBefore = repo.inspectOfflineMigrationCandidate(targetUid)
            assertTrue(summaryBefore.hasPendingPrompt)
            assertEquals(4, summaryBefore.totalMigratableCount)

            val migrated = repo.migrateOfflineDataWithConsent(targetUid, explicitUserConsent = true).getOrThrow()
            assertEquals(3, migrated) // duplicate note was NOT duplicated

            assertEquals(1, repo.observeNotes(targetUid).first().size)
            assertEquals(1, repo.observeChatMessages(targetUid).first().size)
            val targetLogs = db.fridayDao().observeTerminalLogs(targetUid).first()
            assertEquals(1, targetLogs.size)
            assertTrue(targetLogs.first().localOnly)
            assertFalse(repo.inspectOfflineMigrationCandidate(targetUid).hasPendingPrompt)

            // 4. Scoped agent history isolation (CHAT vs CODING vs REPORT_ANALYSIS)
            repo.saveChatMessage("USER", "Write a Kotlin parser", LanguageMode.ENGLISH, AgentType.CODING, targetUid)
            repo.saveChatMessage("FRIDAY", "fun parse() = Unit", LanguageMode.ENGLISH, AgentType.CODING, targetUid)

            val chatScoped = repo.getScopedAgentHistory(targetUid, AgentType.CHAT, "Offline")
            val codingScoped = repo.getScopedAgentHistory(targetUid, AgentType.CODING, "Kotlin")
            assertEquals(1, chatScoped.size)
            assertFalse(chatScoped.any { it.second.contains("fun parse") })
            assertEquals(2, codingScoped.size)
        } finally {
            db.close()
        }
    }
}

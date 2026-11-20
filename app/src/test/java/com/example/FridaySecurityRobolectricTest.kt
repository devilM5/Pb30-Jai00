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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
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
        val result = executor.executeCommand(validProfile, "uname -a", timeoutSeconds = 15)
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

        val execWhenDisconnected = client.executeApprovedCommand(state, "python3 --version", 15)
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

        val nmap = FridayCommandRouter.detectAction("FRIDAY, scan my authorized test network using Nmap.")
        assertTrue(nmap is ConversationalAction.RequestAuthorizedNmap)

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
                var model by remember { mutableStateOf(AiModelOption.FLASH_3_8) }
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
                    selectedModel = AiModelOption.FLASH_3_8,
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
        composeRule.onNodeWithTag("hub_card_chat").assertIsDisplayed().performClick()
        assertEquals("chat", openedTarget)
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
                    selectedModel = AiModelOption.FLASH_3_8,
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
}

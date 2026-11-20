package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CyberEmerald
import com.example.ui.theme.CyberGridBackdrop

const val ROUTE_DASHBOARD = "dashboard"
const val ROUTE_CHAT = "friday_chat"
const val ROUTE_TOOLS = "ai_tools_studio"
const val ROUTE_SEC_AGENT = "security_agent"
const val ROUTE_LAB = "learning_lab"
const val ROUTE_MEMORY = "memory"
const val ROUTE_REMOTE_SETTINGS = "remote_server_settings"

data class PrimaryNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

val primaryNavDestinations = listOf(
    PrimaryNavDestination(
        route = ROUTE_DASHBOARD,
        labelRes = R.string.nav_dashboard,
        selectedIcon = Icons.Filled.Dashboard,
        unselectedIcon = Icons.Outlined.Dashboard,
        testTag = "nav_dashboard"
    ),
    PrimaryNavDestination(
        route = ROUTE_CHAT,
        labelRes = R.string.nav_chat,
        selectedIcon = Icons.Filled.Psychology,
        unselectedIcon = Icons.Outlined.Psychology,
        testTag = "nav_chat"
    ),
    PrimaryNavDestination(
        route = ROUTE_TOOLS,
        labelRes = R.string.nav_tools,
        selectedIcon = Icons.Filled.Build,
        unselectedIcon = Icons.Outlined.Build,
        testTag = "nav_tools"
    ),
    PrimaryNavDestination(
        route = ROUTE_SEC_AGENT,
        labelRes = R.string.nav_sec_agent,
        selectedIcon = Icons.Filled.Security,
        unselectedIcon = Icons.Outlined.Security,
        testTag = "nav_sec_agent"
    ),
    PrimaryNavDestination(
        route = ROUTE_LAB,
        labelRes = R.string.nav_lab,
        selectedIcon = Icons.Filled.School,
        unselectedIcon = Icons.Outlined.School,
        testTag = "nav_lab"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(
    currentRoute: String,
    snackbarHostState: SnackbarHostState,
    onNavigate: (String) -> Unit,
    onOpenMemory: () -> Unit,
    onOpenRemoteSettings: () -> Unit = {},
    onLockVault: (() -> Unit)? = null,
    onNavigateBack: () -> Unit,
    content: @Composable () -> Unit
) {
    CyberGridBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            navigationIcon = {
                                if (currentRoute == ROUTE_MEMORY || currentRoute == ROUTE_REMOTE_SETTINGS) {
                                    IconButton(
                                        onClick = onNavigateBack,
                                        modifier = Modifier.testTag("top_bar_back_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            },
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(CyberEmerald)
                                    )
                                    Text(
                                        text = "PB-30 JAI // FRIDAY",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            actions = {
                                val settingsSelected = currentRoute == ROUTE_REMOTE_SETTINGS
                                IconButton(
                                    onClick = onOpenRemoteSettings,
                                    modifier = Modifier
                                        .padding(end = 2.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (settingsSelected) {
                                                Modifier
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .testTag("top_bar_remote_settings_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Dns,
                                        contentDescription = stringResource(R.string.remote_server_settings_title),
                                        tint = if (settingsSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val memorySelected = currentRoute == ROUTE_MEMORY
                                IconButton(
                                    onClick = onOpenMemory,
                                    modifier = Modifier
                                        .padding(end = 2.dp)
                                        .clip(CircleShape)
                                        .then(
                                            if (memorySelected) {
                                                Modifier
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                                    .border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .testTag("top_bar_memory_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Bookmarks,
                                        contentDescription = stringResource(R.string.nav_memory),
                                        tint = if (memorySelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (onLockVault != null) {
                                    IconButton(
                                        onClick = onLockVault,
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .testTag("top_bar_lock_vault_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = stringResource(R.string.biometric_lock_vault_cd),
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                        )
                    }
                },
                bottomBar = {
                    if (!useRail) {
                        FridayBottomNavigationBar(
                            currentRoute = currentRoute,
                            onNavigate = onNavigate
                        )
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            Spacer(Modifier.size(8.dp))
                            primaryNavDestinations.forEach { destination ->
                                val selected = currentRoute == destination.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { onNavigate(destination.route) },
                                    modifier = Modifier.testTag(destination.testTag),
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                            contentDescription = stringResource(destination.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(destination.labelRes),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 840.dp)
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FridayBottomNavigationBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        )
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 8.dp
        ) {
            primaryNavDestinations.forEach { destination ->
                val selected = currentRoute == destination.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(destination.route) },
                    modifier = Modifier.testTag(destination.testTag),
                    icon = {
                        Icon(
                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                            contentDescription = stringResource(destination.labelRes)
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(destination.labelRes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }
    }
}

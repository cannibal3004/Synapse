package com.aiassistant.presentation.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiassistant.domain.model.Conversation
import com.aiassistant.presentation.screen.chat.ChatScreen
import com.aiassistant.presentation.screen.conversation.ConversationListScreen
import com.aiassistant.presentation.screen.settings.SettingsScreen
import com.aiassistant.presentation.screen.tasks.TaskScreen
import com.aiassistant.presentation.vm.ConversationListViewModel

/**
 * Width at which the app stops being a phone and becomes a desktop.
 *
 * Material's expanded breakpoint. Above it the drawer stops hiding itself and the conversation
 * list moves into it, which is the shape a DeX window or a tablet in landscape wants; below it
 * nothing changes and the drawer stays modal. A maximised DeX window on a 1080p display is
 * comfortably past this, and so is half of one.
 */
private const val DESKTOP_WIDTH_DP = 840

private val SIDEBAR_WIDTH = 280.dp

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    deepLinkType: String? = null,
    deepLinkId: String? = null
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    // Recomposes as the window is dragged, so the layout follows the resize rather than the
    // launch size. This is why MainActivity handles screenSize itself -- see the manifest.
    val desktop = LocalConfiguration.current.screenWidthDp >= DESKTOP_WIDTH_DP

    LaunchedEffect(deepLinkType, deepLinkId) {
        if (deepLinkType == "EXECUTION_HISTORY" && deepLinkId != null) {
            navController.navigate("tasks/deeplink/$deepLinkId") {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openConversationId = backStackEntry?.arguments?.getString("conversationId")

    // The list is the phone's home, but with the sidebar showing it would be the same list
    // twice. Hand the pane a chat instead -- on launch, and again if the window is widened
    // while the list happens to be open.
    LaunchedEffect(desktop, currentRoute) {
        if (desktop && currentRoute == "conversationList") {
            navController.navigateTopLevel("chat/new")
        }
    }

    val sidebar = @Composable {
        SidebarContent(
            // The list only belongs in the sidebar when the sidebar is always there. On a phone
            // it stays a destination of its own, reached from the item below.
            inlineConversations = desktop,
            currentRoute = currentRoute,
            openConversationId = openConversationId,
            onNavigate = { route ->
                navController.navigateTopLevel(route)
                if (!desktop) scope.launch { drawerState.close() }
            },
            onOpenConversation = { id ->
                navController.navigateTopLevel("chat/$id")
                if (!desktop) scope.launch { drawerState.close() }
            }
        )
    }

    if (desktop) {
        Row(modifier = Modifier.fillMaxSize()) {
            PermanentDrawerSheet(
                modifier = Modifier.width(SIDEBAR_WIDTH),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                sidebar()
            }
            VerticalDivider()
            Box(modifier = Modifier.weight(1f)) {
                AppNavHost(
                    navController = navController,
                    modifier = modifier,
                    // No drawer to open, so the screens drop their menu button.
                    onToggleDrawer = null
                )
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(SIDEBAR_WIDTH)) { sidebar() }
            },
            content = {
                AppNavHost(
                    navController = navController,
                    modifier = modifier,
                    onToggleDrawer = { scope.launch { drawerState.open() } }
                )
            }
        )
    }
}

/**
 * Switching panes, not stacking them.
 *
 * With the sidebar always visible every item in it is a sibling, so navigating from one to
 * another replaces what is on screen rather than piling onto a back stack the user cannot see.
 * Clearing to the root leaves exactly one entry, so Back leaves the app instead of walking
 * through everywhere you clicked.
 *
 * Deliberately no saveState/restoreState. Every conversation shares the one `chat/{id}`
 * destination, so saved state is keyed by a destination that cannot tell them apart -- with it
 * on, opening a second conversation restored the first one's arguments and the pane never
 * changed.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun SidebarContent(
    inlineConversations: Boolean,
    currentRoute: String?,
    openConversationId: String?,
    onNavigate: (String) -> Unit,
    onOpenConversation: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Synapse",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        if (!inlineConversations) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI Assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onNavigate("chat/new") },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("New Chat")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (inlineConversations) {
            // The list takes the free space, so Tasks and Settings sit at the bottom of the
            // sidebar the way they do in every desktop app.
            ConversationSidebarList(
                openConversationId = openConversationId,
                onOpenConversation = onOpenConversation,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
        } else {
            NavigationDrawerItem(
                label = { Text("Chat History") },
                icon = { Icon(Icons.Default.Forum, contentDescription = null) },
                selected = currentRoute == "conversationList",
                onClick = { onNavigate("conversationList") }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        NavigationDrawerItem(
            label = { Text("Tasks") },
            icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
            selected = currentRoute == "tasks",
            onClick = { onNavigate("tasks") }
        )

        Spacer(modifier = Modifier.height(8.dp))

        NavigationDrawerItem(
            label = { Text("Settings") },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            selected = currentRoute == "settings",
            onClick = { onNavigate("settings") }
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ConversationSidebarList(
    openConversationId: String?,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.searchResults.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }

    LaunchedEffect(query) { viewModel.setSearchQuery(query) }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (conversations.isEmpty()) {
            Text(
                text = if (query.isBlank()) "No conversations yet" else "Nothing matches \"$query\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(conversations, key = { it.id }) { conversation ->
                ConversationSidebarRow(
                    conversation = conversation,
                    selected = conversation.id == openConversationId,
                    onClick = { onOpenConversation(conversation.id) },
                    onDelete = { pendingDelete = conversation }
                )
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete conversation?") },
            text = { Text("\"${target.title}\" and its messages will be removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation(target.id)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ConversationSidebarRow(
    conversation: Conversation,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    // Delete appears on hover rather than sitting on every row. There is a pointer wherever this
    // layout is showing -- DeX, a tablet with a mouse, a desktop window -- so hover is a real
    // affordance here in a way it never is on a phone. Selection reveals it too, which keeps it
    // reachable by keyboard and touch.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    NavigationDrawerItem(
        label = {
            Text(
                text = conversation.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        selected = selected,
        onClick = onClick,
        interactionSource = interactionSource,
        badge = if (hovered || selected) {
            {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete ${conversation.title}",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else null
    )
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier,
    onToggleDrawer: (() -> Unit)?
) {
    NavHost(
        navController = navController,
        startDestination = "conversationList",
        modifier = modifier
    ) {
        composable("conversationList") {
            ConversationListScreen(
                onToggleDrawer = onToggleDrawer,
                onConversationSelected = { conversationId ->
                    navController.navigate("chat/$conversationId")
                },
                onNewConversation = {
                    navController.navigate("chat/new")
                },
                viewModel = hiltViewModel()
            )
        }
        composable(
            route = "chat/{conversationId}",
            arguments = listOf(
                navArgument("conversationId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: "new"
            ChatScreen(
                conversationId = conversationId,
                onToggleDrawer = onToggleDrawer,
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
                viewModel = hiltViewModel()
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onToggleDrawer = onToggleDrawer,
                viewModel = hiltViewModel()
            )
        }
        composable("tasks") {
            TaskScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onToggleDrawer = onToggleDrawer,
                viewModel = hiltViewModel()
            )
        }
        composable(
            route = "tasks/deeplink/{executionHistoryId}",
            arguments = listOf(
                navArgument("executionHistoryId") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val executionHistoryId = backStackEntry.arguments?.getString("executionHistoryId")
            TaskScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onToggleDrawer = onToggleDrawer,
                viewModel = hiltViewModel(),
                executionHistoryId = executionHistoryId
            )
        }
    }
}

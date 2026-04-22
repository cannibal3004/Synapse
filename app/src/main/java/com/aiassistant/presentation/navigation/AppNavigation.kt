package com.aiassistant.presentation.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aiassistant.presentation.screen.chat.ChatScreen
import com.aiassistant.presentation.screen.conversation.ConversationListScreen
import com.aiassistant.presentation.screen.settings.SettingsScreen
import com.aiassistant.presentation.screen.tasks.TaskScreen
import com.aiassistant.presentation.vm.ChatViewModel
import com.aiassistant.presentation.vm.ConversationListViewModel
import com.aiassistant.presentation.vm.SettingsViewModel
import com.aiassistant.presentation.vm.TaskViewModel

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    deepLinkType: String? = null,
    deepLinkId: String? = null
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    LaunchedEffect(deepLinkType, deepLinkId) {
        if (deepLinkType == "EXECUTION_HISTORY" && deepLinkId != null) {
            navController.navigate("tasks/deeplink/$deepLinkId") {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Synapse",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AI Assistant",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            navController.navigate("chat/new") {
                                popUpTo("conversationList") { inclusive = true }
                            }
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Chat")
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    NavigationDrawerItem(
                        label = { Text("Chat History") },
                        icon = { Icon(Icons.Default.Forum, contentDescription = null) },
                        selected = false,
                        onClick = {
                            navController.navigate("conversationList") {
                                popUpTo("conversationList") { inclusive = true }
                            }
                            scope.launch { drawerState.close() }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Tasks") },
                        icon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                        selected = false,
                        onClick = {
                            navController.navigate("tasks")
                            scope.launch { drawerState.close() }
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        selected = false,
                        onClick = {
                            navController.navigate("settings")
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        },
        content = {
            NavHost(
                navController = navController,
                startDestination = "conversationList",
                modifier = modifier
            ) {
                composable("conversationList") {
                    ConversationListScreen(
                        onToggleDrawer = { scope.launch { drawerState.open() } },
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
                        onToggleDrawer = { scope.launch { drawerState.open() } },
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
                        onToggleDrawer = { scope.launch { drawerState.open() } },
                        viewModel = hiltViewModel()
                    )
                }
                composable("tasks") {
                    TaskScreen(
                        onNavigateBack = {
                            navController.popBackStack()
                        },
                        onToggleDrawer = { scope.launch { drawerState.open() } },
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
                        onToggleDrawer = { scope.launch { drawerState.open() } },
                        viewModel = hiltViewModel(),
                        executionHistoryId = executionHistoryId
                    )
                }
            }
        }
    )
}

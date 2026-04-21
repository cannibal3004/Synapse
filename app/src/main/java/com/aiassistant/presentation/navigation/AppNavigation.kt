package com.aiassistant.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aiassistant.presentation.screen.chat.ChatScreen
import com.aiassistant.presentation.screen.settings.SettingsScreen

@Composable
fun AppNavigation(
    modifier: Modifier = Modifier
) {
    val navController = androidx.navigation.compose.rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "chat",
        modifier = modifier
    ) {
        composable("chat") {
            ChatScreen(
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

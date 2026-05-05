package com.translive.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.translive.app.ui.screens.*

@Composable
fun TransLiveNavHost() {
    val navController = rememberNavController()

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo("translate") { inclusive = route == "translate" }
            launchSingleTop = true
        }
    }

    NavHost(navController = navController, startDestination = "translate") {
        composable("translate") {
            TranslationScreen(
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("dialogue") {
            DialogueScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("history") {
            HistoryScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("models") {
            ModelManagerScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") }
            )
        }
    }
}

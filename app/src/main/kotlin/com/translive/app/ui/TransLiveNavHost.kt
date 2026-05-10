package com.translive.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.translive.app.ui.screens.*

@androidx.camera.core.ExperimentalGetImage
@Composable
fun TransLiveNavHost() {
    val navController = rememberNavController()

    fun navigateTo(route: String) {
        if (route == "translate") {
            // Return to translate without recreating it
            navController.popBackStack("translate", inclusive = false)
        } else {
            navController.navigate(route) {
                popUpTo("translate") { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    NavHost(navController = navController, startDestination = "translate") {
        composable("translate") {
            TranslationScreen(
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToCamera = { navigateTo("camera") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("dialogue") {
            DialogueScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToCamera = { navigateTo("camera") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("camera") {
            CameraScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("history") {
            HistoryScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToCamera = { navigateTo("camera") },
                onNavigateToModels = { navigateTo("models") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("models") {
            ModelManagerScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToCamera = { navigateTo("camera") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToSettings = { navigateTo("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateToTranslate = { navigateTo("translate") },
                onNavigateToDialogue = { navigateTo("dialogue") },
                onNavigateToCamera = { navigateTo("camera") },
                onNavigateToHistory = { navigateTo("history") },
                onNavigateToModels = { navigateTo("models") }
            )
        }
    }
}

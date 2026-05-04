package com.translive.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.translive.app.ui.screens.DialogueScreen
import com.translive.app.ui.screens.ModelManagerScreen
import com.translive.app.ui.screens.TranslationScreen

@Composable
fun TransLiveNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "translate") {
        composable("translate") {
            TranslationScreen(
                onNavigateToDialogue = { navController.navigate("dialogue") },
                onNavigateToModels = { navController.navigate("models") }
            )
        }
        composable("dialogue") {
            DialogueScreen(
                onNavigateToTranslate = {
                    navController.navigate("translate") {
                        popUpTo("translate") { inclusive = true }
                    }
                },
                onNavigateToModels = { navController.navigate("models") }
            )
        }
        composable("models") {
            ModelManagerScreen(
                onNavigateToTranslate = {
                    navController.navigate("translate") {
                        popUpTo("translate") { inclusive = true }
                    }
                },
                onNavigateToDialogue = { navController.navigate("dialogue") }
            )
        }
    }
}

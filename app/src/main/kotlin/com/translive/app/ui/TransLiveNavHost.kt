package com.translive.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.translive.app.ui.screens.TranslationScreen

@Composable
fun TransLiveNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "translate") {
        composable("translate") {
            TranslationScreen(
                onNavigateToDialogue = { navController.navigate("dialogue") },
                onNavigateToHistory = { navController.navigate("history") }
            )
        }
        composable("dialogue") {
            // Phase 2: DialogueScreen
        }
        composable("history") {
            // Phase 1 week 4: HistoryScreen
        }
    }
}

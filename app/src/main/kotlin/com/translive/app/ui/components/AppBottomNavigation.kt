package com.translive.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

enum class BottomNavDestination {
    TRANSLATE,
    DIALOGUE,
    CAMERA,
    HISTORY,
    MODELS,
    SETTINGS
}

@Composable
fun AppBottomNavigation(
    selected: BottomNavDestination,
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToCamera: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == BottomNavDestination.TRANSLATE,
            onClick = onNavigateToTranslate,
            icon = { Icon(Icons.Filled.Translate, "Translate") }
        )
        NavigationBarItem(
            selected = selected == BottomNavDestination.DIALOGUE,
            onClick = onNavigateToDialogue,
            icon = { Icon(Icons.Filled.Mic, "Dialogue") }
        )
        NavigationBarItem(
            selected = selected == BottomNavDestination.CAMERA,
            onClick = onNavigateToCamera,
            icon = { Icon(Icons.Filled.CameraAlt, "Camera") }
        )
        NavigationBarItem(
            selected = selected == BottomNavDestination.HISTORY,
            onClick = onNavigateToHistory,
            icon = { Icon(Icons.Filled.History, "History") }
        )
        NavigationBarItem(
            selected = selected == BottomNavDestination.MODELS,
            onClick = onNavigateToModels,
            icon = { Icon(Icons.Filled.Storage, "Models") }
        )
        NavigationBarItem(
            selected = selected == BottomNavDestination.SETTINGS,
            onClick = onNavigateToSettings,
            icon = { Icon(Icons.Filled.Settings, "Settings") }
        )
    }
}

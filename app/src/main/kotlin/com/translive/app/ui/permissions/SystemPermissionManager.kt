package com.translive.app.ui.permissions

import android.Manifest
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.translive.app.service.accessibility.ScreenAccessibilityService
import com.translive.app.service.assist.ParlexVoiceInteractionService

object SystemPermissionManager {

    private const val TAG = "SystemPermMgr"

    fun getPermissionsState(context: Context): SystemPermissionsState {
        return SystemPermissionsState(
            isAccessibilityConfigured = isAccessibilityConfigured(context),
            isAccessibilityConnected = ScreenAccessibilityService.isConnected(),
            isAssistantRoleHeld = isAssistantHeld(context),
            isOverlayGranted = Settings.canDrawOverlays(context),
            isNotificationGranted = areNotificationsEnabled(context)
        )
    }

    fun isAccessibilityConfigured(context: Context): Boolean {
        val isA11yOn = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
        if (!isA11yOn) return false

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val targetFull = ComponentName(context, ScreenAccessibilityService::class.java).flattenToString()
        val targetShort = ComponentName(context, ScreenAccessibilityService::class.java).flattenToShortString()

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val service = splitter.next()
            if (service.equals(targetFull, ignoreCase = true) || service.equals(targetShort, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    fun isAssistantHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            }
        }
        val currentAssist = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?: Settings.Secure.getString(context.contentResolver, "assistant") ?: return false
        val expectedFull = ComponentName(context, ParlexVoiceInteractionService::class.java).flattenToString()
        val expectedShort = ComponentName(context, ParlexVoiceInteractionService::class.java).flattenToShortString()
        return currentAssist.equals(expectedFull, ignoreCase = true) || currentAssist.equals(expectedShort, ignoreCase = true)
    }

    fun areNotificationsEnabled(context: Context): Boolean {
        val appEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (!appEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun createAssistantRoleRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            }
        }
        return null
    }

    fun openAccessibilitySettings(context: Context) {
        val componentName = ComponentName(context, ScreenAccessibilityService::class.java).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(":settings:fragment_args_key", componentName)
            putExtra(":settings:show_fragment_args", Bundle().apply {
                putString(":settings:fragment_args_key", componentName)
            })
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open accessibility settings with fragment args, falling back", e)
            context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun openOverlaySettings(context: Context) {
        val packageUriIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(packageUriIntent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                openAppDetailsSettings(context)
            }
        }
    }

    fun openAssistantSettingsFallback(context: Context) {
        val voiceInputIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(voiceInputIntent)
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e2: Exception) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app details settings", e)
        }
    }
}

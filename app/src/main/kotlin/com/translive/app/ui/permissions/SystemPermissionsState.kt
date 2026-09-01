package com.translive.app.ui.permissions

enum class AccessibilityUiStatus {
    ACTIVE,
    CONFIGURED_NOT_BOUND,
    DISABLED
}

data class SystemPermissionsState(
    val isAccessibilityConfigured: Boolean = false,
    val isAccessibilityConnected: Boolean = false,
    val isAssistantRoleHeld: Boolean = false,
    val isOverlayGranted: Boolean = false,
    val isNotificationGranted: Boolean = false
) {
    val isFullSilentCaptureReady: Boolean
        get() = isAccessibilityConnected && isOverlayGranted

    val accessibilityStatus: AccessibilityUiStatus
        get() = when {
            isAccessibilityConnected -> AccessibilityUiStatus.ACTIVE
            isAccessibilityConfigured -> AccessibilityUiStatus.CONFIGURED_NOT_BOUND
            else -> AccessibilityUiStatus.DISABLED
        }
}

package com.translive.app.service.overlay

/**
 * Decides whether a window-state change invalidates the frozen screen
 * translation overlay.
 *
 * The old behavior dismissed on every `TYPE_WINDOW_STATE_CHANGED` after a fixed
 * delay. That also killed the overlay when the keyboard, an in-app dialog or a
 * system prompt appeared. We only dismiss when the foreground package actually
 * changes, while ignoring our own package and IME windows.
 */
object OverlayWindowDismissPolicy {

    const val DEFAULT_GUARD_MS = 500L

    fun shouldDismiss(
        sourcePackage: String?,
        eventPackage: String?,
        eventClassName: String? = null,
        ownPackage: String,
        msSinceShown: Long,
        guardMs: Long = DEFAULT_GUARD_MS
    ): Boolean {
        if (msSinceShown < guardMs) return false
        if (eventPackage.isNullOrBlank()) return false
        if (eventPackage == ownPackage) return false
        if (isIme(eventPackage, eventClassName)) return false

        // Without a known source package we cannot prove that the underlying
        // app changed. Prefer keeping the overlay rather than dismissing it on
        // an unrelated system window.
        val source = sourcePackage?.takeIf { it.isNotBlank() } ?: return false
        return eventPackage != source
    }

    private fun isIme(packageName: String?, className: String?): Boolean {
        val pkg = packageName?.lowercase().orEmpty()
        val cls = className?.lowercase().orEmpty()
        return pkg.contains("inputmethod") ||
            pkg.endsWith(".ime") ||
            pkg.contains("keyboard") ||
            pkg.contains("honeyboard") ||
            pkg.contains("swiftkey") ||
            pkg.contains("gboard") ||
            cls.contains("inputmethod")
    }
}

package com.translive.app.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.translive.app.data.ScreenA11yShortcutBehavior
import com.translive.app.data.SettingsRepository
import com.translive.app.service.ScreenTranslateOverlayService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject

data class AccessibilityTextNode(
    val text: String,
    val bounds: Rect
)

@AndroidEntryPoint
class ScreenAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var settings: SettingsRepository

    private val safeSettings: SettingsRepository
        get() = if (::settings.isInitialized) settings else SettingsRepository(this)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _isServiceActive.value = true
        Log.i(TAG, "ScreenAccessibilityService connected successfully")

        // Register accessibility button/shortcut callback (API 26+)
        // Triggered by: Volume Up + Down hold, two-finger swipe up, or floating a11y button
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val controller = accessibilityButtonController
            controller.registerAccessibilityButtonCallback(accessibilityButtonCallback)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive observation of window events
    }

    override fun onInterrupt() {
        Log.w(TAG, "ScreenAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceActive.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback)
        }
        if (instanceRef?.get() == this) {
            instanceRef = null
        }
        Log.i(TAG, "ScreenAccessibilityService destroyed")
    }

    /**
     * Accessibility button callback — fires when the user triggers the shortcut:
     * - Volume Up + Volume Down hold (3s)
     * - Two-finger swipe up from bottom edge
     * - Floating accessibility button tap
     */
    private val accessibilityButtonCallback =
        object : android.accessibilityservice.AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: android.accessibilityservice.AccessibilityButtonController) {
                Log.i(TAG, "Accessibility shortcut triggered")
                val behavior = safeSettings.screenA11yShortcutBehavior

                when (behavior) {
                    ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE -> {
                        // 1. First try instant 0ms vector text extraction
                        val nodes = extractVisibleTextNodesFast()
                        if (nodes.isNotEmpty()) {
                            Log.i(TAG, "Extracted ${nodes.size} vector text nodes from A11y tree in 0ms (Single-shot)")
                            ScreenTranslateOverlayService.translateNodesOneShot(this@ScreenAccessibilityService, nodes)
                        } else {
                            // 2. Fallback to silent screenshot OCR for canvas/games
                            Log.i(TAG, "0 text nodes in A11y tree — falling back to silent screenshot capture")
                            captureSilentScreenshot(
                                onSuccess = { bitmap ->
                                    ScreenTranslateOverlayService.translateScreenshotOneShot(this@ScreenAccessibilityService, bitmap)
                                },
                                onError = { err ->
                                    Log.e(TAG, "Accessibility shortcut screenshot fallback failed: $err")
                                }
                            )
                        }
                    }
                    ScreenA11yShortcutBehavior.TOGGLE_FLOATING_BUBBLE -> {
                        if (ScreenTranslateOverlayService.isServiceRunning.value) {
                            ScreenTranslateOverlayService.stop(this@ScreenAccessibilityService)
                        } else {
                            ScreenTranslateOverlayService.start(this@ScreenAccessibilityService)
                        }
                    }
                }
            }
        }

    /**
     * Captures a silent screenshot using takeScreenshot API (Android 11 / API 30+).
     * No MediaProjection permission prompt is shown to the user.
     */
    fun captureSilentScreenshot(
        displayId: Int = Display.DEFAULT_DISPLAY,
        onSuccess: (Bitmap) -> Unit,
        onError: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onError("takeScreenshot requires Android 11+ (API 30+)")
            return
        }

        takeScreenshot(
            displayId,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace
                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (bitmap != null) {
                            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            if (copy != null) {
                                onSuccess(copy)
                                return
                            }
                        }
                        hardwareBuffer.close()
                        onError("Failed to convert hardwareBuffer to bitmap")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing screenshot result", e)
                        onError(e.message ?: "Failed to process screenshot")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val errorMsg = when (errorCode) {
                        1 -> "Internal error"
                        2 -> "No accessibility services"
                        3 -> "Interval time too short"
                        4 -> "Invalid display"
                        else -> "Screenshot capture failed"
                    }
                    Log.e(TAG, "takeScreenshot failed: $errorMsg ($errorCode)")
                    onError(errorMsg)
                }
            }
        )
    }

    /**
     * Fast, iterative BFS traversal of visible window roots to extract text nodes
     * directly with exact screen coordinates in 1-3ms without OCR.
     */
    fun extractVisibleTextNodesFast(maxNodes: Int = 600): List<AccessibilityTextNode> {
        val results = mutableListOf<AccessibilityTextNode>()
        val screenBounds = Rect().apply {
            val dm = resources.displayMetrics
            left = 0
            top = 0
            right = dm.widthPixels
            bottom = dm.heightPixels
        }

        val roots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val wins = windows
            if (wins.isNotEmpty()) {
                wins.filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    it.type == AccessibilityWindowInfo.TYPE_SYSTEM
                }.mapNotNull { it.root }
            } else {
                listOfNotNull(rootInActiveWindow)
            }
        } else {
            listOfNotNull(rootInActiveWindow)
        }

        if (roots.isEmpty()) return results

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val tempRect = Rect()

        for (root in roots) {
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < maxNodes) {
                val node = queue.removeFirst()
                visited++

                if (!node.isVisibleToUser) continue
                if (node.isPassword) continue
                if (node.packageName == packageName) continue

                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val rawContent = if (!text.isNullOrBlank()) text else desc

                node.getBoundsInScreen(tempRect)

                val width = tempRect.right - tempRect.left
                val height = tempRect.bottom - tempRect.top
                val isValidGeometry = width > 8 && height > 8 && Rect.intersects(tempRect, screenBounds)
                val hasMeaningfulText = !rawContent.isNullOrBlank() && rawContent.any { it.isLetterOrDigit() }

                if (isValidGeometry && hasMeaningfulText) {
                    val nodeRect = Rect().apply {
                        left = tempRect.left
                        top = tempRect.top
                        right = tempRect.right
                        bottom = tempRect.bottom
                    }
                    results.add(AccessibilityTextNode(text = rawContent!!, bounds = nodeRect))
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { child ->
                        queue.add(child)
                    }
                }
            }
        }
        return deduplicateNestedNodes(results)
    }

    private fun deduplicateNestedNodes(nodes: List<AccessibilityTextNode>): List<AccessibilityTextNode> {
        if (nodes.size <= 1) return nodes
        val sorted = nodes.sortedBy { (it.bounds.right - it.bounds.left) * (it.bounds.bottom - it.bounds.top) }
        val preserved = mutableListOf<AccessibilityTextNode>()

        for (candidate in sorted) {
            val isDuplicateContainer = preserved.any { existing ->
                existing.bounds != candidate.bounds &&
                candidate.bounds.left <= existing.bounds.left &&
                candidate.bounds.top <= existing.bounds.top &&
                candidate.bounds.right >= existing.bounds.right &&
                candidate.bounds.bottom >= existing.bounds.bottom &&
                candidate.text.contains(existing.text)
            }
            if (!isDuplicateContainer) {
                preserved.add(candidate)
            }
        }
        return preserved
    }

    /**
     * Backward-compatible helper.
     */
    fun extractVisibleTextNodes(): List<AccessibilityTextNode> = extractVisibleTextNodesFast()

    companion object {
        private const val TAG = "ScreenA11yService"
        private var instanceRef: WeakReference<ScreenAccessibilityService>? = null

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        fun getInstance(): ScreenAccessibilityService? = instanceRef?.get()

        fun isConnected(): Boolean = instanceRef?.get() != null && _isServiceActive.value
    }
}

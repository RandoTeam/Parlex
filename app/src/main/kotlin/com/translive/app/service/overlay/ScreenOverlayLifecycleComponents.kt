package com.translive.app.service.overlay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// --- 1. Token Retention & Session Model ---
interface ProjectionToken {
    val id: String
    val isValid: Boolean
    fun invalidate()
}

class ScreenCaptureSessionManager {
    var activeToken: ProjectionToken? = null
        private set

    var tokenReuseCount: Int = 0
        private set

    var permissionRequestCount: Int = 0
        private set

    val isSessionActive: Boolean
        get() = activeToken?.isValid == true

    fun attachToken(token: ProjectionToken) {
        activeToken = token
        tokenReuseCount = 0
    }

    fun requestCapture(): ProjectionToken {
        val token = activeToken
        if (token != null && token.isValid) {
            tokenReuseCount++
            return token
        }
        permissionRequestCount++
        throw IllegalStateException("No active MediaProjection token available")
    }

    fun releaseSession() {
        activeToken?.invalidate()
        activeToken = null
    }
}

// --- 2. Debounce & Mutex Re-entrancy Guard ---
class CaptureDebounceMutex(private val debounceWindowMs: Long = 300L) {
    private val mutex = Mutex()
    private val isRunning = AtomicBoolean(false)
    private var lastTriggerTimestamp = 0L

    val droppedTriggers = AtomicInteger(0)
    val executedTriggers = AtomicInteger(0)

    suspend fun executeGuarded(currentTimeMs: Long, block: suspend () -> Unit): Boolean {
        if (currentTimeMs - lastTriggerTimestamp < debounceWindowMs) {
            droppedTriggers.incrementAndGet()
            return false
        }

        if (!isRunning.compareAndSet(false, true)) {
            droppedTriggers.incrementAndGet()
            return false
        }

        return try {
            mutex.withLock {
                lastTriggerTimestamp = currentTimeMs
                executedTriggers.incrementAndGet()
                block()
            }
            true
        } finally {
            isRunning.set(false)
        }
    }
}

// --- 3. Floating Button State Machine ---
enum class OverlayState {
    IDLE,
    SCANNING,
    TRANSLATING,
    DISPLAYING,
    ERROR
}

sealed interface OverlayEvent {
    data object TriggerClick : OverlayEvent
    data object FrameCaptured : OverlayEvent
    data object TranslationReady : OverlayEvent
    data object DismissOverlay : OverlayEvent
    data class ErrorOccurred(val message: String) : OverlayEvent
    data object AutoRecoverTimerExpired : OverlayEvent
}

class FloatingBubbleStateMachine(
    private val onStateChanged: ((OverlayState) -> Unit)? = null
) {
    var currentState: OverlayState = OverlayState.IDLE
        private set

    var lastErrorMessage: String? = null
        private set

    fun transition(event: OverlayEvent): Boolean {
        val nextState = when (currentState) {
            OverlayState.IDLE -> when (event) {
                is OverlayEvent.TriggerClick -> OverlayState.SCANNING
                is OverlayEvent.ErrorOccurred -> {
                    lastErrorMessage = event.message
                    OverlayState.ERROR
                }
                else -> null
            }
            OverlayState.SCANNING -> when (event) {
                is OverlayEvent.FrameCaptured -> OverlayState.TRANSLATING
                is OverlayEvent.ErrorOccurred -> {
                    lastErrorMessage = event.message
                    OverlayState.ERROR
                }
                is OverlayEvent.DismissOverlay -> OverlayState.IDLE
                else -> null
            }
            OverlayState.TRANSLATING -> when (event) {
                is OverlayEvent.TranslationReady -> OverlayState.DISPLAYING
                is OverlayEvent.ErrorOccurred -> {
                    lastErrorMessage = event.message
                    OverlayState.ERROR
                }
                is OverlayEvent.DismissOverlay -> OverlayState.IDLE
                else -> null
            }
            OverlayState.DISPLAYING -> when (event) {
                is OverlayEvent.DismissOverlay -> OverlayState.IDLE
                is OverlayEvent.TriggerClick -> OverlayState.SCANNING
                is OverlayEvent.ErrorOccurred -> {
                    lastErrorMessage = event.message
                    OverlayState.ERROR
                }
                else -> null
            }
            OverlayState.ERROR -> when (event) {
                is OverlayEvent.AutoRecoverTimerExpired,
                is OverlayEvent.DismissOverlay,
                is OverlayEvent.TriggerClick -> {
                    lastErrorMessage = null
                    OverlayState.IDLE
                }
                else -> null
            }
        }

        return if (nextState != null && nextState != currentState) {
            currentState = nextState
            onStateChanged?.invoke(currentState)
            true
        } else {
            false
        }
    }
}

// --- 4. Window LayoutParams & Geometry Calculator ---
object OverlayLayoutCalculator {
    const val TYPE_APPLICATION_OVERLAY = 2038
    const val FLAG_NOT_FOCUSABLE = 8
    const val FLAG_NOT_TOUCH_MODAL = 32
    const val FLAG_LAYOUT_NO_LIMITS = 512
    const val FLAG_WATCH_OUTSIDE_TOUCH = 262144
    const val PIXEL_FORMAT_TRANSLUCENT = -3

    data class WindowConfig(
        val type: Int,
        val flags: Int,
        val format: Int
    )

    data class Point(val x: Int, val y: Int)
    data class Dimensions(val width: Int, val height: Int)

    fun getBubbleWindowConfig(): WindowConfig {
        return WindowConfig(
            type = TYPE_APPLICATION_OVERLAY,
            flags = FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
            format = PIXEL_FORMAT_TRANSLUCENT
        )
    }

    fun getHudWindowConfig(): WindowConfig {
        return WindowConfig(
            type = TYPE_APPLICATION_OVERLAY,
            flags = FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH,
            format = PIXEL_FORMAT_TRANSLUCENT
        )
    }

    fun getFullscreenOverlayConfig(): WindowConfig {
        return WindowConfig(
            type = TYPE_APPLICATION_OVERLAY,
            flags = FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,
            format = PIXEL_FORMAT_TRANSLUCENT
        )
    }

    fun computeDockedPosition(
        currentX: Int,
        currentY: Int,
        buttonSize: Int,
        margin: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Point {
        val centerX = currentX + buttonSize / 2
        val targetX = if (centerX < screenWidth / 2) {
            margin
        } else {
            screenWidth - buttonSize - margin
        }
        val targetY = currentY.coerceIn(margin, screenHeight - buttonSize - margin)
        return Point(targetX, targetY)
    }

    fun scalePositionOnOrientationChange(
        oldPos: Point,
        oldScreen: Dimensions,
        newScreen: Dimensions,
        buttonSize: Int,
        margin: Int
    ): Point {
        val isLeftDocked = (oldPos.x + buttonSize / 2) < (oldScreen.width / 2)
        val targetX = if (isLeftDocked) margin else (newScreen.width - buttonSize - margin)

        val oldSpan = (oldScreen.height - buttonSize - 2 * margin).coerceAtLeast(1)
        val ratioY = ((oldPos.y - margin).toFloat() / oldSpan).coerceIn(0f, 1f)
        val newSpan = (newScreen.height - buttonSize - 2 * margin).coerceAtLeast(1)
        val targetY = (margin + ratioY * newSpan).toInt().coerceIn(margin, newScreen.height - buttonSize - margin)

        return Point(targetX, targetY)
    }

    fun computeHudPosition(
        buttonPos: Point,
        buttonSize: Int,
        hudWidth: Int,
        hudHeight: Int,
        margin: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Point {
        val isLeftDocked = buttonPos.x < screenWidth / 2
        val targetX = if (isLeftDocked) {
            (buttonPos.x + buttonSize + margin).coerceIn(margin, screenWidth - hudWidth - margin)
        } else {
            (buttonPos.x - hudWidth - margin).coerceIn(margin, screenWidth - hudWidth - margin)
        }
        val targetY = buttonPos.y.coerceIn(margin, screenHeight - hudHeight - margin)
        return Point(targetX, targetY)
    }
}

// --- 5. Drag-to-Dismiss & Magnetic Trash Zone ---
enum class DragDismissState {
    DRAGGING,
    MAGNETIC_HOVER
}

sealed interface DragDismissAction {
    data object DISMISS_SERVICE : DragDismissAction
    data class DOCK_TO_EDGE(val side: DockSide, val targetX: Float, val targetY: Float) : DragDismissAction

    enum class DockSide {
        LEFT,
        RIGHT
    }
}

data class DragEvaluationResult(
    val state: DragDismissState,
    val isInsideMagneticZone: Boolean,
    val shouldTriggerHaptic: Boolean,
    val trashScaleFactor: Float,
    val snapTargetX: Float,
    val snapTargetY: Float,
    val distanceToTrash: Float
)

data class TargetPoint(val x: Float, val y: Float)

class DragToDismissCalculator(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val density: Float,
    private val bubbleSizePx: Int,
    private val trashRadiusDp: Float = 40f,
    private val bottomMarginDp: Float = 48f,
    private val magneticThresholdDp: Float = 90f
) {
    val trashCenter: TargetPoint
        get() = TargetPoint(
            x = screenWidth / 2f,
            y = screenHeight - (bottomMarginDp * density)
        )

    private val magneticThresholdPx = magneticThresholdDp * density
    private var isCurrentlyHovered = false
    private var lastEvaluatedBubbleCenter = TargetPoint(0f, 0f)

    fun evaluateDrag(bubbleCenterX: Float, bubbleCenterY: Float): DragEvaluationResult {
        lastEvaluatedBubbleCenter = TargetPoint(bubbleCenterX, bubbleCenterY)
        val trash = trashCenter
        val dx = bubbleCenterX - trash.x
        val dy = bubbleCenterY - trash.y
        val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

        val isInside = distance <= magneticThresholdPx
        val shouldHaptic = isInside && !isCurrentlyHovered
        isCurrentlyHovered = isInside

        val state = if (isInside) DragDismissState.MAGNETIC_HOVER else DragDismissState.DRAGGING
        val scaleFactor = if (isInside) {
            1.0f + ((1.0f - (distance / magneticThresholdPx).coerceIn(0f, 1f)) * 0.35f)
        } else {
            1.0f
        }

        return DragEvaluationResult(
            state = state,
            isInsideMagneticZone = isInside,
            shouldTriggerHaptic = shouldHaptic,
            trashScaleFactor = scaleFactor,
            snapTargetX = trash.x,
            snapTargetY = trash.y,
            distanceToTrash = distance
        )
    }

    fun onRelease(): DragDismissAction {
        if (isCurrentlyHovered) {
            return DragDismissAction.DISMISS_SERVICE
        }

        val margin = 16f * density
        val isLeft = lastEvaluatedBubbleCenter.x < (screenWidth / 2f)
        val targetX = if (isLeft) margin else (screenWidth - bubbleSizePx - margin)
        val targetY = (lastEvaluatedBubbleCenter.y - (bubbleSizePx / 2f)).coerceIn(
            margin,
            screenHeight - bubbleSizePx - margin
        )

        return DragDismissAction.DOCK_TO_EDGE(
            side = if (isLeft) DragDismissAction.DockSide.LEFT else DragDismissAction.DockSide.RIGHT,
            targetX = targetX,
            targetY = targetY
        )
    }

    fun reset() {
        isCurrentlyHovered = false
    }
}

// --- 6. Predictive Back Gesture & AR Overlay Window Flags ---
enum class ArOverlayBackState {
    IDLE,
    SCANNING,
    DISPLAYING_AR
}

data class WindowFlagsConfig(
    val isNotFocusable: Boolean,
    val isNotTouchModal: Boolean,
    val isWatchOutsideTouch: Boolean,
    val isBackInterceptionActive: Boolean
)

object ArOverlayWindowFlags {
    fun computeFlagsForState(state: ArOverlayBackState): WindowFlagsConfig {
        return when (state) {
            ArOverlayBackState.IDLE -> WindowFlagsConfig(
                isNotFocusable = true,
                isNotTouchModal = false,
                isWatchOutsideTouch = false,
                isBackInterceptionActive = false
            )
            ArOverlayBackState.SCANNING -> WindowFlagsConfig(
                isNotFocusable = true,
                isNotTouchModal = false,
                isWatchOutsideTouch = false,
                isBackInterceptionActive = false
            )
            ArOverlayBackState.DISPLAYING_AR -> WindowFlagsConfig(
                isNotFocusable = false,
                isNotTouchModal = true,
                isWatchOutsideTouch = true,
                isBackInterceptionActive = true
            )
        }
    }
}

class ArOverlayBackController {
    var currentState: ArOverlayBackState = ArOverlayBackState.IDLE
        private set

    fun setState(state: ArOverlayBackState) {
        currentState = state
    }

    fun onBackInvoked(onCancelJob: () -> Unit, onDismissOverlay: () -> Unit): Boolean {
        return when (currentState) {
            ArOverlayBackState.SCANNING -> {
                currentState = ArOverlayBackState.IDLE
                onCancelJob()
                true
            }
            ArOverlayBackState.DISPLAYING_AR -> {
                currentState = ArOverlayBackState.IDLE
                onDismissOverlay()
                true
            }
            ArOverlayBackState.IDLE -> {
                false
            }
        }
    }

    fun onOutsideTouch(onDismissOverlay: () -> Unit): Boolean {
        return if (currentState == ArOverlayBackState.DISPLAYING_AR) {
            currentState = ArOverlayBackState.IDLE
            onDismissOverlay()
            true
        } else {
            false
        }
    }
}

// --- 7. Material 3 Expressive FAB Menu Models & Layout Calculator ---
enum class FabMenuAction(val actionId: String) {
    TRANSLATE_SCREEN("action_translate_screen"),
    VISION_AI_ANALYZE("action_vision_ai_analyze"),
    SELECT_LANGUAGE("action_select_language"),
    SAVE_SCREENSHOT("action_save_screenshot"),
    CLOSE_SERVICE("action_close_service")
}

data class FabMenuPlacement(
    val menuX: Int,
    val menuY: Int,
    val menuWidthPx: Int,
    val direction: ExpandDirection
) {
    enum class ExpandDirection {
        EXPAND_RIGHT,
        EXPAND_LEFT
    }
}

class FabMenuLayoutCalculator(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val density: Float,
    private val bubbleSizePx: Int,
    private val menuWidthDp: Float = 260f,
    private val marginDp: Float = 12f
) {
    private val menuWidthPx = (menuWidthDp * density).toInt()
    private val marginPx = (marginDp * density).toInt()
    private val gapPx = (8f * density).toInt()

    fun computePlacement(bubbleX: Int, bubbleY: Int): FabMenuPlacement {
        val bubbleCenterX = bubbleX + (bubbleSizePx / 2)
        val isLeftDocked = bubbleCenterX < (screenWidth / 2)

        val direction = if (isLeftDocked) {
            FabMenuPlacement.ExpandDirection.EXPAND_RIGHT
        } else {
            FabMenuPlacement.ExpandDirection.EXPAND_LEFT
        }

        val menuX = if (isLeftDocked) {
            (bubbleX + bubbleSizePx + gapPx).coerceIn(
                marginPx,
                screenWidth - menuWidthPx - marginPx
            )
        } else {
            (bubbleX - menuWidthPx - gapPx).coerceIn(
                marginPx,
                screenWidth - menuWidthPx - marginPx
            )
        }

        val topLimit = (24f * density).toInt()
        val estimatedMenuHeight = (320f * density).toInt()
        val bottomLimit = screenHeight - estimatedMenuHeight - (24f * density).toInt()
        val menuY = bubbleY.coerceIn(topLimit, bottomLimit.coerceAtLeast(topLimit))

        return FabMenuPlacement(
            menuX = menuX,
            menuY = menuY,
            menuWidthPx = menuWidthPx,
            direction = direction
        )
    }
}




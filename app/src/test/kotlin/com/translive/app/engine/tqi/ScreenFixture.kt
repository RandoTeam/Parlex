package com.translive.app.engine.tqi

import android.graphics.Rect
import com.translive.app.service.accessibility.ExtractedTextNode

/**
 * Real-world screen fixture representing an application UI state for AR Translation evaluation.
 */
data class ScreenFixture(
    val id: String,
    val name: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val rawNodes: List<ExtractedTextNode>,
    val expectedPreservedIds: Set<String>,
    val expectedNoiseIds: Set<String>,
    val protectedEntities: List<String> = emptyList(),
    val isMultiColumn: Boolean = false
) {
    val screenBounds: Rect = Rect().apply {
        left = 0
        top = 0
        right = screenWidth
        bottom = screenHeight
    }
}

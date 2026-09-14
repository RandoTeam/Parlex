package com.translive.app.service.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.translive.app.service.ScreenTextNoiseFilter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ExtractedTextNode(
    val id: String,
    val text: String,
    val bounds: Rect,
    val className: String? = null,
    val packageName: String? = null,
    val isClickable: Boolean = false
)

object NativeScreenTextExtractor {

    private val ACTION_DESCRIPTION_REGEX = Regex(
        """(?i)^(button|avatar|profile picture|icon|image|navigate up|back|close|double tap to activate|selected|unselected)$"""
    )

    fun filterAndDeduplicate(nodes: List<ExtractedTextNode>, screenBounds: Rect? = null): List<ExtractedTextNode> {
        val validNodes = nodes.filter { node ->
            val t = node.text.trim()
            val w = node.bounds.right - node.bounds.left
            val h = node.bounds.bottom - node.bounds.top
            t.isNotBlank() && t.any { it.isLetterOrDigit() } && w >= 6 && h >= 6
        }

        if (validNodes.size <= 1) return validNodes

        // Sort by area ascending so smaller child widgets come first
        val sortedByArea = validNodes.sortedBy { node ->
            (node.bounds.right - node.bounds.left) * (node.bounds.bottom - node.bounds.top)
        }

        val maxContainerHeightPx = screenBounds?.let { it.height() * 0.25f } ?: 700f
        val maxContainerAreaPx = screenBounds?.let { it.width() * it.height() * 0.20f } ?: (1000f * 600f)

        val preserved = mutableListOf<ExtractedTextNode>()

        for (candidate in sortedByArea) {
            val candidateWidth = (candidate.bounds.right - candidate.bounds.left).toFloat()
            val candidateHeight = (candidate.bounds.bottom - candidate.bounds.top).toFloat()
            val candidateArea = candidateWidth * candidateHeight

            // Find all already preserved children enclosed by this candidate where candidate's text contains the child's text
            val enclosedChildren = preserved.filter { child ->
                child.bounds != candidate.bounds &&
                candidate.bounds.left <= child.bounds.left &&
                candidate.bounds.top <= child.bounds.top &&
                candidate.bounds.right >= child.bounds.right &&
                candidate.bounds.bottom >= child.bounds.bottom &&
                candidate.text.contains(child.text)
            }

            // Drop exact duplicate bounds with identical text
            val isExactDuplicate = preserved.any { existing ->
                existing.bounds == candidate.bounds && existing.text == candidate.text
            }

            if (isExactDuplicate) continue

            if (enclosedChildren.isNotEmpty()) {
                val isContainerClass = candidate.className?.let { c ->
                    c.contains("WebView", true) ||
                    c.contains("Gecko", true) ||
                    c.contains("Scroll", true) ||
                    c.contains("Recycler", true) ||
                    c.contains("List", true) ||
                    c.contains("ViewGroup", true) ||
                    c.contains("FrameLayout", true) ||
                    c.contains("RelativeLayout", true)
                } ?: false

                val childrenSpanHeight = if (enclosedChildren.size > 1) {
                    (enclosedChildren.maxOf { it.bounds.bottom } - enclosedChildren.minOf { it.bounds.top }).toFloat()
                } else 0f

                val isStructuralContainer = isContainerClass ||
                    candidateHeight > maxContainerHeightPx ||
                    candidateArea > maxContainerAreaPx ||
                    candidate.text.length > 350 ||
                    (enclosedChildren.size > 1 && childrenSpanHeight > maxContainerHeightPx * 0.7f)

                if (isStructuralContainer) {
                    // Structural containers (e.g. WebView root, ScrollView, or multi-card layout) must NEVER
                    // swallow their distinct child cards. Drop the container candidate and retain children.
                    continue
                }

                val candidateLen = candidate.text.trim().length
                val combinedChildLen = enclosedChildren.sumOf { it.text.trim().length }
                val coverageRatio = if (candidateLen > 0) combinedChildLen.toFloat() / candidateLen else 0f

                // If enclosed children represent >= 70% of the candidate's text (e.g. distinct sub-widgets like title + timestamp),
                // the candidate is a composite container and its distinct children are preferred.
                if (coverageRatio >= 0.70f) {
                    continue
                } else {
                    // Substring Coverage Inversion for single compact paragraph/headline only:
                    // Enclosed children are partial formatting runs (e.g. <b> tags) or isolated word fragments.
                    // The candidate container contains the complete sentence and broad card geometry.
                    // Suppress partial children and promote the coherent parent container.
                    preserved.removeAll(enclosedChildren.toSet())
                    preserved.add(candidate)
                }
            } else {
                preserved.add(candidate)
            }
        }

        return preserved
    }

    fun sortByReadingOrder(nodes: List<ExtractedTextNode>, yBandPx: Int = 20): List<ExtractedTextNode> {
        if (nodes.size <= 1) return nodes

        val sortedByTop = nodes.sortedWith(
            compareBy<ExtractedTextNode> { it.bounds.top }.thenBy { it.bounds.left }
        )

        val bands = mutableListOf<MutableList<ExtractedTextNode>>()

        for (node in sortedByTop) {
            val matchingBand = bands.find { band ->
                val avgTop = band.map { it.bounds.top }.average()
                val avgHeight = band.map { it.bounds.bottom - it.bounds.top }.average().coerceAtLeast(1.0)
                val tolerance = max(yBandPx.toDouble(), avgHeight * 0.45)
                abs(node.bounds.top - avgTop) <= tolerance
            }

            if (matchingBand != null) {
                matchingBand.add(node)
            } else {
                bands.add(mutableListOf(node))
            }
        }

        // Inside each horizontal band, sort strictly left-to-right
        for (band in bands) {
            band.sortBy { it.bounds.left }
        }

        return bands.flatten()
    }

    fun extract(
        windows: List<AccessibilityWindowInfo>?,
        rootInActiveWindow: AccessibilityNodeInfo?,
        currentPackageName: String,
        screenBounds: Rect,
        maxNodes: Int = 400
    ): List<ExtractedTextNode> {
        val rawNodes = mutableListOf<ExtractedTextNode>()

        val roots = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !windows.isNullOrEmpty()) {
            windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }.mapNotNull { it.root }
        } else {
            listOfNotNull(rootInActiveWindow)
        }

        if (roots.isEmpty()) return emptyList()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val tempRect = Rect()
        var nodeIdCounter = 0

        for (root in roots) {
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < maxNodes) {
                val node = queue.removeFirst()
                visited++

                if (!node.isVisibleToUser) continue
                if (node.isPassword) continue
                if (node.packageName == currentPackageName) continue

                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val className = node.className?.toString()

                // Distinguish real displayed text from accessibility action description
                val visualText = if (!text.isNullOrBlank()) {
                    text
                } else if (!desc.isNullOrBlank() && isAcceptableTextDescription(desc, className)) {
                    desc
                } else {
                    null
                }

                node.getBoundsInScreen(tempRect)

                val width = tempRect.right - tempRect.left
                val height = tempRect.bottom - tempRect.top
                val isValidGeometry = width >= 6 && height >= 6 && Rect.intersects(tempRect, screenBounds)
                val hasLettersOrDigits = visualText != null && visualText.any { it.isLetterOrDigit() }

                if (isValidGeometry && hasLettersOrDigits) {
                    val nodeRect = Rect(tempRect)
                    rawNodes.add(
                        ExtractedTextNode(
                            id = "a11y_node_${nodeIdCounter++}",
                            text = visualText,
                            bounds = nodeRect,
                            className = className,
                            packageName = node.packageName?.toString(),
                            isClickable = node.isClickable
                        )
                    )
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    node.getChild(i)?.let { child ->
                        queue.add(child)
                    }
                }
            }
        }

        val deduplicated = filterAndDeduplicate(rawNodes, screenBounds)
        val ordered = sortByReadingOrder(deduplicated)
        return ordered.filter { !ScreenTextNoiseFilter.isNoise(it.text) }
    }

    private fun isAcceptableTextDescription(desc: String, className: String?): Boolean {
        if (ACTION_DESCRIPTION_REGEX.matches(desc)) return false
        // Allow text descriptions for TextView-like widgets only
        return className != null && (
            className.contains("TextView") ||
            className.contains("StaticLayout") ||
            className.contains("Text")
        )
    }
}

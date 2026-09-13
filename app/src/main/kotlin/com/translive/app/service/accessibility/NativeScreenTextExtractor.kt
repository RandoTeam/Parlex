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

    fun filterAndDeduplicate(nodes: List<ExtractedTextNode>): List<ExtractedTextNode> {
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

        val preserved = mutableListOf<ExtractedTextNode>()

        for (candidate in sortedByArea) {
            // Drop candidate if it is a large container whose text is already represented by preserved child nodes
            val isParentContainer = preserved.any { child ->
                child.bounds != candidate.bounds &&
                candidate.bounds.left <= child.bounds.left &&
                candidate.bounds.top <= child.bounds.top &&
                candidate.bounds.right >= child.bounds.right &&
                candidate.bounds.bottom >= child.bounds.bottom &&
                candidate.text.contains(child.text)
            }

            // Drop exact duplicate bounds with shorter or identical text
            val isExactDuplicate = preserved.any { existing ->
                existing.bounds == candidate.bounds && existing.text == candidate.text
            }

            if (!isParentContainer && !isExactDuplicate) {
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

        val deduplicated = filterAndDeduplicate(rawNodes)
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

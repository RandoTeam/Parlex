package com.translive.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translive.app.ui.viewmodel.TranslationUiState

/**
 * Representation of the active translation mode badge.
 * Complies with Material 3 Zero-Emoji guidelines.
 */
@Immutable
sealed interface TranslationBadgeState {
    val text: String
    val isPulsing: Boolean

    data object FastNmt : TranslationBadgeState {
        override val text = "NMT"
        override val isPulsing = false
    }

    data object FastNmtImproving : TranslationBadgeState {
        override val text = "NMT (улучшение...)"
        override val isPulsing = true
    }

    data class LlmImproved(val modelTag: String = "LLM") : TranslationBadgeState {
        override val text = "$modelTag (улучшен)"
        override val isPulsing = false
    }

    data class LlmDirect(val modelTag: String = "LLM") : TranslationBadgeState {
        override val text = modelTag
        override val isPulsing = false
    }
}

/**
 * Resolves the badge state based on current TranslationUiState.
 */
fun resolveTranslationBadgeState(
    isFastResult: Boolean,
    isImprovingWithLlm: Boolean,
    fastTranslationText: String,
    activeModelName: String? = null
): TranslationBadgeState {
    return when {
        isImprovingWithLlm && fastTranslationText.isNotBlank() -> {
            TranslationBadgeState.FastNmtImproving
        }
        !isFastResult && !isImprovingWithLlm && fastTranslationText.isNotBlank() -> {
            TranslationBadgeState.LlmImproved(modelTag = "LLM")
        }
        isFastResult -> {
            TranslationBadgeState.FastNmt
        }
        else -> {
            TranslationBadgeState.LlmDirect(modelTag = activeModelName?.takeIf { it.isNotBlank() } ?: "LLM")
        }
    }
}

/**
 * Minimalist, de-cluttered translation badge placed at the top-left of the Result Card.
 * Strict Material 3 Zero-Emoji compliant.
 */
@Composable
fun TranslationModeBadge(
    uiState: TranslationUiState,
    modifier: Modifier = Modifier
) {
    val badgeState = resolveTranslationBadgeState(
        isFastResult = uiState.isFastResult,
        isImprovingWithLlm = uiState.isImprovingWithLlm,
        fastTranslationText = uiState.fastTranslationText,
        activeModelName = uiState.activeModelName
    )

    // Breathing pulse animation for in-flight multi-pass improvement
    val infiniteTransition = rememberInfiniteTransition(label = "BadgePulseTransition")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "BadgePulseAlpha"
    )

    Crossfade(
        targetState = badgeState,
        animationSpec = tween(durationMillis = 350),
        label = "BadgeCrossfade",
        modifier = modifier
    ) { state ->
        val currentAlpha = if (state.isPulsing) pulseAlpha else 1.0f

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (state.isPulsing) 0.08f else 0.05f
                    )
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = state.text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    letterSpacing = 0.3.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = 0.7f * currentAlpha
                ),
                maxLines = 1
            )
        }
    }
}

package com.translive.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.translive.app.engine.camera.LiveSubtitleUiState
import com.translive.app.engine.camera.SubtitleAction
import com.translive.app.engine.camera.SubtitleLine

@Composable
fun LiveSubtitleBanner(
    state: LiveSubtitleUiState,
    onAction: (SubtitleAction) -> Unit,
    onSpeakText: (String, String) -> Unit,
    onStopSpeech: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.isSubtitleModeActive) return

    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new subtitles arrive
    LaunchedEffect(state.subtitles.size) {
        if (state.subtitles.isNotEmpty()) {
            listState.animateScrollToItem(state.subtitles.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .zIndex(15f)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.Black.copy(alpha = state.style.backgroundOpacity),
            contentColor = Color.White,
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header Bar with Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.isPaused) Color(0xFFFFB74D) else Color(0xFF81C784))
                        )
                        Text(
                            text = if (state.isPaused) "⏸️ Субтитры (Пауза)" else "⚡ Живые Субтитры",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isPaused) Color(0xFFFFB74D) else Color(0xFF81C784)
                        )
                    }

                    // Action Icons
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Pause / Resume
                        IconButton(
                            onClick = { onAction(SubtitleAction.TogglePause) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = "Пауза",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // TTS Speak All
                        IconButton(
                            onClick = {
                                if (state.isTtsSpeaking) {
                                    onStopSpeech()
                                    onAction(SubtitleAction.ToggleTts)
                                } else {
                                    val fullText = state.subtitles.joinToString(". ") { it.translatedText }
                                    val lang = state.subtitles.firstOrNull()?.targetLanguage?.code ?: "ru"
                                    if (fullText.isNotBlank()) {
                                        onSpeakText(fullText, lang)
                                        onAction(SubtitleAction.ToggleTts)
                                    }
                                }
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                if (state.isTtsSpeaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Озвучить",
                                tint = if (state.isTtsSpeaking) Color(0xFFFF8A80) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Copy all
                        IconButton(
                            onClick = {
                                val fullText = state.subtitles.joinToString("\n") { item ->
                                    if (state.style.showOriginal) "${item.originalText} -> ${item.translatedText}"
                                    else item.translatedText
                                }
                                clipboard.setText(AnnotatedString(fullText))
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Копировать все",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Font size cycle
                        IconButton(
                            onClick = { onAction(SubtitleAction.CycleFontSize) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Text(
                                text = "${state.style.fontSizeSp}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF64B5F6)
                            )
                        }

                        // Close
                        IconButton(
                            onClick = { onAction(SubtitleAction.ToggleSubtitleMode) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Закрыть",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = Color.White.copy(alpha = 0.2f))

                // Subtitle Lines List
                if (state.subtitles.isEmpty()) {
                    Text(
                        text = "Наведите камеру на текст для отображения субтитров...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(state.subtitles, key = { it.id }) { item ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (state.style.showOriginal && item.originalText.isNotBlank()) {
                                    Text(
                                        text = item.originalText,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (state.style.fontSizeSp - 3).sp),
                                        color = Color.White.copy(alpha = 0.65f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = item.translatedText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = state.style.fontSizeSp.sp),
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE0F7FA)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

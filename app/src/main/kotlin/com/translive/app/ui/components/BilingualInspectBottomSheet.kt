package com.translive.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.translive.app.engine.camera.BilingualParagraph

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilingualInspectBottomSheet(
    paragraph: BilingualParagraph,
    isSpeaking: Boolean,
    onSpeak: (text: String, langCode: String) -> Unit,
    onStopSpeech: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Параграф / Перевод",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Original Text Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${paragraph.sourceLanguage.flag} ${paragraph.sourceLanguage.nativeName} (Оригинал)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                if (isSpeaking) onStopSpeech()
                                else onSpeak(paragraph.sourceText, paragraph.sourceLanguage.code)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isSpeaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak original",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = paragraph.sourceText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Translated Text Card
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${paragraph.targetLanguage.flag} ${paragraph.targetLanguage.nativeName} (Перевод)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                if (isSpeaking) onStopSpeech()
                                else onSpeak(paragraph.translatedText, paragraph.targetLanguage.code)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isSpeaking) Icons.Default.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Speak translation",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = paragraph.translatedText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Quick Actions Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val combined = "${paragraph.sourceText}\n\n${paragraph.translatedText}"
                        clipboard.setText(AnnotatedString(combined))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Копировать оба")
                }
                Button(
                    onClick = { clipboard.setText(AnnotatedString(paragraph.translatedText)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Копировать перевод")
                }
            }
        }
    }
}

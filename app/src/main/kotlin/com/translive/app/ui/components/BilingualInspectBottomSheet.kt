package com.translive.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.translive.app.data.model.DictionaryEntry
import com.translive.app.engine.camera.AllergenClassifier
import com.translive.app.engine.camera.BilingualParagraph
import com.translive.app.engine.camera.FoodAllergen

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BilingualInspectBottomSheet(
    paragraph: BilingualParagraph,
    isSpeaking: Boolean,
    currencyConversion: String? = null,
    dictionaryEntries: List<DictionaryEntry> = emptyList(),
    onWordClick: (String) -> Unit = {},
    onToggleFavoriteWord: (DictionaryEntry) -> Unit = {},
    onDismissDictionary: () -> Unit = {},
    onSpeak: (text: String, langCode: String) -> Unit,
    onStopSpeech: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val allergens = AllergenClassifier.detectAllergens("${paragraph.sourceText} ${paragraph.translatedText}")

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "✈️ Travel Card / Перевод",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Currency & Allergen Badges Row
            if (currencyConversion != null || allergens.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currencyConversion != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1B5E20).copy(alpha = 0.2f),
                            contentColor = Color(0xFF4CAF50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CurrencyExchange, contentDescription = null, modifier = Modifier.size(16.dp))
                                Text(
                                    text = currencyConversion,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    for (allergen in allergens) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ) {
                            Text(
                                text = "${allergen.icon} ${allergen.labelRu}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }

            // Original Text Card with Interactive Word Chips
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                    // Word tokens with instant dictionary drill-down
                    val words = paragraph.sourceText.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (words.size in 1..25) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (word in words) {
                                val cleanWord = word.trim().trim { it in ",.!?:;\"'()[]{}<>" }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    modifier = Modifier.clickable { onWordClick(cleanWord) }
                                ) {
                                    Text(
                                        text = word,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        SelectionContainer {
                            Text(
                                text = paragraph.sourceText,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Dictionary Popup if a word was tapped
            if (dictionaryEntries.isNotEmpty()) {
                DictionaryPopup(
                    entries = dictionaryEntries,
                    onSpeak = { text, lang -> onSpeak(text, lang) },
                    onToggleFavorite = { onToggleFavoriteWord(it) },
                    onDismiss = onDismissDictionary
                )
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

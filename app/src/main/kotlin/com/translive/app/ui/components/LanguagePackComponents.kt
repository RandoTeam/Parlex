package com.translive.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translive.app.data.model.*

@Composable
fun LanguagePacksHubSection(
    packs: List<LanguagePack>,
    onDownloadPack: (String) -> Unit,
    onDeletePack: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (packs.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Luggage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "Офлайн Языковые Пакеты (Travel Packs)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "NMT + Словарь + OCR + Голосовые модули в одном пакете",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            items(packs, key = { it.id }) { pack ->
                TravelPackCard(
                    pack = pack,
                    onDownload = { onDownloadPack(pack.id) },
                    onDelete = { onDeletePack(pack.id) },
                    modifier = Modifier.width(310.dp)
                )
            }
        }
    }
}

@Composable
fun TravelPackCard(
    pack: LanguagePack,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (pack.overallStatus) {
                PackOverallStatus.FULLY_INSTALLED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                PackOverallStatus.PARTIALLY_INSTALLED -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Emoji Flag + Title + Status Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = pack.flagEmoji,
                    fontSize = 22.sp,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pack.sourceLanguage.displayName} ↔ ${pack.targetLanguage.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PackStatusBadge(pack.overallStatus, pack.installPercentage)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = pack.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) 6 else 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-Component Segment Bar
            MultiSegmentProgressBar(
                components = pack.components,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            )

            // Expanded Component Breakdown
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))
                    pack.components.forEach { comp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = getComponentIcon(comp.type),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = comp.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = when (comp.status) {
                                    ComponentInstallStatus.INSTALLED -> "Готов"
                                    ComponentInstallStatus.DOWNLOADING -> "Загрузка..."
                                    ComponentInstallStatus.SYSTEM_ACTION_REQUIRED -> "Системный"
                                    else -> "Не установлен"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (comp.status) {
                                    ComponentInstallStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                                    ComponentInstallStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Footer Row: Footprint + Action Button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (pack.overallStatus) {
                        PackOverallStatus.FULLY_INSTALLED -> "Установлен (${formatBytes(pack.installedSizeBytes)})"
                        PackOverallStatus.PARTIALLY_INSTALLED -> "${formatBytes(pack.installedSizeBytes)} / ${formatBytes(pack.totalSizeBytes)}"
                        else -> "Размер: ~${formatBytes(pack.missingDownloadSizeBytes)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (pack.overallStatus) {
                        PackOverallStatus.FULLY_INSTALLED -> {
                            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "Удалить пакет",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        PackOverallStatus.DOWNLOADING -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        else -> {
                            Button(
                                onClick = onDownload,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (pack.overallStatus == PackOverallStatus.PARTIALLY_INSTALLED) "Докачать" else "Скачать",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PackStatusBadge(status: PackOverallStatus, percentage: Int) {
    val (bgColor, textColor, label) = when (status) {
        PackOverallStatus.FULLY_INSTALLED -> Triple(Color(0xFF2E7D32).copy(alpha = 0.15f), Color(0xFF2E7D32), "Готов 100%")
        PackOverallStatus.PARTIALLY_INSTALLED -> Triple(Color(0xFFE65100).copy(alpha = 0.15f), Color(0xFFE65100), "$percentage%")
        PackOverallStatus.DOWNLOADING -> Triple(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.primary, "Загрузка")
        PackOverallStatus.ACTION_REQUIRED -> Triple(Color(0xFFC62828).copy(alpha = 0.15f), Color(0xFFC62828), "Голос")
        PackOverallStatus.NOT_INSTALLED -> Triple(MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), MaterialTheme.colorScheme.outline, "Не скачан")
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MultiSegmentProgressBar(
    components: List<PackComponent>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        components.forEach { comp ->
            val color = when (comp.status) {
                ComponentInstallStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                ComponentInstallStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
                ComponentInstallStatus.SYSTEM_ACTION_REQUIRED -> MaterialTheme.colorScheme.error
                else -> Color.Transparent
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 0.5.dp)
                    .background(color)
            )
        }
    }
}

private fun getComponentIcon(type: PackComponentType) = when (type) {
    PackComponentType.NMT_TRANSLATE -> Icons.Default.Translate
    PackComponentType.DICTIONARY_DB -> Icons.Default.MenuBook
    PackComponentType.OCR_ASSETS -> Icons.Default.DocumentScanner
    PackComponentType.SPEECH_TTS_VOICE -> Icons.Default.VolumeUp
    PackComponentType.SPEECH_STT_MODEL -> Icons.Default.Mic
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}

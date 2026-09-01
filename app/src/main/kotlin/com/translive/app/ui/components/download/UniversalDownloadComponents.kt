package com.translive.app.ui.components.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.translive.app.engine.download.DownloadFormatter
import com.translive.app.engine.download.UniversalDownloadState

@Composable
fun CircularDownloadButton(
    state: UniversalDownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    strokeWidth: Dp = 2.5.dp
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size)
    ) {
        when (state) {
            is UniversalDownloadState.Downloading -> {
                val animatedProgress by animateFloatAsState(
                    targetValue = state.progress,
                    label = "circularProgress"
                )
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = strokeWidth,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
                IconButton(onClick = onPause, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Pause,
                        contentDescription = "Pause download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(size * 0.5f)
                    )
                }
            }
            is UniversalDownloadState.Processing -> {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = strokeWidth,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Icon(
                    Icons.Default.Sync,
                    contentDescription = state.stage,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
            is UniversalDownloadState.Paused -> {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = strokeWidth,
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                )
                IconButton(onClick = onResume, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Resume download",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(size * 0.5f)
                    )
                }
            }
            is UniversalDownloadState.Completed -> {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxSize()
                ) {
                    IconButton(onClick = onSelect, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(size * 0.6f)
                        )
                    }
                }
            }
            is UniversalDownloadState.Failed -> {
                IconButton(onClick = onDownload, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Retry download",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(size * 0.6f)
                    )
                }
            }
            UniversalDownloadState.Idle, UniversalDownloadState.Cancelled -> {
                FilledTonalIconButton(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Start download",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(size * 0.55f)
                    )
                }
            }
            UniversalDownloadState.Queued, UniversalDownloadState.Connecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = strokeWidth,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun DownloadProgressRow(
    isoTag: String,
    title: String,
    totalSizeBytes: Long,
    state: UniversalDownloadState,
    isDownloaded: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when {
                    isDownloaded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    state is UniversalDownloadState.Downloading -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.width(42.dp)
            ) {
                Text(
                    text = isoTag.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isDownloaded -> MaterialTheme.colorScheme.primary
                        state is UniversalDownloadState.Downloading -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(vertical = 3.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (state) {
                        is UniversalDownloadState.Downloading -> DownloadFormatter.formatProgressSnippet(
                            state.bytesDownloaded, state.totalBytes, state.speedBytesPerSec, state.etaSeconds
                        )
                        is UniversalDownloadState.Processing -> "${state.stage} • ${(state.progress * 100).toInt()}%"
                        is UniversalDownloadState.Paused -> "Paused • ${DownloadFormatter.formatBytes(state.bytesDownloaded)} / ${DownloadFormatter.formatBytes(state.totalBytes)}"
                        is UniversalDownloadState.Failed -> "Error: ${state.error}"
                        else -> DownloadFormatter.formatBytes(totalSizeBytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UniversalDownloadState.Failed) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))

            if (isDownloaded && state !is UniversalDownloadState.Downloading) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                CircularDownloadButton(
                    state = state,
                    onDownload = onDownload,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    size = 32.dp
                )
            }
        }

        AnimatedVisibility(visible = state is UniversalDownloadState.Downloading) {
            val dlState = state as? UniversalDownloadState.Downloading
            if (dlState != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { dlState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
fun UniversalDownloadCard(
    title: String,
    description: String,
    icon: ImageVector,
    downloadSizeBytes: Long,
    ramEstimateMb: Int? = null,
    backendBadge: String? = null,
    isDownloaded: Boolean,
    isSelected: Boolean = false,
    downloadState: UniversalDownloadState,
    onDownload: () -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onCancel: () -> Unit = {},
    onSelect: () -> Unit = {},
    onDelete: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                isDownloaded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                backendBadge?.let {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (isDownloaded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (isSelected) "Active" else "Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.FolderZip, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = DownloadFormatter.formatBytes(downloadSizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                ramEstimateMb?.let { ram ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "$ram MB RAM",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (downloadState is UniversalDownloadState.Downloading) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { downloadState.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${downloadState.progressPercent}% • ${DownloadFormatter.formatBytes(downloadState.bytesDownloaded)} / ${DownloadFormatter.formatBytes(downloadState.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (downloadState.speedBytesPerSec > 0) {
                        Text(
                            text = "${DownloadFormatter.formatSpeed(downloadState.speedBytesPerSec)} • ${DownloadFormatter.formatEta(downloadState.etaSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (downloadState is UniversalDownloadState.Processing) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = downloadState.stage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    downloadState is UniversalDownloadState.Downloading -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, "Pause", tint = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                    downloadState is UniversalDownloadState.Paused -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("Discard", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Resume")
                        }
                    }
                    isDownloaded -> {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                        if (!isSelected) {
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = onSelect) {
                                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Select")
                            }
                        }
                    }
                    else -> {
                        FilledTonalButton(onClick = onDownload) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

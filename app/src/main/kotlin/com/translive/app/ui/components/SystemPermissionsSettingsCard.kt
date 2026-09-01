package com.translive.app.ui.components

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.translive.app.R
import com.translive.app.ui.permissions.AccessibilityUiStatus
import com.translive.app.ui.permissions.SystemPermissionManager
import com.translive.app.ui.permissions.SystemPermissionsState

@Composable
fun SystemPermissionsSettingsCard(
    permissionsState: SystemPermissionsState,
    onRefreshPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        onRefreshPermissions()
    }

    val assistantRoleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        onRefreshPermissions()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // --- 1. Accessibility Service Card ---
        PermissionItemCard(
            icon = Icons.Outlined.AccessibilityNew,
            title = stringResource(R.string.settings_a11y_service_title),
            description = stringResource(R.string.settings_a11y_service_desc),
            statusBadge = {
                when (permissionsState.accessibilityStatus) {
                    AccessibilityUiStatus.ACTIVE -> {
                        StatusBadge(
                            text = stringResource(R.string.settings_a11y_status_active),
                            isSuccess = true
                        )
                    }
                    AccessibilityUiStatus.CONFIGURED_NOT_BOUND -> {
                        StatusBadge(
                            text = stringResource(R.string.settings_a11y_status_configured),
                            isSuccess = false,
                            isWarning = true
                        )
                    }
                    AccessibilityUiStatus.DISABLED -> {
                        StatusBadge(
                            text = stringResource(R.string.settings_a11y_status_disabled),
                            isSuccess = false
                        )
                    }
                }
            },
            actionButton = if (!permissionsState.isAccessibilityConnected) {
                {
                    Button(
                        onClick = { SystemPermissionManager.openAccessibilitySettings(context) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_a11y_btn_configure), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else null,
            expandableGuide = {
                var isExpanded by remember { mutableStateOf(!permissionsState.isAccessibilityConnected) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(10.dp)
                        .animateContentSize()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.HelpOutline,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.settings_a11y_guide_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_a11y_guide_step1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_a11y_guide_step2),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_a11y_guide_step3),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_a11y_guide_step4),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { SystemPermissionManager.openAppDetailsSettings(context) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.settings_a11y_btn_app_info), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        )

        // --- 2. Default Digital Assistant Card ---
        PermissionItemCard(
            icon = Icons.Outlined.Assistant,
            title = stringResource(R.string.settings_assistant_title),
            description = stringResource(R.string.settings_assistant_desc),
            statusBadge = {
                StatusBadge(
                    text = if (permissionsState.isAssistantRoleHeld) {
                        stringResource(R.string.settings_assistant_status_held)
                    } else {
                        stringResource(R.string.settings_assistant_status_not_held)
                    },
                    isSuccess = permissionsState.isAssistantRoleHeld
                )
            },
            actionButton = if (!permissionsState.isAssistantRoleHeld) {
                {
                    Button(
                        onClick = {
                            val roleIntent = SystemPermissionManager.createAssistantRoleRequestIntent(context)
                            if (roleIntent != null) {
                                assistantRoleLauncher.launch(roleIntent)
                            } else {
                                SystemPermissionManager.openAssistantSettingsFallback(context)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Assistant, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_assistant_btn_set), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else null
        )

        // --- 3. Overlay (SYSTEM_ALERT_WINDOW) Card ---
        PermissionItemCard(
            icon = Icons.Outlined.Layers,
            title = stringResource(R.string.settings_overlay_title),
            description = stringResource(R.string.settings_overlay_desc),
            statusBadge = {
                StatusBadge(
                    text = if (permissionsState.isOverlayGranted) {
                        stringResource(R.string.settings_overlay_status_granted)
                    } else {
                        stringResource(R.string.settings_overlay_status_required)
                    },
                    isSuccess = permissionsState.isOverlayGranted
                )
            },
            actionButton = if (!permissionsState.isOverlayGranted) {
                {
                    Button(
                        onClick = { SystemPermissionManager.openOverlaySettings(context) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_overlay_btn_grant), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else null
        )

        // --- 4. Notifications Card ---
        PermissionItemCard(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_notifications_title),
            description = stringResource(R.string.settings_notifications_desc),
            statusBadge = {
                StatusBadge(
                    text = if (permissionsState.isNotificationGranted) {
                        stringResource(R.string.settings_notifications_status_enabled)
                    } else {
                        stringResource(R.string.settings_notifications_status_disabled)
                    },
                    isSuccess = permissionsState.isNotificationGranted
                )
            },
            actionButton = if (!permissionsState.isNotificationGranted) {
                {
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                SystemPermissionManager.openNotificationSettings(context)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_notifications_btn_enable), style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else null
        )
    }
}

@Composable
private fun PermissionItemCard(
    icon: ImageVector,
    title: String,
    description: String,
    statusBadge: @Composable () -> Unit,
    actionButton: (@Composable () -> Unit)? = null,
    expandableGuide: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                statusBadge()
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            if (expandableGuide != null) {
                expandableGuide()
            }

            if (actionButton != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    actionButton()
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    isSuccess: Boolean,
    isWarning: Boolean = false
) {
    val backgroundColor = when {
        isSuccess -> Color(0xFF1B5E20).copy(alpha = 0.15f)
        isWarning -> Color(0xFFE65100).copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
    }
    val contentColor = when {
        isSuccess -> Color(0xFF2E7D32)
        isWarning -> Color(0xFFEF6C00)
        else -> MaterialTheme.colorScheme.error
    }
    val borderColor = contentColor.copy(alpha = 0.3f)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Icon(
            imageVector = when {
                isSuccess -> Icons.Default.CheckCircle
                isWarning -> Icons.Default.HourglassEmpty
                else -> Icons.Default.ErrorOutline
            },
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor
        )
    }
}

package com.translive.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.i18n.AppLocale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.SystemPermissionsSettingsCard
import com.translive.app.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val timeoutMinutes by viewModel.idleTimeout.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val hideKeyboardOnTextTranslate by viewModel.hideKeyboardOnTextTranslate.collectAsState()
    val showTechnicalTranslationStats by viewModel.showTechnicalTranslationStats.collectAsState()
    val showTransliteration by viewModel.showTransliteration.collectAsState()
    val translationPolicy by viewModel.translationPolicy.collectAsState()
    val overlayStyle by viewModel.overlayStyle.collectAsState()
    val homeCurrency by viewModel.homeCurrency.collectAsState()
    val enableCurrencyConversion by viewModel.enableCurrencyConversion.collectAsState()
    val currencySyncPolicy by viewModel.currencySyncPolicy.collectAsState()
    val currencyLastUpdated by viewModel.currencyLastUpdated.collectAsState()
    val isCurrencyRefreshing by viewModel.isCurrencyRefreshing.collectAsState()
    val dialogueAutoSpeak by viewModel.dialogueAutoSpeak.collectAsState()
    val dialogueRecordingEnabled by viewModel.dialogueRecordingEnabled.collectAsState()
    val dialogueAudioFormat by viewModel.dialogueAudioFormat.collectAsState()
    val dialogueStorageStats by viewModel.dialogueStorageStats.collectAsState()
    val screenSyncTargetWithMain by viewModel.screenSyncTargetWithMain.collectAsState()
    val screenTargetLanguage by viewModel.screenTargetLanguage.collectAsState()
    val screenA11yShortcutBehavior by viewModel.screenA11yShortcutBehavior.collectAsState()
    val runtimeDiagnostics by viewModel.runtimeDiagnostics.collectAsState()
    val systemPermissionsState by viewModel.systemPermissionsState.collectAsState()
    var showClearStorageDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemPermissions()
                viewModel.refreshDialogueStorageStats()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.SETTINGS,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = onNavigateToDialogue,
                onNavigateToCamera = onNavigateToCamera,
                onNavigateToHistory = onNavigateToHistory,
                onNavigateToModels = onNavigateToModels,
                onNavigateToSettings = {}
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- System Integration & Permissions Section ---
            SectionHeader(
                icon = Icons.Outlined.AdminPanelSettings,
                title = stringResource(R.string.settings_system_permissions_section)
            )
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SystemPermissionsSettingsCard(
                    permissionsState = systemPermissionsState,
                    onRefreshPermissions = { viewModel.refreshSystemPermissions() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Screen Translation & Overlay Section ---
            SectionHeader(
                icon = Icons.Outlined.Screenshot,
                title = stringResource(R.string.settings_screen_translate_section)
            )
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_screen_shortcut_behavior_title), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_screen_shortcut_behavior_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                TranslationPolicyOption(
                    label = stringResource(R.string.settings_screen_shortcut_single_shot),
                    description = stringResource(R.string.settings_screen_shortcut_single_shot_desc),
                    selected = screenA11yShortcutBehavior == com.translive.app.data.ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE,
                    onClick = { viewModel.setScreenA11yShortcutBehavior(com.translive.app.data.ScreenA11yShortcutBehavior.SINGLE_SHOT_NO_BUBBLE) }
                )
                TranslationPolicyOption(
                    label = stringResource(R.string.settings_screen_shortcut_toggle_bubble),
                    description = stringResource(R.string.settings_screen_shortcut_toggle_bubble_desc),
                    selected = screenA11yShortcutBehavior == com.translive.app.data.ScreenA11yShortcutBehavior.TOGGLE_FLOATING_BUBBLE,
                    onClick = { viewModel.setScreenA11yShortcutBehavior(com.translive.app.data.ScreenA11yShortcutBehavior.TOGGLE_FLOATING_BUBBLE) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_screen_sync_target_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.settings_screen_sync_target_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = screenSyncTargetWithMain,
                        onCheckedChange = { viewModel.setScreenSyncTargetWithMain(it) }
                    )
                }

                if (!screenSyncTargetWithMain) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_screen_target_lang_title), style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        com.translive.app.data.model.Language.entries.forEach { lang ->
                            FilterChip(
                                selected = screenTargetLanguage == lang,
                                onClick = { viewModel.setScreenTargetLanguage(lang) },
                                label = { Text(lang.displayName, style = MaterialTheme.typography.bodySmall) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Language section ---
            SectionHeader(icon = Icons.Outlined.Language, title = stringResource(R.string.settings_language_section))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_app_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                AppLanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    selected = appLanguage == AppLocale.SYSTEM,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.SYSTEM)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_english),
                    selected = appLanguage == AppLocale.ENGLISH,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.ENGLISH)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_russian),
                    selected = appLanguage == AppLocale.RUSSIAN,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.RUSSIAN)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_zh_cn),
                    selected = appLanguage == AppLocale.CHINESE_SIMPLIFIED,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.CHINESE_SIMPLIFIED)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_zh_tw),
                    selected = appLanguage == AppLocale.CHINESE_TRADITIONAL,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.CHINESE_TRADITIONAL)
                    }
                )
            }

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Режим перевода", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Быстрый результат через компактный офлайн-пакет или перевод сразу локальной LLM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TranslationPolicyOption(
                    label = "Быстрый",
                    description = "Только быстрый офлайн-перевод",
                    selected = translationPolicy == TranslationPolicy.FAST,
                    onClick = { viewModel.setTranslationPolicy(TranslationPolicy.FAST) }
                )
                TranslationPolicyOption(
                    label = "Быстрый + улучшить",
                    description = "Быстрый результат и кнопка улучшения через LLM",
                    selected = translationPolicy == TranslationPolicy.FAST_WITH_LLM_IMPROVE,
                    onClick = { viewModel.setTranslationPolicy(TranslationPolicy.FAST_WITH_LLM_IMPROVE) }
                )
                TranslationPolicyOption(
                    label = "Сразу через LLM",
                    description = "Качественный перевод без промежуточного результата",
                    selected = translationPolicy == TranslationPolicy.LLM_ONLY,
                    onClick = { viewModel.setTranslationPolicy(TranslationPolicy.LLM_ONLY) }
                )
            }

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Стиль наложения", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Как отображается переведенный текст на экране.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                TranslationPolicyOption(
                    label = "Темные плашки",
                    description = "Белый текст на темном фоне поверх оригинала",
                    selected = overlayStyle == SettingsRepository.OVERLAY_STYLE_DARK,
                    onClick = { viewModel.setOverlayStyle(SettingsRepository.OVERLAY_STYLE_DARK) }
                )
                TranslationPolicyOption(
                    label = "Заливка фоном",
                    description = "Копирует цвет фона оригинального изображения",
                    selected = overlayStyle == SettingsRepository.OVERLAY_STYLE_INPAINTING,
                    onClick = { viewModel.setOverlayStyle(SettingsRepository.OVERLAY_STYLE_INPAINTING) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Compute section ---
            SectionHeader(icon = Icons.Outlined.Memory, title = stringResource(R.string.settings_compute))

            // Threads
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_cpu_threads), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_cpu_threads_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val threadOptions = SettingsRepository.THREAD_OPTIONS
                val currentIndex = threadOptions.indexOf(threads).coerceAtLeast(0)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$threads",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { viewModel.setThreads(threadOptions[it.toInt()]) },
                    valueRange = 0f..(threadOptions.size - 1).toFloat(),
                    steps = threadOptions.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${threadOptions.first()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${threadOptions.last()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Backend
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                val cpuSupported = viewModel.activeModelSupportsCpu()
                val gpuSupported = viewModel.activeModelSupportsGpu()
                val gpuRequiresOpenClBuild = viewModel.activeModelGpuRequiresOpenClBuild()
                Text(stringResource(R.string.settings_compute_backend), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_compute_backend_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                BackendOption(
                    label = "CPU",
                    description = if (cpuSupported) stringResource(R.string.settings_backend_cpu_desc)
                    else "Выбранная модель поддерживает только GPU. Для CPU выберите другую модель.",
                    selected = backend == SettingsRepository.BACKEND_CPU,
                    enabled = cpuSupported,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_CPU) }
                )
                BackendOption(
                    label = "GPU",
                    description = if (gpuSupported) stringResource(R.string.settings_backend_gpu_desc)
                    else if (gpuRequiresOpenClBuild) "GGUF поддерживает GPU через OpenCL, но этот путь ещё не добавлен в текущий APK."
                    else "Выбранная модель не поддерживает GPU.",
                    selected = backend == SettingsRepository.BACKEND_GPU,
                    enabled = gpuSupported,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_GPU) }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Возможности выбранной модели: ${viewModel.activeModelBackendLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Memory section ---
            SectionHeader(icon = Icons.Outlined.Timer, title = stringResource(R.string.settings_memory))

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_auto_unload), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_auto_unload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val options = SettingsRepository.TIMEOUT_OPTIONS
                val currentIndex = options.indexOf(timeoutMinutes).coerceAtLeast(0)
                val currentLabel = if (timeoutMinutes == 0) stringResource(R.string.settings_disabled) else stringResource(R.string.minutes_short, timeoutMinutes)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (timeoutMinutes == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { viewModel.setIdleTimeout(options[it.toInt()]) },
                    valueRange = 0f..(options.size - 1).toFloat(),
                    steps = options.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_off), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.minutes_short, options.last()), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            SectionHeader(icon = Icons.Outlined.Keyboard, title = stringResource(R.string.settings_text_behavior))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_hide_keyboard_on_translate),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_hide_keyboard_on_translate_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = hideKeyboardOnTextTranslate,
                        onCheckedChange = viewModel::setHideKeyboardOnTextTranslate
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Транслитерация (романизация)", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Показывать латинскую транслитерацию для нелатинских языков",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = showTransliteration,
                        onCheckedChange = viewModel::setShowTransliteration
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Технические данные перевода", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Время, backend и токены, если движок их сообщает",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = showTechnicalTranslationStats,
                        onCheckedChange = viewModel::setShowTechnicalTranslationStats
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Currency conversion section (Attribution, Sync & Zero-Emoji) ---
            SectionHeader(icon = Icons.Outlined.Payments, title = stringResource(R.string.settings_currency_section_title))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_currency_auto_convert), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_currency_auto_convert_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = enableCurrencyConversion,
                        onCheckedChange = viewModel::setEnableCurrencyConversion
                    )
                }

                if (enableCurrencyConversion) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    
                    // 1. Data Source Attribution Banner
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_currency_source_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.settings_currency_source_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 2. Home Currency Selector (Strict Zero-Emoji, ISO-4217 Codes & Symbols)
                    Text(stringResource(R.string.settings_currency_home_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_currency_home_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val cleanCurrencyOptions = listOf(
                        "AUTO" to stringResource(R.string.currency_code_auto_label),
                        "RUB" to "RUB (₽) — Российский рубль",
                        "USD" to "USD ($) — Доллар США",
                        "EUR" to "EUR (€) — Евро",
                        "VND" to "VND (₫) — Вьетнамский донг",
                        "CNY" to "CNY (¥) — Китайский юань",
                        "KZT" to "KZT (₸) — Казахстанский тенге",
                        "TRY" to "TRY (₺) — Турецкая лира",
                        "AED" to "AED (د.إ) — Дирхам ОАЭ",
                        "THB" to "THB (฿) — Тайский бат",
                        "GBP" to "GBP (£) — Британский фунт"
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cleanCurrencyOptions.forEach { (code, label) ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setHomeCurrency(code) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (homeCurrency == code) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = homeCurrency == code,
                                        onClick = { viewModel.setHomeCurrency(code) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 3. Sync Policy Preference
                    Text(stringResource(R.string.settings_currency_sync_policy_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_currency_sync_policy_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val syncPolicies = listOf(
                        com.translive.app.data.model.CurrencySyncPolicy.DAILY to (stringResource(R.string.settings_currency_sync_daily) to stringResource(R.string.settings_currency_sync_daily_desc)),
                        com.translive.app.data.model.CurrencySyncPolicy.ON_LAUNCH to (stringResource(R.string.settings_currency_sync_on_launch) to stringResource(R.string.settings_currency_sync_on_launch_desc)),
                        com.translive.app.data.model.CurrencySyncPolicy.MANUAL to (stringResource(R.string.settings_currency_sync_manual) to stringResource(R.string.settings_currency_sync_manual_desc))
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        syncPolicies.forEach { (policy, textPair) ->
                            val (title, desc) = textPair
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setCurrencySyncPolicy(policy) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (currencySyncPolicy == policy) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = currencySyncPolicy == policy,
                                        onClick = { viewModel.setCurrencySyncPolicy(policy) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (currencySyncPolicy == policy) FontWeight.SemiBold else FontWeight.Normal)
                                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // 4. Last Updated Status & Manual Refresh Button
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val relativeTime = com.translive.app.data.CurrencyAttributionFormatter.formatLastUpdated(
                        lastUpdatedMillis = currencyLastUpdated ?: 0L,
                        nowMillis = System.currentTimeMillis()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_currency_status_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (currencyLastUpdated == null || currencyLastUpdated == 0L) {
                                    stringResource(R.string.settings_currency_status_baseline)
                                } else {
                                    stringResource(R.string.settings_currency_status_cached, relativeTime)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = viewModel::refreshExchangeRates,
                            enabled = !isCurrencyRefreshing,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isCurrencyRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Sync,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    if (isCurrencyRefreshing) R.string.settings_currency_refreshing
                                    else R.string.settings_currency_refresh_now
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Dialogue & Voice section ---
            SectionHeader(icon = Icons.Outlined.RecordVoiceOver, title = stringResource(R.string.settings_dialogue_section))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.setDialogueAutoSpeak(!dialogueAutoSpeak) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_dialogue_auto_speak),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_dialogue_auto_speak_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = dialogueAutoSpeak,
                        onCheckedChange = viewModel::setDialogueAutoSpeak
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.setDialogueRecordingEnabled(!dialogueRecordingEnabled) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.settings_dialogue_record_audio),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.settings_dialogue_record_audio_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = dialogueRecordingEnabled,
                        onCheckedChange = viewModel::setDialogueRecordingEnabled
                    )
                }

                AnimatedVisibility(visible = dialogueRecordingEnabled) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = stringResource(R.string.settings_dialogue_audio_format),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val aacSelected = dialogueAudioFormat == com.translive.app.data.model.AudioRecordingFormat.AAC
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.setDialogueAudioFormat(com.translive.app.data.model.AudioRecordingFormat.AAC) }
                                    .border(
                                        1.dp,
                                        if (aacSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(10.dp)
                                    ),
                                color = if (aacSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "AAC (.m4a)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (aacSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.settings_dialogue_format_aac_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            val wavSelected = dialogueAudioFormat == com.translive.app.data.model.AudioRecordingFormat.WAV
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.setDialogueAudioFormat(com.translive.app.data.model.AudioRecordingFormat.WAV) }
                                    .border(
                                        1.dp,
                                        if (wavSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                        RoundedCornerShape(10.dp)
                                    ),
                                color = if (wavSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "WAV (.wav)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (wavSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = stringResource(R.string.settings_dialogue_format_wav_desc),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Storage info & cleanup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_dialogue_storage_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.settings_dialogue_storage_count_format,
                                dialogueStorageStats.fileCount,
                                dialogueStorageStats.formattedSize
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { showClearStorageDialog = true },
                        enabled = dialogueStorageStats.fileCount > 0,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (dialogueStorageStats.fileCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.settings_dialogue_btn_clear_storage),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (dialogueStorageStats.fileCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                    }
                }
            }

            if (showClearStorageDialog) {
                AlertDialog(
                    onDismissRequest = { showClearStorageDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    title = {
                        Text(text = stringResource(R.string.settings_dialogue_clear_dialog_title))
                    },
                    text = {
                        Text(
                            text = stringResource(
                                R.string.settings_dialogue_clear_dialog_desc,
                                dialogueStorageStats.fileCount,
                                dialogueStorageStats.formattedSize
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.clearAllDialogueRecordings()
                                showClearStorageDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.settings_dialogue_clear_dialog_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearStorageDialog = false }) {
                            Text(stringResource(R.string.settings_dialogue_clear_dialog_cancel))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- About App Section (v1.5.0-beta.1) ---
            SectionHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.settings_about))

            // 1. Hero Branding & Version Card
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary
                                    )
                                ),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.about_app_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = "v1.5.0-beta.1",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Build 15000",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "arm64-v8a",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 2. Offline Privacy Guarantee Banner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF004D40).copy(alpha = 0.25f)
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF1DE9B6).copy(alpha = 0.6f), Color(0xFF00B0FF).copy(alpha = 0.3f))
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = Color(0xFF1DE9B6),
                        modifier = Modifier
                            .size(24.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.about_privacy_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1DE9B6)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.about_privacy_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            // 3. Active On-Device Engines Card
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_engines_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                AboutEngineRow(stringResource(R.string.about_engine_nmt_title), "llama.cpp", stringResource(R.string.about_engine_nmt_desc))
                AboutEngineRow(stringResource(R.string.about_engine_stt_title), "Sherpa-ONNX", stringResource(R.string.about_engine_stt_desc))
                AboutEngineRow(stringResource(R.string.about_engine_fast_nmt_title), "ML Kit NMT", stringResource(R.string.about_engine_fast_nmt_desc))
                AboutEngineRow(stringResource(R.string.about_engine_ocr_title), "MNN VisionKit", stringResource(R.string.about_engine_ocr_desc))
                AboutEngineRow(stringResource(R.string.about_engine_litert_title), "LiteRT-LM", stringResource(R.string.about_engine_litert_desc))
                AboutEngineRow(stringResource(R.string.about_engine_gpu_title), "OpenCL 2.0", stringResource(R.string.about_engine_gpu_desc))
            }

            // 4. Core Capabilities Card
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.about_capabilities_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                AboutCapabilityRow(Icons.Outlined.RecordVoiceOver, stringResource(R.string.about_cap_dialogue), stringResource(R.string.about_cap_dialogue_desc))
                AboutCapabilityRow(Icons.Outlined.Layers, stringResource(R.string.about_cap_screen_ar), stringResource(R.string.about_cap_screen_ar_desc))
                AboutCapabilityRow(Icons.Outlined.Payments, stringResource(R.string.about_cap_currency), stringResource(R.string.about_cap_currency_desc))
                AboutCapabilityRow(Icons.Outlined.PictureAsPdf, stringResource(R.string.about_cap_pdf), stringResource(R.string.about_cap_pdf_desc))
                AboutCapabilityRow(Icons.Outlined.MenuBook, stringResource(R.string.about_cap_dictionaries), stringResource(R.string.about_cap_dictionaries_desc))
                AboutCapabilityRow(Icons.Outlined.GraphicEq, stringResource(R.string.about_cap_timeline), stringResource(R.string.about_cap_timeline_desc))
            }

            // 5. System Metadata & Diagnostics Card
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                InfoRow(stringResource(R.string.settings_version), "1.5.0-beta.1 (15000)")
                InfoRow("Target SDK", "Android 15 (API 36)")
                InfoRow("Architecture", "arm64-v8a (64-bit)")
                InfoRow("Text-to-Speech", stringResource(R.string.settings_tts_engine_value))

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = viewModel::runRuntimeDiagnostics,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Analytics, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.about_run_diagnostics_button))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    runtimeDiagnostics?.let { report ->
        AlertDialog(
            onDismissRequest = viewModel::clearRuntimeDiagnostics,
            title = { Text("Отчёт диагностики") },
            text = {
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::clearRuntimeDiagnostics) { Text("Закрыть") }
            }
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun applyAppLanguage(
    context: Context,
    viewModel: SettingsViewModel,
    languageCode: String
) {
    viewModel.setAppLanguage(languageCode)
    AppLocale.applyRuntimeLanguage(context, languageCode)
    context.findActivity()?.recreate()
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary


        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun AppLanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun BackendOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.4f
                )
            )
        }
    }
}

@Composable
private fun TranslationPolicyOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AboutEngineRow(
    title: String,
    runtimeBadge: String,
    description: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
            ) {
                Text(
                    text = runtimeBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AboutCapabilityRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(18.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

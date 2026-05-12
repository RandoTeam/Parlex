package com.translive.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.hardware.camera2.CameraCharacteristics
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Surface as AndroidSurface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.viewmodel.CameraMode
import com.translive.app.ui.viewmodel.CameraQualityWarning
import com.translive.app.ui.viewmodel.CameraViewModel
import com.translive.app.ui.viewmodel.CaptureStatus
import com.translive.app.ui.viewmodel.TranslatedBlock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val CAMERA_PREFS = "parlex_camera"
private const val PREF_SELECTED_CAMERA_ID = "selected_camera_id"

private data class CameraLensOption(
    val id: String,
    val selector: CameraSelector,
    val lensFacing: Int,
    val shortLabel: String,
    val contentDescription: String,
    val hasFlash: Boolean
)

private enum class CaptureFlashMode {
    OFF, AUTO, ON
}

private data class CameraLensCandidate(
    val id: String,
    val selector: CameraSelector,
    val lensFacing: Int,
    val focalLengthMm: Float?,
    val hasFlash: Boolean
)

private fun CaptureFlashMode.next(): CaptureFlashMode = when (this) {
    CaptureFlashMode.OFF -> CaptureFlashMode.AUTO
    CaptureFlashMode.AUTO -> CaptureFlashMode.ON
    CaptureFlashMode.ON -> CaptureFlashMode.OFF
}

private fun CaptureFlashMode.toImageCaptureFlashMode(): Int = when (this) {
    CaptureFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
    CaptureFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
    CaptureFlashMode.ON -> ImageCapture.FLASH_MODE_ON
}

private fun CaptureFlashMode.icon(): ImageVector = when (this) {
    CaptureFlashMode.OFF -> Icons.Filled.FlashOff
    CaptureFlashMode.AUTO -> Icons.Filled.FlashAuto
    CaptureFlashMode.ON -> Icons.Filled.FlashOn
}

private fun CaptureFlashMode.contentDescription(): String = when (this) {
    CaptureFlashMode.OFF -> "Flash off"
    CaptureFlashMode.AUTO -> "Flash auto"
    CaptureFlashMode.ON -> "Flash on"
}

private fun buildCameraLensOptions(cameraInfos: List<CameraInfo>): List<CameraLensOption> {
    val candidates = cameraInfos
        .flatMap { it.toCameraLensCandidates() }
        .distinctBy { it.id }
        .sortedWith(
            compareBy<CameraLensCandidate> { cameraLensSortOrder(it.lensFacing) }
                .thenBy { it.focalLengthMm ?: Float.MAX_VALUE }
                .thenBy { it.id }
        )

    if (candidates.isEmpty()) return emptyList()

    val backMainFocal = candidates
        .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        .mapNotNull { it.focalLengthMm }
        .minByOrNull { kotlin.math.abs(it - MAIN_CAMERA_FOCAL_LENGTH_MM) }

    val labelCounts = mutableMapOf<String, Int>()
    return candidates.mapIndexed { index, candidate ->
        val baseLabel = candidate.toCameraLabel(backMainFocal, index)
        val seen = labelCounts.getOrDefault(baseLabel, 0)
        labelCounts[baseLabel] = seen + 1
        val label = if (seen == 0) baseLabel else "$baseLabel ${seen + 1}"
        CameraLensOption(
            id = candidate.id,
            selector = candidate.selector,
            lensFacing = candidate.lensFacing,
            shortLabel = label,
            contentDescription = candidate.toCameraContentDescription(label),
            hasFlash = candidate.hasFlash
        )
    }
}

private fun preferredCameraOption(
    options: List<CameraLensOption>,
    selectedCameraId: String?
): CameraLensOption? =
    options.firstOrNull { it.id == selectedCameraId }
        ?: options.firstOrNull {
            it.lensFacing == CameraSelector.LENS_FACING_BACK && it.shortLabel == "1x"
        }
        ?: options.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        ?: options.firstOrNull()

private fun CameraInfo.toCameraLensCandidates(): List<CameraLensCandidate> {
    val camera2Info = runCatching { Camera2CameraInfo.from(this) }.getOrNull() ?: return emptyList()
    val cameraId = runCatching { camera2Info.cameraId }.getOrNull() ?: return emptyList()
    val lensFacing = runCatching { this.lensFacing }.getOrDefault(CameraSelector.LENS_FACING_UNKNOWN)
    val hasFlash = runCatching { hasFlashUnit() }.getOrDefault(false)
    val logicalCandidate = CameraLensCandidate(
        id = "logical:$cameraId",
        selector = cameraSelectorForCameraId(cameraId),
        lensFacing = lensFacing,
        focalLengthMm = camera2Info.focalLengthMm(),
        hasFlash = hasFlash
    )
    val physicalCandidates = runCatching { physicalCameraInfos }
        .getOrDefault(emptySet())
        .takeIf { it.size > 1 }
        ?.mapNotNull { physicalInfo ->
            val physicalCamera2Info = runCatching { Camera2CameraInfo.from(physicalInfo) }
                .getOrNull()
                ?: return@mapNotNull null
            val physicalCameraId = runCatching { physicalCamera2Info.cameraId }
                .getOrNull()
                ?: return@mapNotNull null
            CameraLensCandidate(
                id = "physical:$cameraId:$physicalCameraId",
                selector = cameraSelectorForPhysicalCameraId(cameraId, physicalCameraId),
                lensFacing = lensFacing,
                focalLengthMm = physicalCamera2Info.focalLengthMm(),
                hasFlash = hasFlash
            )
        }
        .orEmpty()

    return physicalCandidates.ifEmpty { listOf(logicalCandidate) }
}

private fun cameraSelectorForCameraId(cameraId: String): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            cameraInfos.filter { cameraInfo ->
                runCatching { Camera2CameraInfo.from(cameraInfo).cameraId == cameraId }
                    .getOrDefault(false)
            }
        }
        .build()

private fun cameraSelectorForPhysicalCameraId(
    logicalCameraId: String,
    physicalCameraId: String
): CameraSelector =
    CameraSelector.Builder()
        .addCameraFilter { cameraInfos ->
            cameraInfos.filter { cameraInfo ->
                runCatching { Camera2CameraInfo.from(cameraInfo).cameraId == logicalCameraId }
                    .getOrDefault(false)
            }
        }
        .setPhysicalCameraId(physicalCameraId)
        .build()

private fun Camera2CameraInfo.focalLengthMm(): Float? =
    getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        ?.filter { it > 0f }
        ?.minOrNull()

private fun cameraLensSortOrder(lensFacing: Int): Int = when (lensFacing) {
    CameraSelector.LENS_FACING_BACK -> 0
    CameraSelector.LENS_FACING_FRONT -> 1
    else -> 2
}

private fun CameraLensCandidate.toCameraLabel(backMainFocal: Float?, index: Int): String =
    when (lensFacing) {
        CameraSelector.LENS_FACING_BACK -> {
            val ratio = if (focalLengthMm != null && backMainFocal != null && backMainFocal > 0f) {
                focalLengthMm / backMainFocal
            } else {
                null
            }
            when {
                ratio == null -> "Back ${index + 1}"
                ratio < 0.72f -> "0.5x"
                ratio < 0.9f -> "0.8x"
                ratio < 1.35f -> "1x"
                ratio < 1.75f -> "1.5x"
                ratio < 2.5f -> "2x"
                ratio < 3.5f -> "3x"
                else -> "${formatZoomRatio(ratio)}x"
            }
        }
        CameraSelector.LENS_FACING_FRONT -> "Front"
        else -> "Cam ${index + 1}"
    }

private fun CameraLensCandidate.toCameraContentDescription(label: String): String =
    when (lensFacing) {
        CameraSelector.LENS_FACING_BACK -> "Use back camera $label"
        CameraSelector.LENS_FACING_FRONT -> "Use front camera"
        else -> "Use camera $label"
    }

private fun formatZoomRatio(value: Float): String {
    val rounded = kotlin.math.round(value * 10f) / 10f
    return if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private const val MAIN_CAMERA_FOCAL_LENGTH_MM = 4.2f

@OptIn(ExperimentalMaterial3Api::class)
@androidx.camera.core.ExperimentalGetImage
@Composable
fun CameraScreen(
    onNavigateToTranslate: () -> Unit = {},
    onNavigateToDialogue: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: CameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val systemTts = viewModel.systemTts
    val isSpeaking by systemTts.isSpeaking.collectAsState()
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val cameraPrefs = remember(context) {
        context.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE)
    }

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var cameraOptions by remember { mutableStateOf<List<CameraLensOption>>(emptyList()) }
    var selectedCameraId by rememberSaveable {
        mutableStateOf(cameraPrefs.getString(PREF_SELECTED_CAMERA_ID, null))
    }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var flashModeName by rememberSaveable { mutableStateOf(CaptureFlashMode.OFF.name) }
    var selectedLiveBlock by remember { mutableStateOf<TranslatedBlock?>(null) }
    val captureFlashMode = remember(flashModeName) {
        runCatching { CaptureFlashMode.valueOf(flashModeName) }
            .getOrDefault(CaptureFlashMode.OFF)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPermissionGranted(granted) }

    LaunchedEffect(Unit) {
        systemTts.initialize()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.setPermissionGranted(true)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var captureRequest by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(cameraOptions, selectedCameraId) {
        val selectedOption = cameraOptions.firstOrNull { it.id == selectedCameraId }
        if (selectedOption != null && !selectedOption.hasFlash) {
            torchEnabled = false
            flashModeName = CaptureFlashMode.OFF.name
        }
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(850)
            focusPoint = null
        }
    }

    LaunchedEffect(uiState.mode, uiState.liveBlocks) {
        if (uiState.mode != CameraMode.LIVE) {
            selectedLiveBlock = null
            return@LaunchedEffect
        }
        val selected = selectedLiveBlock ?: return@LaunchedEffect
        val stillVisible = uiState.liveBlocks.any { it.originalText == selected.originalText }
        if (!stillVisible) selectedLiveBlock = null
    }

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.CAMERA,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = onNavigateToDialogue,
                onNavigateToCamera = {},
                onNavigateToHistory = onNavigateToHistory,
                onNavigateToModels = onNavigateToModels,
                onNavigateToSettings = onNavigateToSettings
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (!uiState.hasCameraPermission) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.CameraAlt, null, Modifier.size(64.dp), tint = Color.White.copy(0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("Доступ к камере не разрешён", color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Разрешить")
                    }
                }
            } else {
                when (uiState.mode) {
                    CameraMode.LIVE -> {
                        LiveCameraView(
                            viewModel = viewModel,
                            selectedCameraId = selectedCameraId,
                            torchEnabled = torchEnabled,
                            captureFlashMode = captureFlashMode,
                            onCaptureReady = { captureRequest = it },
                            onCameraOptionsChanged = { options, resolvedCameraId ->
                                cameraOptions = options
                                if (resolvedCameraId != null && resolvedCameraId != selectedCameraId) {
                                    selectedCameraId = resolvedCameraId
                                    cameraPrefs.edit()
                                        .putString(PREF_SELECTED_CAMERA_ID, resolvedCameraId)
                                        .apply()
                                }
                            },
                            onFocusPoint = { focusPoint = it }
                        )

                        CameraBetaBadge()
                        CameraHardwareControls(
                            cameraOptions = cameraOptions,
                            selectedCameraId = selectedCameraId,
                            torchEnabled = torchEnabled,
                            captureFlashMode = captureFlashMode,
                            enabled = !uiState.isCaptureProcessing,
                            onCameraSelected = { cameraId ->
                                selectedCameraId = cameraId
                                torchEnabled = false
                                cameraPrefs.edit()
                                    .putString(PREF_SELECTED_CAMERA_ID, cameraId)
                                    .apply()
                            },
                            onTorchToggle = { torchEnabled = !torchEnabled },
                            onFlashModeChange = { flashModeName = it.name }
                        )
                        CameraQualityBadge(
                            warnings = uiState.qualityWarnings,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp, start = 12.dp, end = 12.dp)
                        )

                        // Live translation overlay
                        if (uiState.liveBlocks.isNotEmpty()) {
                            TranslationOverlay(
                                blocks = uiState.liveBlocks,
                                imageWidth = uiState.imageWidth,
                                imageHeight = uiState.imageHeight
                            )
                            LiveTextHitTargets(
                                blocks = uiState.liveBlocks,
                                imageWidth = uiState.imageWidth,
                                imageHeight = uiState.imageHeight,
                                onBlockSelected = { selectedLiveBlock = it }
                            )
                        }

                        FocusReticle(focusPoint)

                        selectedLiveBlock?.let { block ->
                            LiveTextSelectionPanel(
                                block = block,
                                sourceLanguageCode = uiState.sourceLanguage.code,
                                targetLanguageCode = uiState.targetLanguage.code,
                                isSpeaking = isSpeaking,
                                onCopy = { text ->
                                    clipboardManager.setText(AnnotatedString(text))
                                },
                                onSpeak = { text, languageCode ->
                                    if (isSpeaking) {
                                        systemTts.stop()
                                    } else {
                                        systemTts.speak(text, languageCode)
                                    }
                                },
                                onDismiss = { selectedLiveBlock = null }
                            )
                        }

                        // NMT status badge
                        if (uiState.isNmtDownloading) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Загрузка модели перевода…", color = Color.White,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // NMT error badge
                        if (uiState.nmtError != null && !uiState.isNmtDownloading) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 8.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xCCCC3333))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Warning, null, tint = Color.White,
                                        modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(uiState.nmtError ?: "", color = Color.White,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    CameraMode.CAPTURE -> {
                        CaptureImageView(
                            bitmap = uiState.paintedBitmap
                        )
                        CameraCaptureStatusBadge(
                            status = uiState.captureStatus,
                            message = uiState.captureMessage
                        )
                        CameraQualityBadge(
                            warnings = uiState.qualityWarnings,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp, start = 12.dp, end = 12.dp)
                        )
                        if (isDebugBuild) {
                            CameraDebugCaptureButton(
                                enabled = !uiState.isCaptureProcessing && uiState.capturedBitmap != null,
                                onClick = viewModel::saveDebugCapturePack
                            )
                        }
                    }
                }

                // Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (uiState.mode == CameraMode.LIVE) {
                            IconButton(
                                onClick = {
                                    captureRequest?.invoke()
                                        ?: viewModel.failFullResolutionCapture("Камера еще не готова")
                                },
                                enabled = !uiState.isCaptureProcessing,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.3f))
                            ) {
                                Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White))
                            }
                        } else {
                            FilledTonalButton(onClick = { viewModel.backToLive() }) {
                                Icon(Icons.Filled.CameraAlt, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Назад к камере")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Language bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        AssistChip(
                            onClick = { showSourcePicker = true },
                            label = { Text("${uiState.sourceLanguage.flag} ${uiState.sourceLanguage.nativeName}") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        IconButton(onClick = { viewModel.swapLanguages() }) {
                            Icon(Icons.Filled.SwapHoriz, "Swap")
                        }
                        AssistChip(
                            onClick = { showTargetPicker = true },
                            label = { Text("${uiState.targetLanguage.flag} ${uiState.targetLanguage.nativeName}") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }

                if (uiState.isCaptureProcessing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
            }
        }
    }

    if (showSourcePicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.sourceLanguage,
            excludeLanguage = uiState.targetLanguage,
            onLanguageSelected = { viewModel.setSourceLanguage(it); showSourcePicker = false },
            onDismiss = { showSourcePicker = false }
        )
    }
    if (showTargetPicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.targetLanguage,
            excludeLanguage = uiState.sourceLanguage,
            onLanguageSelected = { viewModel.setTargetLanguage(it); showTargetPicker = false },
            onDismiss = { showTargetPicker = false }
        )
    }
}

@Composable
private fun BoxScope.CameraBetaBadge() {
    Surface(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(12.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = "Beta",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun BoxScope.CameraHardwareControls(
    cameraOptions: List<CameraLensOption>,
    selectedCameraId: String?,
    torchEnabled: Boolean,
    captureFlashMode: CaptureFlashMode,
    enabled: Boolean,
    onCameraSelected: (String) -> Unit,
    onTorchToggle: () -> Unit,
    onFlashModeChange: (CaptureFlashMode) -> Unit
) {
    val selectedCamera = cameraOptions.firstOrNull { it.id == selectedCameraId }
    val hasFlash = selectedCamera?.hasFlash == true

    Surface(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp)
            .zIndex(3f),
        color = Color.Black.copy(alpha = 0.56f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (cameraOptions.size > 1) {
                cameraOptions.forEach { option ->
                    FilterChip(
                        selected = option.id == selectedCameraId,
                        onClick = { onCameraSelected(option.id) },
                        label = { Text(option.shortLabel) },
                        enabled = enabled,
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = Color.White,
                            selectedContainerColor = Color.White.copy(alpha = 0.22f),
                            selectedLabelColor = Color.White,
                            disabledContainerColor = Color.Transparent,
                            disabledLabelColor = Color.White.copy(alpha = 0.38f)
                        )
                    )
                }
            }

            IconButton(
                onClick = onTorchToggle,
                enabled = enabled && hasFlash,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchEnabled) "Выключить фонарь" else "Включить фонарь",
                    tint = if (torchEnabled && hasFlash) Color(0xFFFFD54F) else Color.White
                )
            }

            IconButton(
                onClick = { onFlashModeChange(captureFlashMode.next()) },
                enabled = enabled && hasFlash,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = captureFlashMode.icon(),
                    contentDescription = captureFlashMode.contentDescription(),
                    tint = if (captureFlashMode != CaptureFlashMode.OFF && hasFlash) {
                        Color(0xFFFFD54F)
                    } else {
                        Color.White
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CameraCaptureStatusBadge(
    status: CaptureStatus,
    message: String?
) {
    if (status == CaptureStatus.IDLE || (status == CaptureStatus.READY && message == null)) return

    val color = when (status) {
        CaptureStatus.ERROR -> Color(0xCCCC3333)
        CaptureStatus.EMPTY -> Color(0xCC6B5B1A)
        else -> Color.Black.copy(alpha = 0.62f)
    }

    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp, start = 12.dp, end = 12.dp),
        color = color,
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status == CaptureStatus.PROCESSING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = message ?: "",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun BoxScope.CameraQualityBadge(
    warnings: List<CameraQualityWarning>,
    modifier: Modifier = Modifier
) {
    if (warnings.isEmpty()) return

    val primaryWarning = warnings.first()
    Surface(
        modifier = modifier
            .widthIn(max = 300.dp)
            .zIndex(3f),
        color = Color.Black.copy(alpha = 0.64f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color(0xFFFFD54F)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = primaryWarning.qualityLabel(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (warnings.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "+${warnings.size - 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }
    }
}

private fun CameraQualityWarning.qualityLabel(): String =
    when (this) {
        CameraQualityWarning.LOW_LIGHT -> "Мало света"
        CameraQualityWarning.SOFT_FOCUS -> "Наведите фокус"
        CameraQualityWarning.SMALL_TEXT -> "Подойдите ближе"
        CameraQualityWarning.SCRIPT_MISMATCH -> "Проверьте язык"
        CameraQualityWarning.TRANSLATION_MODEL_UNAVAILABLE -> "Модель не готова"
    }

@Composable
private fun BoxScope.CameraDebugCaptureButton(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(12.dp),
        color = Color.Black.copy(alpha = 0.58f),
        contentColor = Color.White,
        shape = CircleShape
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.BugReport,
                contentDescription = "Save debug capture pack",
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f)
            )
        }
    }
}

@Composable
private fun BoxScope.LiveTextHitTargets(
    blocks: List<TranslatedBlock>,
    imageWidth: Int,
    imageHeight: Int,
    onBlockSelected: (TranslatedBlock) -> Unit
) {
    if (imageWidth <= 0 || imageHeight <= 0) return

    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(2f)
    ) {
        val overlayWidth = with(density) { maxWidth.toPx() }
        val overlayHeight = with(density) { maxHeight.toPx() }
        val minHitSizePx = with(density) { 44.dp.toPx() }

        blocks.forEach { block ->
            val rect = overlayRect(
                box = block.boundingBox,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                overlayWidth = overlayWidth,
                overlayHeight = overlayHeight
            ) ?: return@forEach

            val hitWidth = maxOf(rect.width(), minHitSizePx)
            val hitHeight = maxOf(rect.height(), minHitSizePx)
            val hitLeft = (rect.centerX() - hitWidth / 2f)
                .coerceIn(0f, (overlayWidth - hitWidth).coerceAtLeast(0f))
            val hitTop = (rect.centerY() - hitHeight / 2f)
                .coerceIn(0f, (overlayHeight - hitHeight).coerceAtLeast(0f))
            val interactionSource = remember(block.originalText, block.translatedText, block.boundingBox) {
                MutableInteractionSource()
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(hitLeft.roundToInt(), hitTop.roundToInt()) }
                    .size(
                        width = with(density) { hitWidth.toDp() },
                        height = with(density) { hitHeight.toDp() }
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onBlockSelected(block) }
                    )
            )
        }
    }
}

@Composable
private fun BoxScope.LiveTextSelectionPanel(
    block: TranslatedBlock,
    sourceLanguageCode: String,
    targetLanguageCode: String,
    isSpeaking: Boolean,
    onCopy: (String) -> Unit,
    onSpeak: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    val spokenText = block.translatedText.ifBlank { block.originalText }
    val spokenLanguage = if (block.translatedText.isNotBlank()) targetLanguageCode else sourceLanguageCode
    val copyText = buildString {
        append(block.originalText)
        if (block.translatedText.isNotBlank()) {
            append('\n')
            append(block.translatedText)
        }
    }

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 12.dp, end = 12.dp, bottom = 132.dp)
            .fillMaxWidth()
            .zIndex(4f),
        color = Color.Black.copy(alpha = 0.78f),
        contentColor = Color.White,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = block.originalText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = block.translatedText.ifBlank { block.originalText },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = { onCopy(copyText) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy text")
                }
                IconButton(
                    onClick = { onSpeak(spokenText, spokenLanguage) },
                    enabled = spokenText.isNotBlank()
                ) {
                    Icon(
                        if (isSpeaking) Icons.Filled.StopCircle else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isSpeaking) "Stop speech" else "Speak text",
                        tint = if (isSpeaking) Color(0xFFFFB4AB) else Color.White
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        }
    }
}

@Composable
private fun BoxScope.FocusReticle(focusPoint: Offset?) {
    if (focusPoint == null) return

    Canvas(modifier = Modifier.fillMaxSize()) {
        val color = Color.White.copy(alpha = 0.88f)
        val radius = 28f
        drawCircle(
            color = color,
            radius = radius,
            center = focusPoint,
            style = Stroke(width = 2.5f)
        )
        drawLine(
            color = color,
            start = Offset(focusPoint.x - radius - 10f, focusPoint.y),
            end = Offset(focusPoint.x - radius + 6f, focusPoint.y),
            strokeWidth = 2.5f
        )
        drawLine(
            color = color,
            start = Offset(focusPoint.x + radius - 6f, focusPoint.y),
            end = Offset(focusPoint.x + radius + 10f, focusPoint.y),
            strokeWidth = 2.5f
        )
        drawLine(
            color = color,
            start = Offset(focusPoint.x, focusPoint.y - radius - 10f),
            end = Offset(focusPoint.x, focusPoint.y - radius + 6f),
            strokeWidth = 2.5f
        )
        drawLine(
            color = color,
            start = Offset(focusPoint.x, focusPoint.y + radius - 6f),
            end = Offset(focusPoint.x, focusPoint.y + radius + 10f),
            strokeWidth = 2.5f
        )
    }
}

/** Live camera preview with OCR frame analysis. */
@androidx.camera.core.ExperimentalGetImage
@Composable
private fun LiveCameraView(
    viewModel: CameraViewModel,
    selectedCameraId: String?,
    torchEnabled: Boolean,
    captureFlashMode: CaptureFlashMode,
    onCaptureReady: ((() -> Unit)?) -> Unit,
    onCameraOptionsChanged: (List<CameraLensOption>, String?) -> Unit,
    onFocusPoint: (Offset) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val boundCamera = remember { AtomicReference<Camera?>(null) }
    val imageCaptureRef = remember { AtomicReference<ImageCapture?>(null) }
    val isTakingPicture = remember { AtomicBoolean(false) }
    var currentCamera by remember { mutableStateOf<Camera?>(null) }
    val analysisResolutionSelector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    android.util.Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
    }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(previewView, captureFlashMode) {
        val capture: () -> Unit = {
            val imageCapture = imageCaptureRef.get()
            if (imageCapture == null) {
                viewModel.failFullResolutionCapture("Камера еще не готова")
            } else if (isTakingPicture.compareAndSet(false, true)) {
                viewModel.startFullResolutionCapture()
                imageCapture.setFlashMode(captureFlashMode.toImageCaptureFlashMode())
                imageCapture.takePicture(
                    executor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                            isTakingPicture.set(false)
                            viewModel.captureImage(image)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isTakingPicture.set(false)
                            android.util.Log.e(
                                "CameraScreen",
                                "ImageCapture failed: ${exception.message}",
                                exception
                            )
                            viewModel.failFullResolutionCapture("Не удалось снять кадр")
                        }
                    }
                )
            }
        }
        onCaptureReady(capture)

        onDispose {
            isTakingPicture.set(false)
            onCaptureReady(null)
        }
    }

    LaunchedEffect(torchEnabled, currentCamera) {
        val camera = currentCamera
        if (camera != null) {
            runCatching {
                camera.cameraControl.enableTorch(torchEnabled && camera.cameraInfo.hasFlashUnit())
            }
        }
    }

    DisposableEffect(previewView) {
        previewView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_UP -> {
                    val camera = boundCamera.get()
                    if (camera != null) {
                        val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        )
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        camera.cameraControl.startFocusAndMetering(action)
                    }
                    onFocusPoint(Offset(event.x, event.y))
                    view.performClick()
                    true
                }
                else -> false
            }
        }

        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    DisposableEffect(lifecycleOwner, previewSize, selectedCameraId) {
        if (previewSize == IntSize.Zero) {
            onDispose {}
        } else {
            var isDisposed = false
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                if (isDisposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    val options = buildCameraLensOptions(provider.availableCameraInfos)
                    val selectedOption = preferredCameraOption(options, selectedCameraId)
                    onCameraOptionsChanged(options, selectedOption?.id)

                    val rotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
                    val preview = Preview.Builder()
                        .setTargetRotation(rotation)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setResolutionSelector(analysisResolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(executor) { imageProxy ->
                                viewModel.processLiveFrame(imageProxy)
                            }
                        }
                    val imageCapture = ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setFlashMode(captureFlashMode.toImageCaptureFlashMode())
                        .setJpegQuality(95)
                        .build()

                    provider.unbindAll()
                    boundCamera.set(null)
                    imageCaptureRef.set(null)
                    currentCamera = null

                    val viewPort = previewView.viewPort
                    fun bindCamera(option: CameraLensOption?): Camera {
                        val selector = option?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA
                        return if (viewPort != null) {
                            val useCaseGroup = UseCaseGroup.Builder()
                                .setViewPort(viewPort)
                                .addUseCase(preview)
                                .addUseCase(imageAnalysis)
                                .addUseCase(imageCapture)
                                .build()
                            provider.bindToLifecycle(lifecycleOwner, selector, useCaseGroup)
                        } else {
                            android.util.Log.w(
                                "CameraScreen",
                                "PreviewView ViewPort unavailable, binding fallback"
                            )
                            provider.bindToLifecycle(
                                lifecycleOwner, selector, preview, imageAnalysis, imageCapture
                            )
                        }
                    }

                    val fallbackOption = options.firstOrNull {
                        it.lensFacing == CameraSelector.LENS_FACING_BACK && it.shortLabel == "1x"
                    } ?: options.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                        ?: options.firstOrNull()

                    var resolvedOption = selectedOption
                    val camera = try {
                        bindCamera(selectedOption)
                    } catch (selectedError: Exception) {
                        if (fallbackOption != null && fallbackOption.id != selectedOption?.id) {
                            android.util.Log.w(
                                "CameraScreen",
                                "Selected camera bind failed, falling back to ${fallbackOption.shortLabel}: ${selectedError.message}",
                                selectedError
                            )
                            provider.unbindAll()
                            onCameraOptionsChanged(options, fallbackOption.id)
                            resolvedOption = fallbackOption
                            bindCamera(fallbackOption)
                        } else {
                            throw selectedError
                        }
                    }

                    imageCaptureRef.set(imageCapture)
                    boundCamera.set(camera)
                    currentCamera = camera
                    camera.cameraControl.enableTorch(
                        torchEnabled && camera.cameraInfo.hasFlashUnit()
                    )
                    android.util.Log.i(
                        "CameraScreen",
                        "Camera bound ${resolvedOption?.shortLabel ?: "default"} with ViewPort ${previewSize.width}x${previewSize.height}"
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CameraScreen", "Camera bind failed: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                isDisposed = true
                boundCamera.set(null)
                imageCaptureRef.set(null)
                currentCamera = null
                try {
                    val provider = ProcessCameraProvider.getInstance(context).get()
                    provider.unbindAll()
                } catch (_: Exception) {}
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                previewSize = coordinates.size
            }
    )
}

/** Live translation overlay mapped from OCR bitmap coordinates to PreviewView FILL_CENTER. */
@Composable
private fun TranslationOverlay(
    blocks: List<TranslatedBlock>,
    imageWidth: Int,
    imageHeight: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (block in blocks) {
            val box = block.boundingBox
            if (imageWidth <= 0 || imageHeight <= 0) continue

            val scaleX = size.width / imageWidth.toFloat()
            val scaleY = size.height / imageHeight.toFloat()
            val scale = maxOf(scaleX, scaleY)
            val offsetX = (size.width - imageWidth * scale) / 2f
            val offsetY = (size.height - imageHeight * scale) / 2f
            val left = box.left * scale + offsetX
            val top = box.top * scale + offsetY
            val w = box.width() * scale
            val h = box.height() * scale
            if (w <= 2f || h <= 2f) continue

            val expandedLeft = (left - 2f).coerceAtLeast(0f)
            val expandedTop = (top - 1f).coerceAtLeast(0f)
            val expandedWidth = (w + 4f).coerceAtMost(size.width - expandedLeft)
            val expandedHeight = (h + 2f).coerceAtMost(size.height - expandedTop)

            if (block.translatedText.isNotBlank()) {
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.64f),
                    topLeft = Offset(expandedLeft, expandedTop),
                    size = Size(expandedWidth, expandedHeight),
                    cornerRadius = CornerRadius(6f)
                )

                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    val textPaint = TextPaint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.rgb(250, 250, 250)
                        textSize = (h * 0.58f).coerceIn(11f, 30f)
                        isFakeBoldText = true
                        setShadowLayer(2f, 0f, 1f, android.graphics.Color.BLACK)
                    }
                    val padding = (h * 0.12f).coerceIn(3f, 8f)
                    val textWidth = (expandedWidth - padding * 2f).toInt().coerceAtLeast(1)
                    val textHeight = expandedHeight - padding * 2f
                    val maxLines = if (textHeight > textPaint.textSize * 1.9f) 2 else 1
                    val layout = buildOverlayTextLayout(
                        text = block.translatedText,
                        paint = textPaint,
                        width = textWidth,
                        maxHeight = textHeight,
                        maxLines = maxLines
                    )
                    val textY = expandedTop + padding +
                        ((textHeight - layout.height).coerceAtLeast(0f) / 2f)
                    nativeCanvas.save()
                    nativeCanvas.clipRect(
                        expandedLeft + padding,
                        expandedTop + padding,
                        expandedLeft + padding + textWidth,
                        expandedTop + expandedHeight - padding
                    )
                    nativeCanvas.translate(expandedLeft + padding, textY)
                    layout.draw(nativeCanvas)
                    nativeCanvas.restore()
                }
            } else {
                // No translation yet — thin border
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.72f),
                    topLeft = Offset(expandedLeft, expandedTop),
                    size = Size(expandedWidth, expandedHeight),
                    cornerRadius = CornerRadius(4f),
                    style = Stroke(width = 1.4f)
                )
            }
        }
    }
}

private fun buildOverlayTextLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    maxHeight: Float,
    maxLines: Int
): StaticLayout {
    val normalizedText = text.replace(Regex("\\s+"), " ").trim()
    val minTextSize = 9f
    var layout = createOverlayTextLayout(normalizedText, paint, width, maxLines)
    while (
        (layout.height > maxHeight || layout.lineCount > maxLines) &&
        paint.textSize > minTextSize
    ) {
        paint.textSize -= 1f
        layout = createOverlayTextLayout(normalizedText, paint, width, maxLines)
    }
    return layout
}

private fun createOverlayTextLayout(
    text: String,
    paint: TextPaint,
    width: Int,
    maxLines: Int
): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 0.95f)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

private fun overlayRect(
    box: android.graphics.Rect,
    imageWidth: Int,
    imageHeight: Int,
    overlayWidth: Float,
    overlayHeight: Float
): RectF? {
    if (imageWidth <= 0 || imageHeight <= 0 || overlayWidth <= 0f || overlayHeight <= 0f) {
        return null
    }

    val scaleX = overlayWidth / imageWidth.toFloat()
    val scaleY = overlayHeight / imageHeight.toFloat()
    val scale = maxOf(scaleX, scaleY)
    val offsetX = (overlayWidth - imageWidth * scale) / 2f
    val offsetY = (overlayHeight - imageHeight * scale) / 2f
    val left = box.left * scale + offsetX
    val top = box.top * scale + offsetY
    val right = box.right * scale + offsetX
    val bottom = box.bottom * scale + offsetY
    if (right <= 0f || bottom <= 0f || left >= overlayWidth || top >= overlayHeight) return null
    return RectF(
        left.coerceIn(0f, overlayWidth),
        top.coerceIn(0f, overlayHeight),
        right.coerceIn(0f, overlayWidth),
        bottom.coerceIn(0f, overlayHeight)
    )
}

/** Display the painted bitmap (with translations baked in) with pinch-to-zoom. */
@Composable
private fun CaptureImageView(bitmap: android.graphics.Bitmap?) {
    if (bitmap == null) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

    Image(
        bitmap = imageBitmap,
        contentDescription = "Captured translation",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 5f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            }
    )
}

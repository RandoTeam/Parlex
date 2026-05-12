package com.translive.app.ui.screens

import android.Manifest
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.viewmodel.CameraMode
import com.translive.app.ui.viewmodel.CameraViewModel
import com.translive.app.ui.viewmodel.CaptureStatus
import com.translive.app.ui.viewmodel.TranslatedBlock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setPermissionGranted(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.setPermissionGranted(true)
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(850)
            focusPoint = null
        }
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
                            onPreviewView = { previewViewRef = it },
                            onFocusPoint = { focusPoint = it }
                        )

                        CameraBetaBadge()

                        // Live translation overlay
                        if (uiState.liveBlocks.isNotEmpty()) {
                            TranslationOverlay(
                                blocks = uiState.liveBlocks,
                                imageWidth = uiState.imageWidth,
                                imageHeight = uiState.imageHeight
                            )
                        }

                        FocusReticle(focusPoint)

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
                                    viewModel.capturePreview(previewViewRef?.bitmap)
                                },
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
    onPreviewView: (PreviewView) -> Unit,
    onFocusPoint: (Offset) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val boundCamera = remember { java.util.concurrent.atomic.AtomicReference<Camera?>(null) }
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

    LaunchedEffect(previewView) {
        onPreviewView(previewView)
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

    DisposableEffect(lifecycleOwner, previewSize) {
        if (previewSize == IntSize.Zero) {
            onDispose {}
        } else {
            var isDisposed = false
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                if (isDisposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
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

                    provider.unbindAll()
                    boundCamera.set(null)

                    val viewPort = previewView.viewPort
                    if (viewPort != null) {
                        val useCaseGroup = UseCaseGroup.Builder()
                            .setViewPort(viewPort)
                            .addUseCase(preview)
                            .addUseCase(imageAnalysis)
                            .build()
                        boundCamera.set(
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup
                            )
                        )
                        android.util.Log.i(
                            "CameraScreen",
                            "Camera bound with ViewPort ${previewSize.width}x${previewSize.height}"
                        )
                    } else {
                        android.util.Log.w("CameraScreen", "PreviewView ViewPort unavailable, binding fallback")
                        boundCamera.set(
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, imageAnalysis
                            )
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("CameraScreen", "Camera bind failed: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(context))

            onDispose {
                isDisposed = true
                boundCamera.set(null)
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

            if (block.translatedText.isNotBlank()) {
                // Semi-transparent dark background
                drawRoundRect(
                    color = Color(0xDD222222),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(4f)
                )

                // Draw translated text using native canvas
                drawIntoCanvas { canvas ->
                    val nativeCanvas = canvas.nativeCanvas
                    val textPaint = android.text.TextPaint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.WHITE
                        textSize = (h * 0.65f).coerceIn(10f, 36f)
                        isFakeBoldText = true
                    }
                    // Shrink if too wide
                    var measured = textPaint.measureText(block.translatedText)
                    while (measured > w - 4f && textPaint.textSize > 8f) {
                        textPaint.textSize -= 1f
                        measured = textPaint.measureText(block.translatedText)
                    }
                    val textX = left + 3f
                    val textY = top + (h - textPaint.descent() - textPaint.ascent()) / 2f
                    nativeCanvas.drawText(block.translatedText, textX, textY, textPaint)
                }
            } else {
                // No translation yet — thin border
                drawRoundRect(
                    color = Color(0xFF4FC3F7),
                    topLeft = Offset(left, top),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(4f),
                    style = Stroke(width = 2f)
                )
            }
        }
    }
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

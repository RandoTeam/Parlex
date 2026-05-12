package com.translive.app.ui.screens

import android.Manifest
import android.graphics.Matrix
import android.graphics.RectF
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
                            onPreviewView = { previewViewRef = it }
                        )

                        CameraBetaBadge()

                        // Live translation overlay
                        if (uiState.liveBlocks.isNotEmpty()) {
                            TranslationOverlay(
                                blocks = uiState.liveBlocks,
                                transformMatrix = uiState.transformMatrix,
                                imageWidth = uiState.imageWidth,
                                imageHeight = uiState.imageHeight
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

/** Live camera preview with OCR frame analysis. */
@androidx.camera.core.ExperimentalGetImage
@Composable
private fun LiveCameraView(
    viewModel: CameraViewModel,
    onPreviewView: (PreviewView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
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

    val transformFactory = remember { ImageProxyTransformFactory().apply { isUsingRotationDegrees = true } }

    // Cached transform matrix — computed when preview starts streaming
    var cachedMatrix by remember { mutableStateOf<Matrix?>(null) }

    // Bind camera exactly once
    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            // Try to compute transform matrix if not cached yet
                            if (cachedMatrix == null) {
                                try {
                                    val targetTransform = previewView.outputTransform
                                    if (targetTransform != null) {
                                        val sourceTransform = transformFactory.getOutputTransform(imageProxy)
                                        val coordTransform = CoordinateTransform(sourceTransform, targetTransform)
                                        val m = Matrix()
                                        coordTransform.transform(m)
                                        cachedMatrix = m
                                        android.util.Log.i("CameraScreen", "CoordinateTransform matrix cached OK")
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("CameraScreen", "Transform not ready: ${e.message}")
                                }
                            }
                            viewModel.processLiveFrame(imageProxy, cachedMatrix)
                        }
                    }

                provider.unbindAll()

                // Use UseCaseGroup with shared ViewPort for accurate coordinate alignment
                val viewPort = previewView.viewPort
                if (viewPort != null) {
                    val useCaseGroup = UseCaseGroup.Builder()
                        .setViewPort(viewPort)
                        .addUseCase(preview)
                        .addUseCase(imageAnalysis)
                        .build()
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup
                    )
                } else {
                    // Fallback: bind without ViewPort (first frame before layout)
                    provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageAnalysis
                    )
                }
                android.util.Log.i("CameraScreen", "Camera bound OK with UseCaseGroup, analyzer attached")
            } catch (e: Exception) {
                android.util.Log.e("CameraScreen", "Camera bind failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onPreviewView(previewView)

        onDispose {
            try {
                val provider = ProcessCameraProvider.getInstance(context).get()
                provider.unbindAll()
            } catch (_: Exception) {}
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Live translation overlay — uses CameraX CoordinateTransform matrix when available,
 * falls back to manual FILL_CENTER calculation otherwise.
 */
@Composable
private fun TranslationOverlay(
    blocks: List<TranslatedBlock>,
    transformMatrix: Matrix?,
    imageWidth: Int,
    imageHeight: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (block in blocks) {
            val box = block.boundingBox
            val left: Float
            val top: Float
            val w: Float
            val h: Float

            if (transformMatrix != null) {
                // Official CameraX transform — handles rotation, crop, scaling
                val mappedRect = RectF(
                    box.left.toFloat(), box.top.toFloat(),
                    box.right.toFloat(), box.bottom.toFloat()
                )
                transformMatrix.mapRect(mappedRect)
                left = mappedRect.left
                top = mappedRect.top
                w = mappedRect.width()
                h = mappedRect.height()
            } else if (imageWidth > 0 && imageHeight > 0) {
                // Fallback: manual FILL_CENTER math
                val scaleX = size.width / imageWidth.toFloat()
                val scaleY = size.height / imageHeight.toFloat()
                val scale = maxOf(scaleX, scaleY)
                val offsetX = (size.width - imageWidth * scale) / 2f
                val offsetY = (size.height - imageHeight * scale) / 2f
                left = box.left * scale + offsetX
                top = box.top * scale + offsetY
                w = box.width() * scale
                h = box.height() * scale
            } else continue

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

package com.translive.app.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.model.Language
import com.translive.app.data.model.pdf.DocumentViewMode
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.viewmodel.DocumentTranslateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentTranslateScreen(
    onNavigateBack: () -> Unit,
    viewModel: DocumentTranslateViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadDocument(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (uiState.documentName.isNotBlank()) uiState.documentName else "Перевод PDF",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        if (uiState.pageCount > 0) {
                            Text(
                                text = "Страница ${uiState.currentPageIndex + 1} из ${uiState.pageCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (uiState.documentUri != null) {
                        // View mode selector
                        IconButton(onClick = {
                            val nextMode = when (uiState.viewMode) {
                                DocumentViewMode.OVERLAY -> DocumentViewMode.SIDE_BY_SIDE
                                DocumentViewMode.SIDE_BY_SIDE -> DocumentViewMode.ORIGINAL_ONLY
                                DocumentViewMode.ORIGINAL_ONLY -> DocumentViewMode.OVERLAY
                            }
                            viewModel.setViewMode(nextMode)
                        }) {
                            Icon(
                                when (uiState.viewMode) {
                                    DocumentViewMode.OVERLAY -> Icons.Filled.Layers
                                    DocumentViewMode.SIDE_BY_SIDE -> Icons.Filled.ViewSidebar
                                    DocumentViewMode.ORIGINAL_ONLY -> Icons.Filled.Visibility
                                },
                                contentDescription = "Режим отображения"
                            )
                        }

                        // Export button
                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
                                Icon(Icons.Filled.Share, contentDescription = "Экспорт")
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Экспорт в TXT (перевод)") },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportTranslatedText(bilingual = false) { file ->
                                            Toast.makeText(context, if (file != null) "Сохранено: ${file.name}" else "Ошибка экспорта", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Экспорт в TXT (двуязычный)") },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportTranslatedText(bilingual = true) { file ->
                                            Toast.makeText(context, if (file != null) "Сохранено: ${file.name}" else "Ошибка экспорта", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Экспорт в PDF (с переводом)") },
                                    onClick = {
                                        showExportMenu = false
                                        viewModel.exportTranslatedPdf { file ->
                                            Toast.makeText(context, if (file != null) "Сохранено: ${file.name}" else "Ошибка экспорта", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Open Document action
                    IconButton(onClick = {
                        pdfPickerLauncher.launch(arrayOf("application/pdf"))
                    }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Открыть PDF")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.documentUri != null && uiState.pageCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Language pair selector bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { showSourcePicker = true }) {
                                Text("${uiState.sourceLanguage.flag} ${uiState.sourceLanguage.displayName}")
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            TextButton(onClick = { showTargetPicker = true }) {
                                Text("${uiState.targetLanguage.flag} ${uiState.targetLanguage.displayName}")
                            }
                        }

                        // Page slider & stepper
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { viewModel.selectPage(uiState.currentPageIndex - 1) },
                                enabled = uiState.currentPageIndex > 0
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущая")
                            }

                            Slider(
                                value = uiState.currentPageIndex.toFloat(),
                                onValueChange = { viewModel.selectPage(it.toInt()) },
                                valueRange = 0f..(uiState.pageCount - 1).coerceAtLeast(0).toFloat(),
                                steps = (uiState.pageCount - 2).coerceAtLeast(0),
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(
                                onClick = { viewModel.selectPage(uiState.currentPageIndex + 1) },
                                enabled = uiState.currentPageIndex < uiState.pageCount - 1
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующая")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.documentUri == null) {
                // Empty state: prompt to pick a PDF
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Перевод документов PDF",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Постраничный рендеринг, оптическое распознавание и перевод с сохранением разметки прямо на устройстве.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Выбрать PDF файл")
                    }
                }
            } else {
                val currentPageData = uiState.pages[uiState.currentPageIndex]

                when (uiState.viewMode) {
                    DocumentViewMode.OVERLAY, DocumentViewMode.ORIGINAL_ONLY -> {
                        val bitmapToDisplay = if (uiState.viewMode == DocumentViewMode.OVERLAY) {
                            currentPageData?.translatedBitmap ?: currentPageData?.originalBitmap
                        } else {
                            currentPageData?.originalBitmap
                        }

                        if (bitmapToDisplay != null) {
                            ZoomablePdfPage(bitmap = bitmapToDisplay)
                        } else {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    DocumentViewMode.SIDE_BY_SIDE -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            // Left: Original Page Bitmap
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                currentPageData?.originalBitmap?.let { bitmap ->
                                    ZoomablePdfPage(bitmap = bitmap)
                                } ?: CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }

                            VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

                            // Right: Translated Paragraph Cards
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(currentPageData?.paragraphs ?: emptyList(), key = { it.id }) { para ->
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = para.translatedText.ifBlank { para.sourceText },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            if (para.translatedText.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = para.sourceText,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Processing Indicator
                if (uiState.isProcessingPage) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        tonalElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Обработка страницы...", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    if (showSourcePicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.sourceLanguage,
            onLanguageSelected = {
                viewModel.setSourceLanguage(it)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false }
        )
    }

    if (showTargetPicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.targetLanguage,
            onLanguageSelected = {
                viewModel.setTargetLanguage(it)
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false }
        )
    }
}

@Composable
private fun ZoomablePdfPage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    val maxOffset = 1000f * (scale - 1f)
                    offset = Offset(
                        x = (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                        y = (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "PDF Page",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

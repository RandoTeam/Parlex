package com.translive.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class OcrLine(
    val text: String,
    val boundingBox: Rect
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrLine>
)

data class OcrFrameQuality(
    val averageLuma: Float,
    val sharpness: Float
)

data class OcrDiagnostics(
    val backend: String,
    val recognitionMs: Long,
    val qualityAnalysisMs: Long = 0L
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
    val quality: OcrFrameQuality? = null,
    val diagnostics: OcrDiagnostics? = null
)

/**
 * Which OCR backend to use for a given script.
 */
private enum class OcrBackend {
    MLKIT_LATIN,       // en, fr, de, es, pt, it, nl, pl, cs, tr, vi, id, ms, fil
    MLKIT_CHINESE,     // zh, zh-Hant, yue, nan
    MLKIT_JAPANESE,    // ja
    MLKIT_KOREAN,      // ko
    MLKIT_DEVANAGARI,  // hi, mr, gu
    TESSERACT          // ru, uk, ar, fa, ur, he, th, bn, ta, te, my, km, bo, mn, ug
}

/**
 * Hybrid OCR engine supporting all 33 languages + 5 dialects.
 *
 * ML Kit handles: Latin, CJK, Devanagari scripts.
 * Tesseract handles: Cyrillic, Arabic, Hebrew, Thai, Bengali, Tamil, Telugu,
 *                    Burmese, Khmer, Tibetan, Mongolian, Uyghur.
 */
@Singleton
class OcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ppOcrMnnEngine: PpOcrMnnEngine
) {
    companion object {
        private const val TAG = "OcrEngine"
    }

    // ML Kit recognizers
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val chineseRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private val japaneseRecognizer: TextRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    private val koreanRecognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    private val devanagariRecognizer: TextRecognizer =
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    // Tesseract — lazy init per language
    private val tessLock = ReentrantLock()
    private var tessApi: TessBaseAPI? = null
    private var tessCurrentLang: String = ""
    private var tessDataPath: String? = null

    /** Map language code -> OCR backend. */
    private fun backendFor(code: String): OcrBackend {
        return when (code.lowercase()) {
            "en", "fr", "de", "es", "pt", "it", "nl", "pl", "cs",
            "tr", "vi", "id", "ms", "fil" -> OcrBackend.MLKIT_LATIN

            "zh", "zh-hant", "yue", "nan" -> OcrBackend.MLKIT_CHINESE
            "ja" -> OcrBackend.MLKIT_JAPANESE
            "ko" -> OcrBackend.MLKIT_KOREAN

            "hi", "mr", "gu" -> OcrBackend.MLKIT_DEVANAGARI

            else -> OcrBackend.TESSERACT
        }
    }

    /** Map language code -> Tesseract traineddata name. */
    private fun tessLangFor(code: String): String {
        return when (code.lowercase()) {
            "ru" -> "rus+eng"
            "uk" -> "ukr+eng"
            "ar" -> "ara+eng"
            "fa" -> "fas+eng"
            "ur" -> "urd+eng"
            "he" -> "heb+eng"
            "th" -> "tha+eng"
            "bn" -> "ben+eng"
            "ta" -> "tam+eng"
            "te" -> "tel+eng"
            "my" -> "mya+eng"
            "km" -> "khm+eng"
            "bo" -> "bod+eng"
            "mn" -> "rus+eng"
            "ug" -> "ara+eng"
            "auto" -> "rus+eng"
            else -> "eng"
        }
    }

    // -- Public API -------------------------------------------------------

    fun backendNameFor(sourceLanguageCode: String): String =
        if (sourceLanguageCode.equals("auto", ignoreCase = true)) "HYBRID_AUTO"
        else backendFor(sourceLanguageCode).name

    fun engineLanguageFor(sourceLanguageCode: String): String =
        when (backendFor(sourceLanguageCode)) {
            OcrBackend.TESSERACT -> tessLangFor(sourceLanguageCode)
            else -> sourceLanguageCode
        }

    suspend fun recognize(bitmap: Bitmap, sourceLanguageCode: String = "en"): OcrResult {
        val ppOcrRoot = File(context.filesDir, "ocr/${PpOcrPackage.ID}")
        val ppOcrValidation = PpOcrPackage.validate(ppOcrRoot)
        if (ppOcrValidation.valid) {
            val ppOcrResult = ppOcrMnnEngine.recognize(
                bitmap = bitmap,
                detectorPath = File(ppOcrRoot, PpOcrPackage.detector.fileName).absolutePath,
                recognizerPath = File(ppOcrRoot, PpOcrPackage.recognizer.fileName).absolutePath,
                dictionary = PpOcrDictionary.readValidated(
                    File(ppOcrRoot, PpOcrPackage.dictionary.fileName)
                ).orEmpty(),
                config = PpOcrMnnEngine.Config(backend = 1)
            )
            if (ppOcrResult.blocks.isNotEmpty()) return ppOcrResult
        }

        val qualityStartedAt = SystemClock.elapsedRealtime()
        val quality = analyzeFrameQuality(bitmap)
        val qualityMs = SystemClock.elapsedRealtime() - qualityStartedAt
        val recognitionStartedAt = SystemClock.elapsedRealtime()

        if (sourceLanguageCode.equals("auto", ignoreCase = true)) {
            val autoResult = recognizeAutoHybrid(bitmap)
            return autoResult.copy(
                quality = quality,
                diagnostics = OcrDiagnostics(
                    backend = "HYBRID_AUTO",
                    recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt,
                    qualityAnalysisMs = qualityMs
                )
            )
        }

        val backend = backendFor(sourceLanguageCode)
        val result = when (backend) {
            OcrBackend.MLKIT_LATIN -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, latinRecognizer)
            }
            OcrBackend.MLKIT_CHINESE -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, chineseRecognizer)
            }
            OcrBackend.MLKIT_JAPANESE -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, japaneseRecognizer)
            }
            OcrBackend.MLKIT_KOREAN -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, koreanRecognizer)
            }
            OcrBackend.MLKIT_DEVANAGARI -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, devanagariRecognizer)
            }
            OcrBackend.TESSERACT -> {
                recognizeWithTesseract(bitmap, sourceLanguageCode)
            }
        }
        return result.copy(
            quality = quality,
            diagnostics = OcrDiagnostics(
                backend = backend.name,
                recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt,
                qualityAnalysisMs = qualityMs
            )
        )
    }

    private suspend fun recognizeAutoHybrid(bitmap: Bitmap): OcrResult {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val latinResult = recognizeWithMlKit(inputImage, latinRecognizer)
        val latinText = latinResult.blocks.joinToString(" ") { it.text }

        val tesseractResult = recognizeWithTesseract(bitmap, "ru")
        val tesseractText = tesseractResult.blocks.joinToString(" ") { it.text }
        val cyrillicCount = tesseractText.count { it in 'Ѐ'..'ԯ' }

        if (cyrillicCount >= 2 || (cyrillicCount >= 1 && tesseractText.length <= 6)) {
            return tesseractResult
        }

        val cjkCount = latinText.count { it in '一'..'鿿' || it in '぀'..'ヿ' || it in '가'..'힯' }
        if (cjkCount >= 2 || latinResult.blocks.isEmpty()) {
            val chineseResult = recognizeWithMlKit(inputImage, chineseRecognizer)
            val chineseText = chineseResult.blocks.joinToString(" ") { it.text }
            if (chineseText.count { it in '一'..'鿿' } >= 2) {
                return chineseResult
            }
            val japaneseResult = recognizeWithMlKit(inputImage, japaneseRecognizer)
            val japaneseText = japaneseResult.blocks.joinToString(" ") { it.text }
            if (japaneseText.count { it in '぀'..'ヿ' } >= 1) {
                return japaneseResult
            }
            val koreanResult = recognizeWithMlKit(inputImage, koreanRecognizer)
            val koreanText = koreanResult.blocks.joinToString(" ") { it.text }
            if (koreanText.count { it in '가'..'힯' } >= 1) {
                return koreanResult
            }
        }

        val devanagariCount = latinText.count { it in 'ऀ'..'ॿ' }
        if (devanagariCount >= 2 || latinResult.blocks.isEmpty()) {
            val devanagariResult = recognizeWithMlKit(inputImage, devanagariRecognizer)
            if (devanagariResult.blocks.isNotEmpty()) {
                return devanagariResult
            }
        }

        return if (latinResult.blocks.isNotEmpty()) latinResult
        else if (tesseractResult.blocks.isNotEmpty()) tesseractResult
        else latinResult
    }

    @androidx.camera.core.ExperimentalGetImage
    suspend fun recognize(
        imageProxy: androidx.camera.core.ImageProxy,
        sourceLanguageCode: String = "en"
    ): OcrResult {
        val bitmap = try {
            imageProxyToUprightBitmap(imageProxy)
        } finally {
            imageProxy.close()
        }

        return if (bitmap != null) {
            recognize(bitmap, sourceLanguageCode)
        } else {
            OcrResult(emptyList(), 0, 0)
        }
    }

    /**
     * Fast CameraX path for the scripts supported by ML Kit.
     */
    @androidx.camera.core.ExperimentalGetImage
    suspend fun recognizeLive(
        imageProxy: androidx.camera.core.ImageProxy,
        sourceLanguageCode: String = "en"
    ): OcrResult {
        val recognizer = when (backendFor(sourceLanguageCode)) {
            OcrBackend.MLKIT_LATIN -> latinRecognizer
            OcrBackend.MLKIT_CHINESE -> chineseRecognizer
            OcrBackend.MLKIT_JAPANESE -> japaneseRecognizer
            OcrBackend.MLKIT_KOREAN -> koreanRecognizer
            OcrBackend.MLKIT_DEVANAGARI -> devanagariRecognizer
            OcrBackend.TESSERACT -> null
        }

        if (recognizer == null) {
            return recognize(imageProxy, sourceLanguageCode)
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return OcrResult(emptyList(), 0, 0)
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )
        val recognitionStartedAt = SystemClock.elapsedRealtime()
        return recognizeWithMlKit(
            image = image,
            recognizer = recognizer,
            onComplete = imageProxy::close
        ).copy(
            diagnostics = OcrDiagnostics(
                backend = backendFor(sourceLanguageCode).name,
                recognitionMs = SystemClock.elapsedRealtime() - recognitionStartedAt
            )
        )
    }

    // -- ML Kit -----------------------------------------------------------

    private suspend fun recognizeWithMlKit(
        image: InputImage,
        recognizer: TextRecognizer,
        imageWidth: Int = image.width,
        imageHeight: Int = image.height,
        onComplete: (() -> Unit)? = null
    ): OcrResult = suspendCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val blocks = result.textBlocks.mapNotNull { textBlock ->
                    val blockBox = textBlock.boundingBox ?: return@mapNotNull null
                    val lines = textBlock.lines.mapNotNull { line ->
                        val lineBox = line.boundingBox ?: return@mapNotNull null
                        OcrLine(text = line.text, boundingBox = lineBox)
                    }
                    if (lines.isEmpty()) return@mapNotNull null
                    OcrBlock(text = textBlock.text, boundingBox = blockBox, lines = lines)
                }
                onComplete?.invoke()
                cont.resume(OcrResult(blocks, imageWidth, imageHeight))
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "ML Kit OCR failed: ${e.message}", e)
                onComplete?.invoke()
                cont.resume(OcrResult(emptyList(), imageWidth, imageHeight))
            }
    }

    // -- Tesseract --------------------------------------------------------

    private fun ensureTesseractReady(langCode: String): Boolean {
        val tessLang = tessLangFor(langCode)

        if (tessApi != null && tessCurrentLang == tessLang) return true

        tessApi?.recycle()
        tessApi = null

        try {
            val dataDir = File(context.filesDir, "tesseract")
            val tessDir = File(dataDir, "tessdata")
            tessDir.mkdirs()

            val singleLangs = tessLang.split("+")
            for (subLang in singleLangs) {
                val trainedDataFile = File(tessDir, "$subLang.traineddata")
                if (!trainedDataFile.exists()) {
                    val assetName = "tessdata/$subLang.traineddata"
                    try {
                        context.assets.open(assetName).use { input ->
                            trainedDataFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        Log.i(TAG, "Copied $assetName (${trainedDataFile.length()} bytes)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy tessdata $assetName: ${e.message}", e)
                        if (subLang == singleLangs.first()) return false
                    }
                }
            }

            val api = TessBaseAPI()
            if (!api.init(dataDir.absolutePath, tessLang)) {
                val primary = singleLangs.first()
                if (!api.init(dataDir.absolutePath, primary)) {
                    Log.e(TAG, "Tesseract init failed for $tessLang and $primary")
                    return false
                }
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setVariable("preserve_interword_spaces", "1")

            tessApi = api
            tessCurrentLang = tessLang
            tessDataPath = dataDir.absolutePath
            Log.i(TAG, "Tesseract ready: $tessLang")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract setup error: ${e.message}", e)
            return false
        }
    }

    private fun recognizeWithTesseract(bitmap: Bitmap, langCode: String): OcrResult {
        val ocrBitmap = preprocessForTesseract(bitmap)
        try {
            return tessLock.withLock {
                if (!ensureTesseractReady(langCode)) {
                    return@withLock OcrResult(emptyList(), bitmap.width, bitmap.height)
                }

                val api = tessApi ?: return@withLock OcrResult(emptyList(), bitmap.width, bitmap.height)

                try {
                    api.setImage(ocrBitmap)
                    val recognizedText = api.getUTF8Text()
                    if (recognizedText.isNullOrBlank()) {
                        Log.d(TAG, "Tesseract $langCode returned no text")
                        return@withLock OcrResult(emptyList(), bitmap.width, bitmap.height)
                    }

                    val blocks = mutableListOf<OcrBlock>()
                    val iterator = api.resultIterator

                    if (iterator != null) {
                        val currentLines = mutableListOf<OcrLine>()
                        var blockText = StringBuilder()
                        var blockBox: Rect? = null

                        iterator.begin()
                        do {
                            val lineText = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                            val lineRect = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)

                            if (!lineText.isNullOrBlank() && lineRect != null &&
                                lineRect.width() > 20 && lineRect.height() > 8
                            ) {
                                currentLines.add(OcrLine(lineText.trim(), lineRect))
                                blockText.append(lineText.trim()).append(" ")
                                blockBox = if (blockBox == null) Rect(lineRect) else Rect(
                                    minOf(blockBox.left, lineRect.left),
                                    minOf(blockBox.top, lineRect.top),
                                    maxOf(blockBox.right, lineRect.right),
                                    maxOf(blockBox.bottom, lineRect.bottom)
                                )
                            }

                            if (iterator.isAtFinalElement(
                                    TessBaseAPI.PageIteratorLevel.RIL_BLOCK,
                                    TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                                )
                            ) {
                                if (currentLines.isNotEmpty() && blockBox != null) {
                                    blocks.add(OcrBlock(blockText.toString().trim(), blockBox, currentLines.toList()))
                                }
                                currentLines.clear()
                                blockText = StringBuilder()
                                blockBox = null
                            }
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))

                        if (currentLines.isNotEmpty() && blockBox != null) {
                            blocks.add(OcrBlock(blockText.toString().trim(), blockBox, currentLines.toList()))
                        }
                        iterator.delete()
                    }

                    Log.d(TAG, "Tesseract $langCode found ${blocks.sumOf { it.lines.size }} lines")
                    OcrResult(blocks, bitmap.width, bitmap.height)
                } finally {
                    api.clear()
                }
            }
        } finally {
            if (ocrBitmap !== bitmap) {
                ocrBitmap.recycle()
            }
        }
    }

    // -- Utils ------------------------------------------------------------

    private fun preprocessForTesseract(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var minLum = 255
        var maxLum = 0
        for (pixel in pixels) {
            val lum = luminance(pixel)
            if (lum < minLum) minLum = lum
            if (lum > maxLum) maxLum = lum
        }

        val range = (maxLum - minLum).coerceAtLeast(1)
        val shouldStretch = range > 24
        for (i in pixels.indices) {
            val rawLum = luminance(pixels[i])
            val normalized = if (shouldStretch) {
                ((rawLum - minLum) * 255 / range).coerceIn(0, 255)
            } else {
                rawLum
            }
            val contrasted = (((normalized - 128) * 1.35f) + 128).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(contrasted, contrasted, contrasted)
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun analyzeFrameQuality(bitmap: Bitmap): OcrFrameQuality {
        if (bitmap.width <= 1 || bitmap.height <= 1) {
            return OcrFrameQuality(averageLuma = 0f, sharpness = 0f)
        }

        val step = max(1, min(bitmap.width, bitmap.height) / 120)
        val sampledColumns = ((bitmap.width - 1) / step) + 1
        val previousRow = IntArray(sampledColumns) { -1 }

        var lumaSum = 0L
        var sampleCount = 0
        var edgeSum = 0L
        var edgeCount = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            var column = 0
            var previousLuma = -1
            while (x < bitmap.width) {
                val luma = luminance(bitmap.getPixel(x, y))
                lumaSum += luma
                sampleCount++

                if (previousLuma >= 0) {
                    edgeSum += abs(luma - previousLuma)
                    edgeCount++
                }
                val topLuma = previousRow[column]
                if (topLuma >= 0) {
                    edgeSum += abs(luma - topLuma)
                    edgeCount++
                }
                previousRow[column] = luma
                previousLuma = luma

                x += step
                column++
            }
            y += step
        }

        return OcrFrameQuality(
            averageLuma = if (sampleCount > 0) lumaSum.toFloat() / sampleCount else 0f,
            sharpness = if (edgeCount > 0) edgeSum.toFloat() / edgeCount else 0f
        )
    }

    @androidx.camera.core.ExperimentalGetImage
    fun imageProxyToUprightBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val bitmap = when (image.format) {
            ImageFormat.JPEG -> jpegImageToBitmap(image)
            ImageFormat.YUV_420_888 -> yuv420ImageToBitmap(image)
            else -> {
                Log.w(TAG, "Unsupported image format for OCR: ${image.format}")
                null
            }
        } ?: return null

        return cropAndRotateBitmap(
            bitmap = bitmap,
            crop = imageProxy.cropRect,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees
        )
    }

    private fun jpegImageToBitmap(image: android.media.Image): Bitmap? {
        val buffer = image.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420ImageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)

        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, row * width, width)
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvHeight = height / 2
        val uvWidth = width / 2
        var uvIndex = width * height

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                nv21[uvIndex++] = vBuffer.get(vIndex)
                nv21[uvIndex++] = uBuffer.get(uIndex)
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21,
            width, height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun cropAndRotateBitmap(bitmap: Bitmap, crop: Rect, rotationDegrees: Int): Bitmap {
        val cropLeft = crop.left.coerceIn(0, bitmap.width - 1)
        val cropTop = crop.top.coerceIn(0, bitmap.height - 1)
        val cropRight = crop.right.coerceIn(cropLeft + 1, bitmap.width)
        val cropBottom = crop.bottom.coerceIn(cropTop + 1, bitmap.height)
        val cropped = if (
            cropLeft == 0 && cropTop == 0 &&
            cropRight == bitmap.width && cropBottom == bitmap.height
        ) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropRight - cropLeft, cropBottom - cropTop)
        }

        return if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, matrix, true)
        } else cropped
    }

    fun release() {
        latinRecognizer.close()
        chineseRecognizer.close()
        japaneseRecognizer.close()
        koreanRecognizer.close()
        devanagariRecognizer.close()
        tessLock.withLock {
            tessApi?.recycle()
            tessApi = null
            tessCurrentLang = ""
            tessDataPath = null
        }
    }
}

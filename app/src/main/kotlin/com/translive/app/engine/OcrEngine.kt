package com.translive.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

data class OcrLine(
    val text: String,
    val boundingBox: Rect
)

data class OcrBlock(
    val text: String,
    val boundingBox: Rect,
    val lines: List<OcrLine>
)

data class OcrResult(
    val blocks: List<OcrBlock>,
    val imageWidth: Int,
    val imageHeight: Int
)

/**
 * Which OCR backend to use for a given script.
 */
private enum class OcrBackend {
    MLKIT_LATIN,       // en, fr, de, es, pt, it, nl, pl, cs, tr, vi, id, ms, fil
    MLKIT_CHINESE,     // zh, zh-Hant, ja, ko, yue, nan
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
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "OcrEngine"
    }

    // ML Kit recognizers
    private val latinRecognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val chineseRecognizer: TextRecognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    private val devanagariRecognizer: TextRecognizer =
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

    // Tesseract — lazy init per language
    private var tessApi: TessBaseAPI? = null
    private var tessCurrentLang: String = ""
    private var tessDataPath: String? = null

    /** Map language code → OCR backend. */
    private fun backendFor(code: String): OcrBackend {
        return when (code) {
            // Latin script → ML Kit Latin
            "en", "fr", "de", "es", "pt", "it", "nl", "pl", "cs",
            "tr", "vi", "id", "ms", "fil" -> OcrBackend.MLKIT_LATIN

            // CJK → ML Kit Chinese (handles Japanese, Korean too)
            "zh", "zh-Hant", "ja", "ko", "yue", "nan" -> OcrBackend.MLKIT_CHINESE

            // Devanagari → ML Kit Devanagari
            "hi", "mr", "gu" -> OcrBackend.MLKIT_DEVANAGARI

            // Everything else → Tesseract
            else -> OcrBackend.TESSERACT
        }
    }

    /** Map language code → Tesseract traineddata name. */
    private fun tessLangFor(code: String): String {
        return when (code) {
            "ru" -> "rus"
            "uk" -> "ukr"
            "ar" -> "ara"
            "fa" -> "fas"
            "ur" -> "urd"
            "he" -> "heb"
            "th" -> "tha"
            "bn" -> "ben"
            "ta" -> "tam"
            "te" -> "tel"
            "my" -> "mya"
            "km" -> "khm"
            "bo" -> "bod"
            "mn" -> "rus"  // Mongolian Cyrillic → use Russian model
            "ug" -> "ara"  // Uyghur Arabic script → use Arabic model
            else -> "eng"  // fallback
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    suspend fun recognize(bitmap: Bitmap, sourceLanguageCode: String = "en"): OcrResult {
        return when (backendFor(sourceLanguageCode)) {
            OcrBackend.MLKIT_LATIN -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, latinRecognizer)
            }
            OcrBackend.MLKIT_CHINESE -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, chineseRecognizer)
            }
            OcrBackend.MLKIT_DEVANAGARI -> {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeWithMlKit(image, devanagariRecognizer)
            }
            OcrBackend.TESSERACT -> {
                recognizeWithTesseract(bitmap, sourceLanguageCode)
            }
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    suspend fun recognize(
        imageProxy: androidx.camera.core.ImageProxy,
        sourceLanguageCode: String = "en"
    ): OcrResult {
        val backend = backendFor(sourceLanguageCode)

        if (backend == OcrBackend.TESSERACT) {
            val bitmap = imageProxyToBitmap(imageProxy)
            imageProxy.close()
            return if (bitmap != null) {
                recognizeWithTesseract(bitmap, sourceLanguageCode)
            } else {
                OcrResult(emptyList(), 0, 0)
            }
        }

        // ML Kit path — MUST keep imageProxy open until ML Kit finishes processing.
        // InputImage.fromMediaImage() does NOT copy data; it holds a reference
        // to the underlying buffer. Closing imageProxy before process() completes
        // destroys the buffer → empty/garbage OCR results.
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return OcrResult(emptyList(), 0, 0)
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)
        val w = imageProxy.width
        val h = imageProxy.height
        val recognizer = when (backend) {
            OcrBackend.MLKIT_CHINESE -> chineseRecognizer
            OcrBackend.MLKIT_DEVANAGARI -> devanagariRecognizer
            else -> latinRecognizer
        }
        return recognizeWithMlKit(image, recognizer, w, h) {
            imageProxy.close()  // close AFTER ML Kit is done
        }
    }

    // ── ML Kit ───────────────────────────────────────────────────────────

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

    // ── Tesseract ────────────────────────────────────────────────────────

    private fun ensureTesseractReady(langCode: String): Boolean {
        val tessLang = tessLangFor(langCode)

        // Already initialized for this language
        if (tessApi != null && tessCurrentLang == tessLang) return true

        // Close previous if different language
        tessApi?.recycle()
        tessApi = null

        try {
            val dataDir = File(context.filesDir, "tesseract")
            val tessDir = File(dataDir, "tessdata")
            tessDir.mkdirs()

            val trainedDataFile = File(tessDir, "$tessLang.traineddata")
            if (!trainedDataFile.exists()) {
                val assetName = "tessdata/$tessLang.traineddata"
                try {
                    context.assets.open(assetName).use { input ->
                        trainedDataFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Copied $assetName (${trainedDataFile.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy tessdata $assetName: ${e.message}", e)
                    return false
                }
            }

            val api = TessBaseAPI()
            if (!api.init(dataDir.absolutePath, tessLang)) {
                Log.e(TAG, "Tesseract init failed for $tessLang")
                return false
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

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
        if (!ensureTesseractReady(langCode)) {
            return OcrResult(emptyList(), bitmap.width, bitmap.height)
        }

        val api = tessApi ?: return OcrResult(emptyList(), bitmap.width, bitmap.height)

        val argbBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap

        api.setImage(argbBitmap)

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
                blocks.add(OcrBlock(blockText.toString().trim(), blockBox!!, currentLines.toList()))
            }
            iterator.delete()
        }

        return OcrResult(blocks, bitmap.width, bitmap.height)
    }

    // ── Utils ────────────────────────────────────────────────────────────

    @androidx.camera.core.ExperimentalGetImage
    private fun imageProxyToBitmap(imageProxy: androidx.camera.core.ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        // Build NV21 byte array: Y plane + interleaved VU
        val nv21 = ByteArray(width * height * 3 / 2)

        // Copy Y plane row by row (handles rowStride > width)
        val yBuffer = yPlane.buffer
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, row * width, width)
        }

        // Copy UV planes interleaved as VU (NV21 format)
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvHeight = height / 2
        val uvWidth = width / 2
        var uvIndex = width * height

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val bufferIndex = row * uvRowStride + col * uvPixelStride
                nv21[uvIndex++] = vBuffer.get(bufferIndex)  // V first (NV21)
                nv21[uvIndex++] = uBuffer.get(bufferIndex)  // then U
            }
        }

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21,
            width, height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 95, out)
        val bytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return null

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else bitmap
    }

    fun release() {
        latinRecognizer.close()
        chineseRecognizer.close()
        devanagariRecognizer.close()
        tessApi?.recycle()
        tessApi = null
    }
}

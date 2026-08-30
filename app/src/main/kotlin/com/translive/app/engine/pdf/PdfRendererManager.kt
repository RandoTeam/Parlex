package com.translive.app.engine.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfRendererManager @Inject constructor() {

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()
    private val renderDispatcher = Dispatchers.IO.limitedParallelism(1)

    // Memory-bounded LRU Cache (max 100MB for high-res pages)
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 6).coerceIn(32 * 1024, 100 * 1024) // 32MB - 100MB
    private val pageBitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    suspend fun openDocument(context: Context, uri: Uri): Int = withContext(renderDispatcher) {
        renderMutex.withLock {
            closeDocumentInternal()
            val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Cannot open file descriptor for $uri")
            pfd = fileDescriptor
            renderer = PdfRenderer(fileDescriptor)
            renderer?.pageCount ?: 0
        }
    }

    suspend fun getPageCount(): Int = withContext(renderDispatcher) {
        renderMutex.withLock {
            renderer?.pageCount ?: 0
        }
    }

    suspend fun renderPage(pageIndex: Int, targetDpi: Int = 200): Bitmap = withContext(renderDispatcher) {
        val cacheKey = "${pageIndex}_$targetDpi"
        pageBitmapCache.get(cacheKey)?.let { return@withContext it }

        renderMutex.withLock {
            val r = renderer ?: throw IllegalStateException("PdfRenderer not initialized")
            if (pageIndex < 0 || pageIndex >= r.pageCount) {
                throw IndexOutOfBoundsException("Page index $pageIndex is out of bounds (count: ${r.pageCount})")
            }

            val page = r.openPage(pageIndex)
            try {
                val scale = targetDpi / 72f
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE) // prevent transparent background black artifacts
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                pageBitmapCache.put(cacheKey, bitmap)
                bitmap
            } finally {
                page.close() // Must close before opening any subsequent page
            }
        }
    }

    suspend fun closeDocument() = withContext(renderDispatcher) {
        renderMutex.withLock {
            closeDocumentInternal()
        }
    }

    private fun closeDocumentInternal() {
        pageBitmapCache.evictAll()
        try {
            renderer?.close()
        } catch (_: Exception) {}
        renderer = null

        try {
            pfd?.close()
        } catch (_: Exception) {}
        pfd = null
    }
}

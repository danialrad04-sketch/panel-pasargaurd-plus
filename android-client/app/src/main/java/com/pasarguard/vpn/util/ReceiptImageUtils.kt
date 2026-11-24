package com.pasarguard.vpn.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class CompressedReceiptResult(
    val filePath: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

/**
 * Compresses user-selected payment receipt images before upload (max 1280px edge, target <= 350 KB JPEG).
 * Never logs file paths or receipt content.
 */
object ReceiptImageUtils {

    private const val MAX_DIMENSION_PX = 1280
    private const val TARGET_MAX_BYTES = 350 * 1024
    private const val UPLOAD_MAX_BYTES = 512 * 1024

    suspend fun compressReceiptUri(
        context: Context,
        sourceUri: Uri
    ): Result<CompressedReceiptResult> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(sourceUri) ?: error("Cannot open selected image")
            boundsStream.use { stream ->
                // A bounds-only decode returns null even when the image is readable.
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }

            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            check(srcW > 0 && srcH > 0) { "Could not read the image dimensions" }
            var sampleSize = 1
            while ((srcW / sampleSize) > MAX_DIMENSION_PX * 2 || (srcH / sampleSize) > MAX_DIMENSION_PX * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = resolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: error("Unsupported image format")

            val scaled = scaleDownIfNeeded(rawBitmap, MAX_DIMENSION_PX)
            if (scaled !== rawBitmap) {
                rawBitmap.recycle()
            }

            var quality = 85
            val baos = ByteArrayOutputStream()
            val finalW = scaled.width
            val finalH = scaled.height
            try {
                do {
                    baos.reset()
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)) { "Could not prepare receipt JPEG" }
                    quality -= 10
                } while (baos.size() > TARGET_MAX_BYTES && quality >= 35)
            } finally {
                scaled.recycle()
            }
            check(baos.size() in 1..UPLOAD_MAX_BYTES) { "The prepared receipt is too large" }

            val outDir = File(context.cacheDir, "receipts").apply { mkdirs() }
            val outFile = File(outDir, "rcpt_${UUID.randomUUID().toString().take(12)}.jpg")
            FileOutputStream(outFile).use { fos ->
                baos.writeTo(fos)
            }

            CompressedReceiptResult(
                filePath = outFile.absolutePath,
                sizeBytes = outFile.length(),
                width = finalW,
                height = finalH
            )
        }
    }

    fun decodePreviewBitmap(filePath: String, maxSizePx: Int = 512): Bitmap? {
        return runCatching {
            val file = File(filePath)
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            var sample = 1
            while ((bounds.outWidth / sample) > maxSizePx * 2 || (bounds.outHeight / sample) > maxSizePx * 2) {
                sample *= 2
            }
            BitmapFactory.decodeFile(filePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        if (maxDim <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / maxDim.toFloat()
        val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }
}

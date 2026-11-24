package com.pasarguard.vpn.ui.screens.login

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/** Decodes the luminance plane of a CameraX frame on the analyzer thread. */
internal class CameraBarcodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.TRY_HARDER to true))
    }
    private var luminance = ByteArray(0)

    fun decode(
        plane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        offset: Int,
        rotationDegrees: Int
    ): String? {
        val start = plane.position() + offset
        if (width <= 0 || height <= 0 || start < plane.position() ||
            start + (height - 1) * rowStride + (width - 1) * pixelStride >= plane.limit()
        ) return null

        val rotated = rotationDegrees == 90 || rotationDegrees == 270
        val outputWidth = if (rotated) height else width
        val outputHeight = if (rotated) width else height
        if (luminance.size != width * height) luminance = ByteArray(width * height)

        val source = plane.duplicate()
        for (y in 0 until height) {
            val rowStart = start + y * rowStride
            if (rotationDegrees == 0 && pixelStride == 1) {
                source.position(rowStart)
                source.get(luminance, y * width, width)
                continue
            }

            var output = when (rotationDegrees) {
                90 -> height - 1 - y
                180 -> (height - y) * width - 1
                270 -> (width - 1) * height + y
                else -> y * width
            }
            val step = when (rotationDegrees) {
                90 -> outputWidth
                180 -> -1
                270 -> -outputWidth
                else -> 1
            }

            var input = rowStart
            repeat(width) {
                luminance[output] = source.get(input)
                input += pixelStride
                output += step
            }
        }

        val image = PlanarYUVLuminanceSource(
            luminance, outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, false
        )
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(image))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}

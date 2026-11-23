package com.pasarguard.vpn.ui.screens.login

import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class CameraBarcodeDecoderTest {
    @Test
    fun decodesSubscriptionQrFromCroppedPaddedPlane() {
        val url = "https://panel.example.com/sub/example-subscription"
        val qr = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 217, 217)
        val rowStride = qr.width * 2 + 26
        val start = 13
        val offset = rowStride * 2 + 4
        val pixels = ByteArray(start + offset + rowStride * qr.height) { 0xff.toByte() }
        for (y in 0 until qr.height) {
            for (x in 0 until qr.width) {
                if (qr[x, y]) pixels[start + offset + y * rowStride + x * 2] = 0
            }
        }
        val plane = ByteBuffer.wrap(pixels).apply { position(start) }

        val decoded = CameraBarcodeDecoder().decode(
            plane, qr.width, qr.height, rowStride, 2, offset, 0
        )

        assertEquals(url, decoded)
        assertEquals(start, plane.position())
    }

    @Test
    fun decodesRotatedBarcodeAndCanReuseDecoderForLaterFrames() {
        val token = "subscription-123"
        val code = Code128Writer().encode(token, BarcodeFormat.CODE_128, 400, 96)
        val width = code.height
        val height = code.width
        val rowStride = width + 8
        val pixels = ByteArray(rowStride * height) { 0xff.toByte() }
        for (y in 0 until code.height) {
            for (x in 0 until code.width) {
                if (code[x, y]) pixels[(height - 1 - x) * rowStride + y] = 0
            }
        }
        val decoder = CameraBarcodeDecoder()

        assertEquals(token, decoder.decode(ByteBuffer.wrap(pixels), width, height, rowStride, 1, 0, 90))
        pixels.fill(0xff.toByte())
        assertNull(decoder.decode(ByteBuffer.wrap(pixels), width, height, rowStride, 1, 0, 90))
    }
}

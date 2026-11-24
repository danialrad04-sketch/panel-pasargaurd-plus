package com.pasarguard.vpn.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import com.pasarguard.vpn.BrandConfig
import com.pasarguard.vpn.domain.model.CalendarSystem
import com.pasarguard.vpn.domain.model.OrderStatus
import com.pasarguard.vpn.domain.model.PaymentOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Generates a clean, shareable PNG receipt/invoice image for a [PaymentOrder] using Android Canvas
 * and shares it safely via FileProvider.
 */
object InvoiceGenerator {

    suspend fun generateInvoiceFile(
        context: Context,
        order: PaymentOrder,
        isPersian: Boolean,
        persianDigits: Boolean,
        calendarSystem: CalendarSystem
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val width = 1080
            val height = 1480
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Deep space dark gradient background
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(Color.parseColor("#0B1120"), Color.parseColor("#07090E")),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            // Hero header card
            val headerRect = RectF(64f, 72f, width - 64f, 340f)
            val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    headerRect.left, headerRect.top, headerRect.right, headerRect.bottom,
                    intArrayOf(Color.parseColor("#1D4ED8"), Color.parseColor("#06B6D4")),
                    null,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(headerRect, 44f, 44f, headerPaint)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 54f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(220, 255, 255, 255)
                textSize = 34f
            }
            canvas.drawText(BrandConfig.appName, 110f, 168f, titlePaint)
            canvas.drawText(
                if (isPersian) "رسید سفارش اشتراک" else "Subscription Payment Receipt",
                110f,
                228f,
                subPaint
            )

            val statusLabel = when (order.status) {
                OrderStatus.APPROVED -> if (isPersian) "تأیید شده" else "APPROVED"
                OrderStatus.PENDING_REVIEW -> if (isPersian) "در انتظار تأیید" else "PENDING REVIEW"
                OrderStatus.REJECTED -> if (isPersian) "رد شده" else "REJECTED"
                OrderStatus.EXPIRED -> if (isPersian) "منقضی شده" else "EXPIRED"
                OrderStatus.AWAITING_RECEIPT -> if (isPersian) "در انتظار رسید" else "AWAITING RECEIPT"
            }
            val statusColor = when (order.status) {
                OrderStatus.APPROVED -> Color.parseColor("#10B981")
                OrderStatus.PENDING_REVIEW, OrderStatus.AWAITING_RECEIPT -> Color.parseColor("#F59E0B")
                OrderStatus.REJECTED, OrderStatus.EXPIRED -> Color.parseColor("#F43F5E")
            }
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = statusColor }
            val badgeRect = RectF(110f, 256f, 480f, 312f)
            canvas.drawRoundRect(badgeRect, 28f, 28f, badgePaint)
            val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 30f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(statusLabel, 140f, 295f, badgeTextPaint)

            // Glass body container
            val bodyRect = RectF(64f, 384f, width - 64f, 1340f)
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#141B2D")
            }
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = Color.argb(45, 255, 255, 255)
            }
            canvas.drawRoundRect(bodyRect, 44f, 44f, bodyPaint)
            canvas.drawRoundRect(bodyRect, 44f, 44f, borderPaint)

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textSize = 32f
            }
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F8FAFC")
                textSize = 36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(30, 255, 255, 255)
                strokeWidth = 2f
            }

            val dateText = Formatters.formatEpochDate(
                epochSeconds = order.createdAtMs / 1000L,
                locale = if (isPersian) Locale("fa") else Locale.US,
                calendarSystem = calendarSystem,
                persianDigits = persianDigits
            )
            val currencyUnit = if (isPersian) "تومان" else "Toman"
            val finalAmount = "${Formatters.formatNumber(order.finalPriceToman, persianDigits)} $currencyUnit"
            val discountText = if (order.discountAmountToman > 0L) {
                "-${Formatters.formatNumber(order.discountAmountToman, persianDigits)} $currencyUnit (${order.discountCode.orEmpty()})"
            } else {
                "—"
            }

            val rows = listOf(
                (if (isPersian) "کد سفارش" else "Order ID") to order.id,
                (if (isPersian) "پلن انتخابی" else "Plan") to (if (isPersian) order.planTitleFa else order.planTitleEn),
                (if (isPersian) "تاریخ ثبت" else "Date") to dateText,
                (if (isPersian) "مبلغ نهایی" else "Final Amount") to finalAmount,
                (if (isPersian) "تخفیف" else "Discount") to discountText,
                (if (isPersian) "شماره پیگیری" else "Tracking Number") to (order.trackingNumber ?: "—"),
                (if (isPersian) "۴ رقم آخر کارت مبدا" else "Payer Card Last 4") to (order.payerCardLast4 ?: "—"),
                (if (isPersian) "بانک مقصد" else "Destination Bank") to (if (isPersian) order.destinationBankFa else order.destinationBankEn)
            )

            var y = 465f
            rows.forEachIndexed { idx, (label, value) ->
                canvas.drawText(label, 112f, y, labelPaint)
                canvas.drawText(value.take(42), 112f, y + 46f, valuePaint)
                if (idx < rows.lastIndex) {
                    canvas.drawLine(112f, y + 72f, width - 112f, y + 72f, dividerPaint)
                }
                y += 108f
            }

            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#64748B")
                textSize = 26f
            }
            canvas.drawText(
                if (isPersian) "تولید شده توسط اپلیکیشن رسمی Donino Vpn" else "Generated by Donino Vpn Client",
                112f,
                1410f,
                footerPaint
            )

            val outDir = File(context.cacheDir, "invoices").apply { mkdirs() }
            val outFile = File(outDir, "invoice_${order.id}.png")
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            bitmap.recycle()
            outFile
        }
    }

    fun shareInvoiceFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(shareIntent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

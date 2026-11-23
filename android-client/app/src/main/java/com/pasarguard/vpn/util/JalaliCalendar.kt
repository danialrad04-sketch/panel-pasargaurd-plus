package com.pasarguard.vpn.util

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-Kotlin Gregorian <-> Jalali (Solar Hijri / Shamsi) calendar converter and Persian number
 * formatter with zero Android framework dependency so it runs identically on Android and JVM tests.
 */
object JalaliCalendar {

    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    private val PERSIAN_MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد",
        "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر",
        "دی", "بهمن", "اسفند"
    )

    private val LATIN_MONTHS = arrayOf(
        "Farvardin", "Ordibehesht", "Khordad",
        "Tir", "Mordad", "Shahrivar",
        "Mehr", "Aban", "Azar",
        "Dey", "Bahman", "Esfand"
    )

    /**
     * Exact arithmetic Gregorian-to-Jalali conversion (Kazimierz M. Borkowski / JDF algorithm).
     */
    fun gregorianToJalali(gYear: Int, gMonth: Int, gDay: Int): JalaliDate {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gMonth > 2) gYear + 1 else gYear
        var days = 355666 + (365 * gYear) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gDay + gDaysInMonth[gMonth - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val (jm, jd) = if (days < 186) {
            (1 + (days / 31)) to (1 + (days % 31))
        } else {
            (7 + ((days - 186) / 30)) to (1 + ((days - 186) % 30))
        }
        return JalaliDate(jy, jm, jd)
    }

    fun fromEpochSeconds(epochSeconds: Long, timeZone: TimeZone = TimeZone.getDefault()): JalaliDate {
        val cal = Calendar.getInstance(timeZone).apply {
            timeInMillis = epochSeconds * 1000L
        }
        return gregorianToJalali(
            gYear = cal.get(Calendar.YEAR),
            gMonth = cal.get(Calendar.MONTH) + 1,
            gDay = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    fun formatJalaliNumeric(epochSeconds: Long, persianDigits: Boolean = true): String {
        val j = fromEpochSeconds(epochSeconds)
        val raw = String.format(Locale.US, "%04d/%02d/%02d", j.year, j.month, j.day)
        return if (persianDigits) toPersianDigits(raw) else raw
    }

    fun formatJalaliVerbose(
        epochSeconds: Long,
        persianLanguage: Boolean = true,
        persianDigits: Boolean = persianLanguage
    ): String {
        val j = fromEpochSeconds(epochSeconds)
        val monthName = if (persianLanguage) {
            PERSIAN_MONTHS.getOrElse(j.month - 1) { "" }
        } else {
            LATIN_MONTHS.getOrElse(j.month - 1) { "" }
        }
        val raw = "${j.day} $monthName ${j.year}"
        return if (persianDigits) toPersianDigits(raw) else raw
    }

    fun formatThousands(number: Long, persianDigits: Boolean = false): String {
        val formatted = String.format(Locale.US, "%,d", number)
        return if (persianDigits) {
            toPersianDigits(formatted.replace(',', '٬'))
        } else {
            formatted
        }
    }

    fun toPersianDigits(text: String): String {
        val digits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
        return buildString(text.length) {
            for (ch in text) {
                if (ch in '0'..'9') append(digits[ch - '0']) else append(ch)
            }
        }
    }
}

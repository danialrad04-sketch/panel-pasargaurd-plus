package com.pasarguard.vpn.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class LogEntry(
    val timestamp: String,
    val level: String,
    val tag: String,
    val message: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * In-memory ring buffer of sanitized VPN diagnostics logs.
 * Automatically redacts UUIDs, bearer/subscription tokens, passwords, private keys, and full domains
 * so users can safely inspect or share diagnostics reports with support without leaking secrets.
 *
 * Accessible both via Hilt injection and via companion helpers so VpnService/cores/UI all share
 * one consistent ring buffer.
 */
@Singleton
class SanitizedLogBuffer @Inject constructor() {

    val entries: StateFlow<List<LogEntry>> get() = logs

    fun info(tag: String, rawMessage: String) = Companion.info(tag, rawMessage)
    fun warn(tag: String, rawMessage: String) = Companion.warn(tag, rawMessage)
    fun error(tag: String, rawMessage: String) = Companion.error(tag, rawMessage)
    fun clear() = Companion.clear()
    fun exportReport(headerLines: List<String> = emptyList()): String =
        Companion.exportReport(headerLines)

    companion object {
        private const val MAX_ENTRIES = 250

        private val lock = Any()
        private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
        private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
        val logs: StateFlow<List<LogEntry>> = _entries.asStateFlow()

        private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        private val UUID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val SUB_TOKEN_REGEX = Regex("/sub/[A-Za-z0-9_\\-]{4,}", RegexOption.IGNORE_CASE)
        private val SECRET_PARAM_REGEX = Regex("(password|pbk|sid|pqv|encryption|token|key|secret)=([^&\\s\"']+)", RegexOption.IGNORE_CASE)
        private val PROXY_URI_REGEX = Regex("(vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard)://\\S+", RegexOption.IGNORE_CASE)
        private val DOMAIN_REGEX = Regex("([a-zA-Z0-9-]+\\.)+([a-zA-Z]{2,})")

        fun info(tag: String, rawMessage: String) = append("INFO", tag, rawMessage)
        fun warn(tag: String, rawMessage: String) = append("WARN", tag, rawMessage)
        fun error(tag: String, rawMessage: String) = append("ERROR", tag, rawMessage)

        fun clear() {
            synchronized(lock) {
                buffer.clear()
                _entries.value = emptyList()
            }
        }

        fun exportReport(headerLines: List<String> = emptyList()): String = buildString {
            appendLine("=== PasarGuard VPN Diagnostics Report ===")
            headerLines.forEach { appendLine(sanitize(it)) }
            appendLine("---")
            val snapshot = synchronized(lock) { buffer.toList() }
            if (snapshot.isEmpty()) {
                appendLine("(No events recorded yet)")
            } else {
                snapshot.forEach { e ->
                    appendLine("[${e.timestamp}] [${e.level}] ${e.tag}: ${e.message}")
                }
            }
        }

        fun exportReport(appVersion: String, coreVersion: String): String =
            exportReport(
                listOf(
                    "App Version: $appVersion",
                    "Core Engine: $coreVersion"
                )
            )

        private fun append(level: String, tag: String, rawMessage: String) {
            val nowMs = System.currentTimeMillis()
            val entry = LogEntry(
                timestamp = synchronized(timeFmt) { timeFmt.format(Date(nowMs)) },
                level = level,
                tag = tag,
                message = sanitize(rawMessage),
                timestampMs = nowMs
            )
            synchronized(lock) {
                if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
                buffer.addLast(entry)
                _entries.value = buffer.toList()
            }
        }

        fun sanitize(raw: String): String {
            var s = raw.trim()
            s = PROXY_URI_REGEX.replace(s) { "${it.groupValues[1]}://[REDACTED]" }
            s = UUID_REGEX.replace(s, "[UUID-REDACTED]")
            s = SUB_TOKEN_REGEX.replace(s, "/sub/[TOKEN-REDACTED]")
            s = SECRET_PARAM_REGEX.replace(s) { "${it.groupValues[1]}=[REDACTED]" }
            s = DOMAIN_REGEX.replace(s) { match ->
                val full = match.value
                if (full.endsWith("gstatic.com") || full.endsWith("cloudflare.com") || full.endsWith("google.com")) {
                    full
                } else {
                    "***." + match.groupValues[2]
                }
            }
            return s.take(260)
        }
    }
}

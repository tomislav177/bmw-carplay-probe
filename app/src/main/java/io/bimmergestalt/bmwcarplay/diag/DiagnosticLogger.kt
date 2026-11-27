package io.bimmergestalt.bmwcarplay.diag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Always-on session diagnostic logger.
 *
 * Writes every log entry immediately to disk under filesDir/logs/session_YYYYMMDD_HHMMSS.log
 * from the first millisecond of each test session, keeping the 10 most recent session files.
 *
 * On cold app launch, preserves the most recent real session file so tapping "Podijeli log"
 * after reopening the app shares the actual last car test instead of an empty startup stub.
 */
class DiagnosticLogger(
    context: Context,
) {
    companion object {
        private const val TAG = "BmwCarPlayProbe"
        private const val LOG_DIR_NAME = "logs"
        private const val MAX_SESSION_FILES = 10
        private const val MAX_IN_MEMORY_LINES = 2_500
    }

    private val logsDir: File = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }
    private val fileLock = Any()
    private val uiLinesLock = Any()

    private val lineTimeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private var currentSessionFile: File? = null
    private var writer: BufferedWriter? = null

    private val ringBuffer = ArrayDeque<String>(MAX_IN_MEMORY_LINES)
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    @Volatile
    var fullHexEnabled: Boolean = true

    init {
        restoreLastNonEmptySessionPreview()
    }

    /**
     * Rotates to a fresh session log file on disk and prunes files beyond the last 10 sessions.
     */
    fun startNewSession(banner: String): File {
        synchronized(fileLock) {
            closeWriterLocked()
            val stamp = fileTimeFormat.format(Date())
            var candidate = File(logsDir, "session_$stamp.log")
            var suffix = 1
            while (candidate.exists()) {
                candidate = File(logsDir, "session_${stamp}_$suffix.log")
                suffix++
            }
            currentSessionFile = candidate
            writer = BufferedWriter(OutputStreamWriter(FileOutputStream(candidate, true), Charsets.UTF_8))
            pruneOldSessionsLocked()
        }
        synchronized(uiLinesLock) {
            ringBuffer.clear()
            _logLines.value = emptyList()
        }
        log("SESIJA", "=== $banner ===")
        log("SESIJA", "Datoteka loga: ${currentSessionFile?.name}")
        return currentSessionFile!!
    }

    /**
     * Appends a structured diagnostic line: HH:mm:ss.SSS [CATEGORY] message.
     */
    fun log(category: String, message: String) {
        val timestamp = synchronized(lineTimeFormat) { lineTimeFormat.format(Date()) }
        val cleanMessage = HexDump.sanitizeLogText(message)
        val formatted = "$timestamp [$category] $cleanMessage"
        Log.d(TAG, formatted)

        synchronized(fileLock) {
            try {
                ensureWriterLocked()
                writer?.apply {
                    write(formatted)
                    newLine()
                    flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Greška pri zapisu log datoteke: ${e.message}")
            }
        }

        synchronized(uiLinesLock) {
            if (ringBuffer.size >= MAX_IN_MEMORY_LINES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(formatted)
            _logLines.value = ringBuffer.toList()
        }
    }

    /**
     * Appends a packet summary and, when fullHexEnabled is true, a multi-line HEX/ASCII dump.
     */
    fun logPacket(category: String, summary: String, bytes: ByteArray) {
        if (fullHexEnabled) {
            val hex = if (bytes.size <= 32) {
                "HEX (${bytes.size} B): ${HexDump.toCompactHex(bytes)}"
            } else {
                "HEX (${bytes.size} B):\n${HexDump.toMultilineHex(bytes)}"
            }
            log(category, "$summary\n$hex")
        } else {
            log(category, "$summary (${bytes.size} B)")
        }
    }

    /**
     * Clears only the on-screen log viewer; the persisted disk file remains intact.
     */
    fun clearScreenLog() {
        synchronized(uiLinesLock) {
            ringBuffer.clear()
            _logLines.value = emptyList()
        }
    }

    fun getCurrentSessionFile(): File? = synchronized(fileLock) { currentSessionFile }

    /**
     * Returns full persisted content of the current session log file (or live buffer fallback).
     */
    fun readCurrentSessionText(): String {
        val file = synchronized(fileLock) {
            runCatching { writer?.flush() }
            currentSessionFile
        }
        if (file != null && file.exists()) {
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            if (text != null) return text
        }
        return synchronized(uiLinesLock) { ringBuffer.joinToString("\n") }
    }

    /**
     * Lists saved session log files sorted newest-first (up to 10 files).
     */
    fun listSessionFiles(): List<File> =
        logsDir.listFiles { f -> f.isFile && f.name.startsWith("session_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun readSessionFile(file: File): String =
        runCatching { file.readText(Charsets.UTF_8) }.getOrElse { "Greška pri čitanju datoteke: ${it.message}" }

    private fun restoreLastNonEmptySessionPreview() {
        val latest = listSessionFiles().firstOrNull { it.length() > 200L } ?: listSessionFiles().firstOrNull()
        if (latest == null) return
        synchronized(fileLock) {
            currentSessionFile = latest
        }
        runCatching {
            val lines = latest.readLines(Charsets.UTF_8)
            val tail = if (lines.size > MAX_IN_MEMORY_LINES) lines.takeLast(MAX_IN_MEMORY_LINES) else lines
            synchronized(uiLinesLock) {
                ringBuffer.clear()
                ringBuffer.addAll(tail)
                _logLines.value = ringBuffer.toList()
            }
        }
    }

    private fun ensureWriterLocked() {
        if (writer != null) return
        val stamp = fileTimeFormat.format(Date())
        var candidate = File(logsDir, "session_$stamp.log")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(logsDir, "session_${stamp}_$suffix.log")
            suffix++
        }
        currentSessionFile = candidate
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(candidate, true), Charsets.UTF_8))
        pruneOldSessionsLocked()
    }

    private fun pruneOldSessionsLocked() {
        val files = listSessionFiles()
        if (files.size > MAX_SESSION_FILES) {
            files.drop(MAX_SESSION_FILES).forEach { oldFile ->
                if (oldFile.absolutePath != currentSessionFile?.absolutePath) {
                    runCatching { oldFile.delete() }
                }
            }
        }
    }

    private fun closeWriterLocked() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
    }
}

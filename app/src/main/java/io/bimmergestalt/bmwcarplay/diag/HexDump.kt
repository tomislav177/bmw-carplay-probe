package io.bimmergestalt.bmwcarplay.diag

/**
 * Formatting helpers for binary protocol diagnostics and log sanitization.
 */
object HexDump {
    /**
     * Replaces NUL characters with the visible Unicode symbol so exported logs
     * never turn into binary blobs when opened in text editors or messaging apps.
     */
    fun sanitizeLogText(text: String): String = text.replace('\u0000', '\u2400')

    /**
     * Compact uppercase hex string separated by spaces (e.g. "FF 55 02 00 EE 10").
     */
    fun toCompactHex(bytes: ByteArray, maxBytes: Int = bytes.size): String {
        if (bytes.isEmpty()) return "<prazno>"
        val limit = minOf(bytes.size, maxBytes)
        val sb = StringBuilder(limit * 3 + 16)
        for (i in 0 until limit) {
            if (i > 0) sb.append(' ')
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        if (bytes.size > limit) {
            sb.append(" ... (+").append(bytes.size - limit).append(" B)")
        }
        return sb.toString()
    }

    /**
     * Multi-line canonical hex + ASCII dump (16 bytes per line).
     */
    fun toMultilineHex(bytes: ByteArray, maxBytes: Int = 2048): String {
        if (bytes.isEmpty()) return "  <prazno>"
        val limit = minOf(bytes.size, maxBytes)
        val sb = StringBuilder((limit / 16 + 2) * 76)
        var offset = 0
        while (offset < limit) {
            if (offset > 0) sb.append('\n')
            sb.append("  ")
            sb.append(String.format("%04X", offset))
            sb.append("  ")
            val rowEnd = minOf(offset + 16, limit)
            for (i in offset until offset + 16) {
                if (i == offset + 8) sb.append(' ')
                if (i < rowEnd) {
                    val v = bytes[i].toInt() and 0xFF
                    sb.append(HEX_CHARS[v ushr 4])
                    sb.append(HEX_CHARS[v and 0x0F])
                    sb.append(' ')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(" |")
            for (i in offset until rowEnd) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('|')
            offset += 16
        }
        if (bytes.size > limit) {
            sb.append("\n  ... (skraćeno, ukupno ").append(bytes.size).append(" bajtova)")
        }
        return sb.toString()
    }

    private const val HEX_CHARS = "0123456789ABCDEF"
}

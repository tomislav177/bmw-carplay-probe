package io.bimmergestalt.bmwcarplay.airplay

/**
 * Decodes AirPlay `_airplay._tcp` TXT `features` bitmask (e.g. `0x4040280,0x61`)
 * and determines whether the head unit expects HomeKit pair-verify (ChaCha20-Poly1305)
 * or legacy unencrypted RTSP + AES-128-CTR in Phase 2.
 */
object AirPlayFeatures {

    /** Bit 38 (0x26): Supports CoreUtils / HomeKit Pairing and Encryption (HK_PAIRING_AND_ENCRYPT). */
    const val BIT_HK_PAIRING_AND_ENCRYPT = 38

    /** Bit 37 (0x25): CarPlay control / wireless CarPlay feature bit. */
    const val BIT_CARPLAY_CONTROL = 37

    /** Additional Apple pairing/encryption capability bits (43, 46, 48). */
    private val EXTRA_PAIRING_BITS = intArrayOf(43, 46, 48)

    data class FeaturesAnalysis(
        val rawValue: String,
        val mask: Long,
        val hasBit38HkPairing: Boolean,
        val hasAnyPairingBit: Boolean,
        val hasBit37CarPlayControl: Boolean,
        val setBits: List<Int>,
    ) {
        fun phase2VerdictHr(): String = if (hasBit38HkPairing) {
            "Bit 38 (HK_PAIRING_AND_ENCRYPT) = POSTAVLJEN (1) → Za Fazu 2 BMW HU_ENTRYNAV2 očekuje HomeKit pair-verify (ChaCha20-Poly1305)!"
        } else if (hasAnyPairingBit) {
            "Bit 38 = 0, ali su postavljeni pomoćni pairing bitovi (${EXTRA_PAIRING_BITS.filter { hasBit(mask, it) }}) → Provjeriti HomeKit pair-verify u Fazi 2."
        } else {
            "Bit 38 (HK_PAIRING_AND_ENCRYPT) = NIJE POSTAVLJEN (0) → Za Fazu 2 BMW HU_ENTRYNAV2 koristi neenkriptirani RTSP (et=NONE) + AES-128-CTR fallback!"
        }

        fun formatReportHr(): String =
            "AirPlay features analiza (sirovo=\"$rawValue\", mask=0x${mask.toULong().toString(16).uppercase()}):\n" +
                "  Bit 38 (HK_PAIRING_AND_ENCRYPT): ${if (hasBit38HkPairing) "DA (1)" else "NE (0)"}\n" +
                "  Bit 37 (CARPLAY_CONTROL): ${if (hasBit37CarPlayControl) "DA (1)" else "NE (0)"}\n" +
                "  Postavljeni bitovi: ${setBits.joinToString(", ")}\n" +
                "  Zaključak za Fazu 2: ${phase2VerdictHr()}"
    }

    /**
     * Parses AirPlay features string in either `0xLOW,0xHIGH` format (standard mDNS TXT)
     * or single hex (`0x...`) / decimal integer format. Returns null if unparseable.
     */
    fun parseMask(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.contains(',')) {
                val parts = trimmed.split(',')
                if (parts.size != 2) return null
                val low = parsePart(parts[0]) ?: return null
                val high = parsePart(parts[1]) ?: return null
                (high shl 32) or (low and 0xFFFFFFFFL)
            } else {
                parsePart(trimmed)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun analyze(raw: String?): FeaturesAnalysis? {
        val mask = parseMask(raw) ?: return null
        val bit38 = hasBit(mask, BIT_HK_PAIRING_AND_ENCRYPT)
        val anyPairing = bit38 || EXTRA_PAIRING_BITS.any { hasBit(mask, it) }
        val bit37 = hasBit(mask, BIT_CARPLAY_CONTROL)
        val setBits = (0 until 64).filter { hasBit(mask, it) }
        return FeaturesAnalysis(
            rawValue = raw.orEmpty().trim(),
            mask = mask,
            hasBit38HkPairing = bit38,
            hasAnyPairingBit = anyPairing,
            hasBit37CarPlayControl = bit37,
            setBits = setBits,
        )
    }

    fun hasBit(mask: Long, bitIndex: Int): Boolean =
        bitIndex in 0..63 && ((mask ushr bitIndex) and 1L) != 0L

    private fun parsePart(token: String): Long? {
        val s = token.trim()
        if (s.isEmpty()) return null
        return if (s.startsWith("0x", ignoreCase = true)) {
            s.substring(2).toULongOrNull(16)?.toLong()
        } else {
            s.toULongOrNull(10)?.toLong() ?: s.toULongOrNull(16)?.toLong()
        }
    }
}

package io.bimmergestalt.bmwcarplay.airplay.rtsp

import io.bimmergestalt.bmwcarplay.diag.HexDump

/**
 * Pure-Kotlin decoder for BMW `HU_ENTRYNAV2` (`AirTunes/280.33.8`) CarPlay `hidSendReport` packets.
 *
 * Verified live against BMW X1 F48 encrypted `/info` `hidDevices[]`:
 * - `TouchDescriptor` (`05 0D 09 04`, X `0..1280`, Y `0..480`):
 *   5 bytes `[tip_u8 (bit 0 = touch)][X_u16LE][Y_u16LE]` (or 6 bytes if prefixed with 1-byte Report ID).
 * - `KnobDescriptor` (`05 01 09 08`):
 *   4 bytes `[buttons: bit0=Select, bit1=Home, bit2=Back][X_s8][Y_s8][Wheel_s8]` (or 5 bytes with Report ID).
 * - `MediaButtons` (`05 0C 09 01`):
 *   1 byte `[bit0=Next, bit1=Prev, bit2=Play/Pause, bit3=Play, bit4=Pause]`.
 * - `PhoneDescriptor` (`05 0B 09 01`):
 *   2–3 bytes `[bit0=Voice/Siri, bit1=Hook, bit2=Drop][keypad bits...]`.
 */
object CarPlayHidDecoder {

    enum class DeviceKind {
        TOUCH,
        KNOB,
        MEDIA,
        PHONE,
        UNKNOWN,
    }

    data class HidDeviceInfo(
        val uuid: Any,
        val name: String,
        val kind: DeviceKind,
        val maxX: Int = 1280,
        val maxY: Int = 480,
    ) {
        val uuidKey: String get() = uuidToKey(uuid)
    }

    data class TouchPoint(
        val down: Boolean,
        val x: Int,
        val y: Int,
    )

    data class DecodedReport(
        val kind: DeviceKind,
        val deviceName: String,
        val summaryHr: String,
        val touch: TouchPoint? = null,
    )

    data class ExtractedReport(
        val uuid: Any?,
        val report: ByteArray?,
        val sourceNote: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ExtractedReport) return false
            val uuidEqual = when {
                uuid is ByteArray && other.uuid is ByteArray -> uuid.contentEquals(other.uuid)
                else -> uuid == other.uuid
            }
            if (!uuidEqual) return false
            if (report != null) {
                if (other.report == null) return false
                if (!report.contentEquals(other.report)) return false
            } else if (other.report != null) {
                return false
            }
            return sourceNote == other.sourceNote
        }

        override fun hashCode(): Int {
            var result = when (uuid) {
                is ByteArray -> uuid.contentHashCode()
                else -> uuid?.hashCode() ?: 0
            }
            result = 31 * result + (report?.contentHashCode() ?: 0)
            result = 31 * result + sourceNote.hashCode()
            return result
        }
    }

    private val UUID_KEYS = listOf(
        "uuid",
        "hidDeviceUUID",
        "hidUUID",
        "deviceUUID",
        "deviceID",
        "hidDevice",
        "id",
    )

    private val REPORT_KEYS = listOf(
        "hidReport",
        "report",
        "data",
        "hidData",
        "payload",
    )

    private val NESTED_REPORT_CONTAINER_KEYS = listOf(
        "hidReports",
        "reports",
    )

    private data class CandidateExtraction(
        val uuid: Any?,
        val report: ByteArray?,
        val source: String,
    )

    /**
     * Flexibly extracts the HID device UUID and raw HID report bytes from an incoming AirPlay
     * `hidSendReport` bplist dictionary. Inspects `cmd["params"]` first, then falls back to
     * top-level `cmd`, including nested `"hidReports"` / `"reports"` lists or maps.
     */
    fun extractFromCommand(cmd: Map<*, *>): ExtractedReport {
        val candidates = ArrayList<CandidateExtraction>(6)
        val params = findValueIgnoreCase(cmd, "params") as? Map<*, *>
        if (params != null) {
            collectMapCandidates(params, "params", candidates)
        }
        collectMapCandidates(cmd, "top-level", candidates)

        // Prefer a single self-contained candidate carrying both UUID and non-empty report bytes
        // before cross-scope stitching (prevents a generic outer "id" from shadowing a nested "uuid").
        candidates.firstOrNull { it.uuid != null && it.report != null && it.report.isNotEmpty() }?.let { complete ->
            return ExtractedReport(uuid = complete.uuid, report = complete.report, sourceNote = complete.source)
        }

        var uuid: Any? = null
        var uuidSource: String? = null
        var report: ByteArray? = null
        var reportSource: String? = null

        for (cand in candidates) {
            if (uuid == null && cand.uuid != null) {
                uuid = cand.uuid
                uuidSource = cand.source
            }
            if (cand.report != null) {
                if (report == null || (report.isEmpty() && cand.report.isNotEmpty())) {
                    report = cand.report
                    reportSource = cand.source
                }
            }
            if (uuid != null && report != null && report.isNotEmpty()) {
                break
            }
        }

        val sourceNote = when {
            uuidSource == null && reportSource == null -> "missing"
            uuidSource == reportSource -> uuidSource!!
            else -> "uuid=${uuidSource ?: "missing"},report=${reportSource ?: "missing"}"
        }
        return ExtractedReport(uuid = uuid, report = report, sourceNote = sourceNote)
    }

    private fun collectMapCandidates(
        map: Map<*, *>,
        scopeLabel: String,
        out: MutableList<CandidateExtraction>,
    ) {
        val directUuid = findUuidInMap(map)
        val directReport = findReportInMap(map)
        if (directUuid != null || directReport != null) {
            out.add(CandidateExtraction(directUuid, directReport, scopeLabel))
        }
        for (nestedKey in NESTED_REPORT_CONTAINER_KEYS) {
            val container = findValueIgnoreCase(map, nestedKey) ?: continue
            val nestedLabel = "$scopeLabel.$nestedKey"
            extractFromNestedContainer(container, nestedLabel)?.let { out.add(it) }
        }
    }

    private fun extractFromNestedContainer(container: Any, nestedLabel: String): CandidateExtraction? {
        return when (container) {
            is List<*> -> {
                var fallback: CandidateExtraction? = null
                for (item in container) {
                    val extracted = when (item) {
                        is Map<*, *> -> extractFromNestedMap(item, nestedLabel)
                        is ByteArray -> CandidateExtraction(null, item, nestedLabel)
                        else -> null
                    } ?: continue

                    if (extracted.report != null && extracted.report.isNotEmpty()) {
                        return extracted
                    }
                    if (fallback == null && (extracted.uuid != null || extracted.report != null)) {
                        fallback = extracted
                    }
                }
                fallback
            }

            is Map<*, *> -> extractFromNestedMap(container, nestedLabel)
            is ByteArray -> CandidateExtraction(null, container, nestedLabel)
            else -> null
        }
    }

    private fun extractFromNestedMap(map: Map<*, *>, nestedLabel: String): CandidateExtraction? {
        val u = findUuidInMap(map)
        val r = findReportInMap(map)
        if (u != null || r != null) {
            return CandidateExtraction(u, r, nestedLabel)
        }
        // Fallback for maps keyed directly by UUID -> ByteArray (or List<ByteArray>)
        for ((k, v) in map) {
            if (k == null) continue
            val bytes = extractReportFromValue(v) ?: continue
            if (bytes.isNotEmpty()) {
                return CandidateExtraction(k, bytes, nestedLabel)
            }
        }
        return null
    }

    private fun findValueIgnoreCase(map: Map<*, *>, targetKey: String): Any? {
        val direct = map[targetKey]
        if (direct != null) return direct
        for ((k, v) in map) {
            if (v != null && k?.toString()?.equals(targetKey, ignoreCase = true) == true) {
                return v
            }
        }
        return null
    }

    private fun findUuidInMap(map: Map<*, *>): Any? {
        for (candidate in UUID_KEYS) {
            val value = findValueIgnoreCase(map, candidate) ?: continue
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is Map<*, *> -> {
                    val nested = findUuidInMap(value)
                    if (nested != null) return nested
                }
                else -> return value
            }
        }
        return null
    }

    private fun findReportInMap(map: Map<*, *>): ByteArray? {
        var emptyFallback: ByteArray? = null
        for (candidate in REPORT_KEYS) {
            val raw = findValueIgnoreCase(map, candidate) ?: continue
            val bytes = extractReportFromValue(raw) ?: continue
            if (bytes.isNotEmpty()) return bytes
            if (emptyFallback == null) emptyFallback = bytes
        }
        return emptyFallback
    }

    private fun extractReportFromValue(value: Any?): ByteArray? = when (value) {
        is ByteArray -> value
        is List<*> -> value.filterIsInstance<ByteArray>().firstOrNull { it.isNotEmpty() }
        else -> null
    }

    fun classifyDevice(name: String?, descriptor: ByteArray?): DeviceKind {
        val n = name.orEmpty().lowercase()
        return when {
            "touch" in n -> DeviceKind.TOUCH
            "knob" in n || "rotary" in n || "idrive" in n -> DeviceKind.KNOB
            "media" in n || "consumer" in n -> DeviceKind.MEDIA
            "phone" in n || "telephony" in n -> DeviceKind.PHONE
            descriptor != null && descriptor.size >= 4 -> classifyByDescriptor(descriptor)
            else -> DeviceKind.UNKNOWN
        }
    }

    private fun classifyByDescriptor(desc: ByteArray): DeviceKind {
        if (desc[0] != 0x05.toByte()) return DeviceKind.UNKNOWN
        return when (desc[1].toInt() and 0xFF) {
            0x0D -> DeviceKind.TOUCH // Digitizer
            0x01 -> DeviceKind.KNOB // Generic Desktop (Multi-axis / Wheel)
            0x0C -> DeviceKind.MEDIA // Consumer
            0x0B -> DeviceKind.PHONE // Telephony
            else -> DeviceKind.UNKNOWN
        }
    }

    fun decode(
        uuid: Any?,
        report: ByteArray?,
        devicesByUuidKey: Map<String, HidDeviceInfo>,
        screenWidth: Int,
        screenHeight: Int,
    ): DecodedReport {
        val raw = report ?: ByteArray(0)
        val hex = HexDump.toCompactHex(raw, 24)
        val dev = uuid?.let { devicesByUuidKey[uuidToKey(it)] }
        val kind = dev?.kind ?: DeviceKind.UNKNOWN
        val devName = dev?.name ?: "HID(${uuid?.let(::uuidToKey) ?: "?"})"

        if (raw.isEmpty()) {
            return DecodedReport(kind, devName, "$devName: <prazan izvještaj>")
        }

        return when (kind) {
            DeviceKind.TOUCH -> decodeTouch(devName, raw, hex, dev?.maxX ?: screenWidth, dev?.maxY ?: screenHeight)
            DeviceKind.KNOB -> decodeKnob(devName, raw, hex)
            DeviceKind.MEDIA -> decodeMedia(devName, raw, hex)
            DeviceKind.PHONE -> decodePhone(devName, raw, hex)
            DeviceKind.UNKNOWN -> DecodedReport(kind, devName, "$devName [${raw.size}B: $hex]")
        }
    }

    private fun decodeTouch(
        devName: String,
        raw: ByteArray,
        hex: String,
        maxX: Int,
        maxY: Int,
    ): DecodedReport {
        val clampedMaxX = maxX.coerceAtLeast(1)
        val clampedMaxY = maxY.coerceAtLeast(1)

        // 10-byte dual-slot report: [slot0 5B][slot1 5B] (or 11-byte with leading Report ID).
        // Read slot 0; only check slot 1 if slot 0 is inactive at (0,0) while slot 1 is active
        // so an idle (0,0) slot never overwrites an active touch.
        if (raw.size == 10 || raw.size == 11) {
            val baseOffset = if (raw.size == 11) 1 else 0
            val s0Down = (raw[baseOffset].toInt() and 0x01) != 0
            val s0X = readU16Le(raw, baseOffset + 1).coerceIn(0, clampedMaxX)
            val s0Y = readU16Le(raw, baseOffset + 3).coerceIn(0, clampedMaxY)

            val s1Down = (raw[baseOffset + 5].toInt() and 0x01) != 0
            val s1X = readU16Le(raw, baseOffset + 6).coerceIn(0, clampedMaxX)
            val s1Y = readU16Le(raw, baseOffset + 8).coerceIn(0, clampedMaxY)

            val useSlot1 = !s0Down && s0X == 0 && s0Y == 0 && s1Down
            val down = if (useSlot1) s1Down else s0Down
            val x = if (useSlot1) s1X else s0X
            val y = if (useSlot1) s1Y else s0Y
            val slotTag = if (useSlot1) " [slot1]" else ""
            val stateHr = if (down) "DODIR DOLJE" else "DODIR GORE"
            return DecodedReport(
                kind = DeviceKind.TOUCH,
                deviceName = devName,
                summaryHr = "$stateHr ($x, $y)$slotTag [${raw.size}B: $hex]",
                touch = TouchPoint(down = down, x = x, y = y),
            )
        }

        // Standard 5-byte single-touch report: [tip_u8][x_u16LE][y_u16LE]
        // Also support 6-byte report with leading Report ID byte: [id][tip_u8][x_u16LE][y_u16LE]
        val baseOffset = if (raw.size == 6) 1 else 0
        if (raw.size < baseOffset + 5) {
            return DecodedReport(DeviceKind.TOUCH, devName, "$devName [${raw.size}B: $hex]")
        }
        val tipByte = raw[baseOffset].toInt() and 0xFF
        val down = (tipByte and 0x01) != 0
        val x = readU16Le(raw, baseOffset + 1).coerceIn(0, clampedMaxX)
        val y = readU16Le(raw, baseOffset + 3).coerceIn(0, clampedMaxY)
        val stateHr = if (down) "DODIR DOLJE" else "DODIR GORE"
        return DecodedReport(
            kind = DeviceKind.TOUCH,
            deviceName = devName,
            summaryHr = "$stateHr ($x, $y) [${raw.size}B: $hex]",
            touch = TouchPoint(down = down, x = x, y = y),
        )
    }

    private fun decodeKnob(devName: String, raw: ByteArray, hex: String): DecodedReport {
        val baseOffset = if (raw.size == 5) 1 else 0
        if (raw.size < baseOffset + 4) {
            return DecodedReport(DeviceKind.KNOB, devName, "$devName [${raw.size}B: $hex]")
        }
        val buttons = raw[baseOffset].toInt() and 0xFF
        val nudgeX = raw[baseOffset + 1].toInt() // signed byte
        val nudgeY = raw[baseOffset + 2].toInt() // signed byte
        val wheel = raw[baseOffset + 3].toInt() // signed byte

        val parts = ArrayList<String>(5)
        if ((buttons and 0x01) != 0) parts.add("KLIK")
        if ((buttons and 0x02) != 0) parts.add("HOME")
        if ((buttons and 0x04) != 0) parts.add("BACK")
        when {
            nudgeX < 0 -> parts.add("LIJEVO($nudgeX)")
            nudgeX > 0 -> parts.add("DESNO(+$nudgeX)")
        }
        when {
            nudgeY < 0 -> parts.add("GORE($nudgeY)")
            nudgeY > 0 -> parts.add("DOLJE(+$nudgeY)")
        }
        if (wheel != 0) {
            val dir = if (wheel > 0) "+$wheel ↻" else "$wheel ↺"
            parts.add("KOTAČIĆ $dir")
        }
        val action = if (parts.isEmpty()) "OTPUŠTENO" else parts.joinToString(" + ")
        return DecodedReport(
            kind = DeviceKind.KNOB,
            deviceName = devName,
            summaryHr = "iDrive $action [${raw.size}B: $hex]",
        )
    }

    private fun decodeMedia(devName: String, raw: ByteArray, hex: String): DecodedReport {
        val b = raw[0].toInt() and 0xFF
        val parts = ArrayList<String>(3)
        if ((b and 0x01) != 0) parts.add("SLJEDEĆA")
        if ((b and 0x02) != 0) parts.add("PRETHODNA")
        if ((b and 0x04) != 0) parts.add("PLAY/PAUSE")
        if ((b and 0x08) != 0) parts.add("PLAY")
        if ((b and 0x10) != 0) parts.add("PAUSE")
        val action = if (parts.isEmpty()) "OTPUŠTENO" else parts.joinToString("+")
        return DecodedReport(DeviceKind.MEDIA, devName, "Media: $action [${raw.size}B: $hex]")
    }

    private fun decodePhone(devName: String, raw: ByteArray, hex: String): DecodedReport {
        val b = raw[0].toInt() and 0xFF
        val parts = ArrayList<String>(3)
        if ((b and 0x01) != 0) parts.add("GLAS/SIRI")
        if ((b and 0x02) != 0) parts.add("POZIV")
        if ((b and 0x04) != 0) parts.add("PREKID")
        val action = if (parts.isEmpty()) "OTPUŠTENO" else parts.joinToString("+")
        return DecodedReport(DeviceKind.PHONE, devName, "Telefon: $action [${raw.size}B: $hex]")
    }

    private fun readU16Le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun uuidToKey(uuid: Any): String = when (uuid) {
        is ByteArray -> if (uuid.size == 16) formatUuidBytes(uuid) else HexDump.toCompactHex(uuid, uuid.size).lowercase()
        else -> uuid.toString().lowercase()
    }

    private fun formatUuidBytes(b: ByteArray): String {
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            val v = b[i].toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}

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
        val uuidKey: String get() = uuid.toString().lowercase()
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
        val dev = uuid?.toString()?.lowercase()?.let { devicesByUuidKey[it] }
        val kind = dev?.kind ?: DeviceKind.UNKNOWN
        val devName = dev?.name ?: "HID(${uuid ?: "?"})"

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
        // Standard 5-byte single-touch report: [tip_u8][x_u16LE][y_u16LE]
        // Also tolerate 6-byte report with leading Report ID byte, or 10-byte dual-slot report (first slot).
        val baseOffset = if (raw.size == 6) 1 else 0
        if (raw.size < baseOffset + 5) {
            return DecodedReport(DeviceKind.TOUCH, devName, "$devName [${raw.size}B: $hex]")
        }
        val tipByte = raw[baseOffset].toInt() and 0xFF
        val down = (tipByte and 0x01) != 0
        val x = readU16Le(raw, baseOffset + 1).coerceIn(0, maxX.coerceAtLeast(1))
        val y = readU16Le(raw, baseOffset + 3).coerceIn(0, maxY.coerceAtLeast(1))
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

        val parts = ArrayList<String>(4)
        if ((buttons and 0x01) != 0) parts.add("KLIK")
        if ((buttons and 0x02) != 0) parts.add("HOME")
        if ((buttons and 0x04) != 0) parts.add("BACK")
        if (nudgeX != 0 || nudgeY != 0) parts.add("POMAK($nudgeX,$nudgeY)")
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
}

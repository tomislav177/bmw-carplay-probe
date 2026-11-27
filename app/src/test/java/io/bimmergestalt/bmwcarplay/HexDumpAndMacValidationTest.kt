package io.bimmergestalt.bmwcarplay

import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.HexDump
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2CsmCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HexDumpAndMacValidationTest {

    @Test
    fun macValidationAcceptsStandardUpperAndLowercaseAndRejectsInvalid() {
        assertTrue(ProbePrefs.isValidMac("7C:7B:BF:74:40:67"))
        assertTrue(ProbePrefs.isValidMac("f8:30:02:fc:79:a4"))
        assertEquals("F8:30:02:FC:79:A4", ProbePrefs.normalizeMac(" f8:30:02:fc:79:a4 "))

        assertFalse(ProbePrefs.isValidMac(""))
        assertFalse(ProbePrefs.isValidMac("7C-7B-BF-74-40-67"))
        assertFalse(ProbePrefs.isValidMac("7C:7B:BF:74:40"))
        assertFalse(ProbePrefs.isValidMac("ZZ:7B:BF:74:40:67"))
    }

    @Test
    fun hexDumpSanitizesNulBytesAndFormatsCompactAndMultiline() {
        val raw = "10.174.189.1\u0000\u0000"
        val sanitized = HexDump.sanitizeLogText(raw)
        assertFalse(sanitized.contains('\u0000'))
        assertEquals("10.174.189.1\u2400\u2400", sanitized)

        val marker = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        assertEquals("FF 55 02 00 EE 10", HexDump.toCompactHex(marker))

        val multiline = HexDump.toMultilineHex("Hello BMW CarPlay!".toByteArray(Charsets.US_ASCII))
        assertTrue(multiline.contains("0000"))
        assertTrue(multiline.contains("0010"))
        assertTrue(multiline.contains("|Hello BMW CarPla|"))
    }

    @Test
    fun unknownCsmTlvDumpExposesAllParamIdsAndReadableStrings() {
        val body = Iap2CsmCodec.paramU8(0, 1) +
            Iap2CsmCodec.paramString(1, "BMW_TEST") +
            Iap2CsmCodec.paramRaw(9, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        val dump = Iap2CsmCodec.dumpParamsReadable(body)
        assertTrue(dump.contains("0x0000"))
        assertTrue(dump.contains("0x0001"))
        assertTrue(dump.contains("\"BMW_TEST\""))
        assertTrue(dump.contains("0x0009"))
        assertTrue(dump.contains("DE AD BE EF"))
    }
}

package io.bimmergestalt.bmwcarplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BinaryPlistTest {

    @Test
    fun roundTripAllCoreTypesIncludingUnicodeAndNestedInfoStructure() {
        val sampleInfo = linkedMapOf<String, Any?>(
            "model" to "HU_ENTRYNAV2",
            "name" to "BMW X1 ČĆŽŠĐ",
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to false,
            "statusFlags" to 68,
            "maxFPS" to 60,
            "ratio" to 2.6666,
            "hidDescriptor" to byteArrayOf(0x05, 0x0D, 0x09, 0x04),
            "displays" to listOf(
                linkedMapOf(
                    "widthPixels" to 1280,
                    "heightPixels" to 480,
                    "widthPhysical" to 210,
                    "heightPhysical" to 79,
                    "maxFPS" to 60,
                ),
            ),
        )

        val encoded = BinaryPlist.encode(sampleInfo)
        val parsed = BinaryPlist.parse(encoded) as Map<*, *>

        assertEquals("HU_ENTRYNAV2", parsed["model"])
        assertEquals("BMW X1 ČĆŽŠĐ", parsed["name"])
        assertEquals(true, parsed["keepAliveLowPower"])
        assertEquals(false, parsed["keepAliveSendStatsAsBody"])
        assertEquals(68L, parsed["statusFlags"])
        assertEquals(2.6666, parsed["ratio"] as Double, 0.0001)
        assertArrayEquals(byteArrayOf(0x05, 0x0D, 0x09, 0x04), parsed["hidDescriptor"] as ByteArray)

        val displays = parsed["displays"] as List<*>
        val d0 = displays[0] as Map<*, *>
        assertEquals(1280L, d0["widthPixels"])
        assertEquals(480L, d0["heightPixels"])
    }

    @Test
    fun unsignedPortsAbove32767EncodeSafelyInFourBytesAndDecodePositively() {
        val testValues = listOf(
            0L,
            127L,
            128L,
            255L,
            256L,
            32767L,
            32768L,
            35449L,
            49152L,
            65535L,
            65536L,
            -1L,
            -30087L,
        )

        for (v in testValues) {
            val encoded = BinaryPlist.encode(v)
            // Root int is immediately at offset 8
            val marker = encoded[8].toInt() and 0xFF
            when {
                v in 0L..127L -> assertEquals("0..127 must use 1-byte int (0x10)", 0x10, marker)
                v in 128L..32767L -> assertEquals("128..32767 must use 2-byte int (0x11)", 0x11, marker)
                v in 32768L..Int.MAX_VALUE.toLong() -> {
                    assertEquals("Ports 32768..65535 must use 4-byte int (0x12) so signed parsers never see negative values", 0x12, marker)
                }
                v < 0L -> assertEquals("Negative ints must use 8-byte int (0x13)", 0x13, marker)
            }
            assertEquals("Round-trip failed for $v", v, BinaryPlist.parse(encoded))
        }
    }

    @Test
    fun parsesTwoByteUnsignedPortFromHeadUnitAsPositive() {
        // Construct a minimal bplist00 containing a single 2-byte integer 0xC000 (49152)
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        // Object 0 at offset 8: marker 0x11 (2-byte int), value 0xC0 0x00 = 49152
        out.write(0x11)
        out.write(0xC0)
        out.write(0x00)
        // Offset table at offset 11: single 1-byte offset = 8
        out.write(8)
        // Trailer (32 bytes): unused(6), offsetIntSize(1)=1, refSize(1)=1, numObjects(8)=1, top(8)=0, offsetTableOffset(8)=11
        out.write(ByteArray(6))
        out.write(1)
        out.write(1)
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        out.write(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 11))

        val parsed = BinaryPlist.parse(out.toByteArray())
        assertEquals(49152L, parsed)
    }

    @Test
    fun rootObjectAlwaysStartsAtByteOffsetEightEvenWithMoreThan255Objects() {
        val largeMap = LinkedHashMap<String, Any?>()
        for (i in 0 until 200) {
            largeMap["key_$i"] = i + 40000
        }
        val encoded = BinaryPlist.encode(largeMap)

        // Byte 8 MUST be the dictionary marker (0xDF for count >= 15)
        assertEquals(0xDF, encoded[8].toInt() and 0xFF)

        // Trailer topObject must be 0
        val trailerStart = encoded.size - 32
        var topObject = 0L
        for (i in 0 until 8) {
            topObject = (topObject shl 8) or (encoded[trailerStart + 16 + i].toLong() and 0xFFL)
        }
        assertEquals(0L, topObject)

        val decoded = BinaryPlist.parse(encoded) as Map<*, *>
        assertEquals(200, decoded.size)
        assertEquals(40199L, decoded["key_199"])
        assertTrue(encoded.size > 1000)
    }
}

package io.bimmergestalt.bmwcarplay.airplay.rtsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TimingServerTest {

    @Test
    fun buildsValid32ByteNtpTimesyncResponseEchoingRequestT3() {
        val request = ByteArray(32)
        request[0] = 0x80.toByte() // version=2
        request[1] = 0xD2.toByte() // PT_TIMESYNC_REQUEST
        request[2] = 0x00
        request[3] = 0x07 // (32/4) - 1 = 7
        val carT3 = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte())
        System.arraycopy(carT3, 0, request, 24, 8)

        val response = TimingServer.buildResponse(request, receiveNanos = 12_500_000_000L, transmitNanos = 12_500_100_000L)
        assertNotNull(response)
        assertEquals(32, response!!.size)
        assertEquals(0x80.toByte(), response[0])
        assertEquals(0xD3.toByte(), response[1])
        assertEquals(0x07.toByte(), response[3])
        // t1 (offset 8..15) MUST echo car's t3 (offset 24..31)
        assertArrayEquals(carT3, response.copyOfRange(8, 16))
        // t2 seconds == 12 (0x0000000C), fraction == 0.5s (0x80000000)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x0C, 0x80.toByte(), 0x00, 0x00, 0x00),
            response.copyOfRange(16, 24),
        )
    }

    @Test
    fun rejectsMalformedOrNonTimesyncPackets() {
        assertNull(TimingServer.buildResponse(ByteArray(16), 1_000L))
        val wrongPt = ByteArray(32).apply {
            this[0] = 0x80.toByte()
            this[1] = 0xD3.toByte()
            this[3] = 0x07
        }
        assertNull(TimingServer.buildResponse(wrongPt, 1_000L))
    }
}

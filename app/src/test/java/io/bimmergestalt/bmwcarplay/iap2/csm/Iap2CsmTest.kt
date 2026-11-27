package io.bimmergestalt.bmwcarplay.iap2.csm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2CsmTest {

    @Test
    fun csmReassemblerHandlesFragmentedAndCombinedFrames() {
        val f1 = Iap2CsmCodec.encodeFrame(Iap2MessageIds.START_IDENTIFICATION)
        val f2 = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE,
            Iap2CsmCodec.paramRaw(0, ByteArray(20) { (it + 1).toByte() }),
        )
        val f3 = Iap2CsmCodec.encodeFrame(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG)

        val stream = f1 + f2 + f3
        val reassembler = Iap2CsmCodec.CsmReassembler()
        val collected = ArrayList<Iap2CsmCodec.CsmFrame>()

        // Feed irregular fragments (3 bytes, 7 bytes, rest)
        collected.addAll(reassembler.feed(stream.copyOfRange(0, 3)))
        collected.addAll(reassembler.feed(stream.copyOfRange(3, 10)))
        collected.addAll(reassembler.feed(stream.copyOfRange(10, stream.size)))

        assertEquals(3, collected.size)
        assertEquals(Iap2MessageIds.START_IDENTIFICATION, collected[0].messageId)
        assertEquals(Iap2MessageIds.REQUEST_AUTH_CHALLENGE_RESPONSE, collected[1].messageId)
        assertEquals(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIG, collected[2].messageId)

        val challengeParam = collected[1].parseParams().single()
        assertEquals(0, challengeParam.id)
        assertEquals(20, challengeParam.value.size)
    }

    @Test
    fun parseIdentification0x1D01ExtractsConfirmedBmwFields() {
        val btComp = Iap2CsmCodec.paramGroup(
            17,
            Iap2CsmCodec.paramU16(0, 1),
            Iap2CsmCodec.paramString(1, "BMW 04990"),
            Iap2CsmCodec.paramEmpty(2),
            Iap2CsmCodec.paramRaw(
                3,
                byteArrayOf(0xF8.toByte(), 0x30, 0x02, 0xFC.toByte(), 0x79, 0xA4.toByte()),
            ),
        )
        val cpComp = Iap2CsmCodec.paramGroup(
            24,
            Iap2CsmCodec.paramU16(0, 2),
            Iap2CsmCodec.paramString(1, "DIRECT-BMW-04990"),
            Iap2CsmCodec.paramEmpty(4),
        )
        val frameBytes = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.IDENTIFICATION_INFORMATION,
            Iap2CsmCodec.paramString(0, "BMW 04990"),
            Iap2CsmCodec.paramString(1, "HU_ENTRYNAV2"),
            Iap2CsmCodec.paramString(2, "Alpine"),
            Iap2CsmCodec.paramString(3, "AL07FFL0665583"),
            Iap2CsmCodec.paramString(4, "EntryEvo_ID5_2007"),
            Iap2CsmCodec.paramString(5, "HW33"),
            Iap2CsmCodec.paramU16List(6, listOf(0x5703, 0x4301)),
            Iap2CsmCodec.paramU16List(7, listOf(0x5702, 0x4300, 0x4E0E)),
            btComp,
            cpComp,
        )

        val frame = Iap2CsmCodec.CsmReassembler().feed(frameBytes).single()
        val info = Iap2Parsers.parseIdentificationInfo(frame.body)

        assertEquals("BMW 04990", info.name)
        assertEquals("HU_ENTRYNAV2", info.modelIdentifier)
        assertEquals("Alpine", info.manufacturer)
        assertEquals("AL07FFL0665583", info.serialNumber)
        assertEquals(listOf(0x5703, 0x4301), info.messagesSentByAccessory)
        assertEquals(listOf(0x5702, 0x4300, 0x4E0E), info.messagesReceivedFromDevice)
        assertEquals("F8:30:02:FC:79:A4", info.bluetoothTransports.single().macAddress)
        assertTrue(info.wirelessCarPlayTransports.single().supportsCarPlay)
    }

    @Test
    fun parseIdentificationExtractsPpidHidAndReportsAbsentCarPlayTransport() {
        val hid = Iap2CsmCodec.paramGroup(
            18,
            Iap2CsmCodec.paramU16(0, 3),
            Iap2CsmCodec.paramString(1, "Media Buttons"),
            Iap2CsmCodec.paramU8(2, 1),
        )
        val frameBytes = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.IDENTIFICATION_INFORMATION,
            Iap2CsmCodec.paramString(0, "BMW Group Infotainment System"),
            Iap2CsmCodec.paramString(1, "Entry Evo"),
            Iap2CsmCodec.paramString(2, "ALPINE Electronics,Inc."),
            Iap2CsmCodec.paramString(11, "2GA5JE4AXM"),
            hid,
        )
        val frame = Iap2CsmCodec.CsmReassembler().feed(frameBytes).single()
        val info = Iap2Parsers.parseIdentificationInfo(frame.body)

        assertEquals("2GA5JE4AXM", info.ppid)
        assertEquals(1, info.hidComponents.size)
        assertEquals("Media Buttons", info.hidComponents[0].name)
        assertTrue(info.wirelessCarPlayTransports.isEmpty())
        assertTrue(info.formatReportHr().contains("PPID"))
    }

    @Test
    fun parseWifiConfig0x5703ExtractsSsidPassphraseSecurityAndChannel() {
        val frameBytes = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.ACCESSORY_WIFI_CONFIG,
            Iap2CsmCodec.paramString(1, "DIRECT-BMW-04990"),
            Iap2CsmCodec.paramString(2, "SecretPass5GHz!"),
            Iap2CsmCodec.paramU8(3, 2), // WPA/WPA2
            Iap2CsmCodec.paramU8(4, 149), // 5 GHz channel 149
        )
        val frame = Iap2CsmCodec.CsmReassembler().feed(frameBytes).single()
        val cfg = Iap2Parsers.parseWifiConfiguration(frame.body)

        assertEquals("DIRECT-BMW-04990", cfg.ssid)
        assertEquals("SecretPass5GHz!", cfg.passphrase)
        assertEquals(Iap2Parsers.WifiSecurityType.WPA_WPA2, cfg.securityType)
        assertEquals(149, cfg.channel)
    }

    @Test
    fun parseStartSession0x4301StripsMultipleTrailingNulBytesFromIpAddresses() {
        val nulPaddedIpv4 = "10.174.189.1".toByteArray(Charsets.UTF_8) + ByteArray(5) { 0 }
        val nulPaddedIpv6 = "fe80::1234:5678".toByteArray(Charsets.UTF_8) + ByteArray(16) { 0 }

        val wirelessGroup = Iap2CsmCodec.paramGroup(
            1,
            Iap2CsmCodec.paramRaw(3, nulPaddedIpv4),
            Iap2CsmCodec.paramRaw(3, nulPaddedIpv6),
        )
        val frameBytes = Iap2CsmCodec.encodeFrame(
            Iap2MessageIds.START_WIRELESS_CARPLAY_SESSION,
            wirelessGroup,
            Iap2CsmCodec.paramU32(2, 7000L),
            Iap2CsmCodec.paramString(3, "F8:30:02:FC:79:A4"),
            Iap2CsmCodec.paramString(5, "320.17.7"),
        )

        val frame = Iap2CsmCodec.CsmReassembler().feed(frameBytes).single()
        val parsed = Iap2Parsers.parseCarPlayStartSession(frame.body)

        assertEquals(7000, parsed.airPlayPort)
        assertEquals(listOf("10.174.189.1", "fe80::1234:5678"), parsed.ipAddresses)
        assertEquals("F8:30:02:FC:79:A4", parsed.deviceIdentifier)
        assertEquals("320.17.7", parsed.sourceVersion)
    }

    @Test
    fun buildPhoneIdentification0x1D01IncludesRealBtMac() {
        val bytes = Iap2Parsers.buildPhoneIdentification0x1D01("iPhone", "7C:7B:BF:74:40:67")
        val frame = Iap2CsmCodec.CsmReassembler().feed(bytes).single()
        assertEquals(Iap2MessageIds.IDENTIFICATION_INFORMATION, frame.messageId)

        val info = Iap2Parsers.parseIdentificationInfo(frame.body)
        assertEquals("iPhone", info.name)
        assertEquals("7C:7B:BF:74:40:67", info.bluetoothTransports.single().macAddress)
        assertArrayEquals(
            byteArrayOf(0x7C, 0x7B, 0xBF.toByte(), 0x74, 0x40, 0x67),
            frame.parseParams().first { it.id == 17 }.parseNestedParams().first { it.id == 3 }.value,
        )
    }

    @Test
    fun x509CertInfoClassifiesMfi20Sha1RsaVsMfi30Ecdsa() {
        val bmwMfi20 = Iap2Parsers.X509CertInfo(
            rawLength = 908,
            subjectDn = "CN=IPA_2222AA190918AA06AA7612AA285231,OU=Apple iPod Accessories,O=Apple Inc.,C=US",
            commonName = "IPA_2222AA190918AA06AA7612AA285231",
            issuerDn = "CN=Apple iPod Accessories Certification Authority",
            sigAlgName = "SHA1withRSA",
            keyAlgorithm = "RSA",
        )
        assertTrue(bmwMfi20.isMfi20Sha1Rsa)

        val mfi30 = Iap2Parsers.X509CertInfo(
            rawLength = 611,
            subjectDn = "CN=MFi30",
            commonName = "MFi30",
            issuerDn = "CN=Apple MFi 3.0",
            sigAlgName = "SHA256withECDSA",
            keyAlgorithm = "EC",
        )
        assertEquals(false, mfi30.isMfi20Sha1Rsa)
    }
}

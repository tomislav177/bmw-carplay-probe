package io.bimmergestalt.bmwcarplay.aap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

class AapSessionClientTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun fullSessionLifecycleVersionTlsServiceDiscoveryFragmentedVideoInputAndCleanShutdown() {
        val certDir = tempFolder.newFolder("aap-client-full")
        val certStore = AapCertStore(certDir)
        val profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)

        val expectedSpsPps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x28, 0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val expectedTimestampUs = 987_654_321L
        val expected24kFrame = ByteArray(24_576) { i ->
            when (i) {
                0, 1, 2 -> 0x00
                3 -> 0x01
                4 -> 0x65 // IDR slice NAL
                else -> ((i * 29 + 11) and 0xFF).toByte()
            }
        }

        val serverError = AtomicReference<Throwable?>(null)
        val receivedShutdownReason = AtomicReference<Int?>(null)
        val receivedTouch = AtomicReference<Triple<Int, Int, Int>?>(null)
        val receivedRelative = AtomicReference<Pair<Int, Int>?>(null)
        val receivedNightUpdate = AtomicReference<Boolean?>(null)
        val receivedFocusUpdate = AtomicReference<Int?>(null)

        val videoFrameReceivedLatch = CountDownLatch(1)
        val frameAckReceivedLatch = CountDownLatch(1)
        val inputReadyLatch = CountDownLatch(1)
        val spsReceivedRef = AtomicReference<ByteArray?>(null)
        val videoFrameRef = AtomicReference<Pair<Long, ByteArray>?>(null)
        val sessionEndedLatch = CountDownLatch(1)
        val endedCertRejected = AtomicBoolean(false)

        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Mock AA Phone, O=Google")
        val serverEngine = createServerEngine(serverChain, serverKey, acceptClientCert = true)
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newSingleThreadExecutor()

        val serverTask = exec.submit {
            try {
                serverSocket.soTimeout = 10_000
                serverSocket.accept().use { sock ->
                    sock.soTimeout = 10_000
                    val input = BufferedInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()

                    // 1. Version handshake
                    val verRaw = AapFramer.readRawFrame(input)
                    assertEquals(AapProto.CH_CONTROL, verRaw.channelId)
                    assertEquals(AapProto.VERSION_REQUEST, AapFramer.readMessageId(verRaw.payload))
                    assertArrayEquals(byteArrayOf(0x00, 0x01, 0x00, 0x06), verRaw.payload.copyOfRange(2, 6))
                    sendPlain(output, AapProto.CH_CONTROL, AapProto.VERSION_RESPONSE, AapProto.encodeVersionResponse(1, 6, 0))

                    // 2. Mutual TLS 1.2 handshake over SSL_HANDSHAKE
                    runServerTlsHandshake(serverEngine, input, output)
                    assertNotNull(serverEngine.session.peerCertificates.firstOrNull())

                    // 3. Plaintext AUTH_COMPLETE (status=0)
                    val authRaw = AapFramer.readRawFrame(input)
                    assertFalse(authRaw.isEncrypted)
                    assertEquals(AapProto.AUTH_COMPLETE, AapFramer.readMessageId(authRaw.payload))
                    assertEquals(0, AapProto.decodeAuthComplete(authRaw.payload.copyOfRange(2, authRaw.payload.size)))

                    val serverAssembler = AapFrameAssembler()
                    fun readEncMsg(): Pair<AapFramer.RawFrame, ByteArray> {
                        while (true) {
                            val raw = AapFramer.readRawFrame(input)
                            assertTrue("Expected encrypted frame on ch=${raw.channelId}", raw.isEncrypted)
                            val plain = serverUnwrap(serverEngine, raw.payload)
                            val msg = serverAssembler.feed(raw.channelId, raw.flags, plain, raw.totalSizeHint)
                            if (msg != null) return raw to msg
                        }
                    }

                    // 4. Service Discovery
                    sendEnc(serverEngine, output, AapProto.CH_CONTROL, AapFramer.FLAG_ENC_BULK, AapProto.SERVICE_DISCOVERY_REQUEST, ByteArray(0))
                    val (_, sdpMsg) = readEncMsg()
                    assertEquals(AapProto.SERVICE_DISCOVERY_RESPONSE, AapFramer.readMessageId(sdpMsg))
                    val sdpFields = AapProto.ProtoReader(sdpMsg, 2, sdpMsg.size).readAllFields()
                    assertEquals(5, sdpFields.count { it.fieldNumber == 1 })
                    assertEquals("AL07FFL0665583", sdpFields.first { it.fieldNumber == 5 }.asString())

                    // 5. Ping echo
                    val pingPayload = byteArrayOf(0x42, 0x43, 0x44)
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_CONTROL,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.PING_REQUEST,
                        AapProto.encodePingRequest(123456L, false, pingPayload),
                    )
                    val (_, pingRespMsg) = readEncMsg()
                    assertEquals(AapProto.PING_RESPONSE, AapFramer.readMessageId(pingRespMsg))
                    val pingResp = AapProto.decodePingResponse(pingRespMsg.copyOfRange(2, pingRespMsg.size))
                    assertEquals(123456L, pingResp.timestamp)
                    assertArrayEquals(pingPayload, pingResp.payload)

                    // 6. Audio focus + Nav focus
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_CONTROL,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AUDIO_FOCUS_REQUEST,
                        AapProto.encodeAudioFocusRequest(AapProto.AUDIO_FOCUS_GAIN_TRANSIENT),
                    )
                    val (_, afMsg) = readEncMsg()
                    assertEquals(AapProto.AUDIO_FOCUS_RESPONSE, AapFramer.readMessageId(afMsg))
                    val af = AapProto.decodeAudioFocusResponse(afMsg.copyOfRange(2, afMsg.size))
                    assertEquals(AapProto.AUDIO_FOCUS_STATE_GAIN_TRANSIENT, af.focusState)
                    assertTrue(af.granted)

                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_CONTROL,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.NAV_FOCUS_REQUEST,
                        AapProto.encodeNavFocusRequest(AapProto.NAV_FOCUS_PROJECTED),
                    )
                    val (_, nfMsg) = readEncMsg()
                    assertEquals(AapProto.NAV_FOCUS_RESPONSE, AapFramer.readMessageId(nfMsg))
                    assertEquals(AapProto.NAV_FOCUS_PROJECTED, AapProto.decodeNavFocusResponse(nfMsg.copyOfRange(2, nfMsg.size)))

                    // 7. Open Sensor channel -> expect CHANNEL_OPEN_RESPONSE (FLAG_ENC_CONTROL) + 3 initial sensors
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_SENSOR,
                        AapFramer.FLAG_ENC_CONTROL,
                        AapProto.CHANNEL_OPEN_REQUEST,
                        AapProto.encodeChannelOpenRequest(0, AapProto.CH_SENSOR),
                    )
                    val (sensorOpenRaw, sensorOpenMsg) = readEncMsg()
                    assertEquals(AapProto.CH_SENSOR, sensorOpenRaw.channelId)
                    assertEquals(AapFramer.FLAG_ENC_CONTROL, sensorOpenRaw.flags)
                    assertEquals(AapProto.CHANNEL_OPEN_RESPONSE, AapFramer.readMessageId(sensorOpenMsg))
                    assertEquals(0, AapProto.decodeChannelOpenResponse(sensorOpenMsg.copyOfRange(2, sensorOpenMsg.size)))

                    val initialSensorFieldNums = mutableSetOf<Int>()
                    repeat(3) {
                        val (sRaw, sMsg) = readEncMsg()
                        assertEquals(AapProto.CH_SENSOR, sRaw.channelId)
                        assertEquals(AapProto.SENSOR_EVENT_INDICATION, AapFramer.readMessageId(sMsg))
                        val f = AapProto.ProtoReader(sMsg, 2, sMsg.size).readAllFields().first()
                        initialSensorFieldNums.add(f.fieldNumber)
                    }
                    assertEquals(
                        setOf(AapProto.SENSOR_DRIVING_STATUS, AapProto.SENSOR_NIGHT_DATA, AapProto.SENSOR_PARKING_BRAKE),
                        initialSensorFieldNums,
                    )

                    // 8. Open Video, Input, SystemAudio, Mic channels
                    for (ch in intArrayOf(AapProto.CH_VIDEO, AapProto.CH_INPUT, AapProto.CH_AUDIO_SYSTEM, AapProto.CH_MIC)) {
                        sendEnc(
                            serverEngine,
                            output,
                            ch,
                            AapFramer.FLAG_ENC_CONTROL,
                            AapProto.CHANNEL_OPEN_REQUEST,
                            AapProto.encodeChannelOpenRequest(0, ch),
                        )
                        val (openRaw, openMsg) = readEncMsg()
                        assertEquals(ch, openRaw.channelId)
                        assertEquals(AapFramer.FLAG_ENC_CONTROL, openRaw.flags)
                        assertEquals(AapProto.CHANNEL_OPEN_RESPONSE, AapFramer.readMessageId(openMsg))
                    }

                    // 9. Input binding
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_INPUT,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.INPUT_BINDING_REQUEST,
                        AapProto.encodeInputBindingRequest(AapProto.AapKeyCode.ALL_SUPPORTED),
                    )
                    val (bindRaw, bindMsg) = readEncMsg()
                    assertEquals(AapProto.CH_INPUT, bindRaw.channelId)
                    assertEquals(AapProto.INPUT_BINDING_RESPONSE, AapFramer.readMessageId(bindMsg))
                    assertEquals(0, AapProto.decodeInputBindingResponse(bindMsg.copyOfRange(2, bindMsg.size)))
                    inputReadyLatch.countDown()

                    // 10. Video Setup -> AV_SETUP_RESPONSE + unrequested VIDEO_FOCUS_INDICATION(PROJECTED)
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_VIDEO,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_SETUP_REQUEST,
                        AapProto.encodeAvSetupRequest(AapProto.STREAM_TYPE_VIDEO_H264_BP),
                    )
                    val (_, avSetupRespMsg) = readEncMsg()
                    assertEquals(AapProto.AV_SETUP_RESPONSE, AapFramer.readMessageId(avSetupRespMsg))
                    val avResp = AapProto.decodeAvSetupResponse(avSetupRespMsg.copyOfRange(2, avSetupRespMsg.size))
                    assertEquals(AapProto.AV_STATUS_OK, avResp.mediaStatus)
                    assertEquals(12, avResp.maxUnacked)

                    val (_, vfIndMsg) = readEncMsg()
                    assertEquals(AapProto.VIDEO_FOCUS_INDICATION, AapFramer.readMessageId(vfIndMsg))
                    val vfInd = AapProto.decodeVideoFocusIndication(vfIndMsg.copyOfRange(2, vfIndMsg.size))
                    assertEquals(AapProto.VIDEO_FOCUS_PROJECTED, vfInd.focusMode)
                    assertTrue(vfInd.unrequested)

                    // 11. Video Start + SPS/PPS Config + ACK
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_VIDEO,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_START_INDICATION,
                        AapProto.encodeAvStartIndication(session = 42, config = 0),
                    )
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_VIDEO,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_MEDIA_INDICATION,
                        expectedSpsPps,
                    )
                    val (_, cfgAckMsg) = readEncMsg()
                    assertEquals(AapProto.AV_MEDIA_ACK, AapFramer.readMessageId(cfgAckMsg))
                    val cfgAck = AapProto.decodeAvMediaAck(cfgAckMsg.copyOfRange(2, cfgAckMsg.size))
                    assertEquals(42, cfgAck.sessionId)
                    assertEquals(1, cfgAck.ackCount)

                    // 12. Send 24 KB fragmented H.264 video frame in 3 encrypted wire fragments
                    // (each wire fragment carries its OWN TLS record — real AAP wire behavior!)
                    val fullMediaPlain = buildMediaWithTimestampMessage(expectedTimestampUs, expected24kFrame)
                    sendEncFragmented(
                        engine = serverEngine,
                        output = output,
                        channelId = AapProto.CH_VIDEO,
                        fullPlainMessage = fullMediaPlain,
                        chunkSize = 10_000,
                    )
                    val (_, frameAckMsg) = readEncMsg()
                    assertEquals(AapProto.AV_MEDIA_ACK, AapFramer.readMessageId(frameAckMsg))
                    val frameAck = AapProto.decodeAvMediaAck(frameAckMsg.copyOfRange(2, frameAckMsg.size))
                    assertEquals(42, frameAck.sessionId)
                    assertEquals(1, frameAck.ackCount)
                    frameAckReceivedLatch.countDown()

                    // 13. Read Touch, Rotary, NightMode, VideoFocus, and finally SHUTDOWN_REQUEST
                    while (true) {
                        val (raw, msg) = readEncMsg()
                        val msgId = AapFramer.readMessageId(msg)
                        val body = msg.copyOfRange(2, msg.size)
                        when {
                            raw.channelId == AapProto.CH_INPUT && msgId == AapProto.INPUT_EVENT_INDICATION -> {
                                val fields = AapProto.ProtoReader(body).readAllFields()
                                fields.firstOrNull { it.fieldNumber == 3 }?.let { touchF ->
                                    val tFields = touchF.asSubmessageFields()
                                    val loc = tFields.first { it.fieldNumber == 1 }.asSubmessageFields()
                                    val x = loc.first { it.fieldNumber == 1 }.asInt()
                                    val y = loc.first { it.fieldNumber == 2 }.asInt()
                                    val action = tFields.first { it.fieldNumber == 3 }.asInt()
                                    receivedTouch.set(Triple(x, y, action))
                                }
                                fields.firstOrNull { it.fieldNumber == 6 }?.let { relF ->
                                    val ev = relF.asSubmessageFields().first { it.fieldNumber == 1 }.asSubmessageFields()
                                    val code = ev.first { it.fieldNumber == 1 }.asInt()
                                    val delta = ev.first { it.fieldNumber == 2 }.asInt()
                                    receivedRelative.set(code to delta)
                                }
                            }

                            raw.channelId == AapProto.CH_SENSOR && msgId == AapProto.SENSOR_EVENT_INDICATION -> {
                                val fields = AapProto.ProtoReader(body).readAllFields()
                                fields.firstOrNull { it.fieldNumber == AapProto.SENSOR_NIGHT_DATA }?.let { nf ->
                                    receivedNightUpdate.set(
                                        nf.asSubmessageFields().first { it.fieldNumber == 1 }.asBool(),
                                    )
                                }
                            }

                            raw.channelId == AapProto.CH_VIDEO && msgId == AapProto.VIDEO_FOCUS_INDICATION -> {
                                val vf = AapProto.decodeVideoFocusIndication(body)
                                receivedFocusUpdate.set(vf.focusMode)
                            }

                            raw.channelId == AapProto.CH_CONTROL && msgId == AapProto.SHUTDOWN_REQUEST -> {
                                receivedShutdownReason.set(AapProto.decodeShutdownRequest(body))
                                sendEnc(
                                    serverEngine,
                                    output,
                                    AapProto.CH_CONTROL,
                                    AapFramer.FLAG_ENC_BULK,
                                    AapProto.SHUTDOWN_RESPONSE,
                                    AapProto.encodeShutdownResponse(),
                                )
                                break
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }

        val fixedTime = AtomicReference(1_000_000L)
        val client = AapSessionClient(
            certStore = certStore,
            log = { _, _ -> },
            host = "127.0.0.1",
            port = serverSocket.localPort,
            monotonicTimeNs = { fixedTime.getAndUpdate { it + 1_000L } },
        )

        try {
            client.start(
                profile = profile,
                carSerial = "AL07FFL0665583",
                initialNightMode = false,
                listener = object : AapSessionClient.Listener {
                    override fun onStateChanged(titleHr: String, detailHr: String, connected: Boolean) {}

                    override fun onVideoConfig(spsPpsAnnexB: ByteArray) {
                        spsReceivedRef.set(spsPpsAnnexB)
                    }

                    override fun onVideoFrame(timestampUs: Long, annexB: ByteArray) {
                        videoFrameRef.set(timestampUs to annexB)
                        videoFrameReceivedLatch.countDown()
                    }

                    override fun onVideoStopped() {}

                    override fun onSessionEnded(reasonHr: String, certRejectedLikely: Boolean) {
                        endedCertRejected.set(certRejectedLikely)
                        sessionEndedLatch.countDown()
                    }
                },
            )

            assertTrue("Timed out waiting for 24 KB fragmented H.264 frame", videoFrameReceivedLatch.await(8, TimeUnit.SECONDS))
            assertTrue("Timed out waiting for video frame ACK", frameAckReceivedLatch.await(5, TimeUnit.SECONDS))
            assertTrue("Timed out waiting for inputReady", inputReadyLatch.await(5, TimeUnit.SECONDS))

            assertArrayEquals(expectedSpsPps, spsReceivedRef.get())
            val (gotTs, gotFrame) = videoFrameRef.get()!!
            assertEquals(expectedTimestampUs, gotTs)
            assertArrayEquals(expected24kFrame, gotFrame)

            // Send BMW touch + iDrive rotary + night mode + video focus
            val mapper = BmwToAapInputMapper(profile)
            mapper.onTouch(down = true, bmwX = 320, bmwY = 200).forEach { client.sendInputAction(it) }
            mapper.onKnob(false, false, false, 0, 0, wheel = 2).forEach { client.sendInputAction(it) }
            client.updateNightMode(true)
            client.setVideoFocus(false)

            // Graceful shutdown with ByeBye
            client.stop()
            assertTrue("Timed out waiting for onSessionEnded", sessionEndedLatch.await(5, TimeUnit.SECONDS))
            serverTask.get(5, TimeUnit.SECONDS)

            serverError.get()?.let { throw AssertionError("Mock server failed: ${it.message}", it) }
            assertFalse(endedCertRejected.get())
            assertEquals(Triple(320, 320, AapProto.TouchAction.PRESS), receivedTouch.get())
            assertEquals(AapProto.AapKeyCode.SCROLL_WHEEL to 2, receivedRelative.get())
            assertEquals(true, receivedNightUpdate.get())
            assertEquals(AapProto.VIDEO_FOCUS_NATIVE, receivedFocusUpdate.get())
            assertEquals(AapProto.SHUTDOWN_REASON_USER_SELECTION, receivedShutdownReason.get())
        } finally {
            client.stop()
            serverSocket.close()
            exec.shutdownNow()
        }
    }

    @Test
    fun loopbackDiagnosticTestCaptures5FramesAndSendsCleanByeBye() {
        val certStore = AapCertStore(tempFolder.newFolder("aap-diag-ok"))
        val profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)

        val serverSawByeBye = AtomicBoolean(false)
        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Mock Diag Server")
        val serverEngine = createServerEngine(serverChain, serverKey, acceptClientCert = true)
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newSingleThreadExecutor()

        val serverTask = exec.submit {
            serverSocket.soTimeout = 10_000
            serverSocket.accept().use { sock ->
                sock.soTimeout = 10_000
                val input = BufferedInputStream(sock.getInputStream())
                val output = sock.getOutputStream()

                AapFramer.readRawFrame(input) // VERSION_REQUEST
                sendPlain(output, AapProto.CH_CONTROL, AapProto.VERSION_RESPONSE, AapProto.encodeVersionResponse(1, 6, 0))
                runServerTlsHandshake(serverEngine, input, output)
                AapFramer.readRawFrame(input) // AUTH_COMPLETE

                val asm = AapFrameAssembler()
                fun readEnc(): Pair<Int, ByteArray> {
                    while (true) {
                        val raw = AapFramer.readRawFrame(input)
                        val plain = serverUnwrap(serverEngine, raw.payload)
                        val msg = asm.feed(raw.channelId, raw.flags, plain, raw.totalSizeHint)
                        if (msg != null) return raw.channelId to msg
                    }
                }

                sendEnc(serverEngine, output, AapProto.CH_CONTROL, AapFramer.FLAG_ENC_BULK, AapProto.SERVICE_DISCOVERY_REQUEST, ByteArray(0))
                readEnc() // SERVICE_DISCOVERY_RESPONSE

                sendEnc(serverEngine, output, AapProto.CH_VIDEO, AapFramer.FLAG_ENC_CONTROL, AapProto.CHANNEL_OPEN_REQUEST, AapProto.encodeChannelOpenRequest(0, AapProto.CH_VIDEO))
                readEnc() // CHANNEL_OPEN_RESPONSE

                sendEnc(serverEngine, output, AapProto.CH_VIDEO, AapFramer.FLAG_ENC_BULK, AapProto.AV_SETUP_REQUEST, AapProto.encodeAvSetupRequest(3))
                readEnc() // AV_SETUP_RESPONSE
                readEnc() // VIDEO_FOCUS_INDICATION

                sendEnc(serverEngine, output, AapProto.CH_VIDEO, AapFramer.FLAG_ENC_BULK, AapProto.AV_START_INDICATION, AapProto.encodeAvStartIndication(1, 0))

                // Stream 5 video frames
                val nal = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
                for (i in 1..5) {
                    val mediaMsg = buildMediaWithTimestampMessage(i * 16_666L, nal)
                    val cipher = serverWrap(serverEngine, mediaMsg)
                    output.write(AapFramer.encodeFrame(AapProto.CH_VIDEO, AapFramer.FLAG_ENC_BULK, cipher))
                    output.flush()
                }

                // Drain ACKs until SHUTDOWN_REQUEST arrives
                while (true) {
                    val (ch, msg) = readEnc()
                    if (ch == AapProto.CH_CONTROL && AapFramer.readMessageId(msg) == AapProto.SHUTDOWN_REQUEST) {
                        serverSawByeBye.set(true)
                        sendEnc(serverEngine, output, AapProto.CH_CONTROL, AapFramer.FLAG_ENC_BULK, AapProto.SHUTDOWN_RESPONSE, ByteArray(0))
                        break
                    }
                }
            }
        }

        val client = AapSessionClient(certStore, { _, _ -> }, "127.0.0.1", serverSocket.localPort)
        try {
            val result = client.runLoopbackDiagnosticTest(profile, timeoutMs = 6_000L)
            serverTask.get(5, TimeUnit.SECONDS)

            assertTrue("Expected diagnostic success: ${result.summaryHr}", result.success)
            assertEquals("1.6", result.versionText)
            assertTrue(result.tlsOk)
            assertTrue(result.serviceDiscoveryOk)
            assertTrue(result.videoFramesReceived >= 5)
            assertFalse(result.certRejectedLikely)
            assertTrue("Mock server must receive clean SHUTDOWN_REQUEST (ByeBye)", serverSawByeBye.get())
        } finally {
            serverSocket.close()
            exec.shutdownNow()
        }
    }

    @Test
    fun tlsRejectionOfFallbackCertFlagsCertRejectedLikelyWithCroatianExplanation() {
        val certStore = AapCertStore(tempFolder.newFolder("aap-diag-cert-reject"))
        val profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)

        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Google AA Strict")
        val serverEngine = createServerEngine(serverChain, serverKey, acceptClientCert = false)
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newSingleThreadExecutor()

        exec.submit {
            serverSocket.soTimeout = 10_000
            try {
                serverSocket.accept().use { sock ->
                    sock.soTimeout = 10_000
                    val input = BufferedInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()

                    AapFramer.readRawFrame(input) // VERSION_REQUEST
                    sendPlain(output, AapProto.CH_CONTROL, AapProto.VERSION_RESPONSE, AapProto.encodeVersionResponse(1, 6, 0))

                    try {
                        runServerTlsHandshake(serverEngine, input, output)
                    } catch (_: Exception) {
                        // Send TLS alert if any, then close socket just like Google AA
                        val alert = ByteBuffer.allocate(4096)
                        try {
                            serverEngine.wrap(ByteBuffer.allocate(0), alert)
                            alert.flip()
                            if (alert.hasRemaining()) {
                                val b = ByteArray(alert.remaining())
                                alert.get(b)
                                sendPlain(output, AapProto.CH_CONTROL, AapProto.SSL_HANDSHAKE, b)
                            }
                        } catch (_: Exception) {
                            // Ignore
                        }
                    }
                }
            } catch (_: Exception) {
                // Expected during rejection
            }
        }

        val client = AapSessionClient(certStore, { _, _ -> }, "127.0.0.1", serverSocket.localPort)
        try {
            val result = client.runLoopbackDiagnosticTest(profile, timeoutMs = 5_000L)
            assertFalse(result.success)
            assertEquals("1.6", result.versionText)
            assertFalse(result.tlsOk)
            assertTrue("Expected certRejectedLikely=true when fallback cert is rejected", result.certRejectedLikely)
            assertTrue(
                "Summary should explain HU cert in Croatian: ${result.summaryHr}",
                result.summaryHr.contains("Google Automotive Link") || result.summaryHr.contains("certifikat"),
            )
        } finally {
            serverSocket.close()
            exec.shutdownNow()
        }
    }

    @Test
    fun connectionRefusedReportsHelpfulCroatianMessageWithoutCertFlag() {
        val certStore = AapCertStore(tempFolder.newFolder("aap-diag-refused"))
        val profile = AapProto.AapDisplayProfile.forBmwScreen(1280, 480, 60)

        // Bind then immediately close a port so nothing listens there
        val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

        val client = AapSessionClient(certStore, { _, _ -> }, "127.0.0.1", closedPort)
        val result = client.runLoopbackDiagnosticTest(profile, timeoutMs = 4_000L)

        assertFalse(result.success)
        assertFalse(result.tlsOk)
        assertFalse(result.certRejectedLikely)
        assertTrue(result.summaryHr.contains("Head Unit Server"))
    }

    @Test
    fun sensorRequestAudioSystemMicVideoStopAndPeerShutdown() {
        val certStore = AapCertStore(tempFolder.newFolder("aap-aux-channels"))
        val profile = AapProto.AapDisplayProfile.forBmwScreen(800, 480, 30)

        val videoStoppedCalled = AtomicBoolean(false)
        val sessionEndedLatch = CountDownLatch(1)
        val serverError = AtomicReference<Throwable?>(null)

        val (serverChain, serverKey) = AapCertStore.generateSelfSignedCredentials("CN=Mock Aux AA")
        val serverEngine = createServerEngine(serverChain, serverKey, acceptClientCert = true)
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val exec = Executors.newSingleThreadExecutor()

        val serverTask = exec.submit {
            try {
                serverSocket.soTimeout = 10_000
                serverSocket.accept().use { sock ->
                    sock.soTimeout = 10_000
                    val input = BufferedInputStream(sock.getInputStream())
                    val output = sock.getOutputStream()

                    AapFramer.readRawFrame(input) // VERSION_REQUEST
                    sendPlain(output, AapProto.CH_CONTROL, AapProto.VERSION_RESPONSE, AapProto.encodeVersionResponse(1, 6, 0))
                    runServerTlsHandshake(serverEngine, input, output)
                    AapFramer.readRawFrame(input) // AUTH_COMPLETE

                    val asm = AapFrameAssembler()
                    fun readEnc(): Pair<Int, ByteArray> {
                        while (true) {
                            val raw = AapFramer.readRawFrame(input)
                            val plain = serverUnwrap(serverEngine, raw.payload)
                            val msg = asm.feed(raw.channelId, raw.flags, plain, raw.totalSizeHint)
                            if (msg != null) return raw.channelId to msg
                        }
                    }

                    // Audio focus RELEASE -> LOSS(3)
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_CONTROL,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AUDIO_FOCUS_REQUEST,
                        AapProto.encodeAudioFocusRequest(AapProto.AUDIO_FOCUS_RELEASE),
                    )
                    val (_, afMsg) = readEnc()
                    val af = AapProto.decodeAudioFocusResponse(afMsg.copyOfRange(2, afMsg.size))
                    assertEquals(AapProto.AUDIO_FOCUS_STATE_LOSS, af.focusState)
                    assertTrue(af.granted)

                    // Sensor explicit SENSOR_REQUEST(NIGHT_DATA) -> SENSOR_START_RESPONSE(0) + SENSOR_EVENT_INDICATION
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_SENSOR,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.SENSOR_REQUEST,
                        AapProto.encodeSensorStartRequest(AapProto.SENSOR_NIGHT_DATA, 0L),
                    )
                    val (srCh, srMsg) = readEnc()
                    assertEquals(AapProto.CH_SENSOR, srCh)
                    assertEquals(AapProto.SENSOR_START_RESPONSE, AapFramer.readMessageId(srMsg))
                    assertEquals(0, AapProto.decodeSensorStartResponse(srMsg.copyOfRange(2, srMsg.size)))
                    val (seCh, seMsg) = readEnc()
                    assertEquals(AapProto.CH_SENSOR, seCh)
                    assertEquals(AapProto.SENSOR_EVENT_INDICATION, AapFramer.readMessageId(seMsg))
                    val nightField = AapProto.ProtoReader(seMsg, 2, seMsg.size).readAllFields()
                        .first { it.fieldNumber == AapProto.SENSOR_NIGHT_DATA }
                    assertTrue(nightField.asSubmessageFields().first { it.fieldNumber == 1 }.asBool())

                    // System Audio setup + start + media -> ACK with session=9
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_AUDIO_SYSTEM,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_SETUP_REQUEST,
                        AapProto.encodeAvSetupRequest(AapProto.STREAM_TYPE_AUDIO_PCM),
                    )
                    val (asCh, asMsg) = readEnc()
                    assertEquals(AapProto.CH_AUDIO_SYSTEM, asCh)
                    assertEquals(AapProto.AV_SETUP_RESPONSE, AapFramer.readMessageId(asMsg))
                    val audioSetup = AapProto.decodeAvSetupResponse(asMsg.copyOfRange(2, asMsg.size))
                    assertEquals(AapProto.AV_STATUS_OK, audioSetup.mediaStatus)
                    assertEquals(30, audioSetup.maxUnacked)

                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_AUDIO_SYSTEM,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_START_INDICATION,
                        AapProto.encodeAvStartIndication(session = 9, config = 0),
                    )
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_AUDIO_SYSTEM,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_MEDIA_INDICATION,
                        ByteArray(64),
                    )
                    val (aaCh, aaMsg) = readEnc()
                    assertEquals(AapProto.CH_AUDIO_SYSTEM, aaCh)
                    assertEquals(AapProto.AV_MEDIA_ACK, AapFramer.readMessageId(aaMsg))
                    val audioAck = AapProto.decodeAvMediaAck(aaMsg.copyOfRange(2, aaMsg.size))
                    assertEquals(9, audioAck.sessionId)
                    assertEquals(1, audioAck.ackCount)

                    // Mic open request -> AV_INPUT_OPEN_RESPONSE(0, 0)
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_MIC,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_INPUT_OPEN_REQUEST,
                        AapProto.encodeAvInputOpenRequest(true),
                    )
                    val (micCh, micMsg) = readEnc()
                    assertEquals(AapProto.CH_MIC, micCh)
                    assertEquals(AapProto.AV_INPUT_OPEN_RESPONSE, AapFramer.readMessageId(micMsg))
                    val micResp = AapProto.decodeAvInputOpenResponse(micMsg.copyOfRange(2, micMsg.size))
                    assertEquals(0, micResp.session)
                    assertEquals(0, micResp.value)

                    // Video focus request -> VIDEO_FOCUS_INDICATION(unrequested=false) + AV_STOP_INDICATION
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_VIDEO,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.VIDEO_FOCUS_REQUEST,
                        AapProto.encodeVideoFocusRequest(AapProto.VIDEO_FOCUS_PROJECTED, 1),
                    )
                    val (vfCh, vfMsg) = readEnc()
                    assertEquals(AapProto.CH_VIDEO, vfCh)
                    assertEquals(AapProto.VIDEO_FOCUS_INDICATION, AapFramer.readMessageId(vfMsg))
                    val vfInd = AapProto.decodeVideoFocusIndication(vfMsg.copyOfRange(2, vfMsg.size))
                    assertEquals(AapProto.VIDEO_FOCUS_PROJECTED, vfInd.focusMode)
                    assertFalse(vfInd.unrequested)

                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_VIDEO,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.AV_STOP_INDICATION,
                        ByteArray(0),
                    )

                    // Peer-initiated SHUTDOWN_REQUEST -> expect SHUTDOWN_RESPONSE
                    sendEnc(
                        serverEngine,
                        output,
                        AapProto.CH_CONTROL,
                        AapFramer.FLAG_ENC_BULK,
                        AapProto.SHUTDOWN_REQUEST,
                        AapProto.encodeShutdownRequest(AapProto.SHUTDOWN_REASON_POWER_DOWN),
                    )
                    val (sdCh, sdMsg) = readEnc()
                    assertEquals(AapProto.CH_CONTROL, sdCh)
                    assertEquals(AapProto.SHUTDOWN_RESPONSE, AapFramer.readMessageId(sdMsg))
                }
            } catch (t: Throwable) {
                serverError.set(t)
            }
        }

        val client = AapSessionClient(certStore, { _, _ -> }, "127.0.0.1", serverSocket.localPort)
        try {
            client.start(
                profile = profile,
                carSerial = "BMW-AUX-TEST",
                initialNightMode = true,
                listener = object : AapSessionClient.Listener {
                    override fun onStateChanged(titleHr: String, detailHr: String, connected: Boolean) {}
                    override fun onVideoConfig(spsPpsAnnexB: ByteArray) {}
                    override fun onVideoFrame(timestampUs: Long, annexB: ByteArray) {}
                    override fun onVideoStopped() {
                        videoStoppedCalled.set(true)
                    }
                    override fun onSessionEnded(reasonHr: String, certRejectedLikely: Boolean) {
                        sessionEndedLatch.countDown()
                    }
                },
            )

            assertTrue("Timed out waiting for peer-initiated shutdown", sessionEndedLatch.await(8, TimeUnit.SECONDS))
            serverTask.get(5, TimeUnit.SECONDS)
            serverError.get()?.let { throw AssertionError("Mock server failed: ${it.message}", it) }
            assertTrue("Expected onVideoStopped() callback", videoStoppedCalled.get())
        } finally {
            client.stop()
            serverSocket.close()
            exec.shutdownNow()
        }
    }

    // ---- Mock server wire + TLS helpers ----

    private fun buildMediaWithTimestampMessage(timestampUs: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(10 + payload.size)
        out[0] = ((AapProto.AV_MEDIA_WITH_TIMESTAMP ushr 8) and 0xFF).toByte()
        out[1] = (AapProto.AV_MEDIA_WITH_TIMESTAMP and 0xFF).toByte()
        for (i in 0 until 8) {
            out[2 + i] = ((timestampUs ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }
        System.arraycopy(payload, 0, out, 10, payload.size)
        return out
    }

    private fun sendPlain(output: OutputStream, channelId: Int, msgId: Int, body: ByteArray) {
        val msg = AapFramer.prependMessageId(msgId, body)
        output.write(AapFramer.encodeFrame(channelId, AapFramer.FLAG_PLAIN_BULK, msg))
        output.flush()
    }

    private fun sendEnc(
        engine: SSLEngine,
        output: OutputStream,
        channelId: Int,
        flags: Int,
        msgId: Int,
        body: ByteArray,
    ) {
        val msg = AapFramer.prependMessageId(msgId, body)
        val cipher = serverWrap(engine, msg)
        output.write(AapFramer.encodeFrame(channelId, flags, cipher))
        output.flush()
    }

    private fun sendEncFragmented(
        engine: SSLEngine,
        output: OutputStream,
        channelId: Int,
        fullPlainMessage: ByteArray,
        chunkSize: Int,
    ) {
        var offset = 0
        while (offset < fullPlainMessage.size) {
            val end = minOf(offset + chunkSize, fullPlainMessage.size)
            val isFirst = offset == 0
            val isLast = end == fullPlainMessage.size
            val chunk = fullPlainMessage.copyOfRange(offset, end)
            val cipher = serverWrap(engine, chunk)
            val flags = AapFramer.FLAG_ENCRYPTED or
                (if (isFirst) AapFramer.FLAG_FIRST else 0) or
                (if (isLast) AapFramer.FLAG_LAST else 0)
            val wire = if (isFirst && !isLast) {
                AapFramer.encodeFirstFragmentFrame(channelId, flags, fullPlainMessage.size, cipher)
            } else {
                AapFramer.encodeFrame(channelId, flags, cipher)
            }
            output.write(wire)
            output.flush()
            offset = end
        }
    }

    private fun createServerEngine(
        serverChain: Array<X509Certificate>,
        serverKey: PrivateKey,
        acceptClientCert: Boolean,
    ): SSLEngine {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", serverKey, CharArray(0), serverChain)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, CharArray(0))

        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!acceptClientCert) throw CertificateException("Untrusted client certificate")
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val ctx = SSLContext.getInstance("TLSv1.2")
        ctx.init(kmf.keyManagers, arrayOf(tm), SecureRandom())
        return ctx.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            if ("TLSv1.2" in supportedProtocols) {
                enabledProtocols = arrayOf("TLSv1.2")
            }
        }
    }

    private fun runServerTlsHandshake(engine: SSLEngine, input: InputStream, output: OutputStream) {
        val empty = ByteBuffer.allocate(0)
        var netOut = ByteBuffer.allocate(32_768)
        var netIn = ByteBuffer.allocate(32_768).apply { flip() }
        var appIn = ByteBuffer.allocate(32_768)
        val pendingOut = ByteArrayOutputStream()

        fun flushOut() {
            if (pendingOut.size() > 0) {
                sendPlain(output, AapProto.CH_CONTROL, AapProto.SSL_HANDSHAKE, pendingOut.toByteArray())
                pendingOut.reset()
            }
        }

        fun recvSsl(): ByteArray {
            val raw = AapFramer.readRawFrame(input)
            assertEquals(AapProto.SSL_HANDSHAKE, AapFramer.readMessageId(raw.payload))
            return raw.payload.copyOfRange(2, raw.payload.size)
        }

        engine.beginHandshake()
        var status = engine.handshakeStatus
        while (status != SSLEngineResult.HandshakeStatus.FINISHED &&
            status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) {
                        val t = engine.delegatedTask ?: break
                        t.run()
                    }
                    status = engine.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val res = engine.wrap(empty, netOut)
                    if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                        netOut = ByteBuffer.allocate(netOut.capacity() * 2)
                        continue
                    }
                    netOut.flip()
                    if (netOut.hasRemaining()) {
                        val b = ByteArray(netOut.remaining())
                        netOut.get(b)
                        pendingOut.write(b)
                    }
                    status = res.handshakeStatus
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    flushOut()
                    if (!netIn.hasRemaining()) {
                        val incoming = recvSsl()
                        val next = ByteBuffer.allocate(maxOf(netIn.capacity(), incoming.size))
                        next.put(incoming)
                        next.flip()
                        netIn = next
                    }
                    appIn.clear()
                    val res = engine.unwrap(netIn, appIn)
                    when (res.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val incoming = recvSsl()
                            val next = ByteBuffer.allocate(maxOf(netIn.capacity(), netIn.remaining() + incoming.size))
                            next.put(netIn)
                            next.put(incoming)
                            next.flip()
                            netIn = next
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                            appIn = ByteBuffer.allocate(appIn.capacity() * 2)
                        }
                        SSLEngineResult.Status.CLOSED, null -> throw EOFException("TLS closed")
                        SSLEngineResult.Status.OK -> status = res.handshakeStatus
                    }
                }

                else -> break
            }
        }
        flushOut()
    }

    private fun serverWrap(engine: SSLEngine, plain: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(plain)
        var dst = ByteBuffer.allocate(maxOf(engine.session.packetBufferSize, 32_768))
        val out = ByteArrayOutputStream()
        do {
            dst.clear()
            val res = engine.wrap(src, dst)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            out.write(b)
        } while (src.hasRemaining())
        return out.toByteArray()
    }

    private fun serverUnwrap(engine: SSLEngine, cipher: ByteArray): ByteArray {
        val src = ByteBuffer.wrap(cipher)
        var dst = ByteBuffer.allocate(maxOf(engine.session.applicationBufferSize, 32_768))
        val out = ByteArrayOutputStream()
        while (src.hasRemaining()) {
            dst.clear()
            val res = engine.unwrap(src, dst)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                dst = ByteBuffer.allocate(dst.capacity() * 2)
                continue
            }
            if (res.status == SSLEngineResult.Status.CLOSED) break
            dst.flip()
            val b = ByteArray(dst.remaining())
            dst.get(b)
            out.write(b)
        }
        return out.toByteArray()
    }
}

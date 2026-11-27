package io.bimmergestalt.bmwcarplay.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware-accelerated Android Auto (`1280×720` with vertical margin crop → `1280×480` @ 60 fps,
 * or `800×480` uncropped) video bridge into [CarPlayVideoSender]'s hardware H.264 encoder [Surface].
 *
 * Why a single dedicated EGL14 / OpenGL ES 2.0 renderer owns the encoder [Surface]:
 * - Android's `BufferQueue` forbids mixing `Surface.lockHardwareCanvas()` and `eglCreateWindowSurface()`
 *   on the same encoder `Surface`.
 * - Before Android Auto produces its first decoded H.264 frame (or if the Head Unit Server needs
 *   attention), this renderer draws a crisp Croatian BMW-themed status card via an offscreen `Bitmap`
 *   uploaded to a `GL_TEXTURE_2D` — preventing black screens or CarPlay RTSP timeouts.
 * - The instant Android Auto delivers H.264 `CODEC_CONFIG` (SPS/PPS) and video access units, a hardware
 *   `MediaCodec` AVC decoder renders directly into a `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`) in the
 *   same EGL context and blits the exact centered active sub-region (`cropTop..cropTop+cropHeight`) onto
 *   the CarPlay encoder `Surface` with zero CPU pixel copy (<2 ms on Galaxy S25).
 * - Periodic keep-alive redraws (every 100 ms) keep BMW `HU_ENTRYNAV2` smoothly refreshed even on
 *   static Android Auto screens.
 */
class AapGlSurfaceRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val marginWidth: Int,
    private val marginHeight: Int,
    private val logger: DiagnosticLogger,
) {
    companion object {
        private const val KEEPALIVE_REDRAW_MS = 100L
        private const val DECODER_TIMEOUT_US = 5_000L

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER_OES = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val FRAGMENT_SHADER_2D = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private val FULLSCREEN_QUAD_POS = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )

        private val STATUS_QUAD_UV = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f,
        )

        private val IDENTITY_MATRIX = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
    }

    private val running = AtomicBoolean(false)
    private val hasVideoFrame = AtomicBoolean(false)
    private val frameAvailable = AtomicBoolean(false)
    private val statusDirty = AtomicBoolean(true)
    private val decodedFrames = AtomicLong(0L)
    private val renderedFrames = AtomicLong(0L)

    private val statusTitle = AtomicReference("Android Auto — spajanje…")
    private val statusDetail = AtomicReference("Čekam lokalni poslužitelj glavne jedinice (127.0.0.1:5277)")
    private val statusHint = AtomicReference("BMW ${outputWidth}×${outputHeight} · 5G internet ostaje aktivan")

    private var glThread: HandlerThread? = null
    @Volatile
    private var glHandler: Handler? = null

    // EGL / GL state (strictly owned by glThread)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var oesProgram = 0
    private var tex2dProgram = 0
    private var oesTextureId = 0
    private var statusTextureId = 0
    private var decoderSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var decoderOutputSurface: Surface? = null
    private val stMatrix = IDENTITY_MATRIX.copyOf()
    private var lastRenderUptimeMs = 0L

    private val posBuffer: FloatBuffer = createFloatBuffer(FULLSCREEN_QUAD_POS)
    private val statusUvBuffer: FloatBuffer = createFloatBuffer(STATUS_QUAD_UV)
    private val videoUvBuffer: FloatBuffer = createFloatBuffer(buildCroppedVideoUv())

    // Hardware H.264 decoder (synchronized by decoderLock)
    private val decoderLock = Any()
    @Volatile
    private var decoder: MediaCodec? = null
    @Volatile
    private var decoderConfigured = false
    private var savedCodecConfig: ByteArray? = null
    private val decoderOutputInfo = MediaCodec.BufferInfo()
    private var decoderInputErrors = 0

    private val keepAliveTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = SystemClock.uptimeMillis()
            if (frameAvailable.get() || statusDirty.get() || (now - lastRenderUptimeMs) >= KEEPALIVE_REDRAW_MS) {
                renderFrameOnGlThread()
            }
            glHandler?.postDelayed(this, 33L)
        }
    }

    val totalDecodedFrames: Long get() = decodedFrames.get()
    val totalRenderedFrames: Long get() = renderedFrames.get()

    fun updateStatus(titleHr: String, detailHr: String, hintHr: String? = null) {
        statusTitle.set(titleHr)
        statusDetail.set(detailHr)
        if (hintHr != null) {
            statusHint.set(hintHr)
        }
        statusDirty.set(true)
        glHandler?.post {
            if (running.get() && !hasVideoFrame.get()) {
                renderFrameOnGlThread()
            }
        }
    }

    /** Switches back to the status overlay (e.g., if Android Auto stops or reconnects). */
    fun showStatusOverlay(titleHr: String, detailHr: String, hintHr: String? = null) {
        hasVideoFrame.set(false)
        updateStatus(titleHr, detailHr, hintHr)
    }

    @Synchronized
    fun start(encoderInputSurface: Surface) {
        stop()
        running.set(true)
        hasVideoFrame.set(false)
        frameAvailable.set(false)
        statusDirty.set(true)
        decodedFrames.set(0L)
        renderedFrames.set(0L)
        decoderInputErrors = 0

        val thread = HandlerThread("aap-gl-bridge").apply {
            isDaemon = true
            start()
        }
        val handler = Handler(thread.looper)
        glThread = thread
        glHandler = handler

        handler.post {
            try {
                initEglAndGl(encoderInputSurface)
                renderFrameOnGlThread()
                handler.postDelayed(keepAliveTick, 33L)
                logger.log(
                    "AAP-VIDEO",
                    "✓ AapGlSurfaceRenderer aktivan: izvor ${sourceWidth}×${sourceHeight} " +
                        "(margin=${marginWidth}×${marginHeight}) → BMW ${outputWidth}×${outputHeight}",
                )
            } catch (e: Exception) {
                logger.log("AAP-VIDEO", "✗ Greška inicijalizacije OpenGL/EGL mosta: ${e.message}")
                running.set(false)
                releaseEglAndGl()
            }
        }
    }

    /**
     * Feeds H.264 Annex-B `CODEC_CONFIG` (SPS/PPS) received from Android Auto (`AV_MEDIA_INDICATION 0x0001`).
     */
    fun onCodecConfig(spsPpsAnnexB: ByteArray) {
        if (!running.get() || spsPpsAnnexB.isEmpty()) return
        synchronized(decoderLock) {
            if (!running.get()) return
            savedCodecConfig = spsPpsAnnexB.copyOf()
            val justSeededCsd = ensureDecoderReadyLocked()
            val codec = decoder ?: return
            if (!justSeededCsd) {
                feedDecoderBufferLocked(codec, spsPpsAnnexB, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            drainDecoderLocked(codec)
        }
    }

    /**
     * Feeds an H.264 Annex-B video access unit received from Android Auto (`AV_MEDIA_WITH_TIMESTAMP 0x0000`).
     */
    fun onVideoFrame(timestampUs: Long, annexB: ByteArray) {
        if (!running.get() || annexB.isEmpty()) return
        synchronized(decoderLock) {
            if (!running.get()) return
            ensureDecoderReadyLocked()
            val codec = decoder ?: return
            feedDecoderBufferLocked(codec, annexB, timestampUs, 0)
            drainDecoderLocked(codec)
        }
    }

    /** Resets the hardware decoder when Android Auto sends `STOP_INDICATION` or reconnects. */
    fun resetDecoder() {
        synchronized(decoderLock) {
            releaseDecoderLocked()
            savedCodecConfig = null
        }
        hasVideoFrame.set(false)
        statusDirty.set(true)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        val handler = glHandler
        val thread = glThread
        glHandler = null
        glThread = null

        synchronized(decoderLock) {
            releaseDecoderLocked()
            savedCodecConfig = null
        }

        if (handler != null && thread != null) {
            handler.removeCallbacksAndMessages(null)
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = handler.post {
                try {
                    releaseEglAndGl()
                } finally {
                    latch.countDown()
                }
            }
            if (posted) {
                runCatching { latch.await(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS) }
            }
            runCatching { thread.quitSafely() }
        }
        logger.log(
            "AAP-VIDEO",
            "AapGlSurfaceRenderer zaustavljen (dekodirano=${decodedFrames.get()}, iscrtano=${renderedFrames.get()} sličica)",
        )
    }

    // ---- Hardware H.264 Decoder ----

    /**
     * Ensures the hardware H.264 decoder is configured and running.
     * Returns `true` if the decoder was newly created AND immediately seeded with [savedCodecConfig].
     */
    private fun ensureDecoderReadyLocked(): Boolean {
        if (!running.get()) return false
        if (decoderConfigured && decoder != null) return false
        val outSurface = decoderOutputSurface ?: return false
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                sourceWidth,
                sourceHeight,
            ).apply {
                // Request low-latency decode on Snapdragon 8 Elite (Android 11+ MediaFormat key)
                runCatching { setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            }
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, outSurface, null, 0)
            codec.start()
            decoder = codec
            decoderConfigured = true
            logger.log("AAP-VIDEO", "✓ Hardverski H.264 dekoder pokrenut za Android Auto (${sourceWidth}×${sourceHeight})")

            val csd = savedCodecConfig
            if (csd != null && csd.isNotEmpty()) {
                feedDecoderBufferLocked(codec, csd, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                return true
            }
            return false
        } catch (e: Exception) {
            logger.log("AAP-VIDEO", "✗ Pokretanje H.264 dekodera nije uspjelo: ${e.message}")
            releaseDecoderLocked()
            return false
        }
    }

    private fun feedDecoderBufferLocked(codec: MediaCodec, data: ByteArray, ptsUs: Long, flags: Int) {
        try {
            val inIdx = codec.dequeueInputBuffer(DECODER_TIMEOUT_US)
            if (inIdx < 0) return
            val inBuf = codec.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            if (data.size > inBuf.capacity()) {
                logger.log("AAP-VIDEO", "Upozorenje: AA H.264 okvir (${data.size} B) veći od ulaznog buffera (${inBuf.capacity()} B)")
                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                return
            }
            inBuf.put(data)
            codec.queueInputBuffer(inIdx, 0, data.size, ptsUs, flags)
            decoderInputErrors = 0
        } catch (e: Exception) {
            decoderInputErrors++
            if (decoderInputErrors <= 5 || decoderInputErrors % 50 == 0) {
                logger.log("AAP-VIDEO", "Greška punjenja H.264 dekodera (#$decoderInputErrors): ${e.message}")
            }
        }
    }

    private fun drainDecoderLocked(codec: MediaCodec) {
        try {
            var loops = 0
            while (loops < 8) {
                loops++
                val outIdx = codec.dequeueOutputBuffer(decoderOutputInfo, 0L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = runCatching { codec.outputFormat }.getOrNull()
                        logger.log("AAP-VIDEO", "H.264 dekoder format potvrđen: $fmt")
                    }
                    outIdx >= 0 -> {
                        val doRender = decoderOutputInfo.size > 0
                        codec.releaseOutputBuffer(outIdx, doRender)
                        if (doRender) {
                            val count = decodedFrames.incrementAndGet()
                            if (count == 1L) {
                                logger.log("AAP-VIDEO", "✓✓ PRVA SLIKA ANDROID AUTA DEKODIRANA! Prebacujem BMW zaslon uživo na Android Auto!")
                            } else if (count % 300L == 0L) {
                                logger.log("AAP-VIDEO", "Dekodirano #$count Android Auto sličica")
                            }
                        }
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            logger.log("AAP-VIDEO", "Greška čitanja H.264 dekodera: ${e.message}")
        }
    }

    private fun releaseDecoderLocked() {
        decoderConfigured = false
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }

    // ---- OpenGL ES 2.0 & EGL14 ----

    private fun buildCroppedVideoUv(): FloatArray {
        val w = sourceWidth.coerceAtLeast(1).toFloat()
        val h = sourceHeight.coerceAtLeast(1).toFloat()
        val uMin = (marginWidth / 2f / w).coerceIn(0f, 0.45f)
        val uMax = (1f - uMin).coerceIn(0.55f, 1f)
        val vMin = (marginHeight / 2f / h).coerceIn(0f, 0.45f)
        val vMax = (1f - vMin).coerceIn(0.55f, 1f)
        // Bottom-left, bottom-right, top-left, top-right (SurfaceTexture transform matrix maps GL Y to video Y)
        return floatArrayOf(
            uMin, vMin,
            uMax, vMin,
            uMin, vMax,
            uMax, vMax,
        )
    }

    private fun initEglAndGl(encoderSurface: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay nije uspio" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize nije uspio" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0 && configs[0] != null,
        ) { "eglChooseConfig nije pronašao EGLConfig" }

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext nije uspio" }

        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfAttribs, 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface nije uspio" }

        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent nije uspio" }

        oesProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_OES)
        tex2dProgram = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER_2D)

        val texIds = IntArray(2)
        GLES20.glGenTextures(2, texIds, 0)
        oesTextureId = texIds[0]
        statusTextureId = texIds[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, statusTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTextureId).apply {
            setDefaultBufferSize(sourceWidth, sourceHeight)
            setOnFrameAvailableListener({
                frameAvailable.set(true)
                hasVideoFrame.set(true)
                glHandler?.post {
                    if (running.get()) {
                        renderFrameOnGlThread()
                    }
                }
            }, glHandler)
        }
        decoderSurfaceTexture = st
        decoderOutputSurface = Surface(st)
    }

    private fun renderFrameOnGlThread() {
        if (!running.get() || eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return

        val st = decoderSurfaceTexture
        if (frameAvailable.getAndSet(false) && st != null) {
            runCatching {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            }
        }

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        GLES20.glClearColor(0.06f, 0.08f, 0.11f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (hasVideoFrame.get()) {
            drawQuad(
                program = oesProgram,
                texTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                texId = oesTextureId,
                uvBuffer = videoUvBuffer,
                texMatrix = stMatrix,
            )
        } else {
            if (statusDirty.getAndSet(false)) {
                uploadStatusBitmapTexture()
            }
            drawQuad(
                program = tex2dProgram,
                texTarget = GLES20.GL_TEXTURE_2D,
                texId = statusTextureId,
                uvBuffer = statusUvBuffer,
                texMatrix = IDENTITY_MATRIX,
            )
        }

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        lastRenderUptimeMs = SystemClock.uptimeMillis()
        renderedFrames.incrementAndGet()
    }

    private fun uploadStatusBitmapTexture() {
        val bmp = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            val w = outputWidth.toFloat()
            val h = outputHeight.toFloat()
            val cx = w / 2f

            canvas.drawColor(Color.rgb(14, 19, 28))

            val borderPaint = Paint().apply {
                color = Color.rgb(0, 145, 255)
                style = Paint.Style.STROKE
                strokeWidth = 3f
                isAntiAlias = true
            }
            canvas.drawRect(8f, 8f, w - 8f, h - 8f, borderPaint)

            val badgePaint = Paint().apply {
                color = Color.rgb(0, 175, 255)
                textSize = (h * 0.055f).coerceIn(18f, 28f)
                isFakeBoldText = true
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val titlePaint = Paint().apply {
                color = Color.WHITE
                textSize = (h * 0.095f).coerceIn(28f, 46f)
                isFakeBoldText = true
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val detailPaint = Paint().apply {
                color = Color.rgb(185, 225, 255)
                textSize = minOf(h * 0.052f, w * 0.022f).coerceIn(16f, 26f)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }
            val hintPaint = Paint().apply {
                color = Color.rgb(135, 165, 195)
                textSize = minOf(h * 0.042f, w * 0.018f).coerceIn(14f, 20f)
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
            }

            canvas.drawText("BMW CarPlay Probe · Faza 3A (Android Auto Most)", cx, h * 0.24f, badgePaint)
            canvas.drawText(statusTitle.get(), cx, h * 0.44f, titlePaint)
            canvas.drawText(statusDetail.get(), cx, h * 0.58f, detailPaint)
            canvas.drawText(statusHint.get(), cx, h * 0.74f, hintPaint)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, statusTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        } finally {
            bmp.recycle()
        }
    }

    private fun drawQuad(
        program: Int,
        texTarget: Int,
        texId: Int,
        uvBuffer: FloatBuffer,
        texMatrix: FloatArray,
    ) {
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uMat = GLES20.glGetUniformLocation(program, "uTexMatrix")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(texTarget, texId)
        GLES20.glUniformMatrix4fv(uMat, 1, false, texMatrix, 0)

        posBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuffer)

        uvBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, uvBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glBindTexture(texTarget, 0)
    }

    private fun buildProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(prog)
            GLES20.glDeleteProgram(prog)
            "GL link greška: $log"
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            "GL shader greška: $log"
        }
        return shader
    }

    private fun releaseEglAndGl() {
        runCatching { decoderOutputSurface?.release() }
        decoderOutputSurface = null
        runCatching { decoderSurfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { decoderSurfaceTexture?.release() }
        decoderSurfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (oesProgram != 0) {
                GLES20.glDeleteProgram(oesProgram)
                oesProgram = 0
            }
            if (tex2dProgram != 0) {
                GLES20.glDeleteProgram(tex2dProgram)
                tex2dProgram = 0
            }
            val textures = intArrayOf(oesTextureId, statusTextureId).filter { it != 0 }.toIntArray()
            if (textures.isNotEmpty()) {
                GLES20.glDeleteTextures(textures.size, textures, 0)
            }
            oesTextureId = 0
            statusTextureId = 0

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private fun createFloatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
}

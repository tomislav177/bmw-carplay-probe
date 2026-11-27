package io.bimmergestalt.bmwcarplay.mirror

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.BatteryManager
import io.bimmergestalt.bmwcarplay.mirror.CarPlayDockLayout.DockAction
import io.bimmergestalt.bmwcarplay.mirror.CarPlayDockLayout.DockGeometry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the sleek dark OEM BMW / Apple CarPlay vertical dock onto an offscreen [Bitmap]
 * for upload into [MirrorDockGlRenderer]'s `GL_TEXTURE_2D`.
 *
 * Supports both Thin OEM Dock (`122×480`) and Wide OEM Dock (`240×480`), Left or Right placement,
 * real installed app icons, active foreground app pill indicator, pressed touch highlight,
 * iDrive rotary focus ring, live clock + battery/touch-service status, and media transport controls.
 */
class CarPlayDockRenderer(
    private val context: Context,
) {
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(11, 15, 23)
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, 255, 255, 255)
    }
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 250, 252)
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val subStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tileBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(24, 33, 49)
    }
    private val tilePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(46, 68, 102)
    }
    private val tileStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(42, 255, 255, 255)
    }
    private val activeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(56, 189, 248)
    }
    private val focusRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(96, 165, 250)
    }
    private val activePillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(56, 189, 248)
    }
    private val appLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(226, 232, 240)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val mediaCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 29, 44)
    }
    private val mediaTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(148, 163, 184)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(241, 245, 249)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var cachedBitmap: Bitmap? = null

    fun render(
        geometry: DockGeometry,
        dockSide: DockSide,
        slots: List<DockAppCatalog.DockSlotVisual>,
        activeForegroundPackage: String?,
        pressedAction: DockAction?,
        focusedSlotIndex: Int?,
        accessibilityActive: Boolean,
    ): Bitmap {
        val w = geometry.dockRect.width.coerceAtLeast(64)
        val h = geometry.dockRect.height.coerceAtLeast(240)
        val bmp = cachedBitmap?.takeIf { !it.isRecycled && it.width == w && it.height == h }
            ?: run {
                cachedBitmap?.recycle()
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { cachedBitmap = it }
            }

        val canvas = Canvas(bmp)
        val ox = geometry.dockRect.left
        val oy = geometry.dockRect.top
        val scaleY = h / 480f

        // Background + map-facing edge divider
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        if (dockSide == DockSide.LEFT) {
            canvas.drawRect(w - 2f, 0f, w.toFloat(), h.toFloat(), dividerPaint)
        } else {
            canvas.drawRect(0f, 0f, 2f, h.toFloat(), dividerPaint)
        }

        // Top Clock + Battery / Touch Readiness Status
        val clockCenterX = w / 2f
        val timeText = timeFormat.format(Date())
        clockPaint.textSize = (if (geometry.isWide) 30f else 27f) * scaleY
        canvas.drawText(timeText, clockCenterX, 34f * scaleY, clockPaint)

        val batteryPct = readBatteryPercent()
        val statusText = buildString {
            if (batteryPct in 0..100) append("$batteryPct% · ")
            append(if (accessibilityActive) "Dodir OK" else "Bez dodira")
        }
        subStatusPaint.textSize = (if (geometry.isWide) 15f else 13.5f) * scaleY
        subStatusPaint.color = if (accessibilityActive) Color.rgb(52, 211, 153) else Color.rgb(251, 191, 36)
        canvas.drawText(statusText, clockCenterX, 58f * scaleY, subStatusPaint)

        // Wide Dock Media Card Background
        geometry.mediaCardRect?.let { mr ->
            val local = toLocalRectF(mr, ox, oy)
            canvas.drawRoundRect(local, 16f, 16f, mediaCardPaint)
            canvas.drawRoundRect(local, 16f, 16f, tileStrokePaint)
            mediaTitlePaint.textSize = 14f * scaleY
            canvas.drawText("GLAZBA / MEDIJI", local.centerX(), local.top + 24f * scaleY, mediaTitlePaint)
        }

        // Buttons (4 App Slots, Media Buttons, Back/Home)
        for (btn in geometry.buttons) {
            val local = toLocalRectF(btn.visualRect, ox, oy)
            val isPressed = btn.action == pressedAction

            when (val act = btn.action) {
                is DockAction.LaunchApp -> {
                    val slot = slots.getOrNull(act.slotIndex)
                    val isActive = !slot?.packageName.isNullOrBlank() && slot?.packageName == activeForegroundPackage
                    val isFocused = focusedSlotIndex == act.slotIndex
                    drawAppTile(
                        canvas = canvas,
                        rect = local,
                        dockWidth = w,
                        dockSide = dockSide,
                        isWide = geometry.isWide,
                        slot = slot,
                        isActive = isActive,
                        isPressed = isPressed,
                        isFocused = isFocused,
                        scaleY = scaleY,
                    )
                }

                DockAction.NavBack -> {
                    drawPillButton(
                        canvas = canvas,
                        rect = local,
                        text = if (geometry.isWide) "◀ Natrag" else "◀",
                        textSize = (if (geometry.isWide) 17f else 22f) * scaleY,
                        isPressed = isPressed,
                    )
                }

                DockAction.NavHome -> {
                    drawPillButton(
                        canvas = canvas,
                        rect = local,
                        text = if (geometry.isWide) "⌂ Početna" else "⌂",
                        textSize = (if (geometry.isWide) 17f else 22f) * scaleY,
                        isPressed = isPressed,
                    )
                }

                DockAction.MediaPrev -> {
                    drawMediaButton(canvas, local, "⏮", 22f * scaleY, isPressed)
                }

                DockAction.MediaPlayPause -> {
                    drawMediaButton(canvas, local, "⏯", 24f * scaleY, isPressed)
                }

                DockAction.MediaNext -> {
                    drawMediaButton(canvas, local, "⏭", 22f * scaleY, isPressed)
                }
            }
        }

        return bmp
    }

    fun release() {
        cachedBitmap?.recycle()
        cachedBitmap = null
    }

    private fun drawAppTile(
        canvas: Canvas,
        rect: RectF,
        dockWidth: Int,
        dockSide: DockSide,
        isWide: Boolean,
        slot: DockAppCatalog.DockSlotVisual?,
        isActive: Boolean,
        isPressed: Boolean,
        isFocused: Boolean,
        scaleY: Float,
    ) {
        val radius = if (isWide) 18f else 16f
        canvas.drawRoundRect(rect, radius, radius, if (isPressed) tilePressedPaint else tileBgPaint)

        // Active foreground app edge pill (classic CarPlay indicator on the driver-facing edge)
        if (isActive) {
            val pillW = 5f
            val pillH = rect.height() * 0.56f
            val pillTop = rect.centerY() - pillH / 2f
            val pillLeft = if (dockSide == DockSide.LEFT) 4f else dockWidth - 4f - pillW
            canvas.drawRoundRect(
                RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
                3f,
                3f,
                activePillPaint,
            )
        }

        val icon = slot?.iconBitmap?.takeIf { !it.isRecycled }
        if (icon != null) {
            val dstRect = if (isWide) {
                val iconSz = (minOf(rect.width(), rect.height()) * 0.56f).coerceAtLeast(32f)
                val ix = rect.centerX() - iconSz / 2f
                val iy = rect.top + 8f * scaleY
                RectF(ix, iy, ix + iconSz, iy + iconSz)
            } else {
                val inset = 5f
                RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
            }
            val clipPath = Path().apply {
                addRoundRect(dstRect, 12f, 12f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(icon, Rect(0, 0, icon.width, icon.height), dstRect, bitmapPaint)
            canvas.restore()
        } else {
            // Empty / fallback slot
            val initial = slot?.label?.firstOrNull()?.uppercase() ?: "+"
            glyphPaint.textSize = (if (isWide) 28f else 26f) * scaleY
            val textY = if (isWide) rect.top + rect.height() * 0.48f else rect.centerY() + glyphPaint.textSize * 0.34f
            canvas.drawText(initial, rect.centerX(), textY, glyphPaint)
        }

        if (isWide) {
            val label = ellipsize(slot?.label ?: "Dodaj", 10)
            appLabelPaint.textSize = 14f * scaleY
            canvas.drawText(label, rect.centerX(), rect.bottom - 10f * scaleY, appLabelPaint)
        }

        val stroke = when {
            isFocused -> focusRingPaint
            isActive -> activeStrokePaint
            else -> tileStrokePaint
        }
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    private fun drawPillButton(
        canvas: Canvas,
        rect: RectF,
        text: String,
        textSize: Float,
        isPressed: Boolean,
    ) {
        canvas.drawRoundRect(rect, 12f, 12f, if (isPressed) tilePressedPaint else tileBgPaint)
        canvas.drawRoundRect(rect, 12f, 12f, tileStrokePaint)
        glyphPaint.textSize = textSize
        val baseline = rect.centerY() - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), baseline, glyphPaint)
    }

    private fun drawMediaButton(
        canvas: Canvas,
        rect: RectF,
        glyph: String,
        textSize: Float,
        isPressed: Boolean,
    ) {
        if (isPressed) {
            val inset = RectF(rect.left + 4f, rect.top + 2f, rect.right - 4f, rect.bottom - 2f)
            canvas.drawRoundRect(inset, 10f, 10f, tilePressedPaint)
        }
        glyphPaint.textSize = textSize
        val baseline = rect.centerY() - (glyphPaint.descent() + glyphPaint.ascent()) / 2f
        canvas.drawText(glyph, rect.centerX(), baseline, glyphPaint)
    }

    private fun toLocalRectF(r: MirrorLayoutMath.RectInt, ox: Int, oy: Int): RectF =
        RectF(
            (r.left - ox).toFloat(),
            (r.top - oy).toFloat(),
            (r.right - ox).toFloat(),
            (r.bottom - oy).toFloat(),
        )

    private fun readBatteryPercent(): Int = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }.getOrDefault(-1)

    private fun ellipsize(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
}

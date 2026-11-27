package io.bimmergestalt.bmwcarplay.mirror

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.input.BmwTouchAccessibilityService
import java.text.Collator
import java.util.Locale

/**
 * Discovers user-installed launchable apps on Galaxy S25, resolves smart first-run CarPlay Dock
 * defaults, renders real app icons to bitmaps for the OpenGL dock texture, and launches selected
 * apps safely under Android 14–17 Background Activity Launch (BAL) rules.
 */
object DockAppCatalog {

    data class InstalledApp(
        val packageName: String,
        val label: String,
    )

    data class DockSlotVisual(
        val slotIndex: Int,
        val packageName: String?,
        val label: String,
        val iconBitmap: Bitmap?,
    )

    private val DEFAULT_SLOT_CANDIDATES: List<List<String>> = listOf(
        listOf(
            "com.google.android.apps.maps",
            "com.waze",
            "com.here.app.maps",
            "net.osmand.plus",
            "net.osmand",
        ),
        listOf(
            "com.waze",
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.google.android.youtube",
        ),
        listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.sec.android.app.music",
            "deezer.android.app",
            "com.aspiro.tidal",
        ),
        listOf(
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.whatsapp",
            "com.viber.voip",
        ),
    )

    fun listLaunchableApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val seen = LinkedHashMap<String, InstalledApp>()
        for (ri in resolved) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == context.packageName) continue
            if (seen.containsKey(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString().trim() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: pkg
            seen[pkg] = InstalledApp(packageName = pkg, label = label)
        }
        val collator = Collator.getInstance(Locale("hr", "HR"))
        return seen.values.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }

    fun suggestDefaultPackages(context: Context): List<String?> {
        val pm = context.packageManager
        val chosen = LinkedHashSet<String>()
        val result = ArrayList<String?>(MirrorDisplayConfig.DOCK_SLOT_COUNT)
        for (candidates in DEFAULT_SLOT_CANDIDATES) {
            val match = candidates.firstOrNull { pkg ->
                !chosen.contains(pkg) && runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
            }
            if (match != null) {
                chosen.add(match)
            }
            result.add(match)
        }
        return result
    }

    fun getAppLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrBlank()) return "Prazno"
        val pm = context.packageManager
        return runCatching {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull() ?: packageName.substringAfterLast('.')
    }

    fun loadAppIconDrawable(context: Context, packageName: String?): Drawable? {
        if (packageName.isNullOrBlank()) return null
        return runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
    }

    fun loadSlotVisuals(
        context: Context,
        packages: List<String?>,
        iconSizePx: Int = 96,
    ): List<DockSlotVisual> {
        val pm = context.packageManager
        return (0 until MirrorDisplayConfig.DOCK_SLOT_COUNT).map { slot ->
            val pkg = packages.getOrNull(slot)?.takeIf { it.isNotBlank() }
            if (pkg == null) {
                DockSlotVisual(slot, null, "Prazno", null)
            } else {
                val label = getAppLabel(context, pkg)
                val bmp = runCatching {
                    val drawable = pm.getApplicationIcon(pkg)
                    drawableToBitmap(drawable, iconSizePx)
                }.getOrNull()
                DockSlotVisual(slot, pkg, label, bmp)
            }
        }
    }

    fun launchApp(context: Context, packageName: String, logger: DiagnosticLogger): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        }
        if (launchIntent == null) {
            logger.log("DOCK", "✗ Aplikacija '$packageName' nema pokretačku aktivnost")
            return false
        }

        // Prefer launching via active AccessibilityService to avoid Android 14-17 BAL blocks
        val a11y = BmwTouchAccessibilityService.instance
        if (a11y != null && a11y.launchAppFromService(launchIntent)) {
            logger.log("DOCK", "✓ Pokrenuta aplikacija iz docka (via Pristupačnost): $packageName")
            return true
        }

        return runCatching {
            context.startActivity(launchIntent)
            logger.log("DOCK", "✓ Pokrenuta aplikacija iz docka: $packageName")
            true
        }.onFailure { e ->
            logger.log("DOCK", "✗ Ne mogu pokrenuti '$packageName': ${e.message}")
        }.getOrDefault(false)
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val safeSize = sizePx.coerceIn(32, 192)
        val bmp = Bitmap.createBitmap(safeSize, safeSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, safeSize, safeSize)
        drawable.draw(canvas)
        return bmp
    }
}

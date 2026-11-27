package io.bimmergestalt.bmwcarplay.mirror

/**
 * Display and CarPlay Dock configuration for Phase 4A Screen Mirroring on BMW HU_ENTRYNAV2.
 */
enum class MirrorLayoutMode(
    val labelHr: String,
    val baseDockWidth1280: Int,
    val defaultCropTopPx: Int,
    val defaultCropBottomPx: Int,
) {
    SMART_CROP_DOCK(
        labelHr = "Smart Crop + tanak Dock (122 px · preporučeno)",
        baseDockWidth1280 = 122,
        defaultCropTopPx = 55,
        defaultCropBottomPx = 55,
    ),
    FIT_WIDE_DOCK(
        labelHr = "1:1 Fit + širi Dock (240 px · s media kontrolama)",
        baseDockWidth1280 = 240,
        defaultCropTopPx = 0,
        defaultCropBottomPx = 0,
    ),
}

enum class DockSide(val labelHr: String) {
    LEFT("Lijevo (uz vozača — preporučeno)"),
    RIGHT("Desno"),
}

data class MirrorDisplayConfig(
    val layoutMode: MirrorLayoutMode = MirrorLayoutMode.SMART_CROP_DOCK,
    val dockSide: DockSide = DockSide.LEFT,
    val cropTopPx: Int = MirrorLayoutMode.SMART_CROP_DOCK.defaultCropTopPx,
    val cropBottomPx: Int = MirrorLayoutMode.SMART_CROP_DOCK.defaultCropBottomPx,
    val dockAppPackages: List<String?> = List(DOCK_SLOT_COUNT) { null },
) {
    companion object {
        const val DOCK_SLOT_COUNT = 4
        const val MIN_CROP_PX = 0
        const val MAX_CROP_PX = 200
    }
}

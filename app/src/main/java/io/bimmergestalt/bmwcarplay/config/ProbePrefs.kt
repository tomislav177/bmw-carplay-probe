package io.bimmergestalt.bmwcarplay.config

import android.content.Context
import android.content.SharedPreferences
import io.bimmergestalt.bmwcarplay.bt.BluetoothConstants
import io.bimmergestalt.bmwcarplay.mirror.DockAppCatalog
import io.bimmergestalt.bmwcarplay.mirror.DockSide
import io.bimmergestalt.bmwcarplay.mirror.MirrorDisplayConfig
import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMode

/**
 * Persistent preferences for BMW CarPlay Probe (Phase 4A):
 * phone BT MAC, target vehicle, screen mirror + CarPlay Dock layout/side/crop sliders/4 user apps,
 * auto-connect, MFi challenge memory, RFCOMM security mode, and diagnostic logging options.
 */
class ProbePrefs(context: Context) {

    enum class MfiChallengeMode(val labelHr: String, val fixedLength: Int?) {
        AUTO("Auto (CP2.0C 20B / MFi3 32B)", null),
        BYTES_32("32 bajta", 32),
        BYTES_20("20 bajtova", 20),
        BYTES_16("16 bajtova", 16),
    }

    enum class RfcommMode(val labelHr: String, val secure: Boolean) {
        INSECURE("Insecure (zadano — BMW potvrđeno)", false),
        SECURE("Secure (A/B test)", true),
    }

    enum class VideoSourceMode(val labelHr: String) {
        SCREEN_MIRROR("Zrcaljenje zaslona + CarPlay Dock (preporučeno)"),
        TEST_PATTERN("Testna slika (dijagnostika)"),
    }

    data class TargetDevice(
        val name: String,
        val address: String,
    )

    companion object {
        private const val PREFS_NAME = "bmw_carplay_probe_prefs"
        private const val KEY_PHONE_BT_MAC = "phone_bt_mac"
        private const val KEY_TARGET_NAME = "target_device_name"
        private const val KEY_TARGET_MAC = "target_device_mac"
        private const val KEY_MFI_MODE = "mfi_challenge_mode"
        private const val KEY_RFCOMM_MODE = "rfcomm_mode"
        private const val KEY_OUTBOUND_CLIENT = "outbound_bt_client_enabled_v2"
        private const val KEY_FULL_HEX = "full_hex_enabled"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_VIDEO_SOURCE_MODE = "video_source_mode_v4"
        private const val KEY_AUTO_CONNECT_ON_OPEN = "auto_connect_on_open"
        private const val KEY_MIRROR_LAYOUT_MODE = "mirror_layout_mode"
        private const val KEY_DOCK_SIDE = "mirror_dock_side"
        private const val PREFIX_CROP_TOP = "mirror_crop_top_"
        private const val PREFIX_CROP_BOTTOM = "mirror_crop_bottom_"
        private const val PREFIX_DOCK_APP = "mirror_dock_app_slot_"
        private const val KEY_DOCK_DEFAULTS_INIT = "mirror_dock_defaults_initialized_v1"
        private const val PREFIX_MFI_SERIAL_OK = "mfi_ok_len_"

        private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

        fun isValidMac(mac: String): Boolean = MAC_REGEX.matches(mac.trim())

        fun normalizeMac(mac: String): String = mac.trim().uppercase()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var phoneBluetoothMac: String
        get() {
            val saved = prefs.getString(KEY_PHONE_BT_MAC, null)
            return if (saved != null && isValidMac(saved)) {
                normalizeMac(saved)
            } else {
                BluetoothConstants.DEFAULT_PHONE_BT_MAC
            }
        }
        set(value) {
            if (isValidMac(value)) {
                prefs.edit().putString(KEY_PHONE_BT_MAC, normalizeMac(value)).apply()
            }
        }

    var targetDevice: TargetDevice?
        get() {
            val mac = prefs.getString(KEY_TARGET_MAC, null) ?: return null
            val name = prefs.getString(KEY_TARGET_NAME, mac) ?: mac
            return TargetDevice(name = name, address = normalizeMac(mac))
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_TARGET_NAME).remove(KEY_TARGET_MAC).apply()
            } else {
                prefs.edit()
                    .putString(KEY_TARGET_NAME, value.name)
                    .putString(KEY_TARGET_MAC, normalizeMac(value.address))
                    .apply()
            }
        }

    var mfiChallengeMode: MfiChallengeMode
        get() {
            val raw = prefs.getString(KEY_MFI_MODE, MfiChallengeMode.AUTO.name)
            return MfiChallengeMode.entries.firstOrNull { it.name == raw } ?: MfiChallengeMode.AUTO
        }
        set(value) {
            prefs.edit().putString(KEY_MFI_MODE, value.name).apply()
        }

    var rfcommMode: RfcommMode
        get() {
            val raw = prefs.getString(KEY_RFCOMM_MODE, RfcommMode.INSECURE.name)
            return RfcommMode.entries.firstOrNull { it.name == raw } ?: RfcommMode.INSECURE
        }
        set(value) {
            prefs.edit().putString(KEY_RFCOMM_MODE, value.name).apply()
        }

    var outboundClientEnabled: Boolean
        get() = prefs.getBoolean(KEY_OUTBOUND_CLIENT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OUTBOUND_CLIENT, value).apply()
        }

    var fullHexEnabled: Boolean
        get() = prefs.getBoolean(KEY_FULL_HEX, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FULL_HEX, value).apply()
        }

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()
        }

    var videoSourceMode: VideoSourceMode
        get() {
            val raw = prefs.getString(KEY_VIDEO_SOURCE_MODE, VideoSourceMode.SCREEN_MIRROR.name)
            return VideoSourceMode.entries.firstOrNull { it.name == raw } ?: VideoSourceMode.SCREEN_MIRROR
        }
        set(value) {
            prefs.edit().putString(KEY_VIDEO_SOURCE_MODE, value.name).apply()
        }

    var autoConnectOnOpen: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONNECT_ON_OPEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CONNECT_ON_OPEN, value).apply()
        }

    var mirrorLayoutMode: MirrorLayoutMode
        get() {
            val raw = prefs.getString(KEY_MIRROR_LAYOUT_MODE, MirrorLayoutMode.SMART_CROP_DOCK.name)
            return MirrorLayoutMode.entries.firstOrNull { it.name == raw } ?: MirrorLayoutMode.SMART_CROP_DOCK
        }
        set(value) {
            prefs.edit().putString(KEY_MIRROR_LAYOUT_MODE, value.name).apply()
        }

    var dockSide: DockSide
        get() {
            val raw = prefs.getString(KEY_DOCK_SIDE, DockSide.LEFT.name)
            return DockSide.entries.firstOrNull { it.name == raw } ?: DockSide.LEFT
        }
        set(value) {
            prefs.edit().putString(KEY_DOCK_SIDE, value.name).apply()
        }

    fun getCropTopPx(mode: MirrorLayoutMode = mirrorLayoutMode): Int =
        prefs.getInt(PREFIX_CROP_TOP + mode.name, mode.defaultCropTopPx)
            .coerceIn(MirrorDisplayConfig.MIN_CROP_PX, MirrorDisplayConfig.MAX_CROP_PX)

    fun setCropTopPx(px: Int, mode: MirrorLayoutMode = mirrorLayoutMode) {
        val clamped = px.coerceIn(MirrorDisplayConfig.MIN_CROP_PX, MirrorDisplayConfig.MAX_CROP_PX)
        prefs.edit().putInt(PREFIX_CROP_TOP + mode.name, clamped).apply()
    }

    fun getCropBottomPx(mode: MirrorLayoutMode = mirrorLayoutMode): Int =
        prefs.getInt(PREFIX_CROP_BOTTOM + mode.name, mode.defaultCropBottomPx)
            .coerceIn(MirrorDisplayConfig.MIN_CROP_PX, MirrorDisplayConfig.MAX_CROP_PX)

    fun setCropBottomPx(px: Int, mode: MirrorLayoutMode = mirrorLayoutMode) {
        val clamped = px.coerceIn(MirrorDisplayConfig.MIN_CROP_PX, MirrorDisplayConfig.MAX_CROP_PX)
        prefs.edit().putInt(PREFIX_CROP_BOTTOM + mode.name, clamped).apply()
    }

    fun resetCropDefaults(mode: MirrorLayoutMode = mirrorLayoutMode) {
        prefs.edit()
            .putInt(PREFIX_CROP_TOP + mode.name, mode.defaultCropTopPx)
            .putInt(PREFIX_CROP_BOTTOM + mode.name, mode.defaultCropBottomPx)
            .apply()
    }

    fun ensureDefaultDockAppsInitialized(context: Context) {
        if (prefs.getBoolean(KEY_DOCK_DEFAULTS_INIT, false)) return
        val defaults = DockAppCatalog.suggestDefaultPackages(context)
        val editor = prefs.edit().putBoolean(KEY_DOCK_DEFAULTS_INIT, true)
        for (slot in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) {
            val pkg = defaults.getOrNull(slot)
            if (!pkg.isNullOrBlank()) {
                editor.putString(PREFIX_DOCK_APP + slot, pkg)
            }
        }
        editor.apply()
    }

    fun getDockAppPackage(slot: Int): String? {
        if (slot !in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) return null
        return prefs.getString(PREFIX_DOCK_APP + slot, null)?.takeIf { it.isNotBlank() }
    }

    fun setDockAppPackage(slot: Int, packageName: String?) {
        if (slot !in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) return
        val editor = prefs.edit()
        if (packageName.isNullOrBlank()) {
            editor.remove(PREFIX_DOCK_APP + slot)
        } else {
            editor.putString(PREFIX_DOCK_APP + slot, packageName.trim())
        }
        editor.apply()
    }

    fun getAllDockAppPackages(): List<String?> =
        (0 until MirrorDisplayConfig.DOCK_SLOT_COUNT).map { getDockAppPackage(it) }

    fun readMirrorDisplayConfig(): MirrorDisplayConfig {
        val mode = mirrorLayoutMode
        return MirrorDisplayConfig(
            layoutMode = mode,
            dockSide = dockSide,
            cropTopPx = getCropTopPx(mode),
            cropBottomPx = getCropBottomPx(mode),
            dockAppPackages = getAllDockAppPackages(),
        )
    }

    fun saveSucceededMfiLength(serialNumber: String, lengthBytes: Int) {
        val cleanSerial = serialNumber.trim()
        if (cleanSerial.isEmpty()) return
        prefs.edit().putInt(PREFIX_MFI_SERIAL_OK + cleanSerial, lengthBytes).apply()
    }

    fun getSucceededMfiLength(serialNumber: String?): Int? {
        val cleanSerial = serialNumber?.trim().orEmpty()
        if (cleanSerial.isEmpty()) return null
        val value = prefs.getInt(PREFIX_MFI_SERIAL_OK + cleanSerial, -1)
        return if (value > 0) value else null
    }

    fun loadOrCreateControllerIdentity(): io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayPairingClient.ControllerIdentity {
        val savedId = prefs.getString("hk_controller_pairing_id", null)
        val savedPrivB64 = prefs.getString("hk_controller_ed25519_priv", null)
        if (!savedId.isNullOrBlank() && !savedPrivB64.isNullOrBlank()) {
            val priv = runCatching {
                android.util.Base64.decode(savedPrivB64, android.util.Base64.NO_WRAP)
            }.getOrNull()
            if (priv != null && priv.size == 32) {
                val pub = io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayCrypto.ed25519PublicFromPrivate(priv)
                return io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayPairingClient.ControllerIdentity(
                    pairingId = savedId,
                    ed25519PrivateKey = priv,
                    ed25519PublicKey = pub,
                )
            }
        }
        val newId = java.util.UUID.randomUUID().toString().uppercase()
        val kp = io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayCrypto.generateEd25519KeyPair()
        val privB64 = android.util.Base64.encodeToString(kp.privateKey, android.util.Base64.NO_WRAP)
        prefs.edit()
            .putString("hk_controller_pairing_id", newId)
            .putString("hk_controller_ed25519_priv", privB64)
            .apply()
        return io.bimmergestalt.bmwcarplay.airplay.crypto.AirPlayPairingClient.ControllerIdentity(
            pairingId = newId,
            ed25519PrivateKey = kp.privateKey,
            ed25519PublicKey = kp.publicKey,
        )
    }
}

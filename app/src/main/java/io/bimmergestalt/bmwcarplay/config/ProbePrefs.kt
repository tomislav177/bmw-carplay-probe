package io.bimmergestalt.bmwcarplay.config

import android.content.Context
import android.content.SharedPreferences
import io.bimmergestalt.bmwcarplay.bt.BluetoothConstants

/**
 * Persistent preferences for BMW CarPlay Probe (phone BT MAC, target vehicle,
 * MFi challenge length setting & per-serial memory, RFCOMM security mode, and UI options).
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

    /**
     * Saves the MFi challenge byte length that succeeded for a given head unit serial number
     * (e.g. "AL07FFL0665583").
     */
    fun saveSucceededMfiLength(serialNumber: String, lengthBytes: Int) {
        val cleanSerial = serialNumber.trim()
        if (cleanSerial.isEmpty()) return
        prefs.edit().putInt(PREFIX_MFI_SERIAL_OK + cleanSerial, lengthBytes).apply()
    }

    /**
     * Loads the previously succeeded MFi challenge length for a given head unit serial number,
     * or null if no successful challenge has been recorded yet.
     */
    fun getSucceededMfiLength(serialNumber: String?): Int? {
        val cleanSerial = serialNumber?.trim().orEmpty()
        if (cleanSerial.isEmpty()) return null
        val value = prefs.getInt(PREFIX_MFI_SERIAL_OK + cleanSerial, -1)
        return if (value > 0) value else null
    }

    /**
     * Loads or generates the persistent HomeKit AirPlay controller identity (UUID + Ed25519 key pair).
     */
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

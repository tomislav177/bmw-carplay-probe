package io.bimmergestalt.bmwcarplay.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Implements the Wireless CarPlay "Bluetooth paired-only, audio profiles disconnected" policy
 * specifically for the target BMW head unit (NEVER touches watches, headsets, or non-BMW devices).
 *
 * Confirmed on BMW Entry Evo (HU_ENTRYNAV2 / iDrive 6):
 * - When Android keeps HFP (Calls) and/or A2DP (Media Audio) connected to BMW 04990,
 *   iDrive treats the phone as a regular BT Telephone/Media iPod device (0x5000/0x4C00)
 *   and does not switch to Wireless CarPlay Wi-Fi (0x5703).
 * - Disconnecting ONLY profile-level HFP/A2DP/LE Audio (NEVER device-level ACL disconnect!)
 *   keeps bonding and iAP2 RFCOMM intact while allowing BMW to enter CarPlay mode.
 */
class BluetoothProfileGuard(
    private val context: Context,
    private val logger: DiagnosticLogger,
) {
    private val proxies = HashMap<Int, BluetoothProfile>()

    private fun watchedProfiles(): List<Int> = buildList {
        add(BluetoothProfile.HEADSET)
        add(BluetoothProfile.A2DP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(BluetoothProfile.LE_AUDIO)
        }
    }

    private fun profileLabelHr(profile: Int): String = when {
        profile == BluetoothProfile.HEADSET -> "Pozivi(HFP)"
        profile == BluetoothProfile.A2DP -> "Zvuk(A2DP)"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            profile == BluetoothProfile.LE_AUDIO -> "LE Audio"
        else -> "Profil#$profile"
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun bluetoothAdapter(): BluetoothAdapter? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /**
     * Queries which audio/call profiles (HFP/A2DP/LE Audio) are currently connected for [device].
     * Must be invoked from a background thread (never main Looper).
     */
    @SuppressLint("MissingPermission")
    fun connectedProfileLabels(device: BluetoothDevice): List<String> {
        if (!hasBluetoothConnectPermission()) return emptyList()
        val addr = runCatching { device.address }.getOrNull() ?: return emptyList()
        return watchedProfiles().mapNotNull { profileId ->
            val proxy = getOrObtainProxy(profileId) ?: return@mapNotNull null
            val isConnected = runCatching {
                proxy.connectedDevices.any { it.address.equals(addr, ignoreCase = true) }
            }.getOrDefault(false)
            if (isConnected) profileLabelHr(profileId) else null
        }
    }

    /**
     * Logs the current HFP/A2DP/LE Audio profile state for [device] without modifying it.
     */
    @SuppressLint("MissingPermission")
    fun logProfileState(device: BluetoothDevice, contextLabelHr: String) {
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "BMW" }
        val addr = runCatching { device.address }.getOrNull() ?: "?"
        val connected = connectedProfileLabels(device)
        val summary = if (connected.isEmpty()) "nijedan (čisto — spremno za CarPlay)" else connected.joinToString(", ")
        logger.log("BT-PROFILE", "$contextLabelHr — aktivni audio/poziv profili za $name ($addr): $summary")
    }

    /**
     * Disconnects ONLY profile-level HFP/A2DP/LE Audio connections for [device] (never touches ACL/RFCOMM).
     * Returns the list of profile labels that were connected before the disconnect attempt.
     */
    @SuppressLint("MissingPermission")
    fun disconnectAudioProfiles(device: BluetoothDevice, reasonHr: String): List<String> {
        if (!hasBluetoothConnectPermission()) return emptyList()
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "BMW" }
        val addr = runCatching { device.address }.getOrNull() ?: return emptyList()

        val before = connectedProfileLabels(device)
        if (before.isEmpty()) {
            logger.log("BT-PROFILE", "$reasonHr: $name ($addr) nema aktivnih HFP/A2DP profila (OK)")
            return emptyList()
        }

        logger.log(
            "BT-PROFILE",
            "$reasonHr: $name ($addr) ima aktivne profile [${before.joinToString(", ")}] — " +
                "odspajam samo HFP/A2DP radi CarPlay načina (uparivanje i RFCOMM ostaju aktivni)...",
        )

        var invokedAny = false
        for (profileId in watchedProfiles()) {
            val label = profileLabelHr(profileId)
            if (label !in before) continue
            val proxy = getOrObtainProxy(profileId) ?: continue
            val ok = invokeProfileDisconnect(proxy, device)
            invokedAny = invokedAny || ok
            logger.log("BT-PROFILE", "  disconnect($label) → ${if (ok) "poslano" else "odbijeno/nedostupno na ovom ROM-u"}")
        }

        if (invokedAny) {
            val deadline = SystemClock.uptimeMillis() + 1_200L
            while (SystemClock.uptimeMillis() < deadline) {
                if (connectedProfileLabels(device).isEmpty()) break
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }

        val after = connectedProfileLabels(device)
        if (after.isEmpty()) {
            logger.log("BT-PROFILE", "✓ HFP/A2DP profili za $name ($addr) uspješno odspojeni")
        } else {
            logger.log(
                "BT-PROFILE",
                "⚠ Profili [${after.joinToString(", ")}] su još aktivni za $name ($addr) — " +
                    "ako BMW ostane na BT Audio, ručno isključi 'Pozivi' i 'Zvuk' u BT postavkama za BMW 04990",
            )
        }
        return before
    }

    /**
     * Logs cached SDP UUIDs for [device] and optionally triggers fresh SDP discovery (ACTION_UUID).
     */
    @SuppressLint("MissingPermission")
    fun inspectSdpUuids(device: BluetoothDevice, contextLabelHr: String, fetchIfMissing: Boolean = true) {
        if (!hasBluetoothConnectPermission()) return
        val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "BMW" }
        val addr = runCatching { device.address }.getOrNull() ?: "?"
        val uuids = runCatching { device.uuids?.mapNotNull { it?.uuid }.orEmpty() }.getOrElse { emptyList() }
        val hasAcc = BluetoothConstants.ACCESSORY_IAP2_UUID in uuids
        val hasCarPlay = BluetoothConstants.VEHICLE_CARPLAY_UUID in uuids
        val hasPhone = BluetoothConstants.IPHONE_IAP2_UUID in uuids

        if (uuids.isNotEmpty()) {
            val formatted = uuids.map { it.toString().lowercase() }.sorted().joinToString("\n  ")
            logger.log(
                "BT-SDP",
                "$contextLabelHr: $name ($addr) | SDP UUID-ovi (${uuids.size}): " +
                    "Accessory(...caff)=$hasAcc, CarPlay(...88ec)=$hasCarPlay, iPhone(...cafe)=$hasPhone\n  $formatted",
            )
        } else {
            logger.log("BT-SDP", "$contextLabelHr: $name ($addr) nema keširanih SDP UUID-ova")
        }

        if (fetchIfMissing && uuids.isEmpty()) {
            val requested = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            logger.log(
                "BT-SDP",
                "Pokrenut fetchUuidsWithSdp() za $name ($addr) → ${if (requested) "zahtjev poslan (čekam ACTION_UUID)" else "nije prihvaćen"}",
            )
        }
    }

    /**
     * Releases all cached BluetoothProfile proxies cleanly when the diagnostic session stops.
     */
    fun close() {
        val adapter = bluetoothAdapter()
        val snapshot: List<Pair<Int, BluetoothProfile>>
        synchronized(proxies) {
            snapshot = proxies.entries.map { it.key to it.value }
            proxies.clear()
        }
        if (adapter != null) {
            for ((profileId, proxy) in snapshot) {
                runCatching { adapter.closeProfileProxy(profileId, proxy) }
            }
        }
    }

    private fun getOrObtainProxy(profileId: Int): BluetoothProfile? {
        synchronized(proxies) {
            proxies[profileId]?.let { return it }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            logger.log("BT-PROFILE", "UPOZORENJE: getOrObtainProxy pozvan na glavnoj niti — preskačem")
            return null
        }
        val adapter = bluetoothAdapter() ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val ok = runCatching {
            adapter.getProfileProxy(
                context.applicationContext,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        ref.set(proxy)
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        synchronized(proxies) { proxies.remove(profile) }
                        latch.countDown()
                    }
                },
                profileId,
            )
        }.getOrDefault(false)
        if (!ok) return null
        if (!latch.await(1_200L, TimeUnit.MILLISECONDS)) return null
        val obtained = ref.get() ?: return null
        synchronized(proxies) {
            proxies[profileId] = obtained
        }
        return obtained
    }

    private fun invokeProfileDisconnect(proxy: BluetoothProfile, device: BluetoothDevice): Boolean =
        runCatching {
            val method = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, device)
            (result as? Boolean) ?: true
        }.getOrDefault(false)
}

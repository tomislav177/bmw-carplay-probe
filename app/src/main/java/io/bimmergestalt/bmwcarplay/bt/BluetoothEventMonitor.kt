package io.bimmergestalt.bmwcarplay.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger

/**
 * Dynamic Bluetooth broadcast monitor logging ACL connect/disconnect,
 * bond state transitions, and discoverable scan mode changes.
 *
 * Registered ONLY while an active diagnostic session (or foreground UI) is running.
 * Never declared statically in AndroidManifest so zero battery is consumed when idle.
 */
class BluetoothEventMonitor(
    private val context: Context,
    private val logger: DiagnosticLogger,
    private val onBondStateChanged: ((deviceName: String, address: String, bondState: Int) -> Unit)? = null,
) {
    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val dev = extractDevice(intent)
                    val name = safeName(dev)
                    val addr = dev?.address ?: "?"
                    logger.log("BT-EVENT", "ACL SPOJEN: $name ($addr)")
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev = extractDevice(intent)
                    val name = safeName(dev)
                    val addr = dev?.address ?: "?"
                    logger.log("BT-EVENT", "ACL ODPOJEN: $name ($addr)")
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev = extractDevice(intent)
                    val name = safeName(dev)
                    val addr = dev?.address ?: "?"
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val curr = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    logger.log(
                        "BT-EVENT",
                        "UPARIVANJE PROMIJENJENO: $name ($addr) ${bondName(prev)} → ${bondName(curr)}",
                    )
                    onBondStateChanged?.invoke(name, addr, curr)
                }

                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED -> {
                    val mode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, BluetoothAdapter.ERROR)
                    val label = when (mode) {
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "VIDLJIV I SPOJIV (Discoverable)"
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "SPOJIV (nije vidljiv za pretragu)"
                        BluetoothAdapter.SCAN_MODE_NONE -> "NEAKTIVAN"
                        else -> "NEPOZNAT ($mode)"
                    }
                    logger.log("BT-EVENT", "Bluetooth vidljivost: $label")
                }
            }
        }
    }

    @Synchronized
    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        logger.log("BT-EVENT", "Praćenje ACL i uparivanja (Bond) pokrenuto")
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
        logger.log("BT-EVENT", "Praćenje ACL i uparivanja zaustavljeno")
    }

    private fun extractDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice?): String =
        runCatching { device?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Nepoznat uređaj"

    private fun bondName(state: Int): String = when (state) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE (nije uparen)"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING (uparivanje u tijeku)"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED (uparen)"
        else -> "STATE($state)"
    }
}

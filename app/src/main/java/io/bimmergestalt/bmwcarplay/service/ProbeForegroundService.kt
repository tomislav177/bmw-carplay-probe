package io.bimmergestalt.bmwcarplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.bimmergestalt.bmwcarplay.ProbeApp
import io.bimmergestalt.bmwcarplay.R
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service of type `connectedDevice` running ONLY while the user has actively
 * pressed "Pokreni test". Holds PARTIAL_WAKE_LOCK while active and provides a persistent
 * notification with a direct "Zaustavi" stop button.
 *
 * Uses START_NOT_STICKY and releases all resources on stop/destroy so battery usage is 0.0%
 * when the diagnostic test is not running.
 */
class ProbeForegroundService : Service() {

    companion object {
        const val ACTION_START_FULL = "io.bimmergestalt.bmwcarplay.ACTION_START_FULL"
        const val ACTION_START_MANUAL_WIFI = "io.bimmergestalt.bmwcarplay.ACTION_START_MANUAL_WIFI"
        const val ACTION_STOP = "io.bimmergestalt.bmwcarplay.ACTION_STOP"

        const val EXTRA_WIFI_SSID = "extra_wifi_ssid"
        const val EXTRA_WIFI_PASS = "extra_wifi_pass"
        const val EXTRA_WIFI_SECURITY_CODE = "extra_wifi_sec"

        private const val CHANNEL_ID = "bmw_carplay_probe_active"
        private const val NOTIFICATION_ID = 1048

        fun startFull(context: Context) {
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_START_FULL
            }
            context.startForegroundService(intent)
        }

        fun startManualWifi(
            context: Context,
            ssid: String,
            passphrase: String,
            securityCode: Int,
        ) {
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_START_MANUAL_WIFI
                putExtra(EXTRA_WIFI_SSID, ssid)
                putExtra(EXTRA_WIFI_PASS, passphrase)
                putExtra(EXTRA_WIFI_SECURITY_CODE, securityCode)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateCollectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ProbeApp
        when (intent?.action) {
            ACTION_STOP -> {
                app.orchestrator.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_MANUAL_WIFI -> {
                val ssid = intent.getStringExtra(EXTRA_WIFI_SSID).orEmpty()
                val pass = intent.getStringExtra(EXTRA_WIFI_PASS).orEmpty()
                val secCode = intent.getIntExtra(EXTRA_WIFI_SECURITY_CODE, 2)
                promoteForeground("Ručni Wi-Fi test: $ssid...")
                acquireWakeLock()
                observeOrchestratorStatus(app)
                app.orchestrator.startManualWifiTest(
                    ssid = ssid,
                    passphrase = pass,
                    securityType = Iap2Parsers.WifiSecurityType.fromCode(secCode),
                )
            }

            ACTION_START_FULL, null -> {
                promoteForeground("Pokrećem BMW CarPlay Probe dijagnostiku...")
                acquireWakeLock()
                observeOrchestratorStatus(app)
                val ok = app.orchestrator.startFullProbe()
                if (!ok) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateCollectJob?.cancel()
        serviceScope.cancel()
        val app = application as? ProbeApp
        if (app?.orchestrator?.state?.value?.isRunning == true) {
            app.orchestrator.stop()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun observeOrchestratorStatus(app: ProbeApp) {
        stateCollectJob?.cancel()
        stateCollectJob = serviceScope.launch {
            app.orchestrator.state.collect { st ->
                val nm = getSystemService(NotificationManager::class.java)
                nm?.notify(NOTIFICATION_ID, buildNotification(st.statusHeadlineHr))
            }
        }
    }

    private fun promoteForeground(statusText: String) {
        val notification = buildNotification(statusText)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProbeForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_probe_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.btn_stop), stopIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BMWCarPlayProbe::ActiveSessionWakeLock",
        ).apply {
            setReferenceCounted(false)
            // Safety cap of 60 minutes in case process is ever killed unexpectedly
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }
}

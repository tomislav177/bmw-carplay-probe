package io.bimmergestalt.bmwcarplay.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
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
 * Foreground service running ONLY while the user has actively started a BMW CarPlay session.
 *
 * Uses `connectedDevice` always, and adds `mediaProjection` strictly AFTER the user has granted
 * `MediaProjectionManager.createScreenCaptureIntent()` (required by Android 14–17 FGS rules).
 * Releases all sockets, VirtualDisplay, MediaProjection, and wake lock immediately on stop so
 * idle battery drain is 0.0%.
 */
class ProbeForegroundService : Service() {

    companion object {
        const val ACTION_START_FULL = "io.bimmergestalt.bmwcarplay.ACTION_START_FULL"
        const val ACTION_START_MANUAL_WIFI = "io.bimmergestalt.bmwcarplay.ACTION_START_MANUAL_WIFI"
        const val ACTION_STOP = "io.bimmergestalt.bmwcarplay.ACTION_STOP"

        const val EXTRA_WIFI_SSID = "extra_wifi_ssid"
        const val EXTRA_WIFI_PASS = "extra_wifi_pass"
        const val EXTRA_WIFI_SECURITY_CODE = "extra_wifi_sec"
        const val EXTRA_MP_RESULT_CODE = "extra_mp_result_code"
        const val EXTRA_MP_DATA = "extra_mp_data"

        private const val CHANNEL_ID = "bmw_carplay_probe_active"
        private const val ALERT_CHANNEL_ID = "bmw_carplay_probe_wifi_alert"
        private const val NOTIFICATION_ID = 1048
        private const val WIFI_ALERT_NOTIFICATION_ID = 1049

        fun startFull(
            context: Context,
            mpResultCode: Int = Activity.RESULT_CANCELED,
            mpData: Intent? = null,
        ) {
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_START_FULL
                putExtra(EXTRA_MP_RESULT_CODE, mpResultCode)
                if (mpData != null) {
                    putExtra(EXTRA_MP_DATA, mpData)
                }
            }
            context.startForegroundService(intent)
        }

        fun startManualWifi(
            context: Context,
            ssid: String,
            passphrase: String,
            securityCode: Int,
            mpResultCode: Int = Activity.RESULT_CANCELED,
            mpData: Intent? = null,
        ) {
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_START_MANUAL_WIFI
                putExtra(EXTRA_WIFI_SSID, ssid)
                putExtra(EXTRA_WIFI_PASS, passphrase)
                putExtra(EXTRA_WIFI_SECURITY_CODE, securityCode)
                putExtra(EXTRA_MP_RESULT_CODE, mpResultCode)
                if (mpData != null) {
                    putExtra(EXTRA_MP_DATA, mpData)
                }
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            cancelWifiReadyAlert(context)
            val intent = Intent(context, ProbeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }

        /**
         * Posts a high-priority heads-up notification when BMW sends 0x5703 while MainActivity
         * is in the background, so one tap brings MainActivity to the foreground for WifiNetworkSpecifier.
         */
        fun showWifiReadyAlert(context: Context, ssid: String) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                val alertChannel = NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.notif_alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_alert_channel_desc)
                    enableVibration(true)
                }
                nm.createNotificationChannel(alertChannel)

                val openIntent = PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

                val text = "Dodirni odmah za spajanje na \"$ssid\" (BMW čeka ~60 s)"
                val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_probe_notif)
                    .setContentTitle("BMW Wi-Fi spreman — vrati se u aplikaciju!")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(openIntent)
                    .build()
                nm.notify(WIFI_ALERT_NOTIFICATION_ID, notif)
            }
        }

        fun cancelWifiReadyAlert(context: Context) {
            runCatching {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(WIFI_ALERT_NOTIFICATION_ID)
            }
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
                val mpResultCode = intent.getIntExtra(EXTRA_MP_RESULT_CODE, Activity.RESULT_CANCELED)
                val mpData = IntentCompat.getParcelableExtra(intent, EXTRA_MP_DATA, Intent::class.java)
                val hasMpConsent = mpResultCode == Activity.RESULT_OK && mpData != null

                promoteForeground("Ručni Wi-Fi test: $ssid...", withMediaProjection = hasMpConsent)
                acquireWakeLock()
                attachOrClearProjection(app, mpResultCode, mpData)
                observeOrchestratorStatus(app)
                app.orchestrator.startManualWifiTest(
                    ssid = ssid,
                    passphrase = pass,
                    securityType = Iap2Parsers.WifiSecurityType.fromCode(secCode),
                )
            }

            ACTION_START_FULL, null -> {
                val mpResultCode = intent?.getIntExtra(EXTRA_MP_RESULT_CODE, Activity.RESULT_CANCELED)
                    ?: Activity.RESULT_CANCELED
                val mpData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_MP_DATA, Intent::class.java) }
                val hasMpConsent = mpResultCode == Activity.RESULT_OK && mpData != null

                promoteForeground("Pokrećem BMW CarPlay Probe…", withMediaProjection = hasMpConsent)
                acquireWakeLock()
                attachOrClearProjection(app, mpResultCode, mpData)
                observeOrchestratorStatus(app)
                val ok = app.orchestrator.startFullProbe()
                if (!ok) {
                    app.orchestrator.clearMediaProjection()
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
        } else {
            app?.orchestrator?.clearMediaProjection()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun attachOrClearProjection(app: ProbeApp, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = runCatching { mpm?.getMediaProjection(resultCode, data) }
                .onFailure { e ->
                    app.logger.log("MIRROR", "✗ getMediaProjection nije uspio: ${e.message}")
                }
                .getOrNull()
            if (projection != null) {
                app.orchestrator.attachMediaProjection(projection)
            } else {
                app.orchestrator.clearMediaProjection()
            }
        } else {
            app.orchestrator.clearMediaProjection()
        }
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

    private fun promoteForeground(statusText: String, withMediaProjection: Boolean) {
        val notification = buildNotification(statusText)
        val fgsType = if (withMediaProjection) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            fgsType,
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

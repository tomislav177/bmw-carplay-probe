package io.bimmergestalt.bmwcarplay

import android.app.Application
import android.util.Log
import io.bimmergestalt.bmwcarplay.aap.AapCertStore
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.session.ProbeOrchestrator
import java.io.File

/**
 * Lightweight Application class holding process-scoped diagnostic components.
 * Starts zero background services or workers when idle.
 */
class ProbeApp : Application() {
    lateinit var prefs: ProbePrefs
        private set

    lateinit var logger: DiagnosticLogger
        private set

    lateinit var aapCertStore: AapCertStore
        private set

    lateinit var orchestrator: ProbeOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = ProbePrefs(this)
        logger = DiagnosticLogger(this).apply {
            fullHexEnabled = prefs.fullHexEnabled
        }
        aapCertStore = AapCertStore(File(filesDir, "aap_certs"))
        orchestrator = ProbeOrchestrator(this, prefs, logger, aapCertStore)

        // Capture any unexpected fatal crash directly into the active session log on disk
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log(
                    "CRASH",
                    "FATALNA IZNIMKA u dretvi '${thread.name}': ${throwable::class.java.simpleName}: ${throwable.message}\n" +
                        Log.getStackTraceString(throwable),
                )
            }
            prevHandler?.uncaughtException(thread, throwable)
        }
    }
}

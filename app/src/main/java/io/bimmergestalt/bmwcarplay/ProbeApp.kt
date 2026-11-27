package io.bimmergestalt.bmwcarplay

import android.app.Application
import android.util.Log
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.diag.DiagnosticLogger
import io.bimmergestalt.bmwcarplay.session.ProbeOrchestrator

/**
 * Lightweight Application class holding process-scoped diagnostic components.
 * Starts zero background services or workers when idle.
 */
class ProbeApp : Application() {
    lateinit var prefs: ProbePrefs
        private set

    lateinit var logger: DiagnosticLogger
        private set

    lateinit var orchestrator: ProbeOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = ProbePrefs(this)
        logger = DiagnosticLogger(this).apply {
            fullHexEnabled = prefs.fullHexEnabled
        }
        orchestrator = ProbeOrchestrator(this, prefs, logger)

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

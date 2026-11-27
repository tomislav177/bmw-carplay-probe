package io.bimmergestalt.bmwcarplay

import android.app.Application
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
    }
}

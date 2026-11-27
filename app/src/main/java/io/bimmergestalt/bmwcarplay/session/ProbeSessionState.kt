package io.bimmergestalt.bmwcarplay.session

/**
 * Immutable snapshot of the 6-step Phase 1 diagnostic pipeline displayed live in MainActivity.
 */
data class ProbeSessionState(
    val isRunning: Boolean = false,
    val statusHeadlineHr: String = "Spremno za testiranje",
    val steps: List<StepInfo> = defaultSteps(),
    val detectedSerial: String? = null,
    val detectedWifiSsid: String? = null,
    val detectedWifiPassphrase: String? = null,
    val detectedWifiChannel: Int = 0,
    val detectedWifiSecurityHr: String? = null,
    val waitingForForegroundWifi: Boolean = false,
    val phase2VerdictHr: String? = null,
    val aapDiagnosticBusy: Boolean = false,
    val aapDiagnosticSummaryHr: String? = null,
    val aapDiagnosticSuccess: Boolean? = null,
) {
    enum class StepStatus {
        WAITING,
        IN_PROGRESS,
        SUCCESS,
        ERROR,
    }

    data class StepInfo(
        val index: Int,
        val titleHr: String,
        val status: StepStatus = StepStatus.WAITING,
        val detailHr: String = "Čeka",
    )

    companion object {
        const val STEP_BT_RFCOMM = 0
        const val STEP_IAP2_LINK = 1
        const val STEP_IDENT_MFI = 2
        const val STEP_WIFI_CREDS = 3
        const val STEP_WIFI_CONNECTED = 4
        const val STEP_AIRPLAY_INFO = 5
        const val STEP_CARPLAY_VIDEO = 6

        fun defaultSteps(): List<StepInfo> = listOf(
            StepInfo(STEP_BT_RFCOMM, "1. Bluetooth RFCOMM"),
            StepInfo(STEP_IAP2_LINK, "2. iAP2 Link (FF55/FF5A)"),
            StepInfo(STEP_IDENT_MFI, "3. MFi & Identifikacija (AL07...)"),
            StepInfo(STEP_WIFI_CREDS, "4. BMW Wi-Fi podaci (0x5703)"),
            StepInfo(STEP_WIFI_CONNECTED, "5. Spojen na BMW Wi-Fi"),
            StepInfo(STEP_AIRPLAY_INFO, "6. AirPlay /info odgovor (200 OK)"),
            StepInfo(STEP_CARPLAY_VIDEO, "7. CarPlay Video Stream (H.264)"),
        )
    }

    fun updateStep(index: Int, status: StepStatus, detailHr: String): ProbeSessionState {
        val next = steps.map { step ->
            if (step.index == index) step.copy(status = status, detailHr = detailHr) else step
        }
        return copy(steps = next)
    }
}

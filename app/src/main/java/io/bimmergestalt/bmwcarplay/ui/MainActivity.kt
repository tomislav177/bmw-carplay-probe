package io.bimmergestalt.bmwcarplay.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.bimmergestalt.bmwcarplay.ProbeApp
import io.bimmergestalt.bmwcarplay.R
import io.bimmergestalt.bmwcarplay.bt.BluetoothConstants
import io.bimmergestalt.bmwcarplay.bt.DeviceFilter
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.databinding.ActivityMainBinding
import io.bimmergestalt.bmwcarplay.databinding.ItemProbeStepBinding
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.service.ProbeForegroundService
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main Material 3 Croatian dashboard for BMW CarPlay Probe (Phase 3A: Android Auto + CarPlay Bridge).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val AA_PACKAGE = "com.google.android.projection.gearhead"
        private const val AA_SETTINGS_ACTIVITY =
            "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
        private const val MAX_CERT_FILE_BYTES = 256 * 1024
        private const val STATE_PENDING_PEM_CERT = "pending_pem_cert_bytes"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: ProbeApp
    private val stepBindings = ArrayList<ItemProbeStepBinding>()

    private data class TargetOption(
        val label: String,
        val device: ProbePrefs.TargetDevice?,
    ) {
        override fun toString(): String = label
    }

    private var targetOptions: List<TargetOption> = emptyList()
    private var suppressTargetSelection = false
    private var advancedExpanded = false
    private var pendingPemCertBytes: ByteArray? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionsUi()
            refreshBondedCars()
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode > 0) {
                app.logger.log(
                    "BT-EVENT",
                    "✓ Mobitel je sada VIDLJIV za BMW iDrive sljedećih ${result.resultCode} sekundi!",
                )
                Toast.makeText(
                    this,
                    "Mobitel je vidljiv za BMW (${result.resultCode} s)",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                app.logger.log("BT-EVENT", "Zahtjev za vidljivost mobitela je odbijen ili otkazan")
            }
        }

    private val pkcs12PickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val bytes = readBoundedUriBytes(uri) ?: return@registerForActivityResult
            showPkcs12PasswordDialog(bytes)
        }

    private val pemCertPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            val certBytes = readBoundedUriBytes(uri) ?: return@registerForActivityResult
            pendingPemCertBytes = certBytes
            Toast.makeText(
                this,
                "Certifikat učitan — sad odaberi privatni ključ (.key / .pem)",
                Toast.LENGTH_LONG,
            ).show()
            pemKeyPickerLauncher.launch(arrayOf("*/*"))
        }

    private val pemKeyPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val certBytes = pendingPemCertBytes
            pendingPemCertBytes = null
            if (uri == null || certBytes == null) return@registerForActivityResult
            val keyBytes = readBoundedUriBytes(uri) ?: return@registerForActivityResult
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) {
                    runCatching { app.aapCertStore.importPem(certBytes, keyBytes) }
                }
                res.onSuccess { info ->
                    app.logger.log("AAP-CERT", "✓ Uvezen PEM HU certifikat: ${info.summaryHr}")
                    refreshCertStatusUi()
                    Toast.makeText(this@MainActivity, "HU certifikat uspješno uvezen!", Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    app.logger.log("AAP-CERT", "✗ Neuspjeli uvoz PEM certifikata: ${e.message}")
                    Toast.makeText(this@MainActivity, "Greška uvoza: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPemCertBytes = savedInstanceState?.getByteArray(STATE_PENDING_PEM_CERT)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ProbeApp
        binding.txtHeaderSubtitle.text =
            "Faza 3A · v${io.bimmergestalt.bmwcarplay.BuildConfig.VERSION_NAME} · Android Auto & CarPlay Most (HU_ENTRYNAV2)"

        setupStepViews()
        setupAndroidAutoCardUi()
        setupSettingsUi()
        setupActionButtons()
        applyKeepScreenOn(app.prefs.keepScreenOn)
        refreshPermissionsUi()
        refreshBondedCars()
        refreshCertStatusUi()
        observeFlows()

        if (savedInstanceState == null) {
            maybeAutoConnectOnOpen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPemCertBytes?.let { outState.putByteArray(STATE_PENDING_PEM_CERT, it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeAutoConnectOnOpen()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsUi()
        refreshBondedCars()
        refreshCertStatusUi()
        app.orchestrator.setActivityForeground(true)
    }

    override fun onPause() {
        app.orchestrator.setActivityForeground(false)
        super.onPause()
    }

    private fun setupStepViews() {
        binding.stepsContainer.removeAllViews()
        stepBindings.clear()
        val inflater = LayoutInflater.from(this)
        for (step in ProbeSessionState.defaultSteps()) {
            val itemBinding = ItemProbeStepBinding.inflate(inflater, binding.stepsContainer, false)
            itemBinding.stepTitle.text = step.titleHr
            itemBinding.stepDetail.text = step.detailHr
            binding.stepsContainer.addView(itemBinding.root)
            stepBindings.add(itemBinding)
        }
    }

    private fun setupAndroidAutoCardUi() {
        val sources = ProbePrefs.VideoSourceMode.entries
        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.labelHr },
        )
        binding.spinnerVideoSource.adapter = sourceAdapter
        binding.spinnerVideoSource.setSelection(sources.indexOf(app.prefs.videoSourceMode).coerceAtLeast(0))
        binding.spinnerVideoSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.prefs.videoSourceMode = sources[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.switchAutoConnect.isChecked = app.prefs.autoConnectOnOpen
        binding.switchAutoConnect.setOnCheckedChangeListener { _, checked ->
            app.prefs.autoConnectOnOpen = checked
            Toast.makeText(
                this,
                if (checked) {
                    "Automatsko spajanje uključeno — otvaranje aplikacije odmah pokreće vezu"
                } else {
                    "Automatsko spajanje isključeno"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }

        binding.btnTestAaLocal.setOnClickListener {
            app.orchestrator.runAapLoopbackDiagnosticTest()
        }

        binding.btnOpenAaSettings.setOnClickListener {
            openAndroidAutoSettings()
        }

        binding.btnImportCert.setOnClickListener {
            showImportCertDialog()
        }

        binding.btnClearCert.setOnClickListener {
            app.aapCertStore.clearImported()
            app.logger.log("AAP-CERT", "Uvezeni HU TLS certifikat uklonjen — vraćen lokalni samopotpisani certifikat")
            refreshCertStatusUi()
            Toast.makeText(this, "Uvezeni certifikat uklonjen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun maybeAutoConnectOnOpen() {
        if (!app.prefs.autoConnectOnOpen) return
        if (app.orchestrator.state.value.isRunning) return
        if (getMissingPermissions().isNotEmpty()) return
        val mac = app.prefs.phoneBluetoothMac
        if (!ProbePrefs.isValidMac(mac)) return
        app.logger.log("SESIJA", "Automatsko spajanje pri otvaranju aktivno — pokrećem BMW vezu…")
        ProbeForegroundService.startFull(this)
    }

    private fun openAndroidAutoSettings() {
        val directIntent = Intent().apply {
            setClassName(AA_PACKAGE, AA_SETTINGS_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val opened = runCatching {
            startActivity(directIntent)
            true
        }.getOrElse {
            val launchIntent = packageManager.getLaunchIntentForPackage(AA_PACKAGE)
            if (launchIntent != null) {
                runCatching {
                    startActivity(launchIntent)
                    true
                }.getOrDefault(false)
            } else {
                runCatching {
                    val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", AA_PACKAGE, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(appInfoIntent)
                    true
                }.getOrDefault(false)
            }
        }
        if (!opened) {
            Toast.makeText(
                this,
                "Otvori Postavke → Povezani uređaji → Android Auto → Postavke razvojnog programa",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun showImportCertDialog() {
        val options = arrayOf(
            "PKCS#12 paket (.p12 / .pfx — certifikat + ključ u jednoj datoteci)",
            "PEM datoteke (1. certifikat .pem/.crt, 2. privatni ključ .key/.pem)",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_cert_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pkcs12PickerLauncher.launch(arrayOf("*/*"))
                    1 -> {
                        pendingPemCertBytes = null
                        Toast.makeText(
                            this,
                            "Odaberi datoteku certifikata (.pem / .crt)",
                            Toast.LENGTH_SHORT,
                        ).show()
                        pemCertPickerLauncher.launch(arrayOf("*/*"))
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPkcs12PasswordDialog(p12Bytes: ByteArray) {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_pkcs12_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_pkcs12_password_title)
            .setView(input)
            .setPositiveButton("Uvezi") { _, _ ->
                val pwd = input.text?.toString().orEmpty()
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { app.aapCertStore.importPkcs12(p12Bytes, pwd) }
                    }
                    res.onSuccess { info ->
                        app.logger.log("AAP-CERT", "✓ Uvezen PKCS#12 HU certifikat: ${info.summaryHr}")
                        refreshCertStatusUi()
                        Toast.makeText(this@MainActivity, "HU certifikat uspješno uvezen!", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        app.logger.log("AAP-CERT", "✗ Neuspjeli uvoz PKCS#12 certifikata: ${e.message}")
                        Toast.makeText(this@MainActivity, "Greška uvoza: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun readBoundedUriBytes(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { stream ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_CERT_FILE_BYTES) {
                    Toast.makeText(this, "Datoteka je prevelika za certifikat (>256 KB)", Toast.LENGTH_LONG).show()
                    return null
                }
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
    } catch (e: Exception) {
        Toast.makeText(this, "Ne mogu pročitati datoteku: ${e.message}", Toast.LENGTH_LONG).show()
        null
    }

    private fun refreshCertStatusUi() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { app.aapCertStore.getCertInfo() }.getOrNull()
            }
            if (info == null) {
                binding.txtCertStatus.text = "Status certifikata nije dostupan"
                binding.txtCertStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_waiting))
                binding.btnClearCert.visibility = View.GONE
                return@launch
            }
            binding.txtCertStatus.text = info.summaryHr
            val colorRes = when {
                info.isExpired -> R.color.status_error
                info.isImported -> R.color.status_success
                else -> R.color.status_waiting
            }
            binding.txtCertStatus.setTextColor(ContextCompat.getColor(this@MainActivity, colorRes))
            binding.btnClearCert.visibility = if (info.isImported) View.VISIBLE else View.GONE
        }
    }

    private fun setupSettingsUi() {
        // Real phone BT MAC field (pre-filled with confirmed Galaxy S25 MAC 7C:7B:BF:74:40:67)
        binding.editPhoneMac.setText(app.prefs.phoneBluetoothMac)
        updateMacBadge(app.prefs.phoneBluetoothMac)

        binding.editPhoneMac.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val input = s?.toString().orEmpty().trim()
                if (ProbePrefs.isValidMac(input)) {
                    app.prefs.phoneBluetoothMac = input
                }
                updateMacBadge(input)
            }
        })

        binding.btnResetS25Mac.setOnClickListener {
            binding.editPhoneMac.setText(BluetoothConstants.DEFAULT_PHONE_BT_MAC)
            app.prefs.phoneBluetoothMac = BluetoothConstants.DEFAULT_PHONE_BT_MAC
            updateMacBadge(BluetoothConstants.DEFAULT_PHONE_BT_MAC)
            Toast.makeText(this, "Vraćena Galaxy S25 BT MAC adresa", Toast.LENGTH_SHORT).show()
        }

        binding.btnRefreshDevices.setOnClickListener {
            refreshBondedCars()
            Toast.makeText(this, "Popis uparenih uređaja osvježen", Toast.LENGTH_SHORT).show()
        }

        // Collapsible advanced test options
        binding.btnToggleAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvancedOptions.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.btnToggleAdvanced.text =
                if (advancedExpanded) "▾ Napredne testne opcije" else "▸ Napredne testne opcije"
        }

        // MFi Challenge Length spinner
        val mfiModes = ProbePrefs.MfiChallengeMode.entries
        val mfiAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            mfiModes.map { it.labelHr },
        )
        binding.spinnerMfiLength.adapter = mfiAdapter
        binding.spinnerMfiLength.setSelection(mfiModes.indexOf(app.prefs.mfiChallengeMode).coerceAtLeast(0))
        binding.spinnerMfiLength.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.prefs.mfiChallengeMode = mfiModes[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // RFCOMM mode spinner (Insecure default vs Secure)
        val rfcommModes = ProbePrefs.RfcommMode.entries
        val rfcommAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rfcommModes.map { it.labelHr },
        )
        binding.spinnerRfcommMode.adapter = rfcommAdapter
        binding.spinnerRfcommMode.setSelection(rfcommModes.indexOf(app.prefs.rfcommMode).coerceAtLeast(0))
        binding.spinnerRfcommMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.prefs.rfcommMode = rfcommModes[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Switches
        binding.switchOutboundClient.isChecked = app.prefs.outboundClientEnabled
        binding.switchOutboundClient.setOnCheckedChangeListener { _, checked ->
            app.prefs.outboundClientEnabled = checked
        }

        binding.switchFullHex.isChecked = app.prefs.fullHexEnabled
        binding.switchFullHex.setOnCheckedChangeListener { _, checked ->
            app.prefs.fullHexEnabled = checked
            app.logger.fullHexEnabled = checked
        }

        binding.switchKeepScreenOn.isChecked = app.prefs.keepScreenOn
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            app.prefs.keepScreenOn = checked
            applyKeepScreenOn(checked)
        }

        binding.btnManualWifiTest.setOnClickListener {
            showManualWifiDialog()
        }
    }

    private fun setupActionButtons() {
        binding.btnGrantPermissions.setOnClickListener {
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
            }
        }

        binding.btnStartTest.setOnClickListener {
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                Toast.makeText(
                    this,
                    "Odobri tražene dozvole pa ponovno klikni Pokreni",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }

            val macInput = binding.editPhoneMac.text?.toString().orEmpty().trim()
            if (!ProbePrefs.isValidMac(macInput)) {
                Toast.makeText(
                    this,
                    "Unesi valjanu BT MAC adresu mobitela (XX:XX:XX:XX:XX:XX)",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            app.prefs.phoneBluetoothMac = macInput
            ProbeForegroundService.startFull(this)
        }

        binding.btnStopTest.setOnClickListener {
            ProbeForegroundService.stop(this)
        }

        binding.btnRetryWifi.setOnClickListener {
            app.orchestrator.retryWifiConnect()
        }

        binding.btnCopyWifiPass.setOnClickListener {
            val st = app.orchestrator.state.value
            val pass = st.detectedWifiPassphrase
            if (!pass.isNullOrEmpty()) {
                copyToClipboard("BMW CarPlay Wi-Fi lozinka", pass)
                val ssid = st.detectedWifiSsid ?: "BMW CarPlay"
                Toast.makeText(
                    this,
                    "Lozinka kopirana (${pass.length} zn.) — zalijepi za \"$ssid\" u Wi-Fi postavkama",
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(this, "Nema zaprimljene Wi-Fi lozinke", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnAdoptManualWifi.setOnClickListener {
            app.orchestrator.adoptCurrentWifiForStep6()
        }

        binding.btnDiscoverable.setOnClickListener {
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                permissionLauncher.launch(missing.toTypedArray())
                return@setOnClickListener
            }
            // Ensure RFCOMM server is already listening on ...decacafe before becoming discoverable
            if (!app.orchestrator.state.value.isRunning) {
                ProbeForegroundService.startFull(this)
            }
            val discoverIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
            }
            discoverableLauncher.launch(discoverIntent)
        }

        binding.btnCopyLog.setOnClickListener {
            val text = app.logger.readCurrentSessionText()
            copyToClipboard("BMW CarPlay Probe log", text)
            Toast.makeText(this, "Dijagnostički log kopiran (${text.length} znakova)", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareLog.setOnClickListener {
            val currentFile = app.logger.getCurrentSessionFile()
            if (currentFile != null && currentFile.exists()) {
                shareLogFile(currentFile)
            } else {
                shareLogText(app.logger.readCurrentSessionText())
            }
        }

        binding.btnPastSessions.setOnClickListener {
            showPastSessionsDialog()
        }

        binding.btnClearLog.setOnClickListener {
            app.logger.clearScreenLog()
            Toast.makeText(this, "Prikaz očišćen (datoteka na disku ostaje sačuvana)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.orchestrator.state.collect { renderState(it) }
                }
                launch {
                    app.logger.logLines.collect { lines ->
                        val fileName = app.logger.getCurrentSessionFile()?.name.orEmpty()
                        binding.txtSessionFile.text = fileName
                        if (lines.isEmpty()) {
                            binding.txtLiveLog.setText(R.string.log_empty_placeholder)
                        } else {
                            binding.txtLiveLog.text = lines.joinToString("\n")
                            binding.logScrollView.post {
                                binding.logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderState(state: ProbeSessionState) {
        binding.btnStartTest.isEnabled = !state.isRunning
        binding.btnStopTest.isEnabled = state.isRunning
        binding.txtHeadlineStatus.text = state.statusHeadlineHr

        state.steps.forEachIndexed { idx, step ->
            val row = stepBindings.getOrNull(idx) ?: return@forEachIndexed
            row.stepTitle.text = step.titleHr
            row.stepDetail.text = step.detailHr

            when (step.status) {
                ProbeSessionState.StepStatus.WAITING -> {
                    row.stepProgress.visibility = View.GONE
                    row.stepStatusSymbol.visibility = View.VISIBLE
                    row.stepStatusSymbol.text = "●"
                    row.stepStatusSymbol.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
                    row.stepDetail.setTextColor(ContextCompat.getColor(this, R.color.status_waiting))
                }

                ProbeSessionState.StepStatus.IN_PROGRESS -> {
                    row.stepProgress.visibility = View.VISIBLE
                    row.stepStatusSymbol.visibility = View.GONE
                    row.stepDetail.setTextColor(ContextCompat.getColor(this, R.color.status_running))
                }

                ProbeSessionState.StepStatus.SUCCESS -> {
                    row.stepProgress.visibility = View.GONE
                    row.stepStatusSymbol.visibility = View.VISIBLE
                    row.stepStatusSymbol.text = "✓"
                    row.stepStatusSymbol.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                    row.stepDetail.setTextColor(ContextCompat.getColor(this, R.color.status_success))
                }

                ProbeSessionState.StepStatus.ERROR -> {
                    row.stepProgress.visibility = View.GONE
                    row.stepStatusSymbol.visibility = View.VISIBLE
                    row.stepStatusSymbol.text = "✗"
                    row.stepStatusSymbol.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                    row.stepDetail.setTextColor(ContextCompat.getColor(this, R.color.status_error))
                }
            }
        }

        val wifiSsid = state.detectedWifiSsid
        val step5Status = state.steps.getOrNull(ProbeSessionState.STEP_WIFI_CONNECTED)?.status
        val showWifiPanel = state.isRunning && !wifiSsid.isNullOrBlank() && step5Status != ProbeSessionState.StepStatus.SUCCESS
        if (showWifiPanel) {
            binding.layoutWifiActions.visibility = View.VISIBLE
            val chPart = if (state.detectedWifiChannel > 0) " (ch ${state.detectedWifiChannel})" else ""
            val passLen = state.detectedWifiPassphrase?.length ?: 0
            val passPart = if (passLen > 0) " · Lozinka spremna ($passLen zn.)" else " · Otvorena mreža"
            binding.txtWifiCredsBanner.text = "BMW Wi-Fi: $wifiSsid$chPart$passPart"
            binding.btnCopyWifiPass.isEnabled = passLen > 0
        } else {
            binding.layoutWifiActions.visibility = View.GONE
        }

        if (!state.phase2VerdictHr.isNullOrBlank()) {
            binding.txtPhase2Verdict.visibility = View.VISIBLE
            binding.txtPhase2Verdict.text = state.phase2VerdictHr
        } else {
            binding.txtPhase2Verdict.visibility = View.GONE
        }

        binding.btnTestAaLocal.isEnabled = !state.aapDiagnosticBusy
        binding.btnTestAaLocal.text = if (state.aapDiagnosticBusy) {
            "Testiram 5277…"
        } else {
            getString(R.string.btn_test_aa_local)
        }
        val aaSummary = state.aapDiagnosticSummaryHr
        if (!aaSummary.isNullOrBlank()) {
            binding.txtAaDiagResult.visibility = View.VISIBLE
            binding.txtAaDiagResult.text = aaSummary
            val (bgRes, fgRes) = when (state.aapDiagnosticSuccess) {
                true -> R.color.status_success_bg to R.color.status_success
                false -> R.color.status_error_bg to R.color.status_error
                null -> R.color.bmw_blue_container to R.color.bmw_blue_dark
            }
            binding.txtAaDiagResult.setBackgroundColor(ContextCompat.getColor(this, bgRes))
            binding.txtAaDiagResult.setTextColor(ContextCompat.getColor(this, fgRes))
        } else {
            binding.txtAaDiagResult.visibility = View.GONE
        }
    }

    private fun updateMacBadge(rawMac: String) {
        if (ProbePrefs.isValidMac(rawMac)) {
            binding.txtMacBadge.setText(R.string.badge_saved)
            binding.txtMacBadge.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.txtMacBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_success_bg))
        } else {
            binding.txtMacBadge.setText(R.string.badge_invalid_mac)
            binding.txtMacBadge.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            binding.txtMacBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_error_bg))
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedCars() {
        val options = ArrayList<TargetOption>()
        options.add(TargetOption(getString(R.string.target_auto_wait), null))

        val hasBtConnect = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

        val candidates = ArrayList<DeviceFilter.DeviceCandidate>()
        if (hasBtConnect) {
            val adapter = runCatching {
                getSystemService(BluetoothManager::class.java)?.adapter
            }.getOrNull()
            val bonded = runCatching { adapter?.bondedDevices.orEmpty() }.getOrElse { emptySet() }
            for (dev in bonded) {
                val cand = DeviceFilter.DeviceCandidate(
                    name = runCatching { dev.name }.getOrNull(),
                    address = dev.address.uppercase(),
                    majorDeviceClass = runCatching { dev.bluetoothClass?.majorDeviceClass }.getOrNull() ?: 0,
                    deviceClass = runCatching { dev.bluetoothClass?.deviceClass }.getOrNull() ?: 0,
                    uuids = runCatching { dev.uuids?.map { it.uuid }?.toSet() }.getOrNull().orEmpty(),
                )
                if (DeviceFilter.isSelectableInUi(cand)) {
                    candidates.add(cand)
                }
            }
        }

        // Put BMW devices first in the dropdown
        val sorted = candidates.sortedWith(
            compareByDescending<DeviceFilter.DeviceCandidate> {
                it.address.equals(BluetoothConstants.KNOWN_BMW_MAC, ignoreCase = true)
            }.thenByDescending {
                it.name.orEmpty().startsWith("BMW", ignoreCase = true)
            }.thenBy {
                it.name.orEmpty()
            },
        )

        for (c in sorted) {
            val displayName = c.name?.takeIf { it.isNotBlank() } ?: "BT uređaj"
            val tag = if (c.address.equals(BluetoothConstants.KNOWN_BMW_MAC, ignoreCase = true)) {
                " [Potvrđeni BMW]"
            } else {
                ""
            }
            options.add(
                TargetOption(
                    label = "$displayName (${c.address})$tag",
                    device = ProbePrefs.TargetDevice(displayName, c.address),
                ),
            )
        }

        // Auto-select BMW if nothing saved yet
        val savedTarget = app.prefs.targetDevice
        if (savedTarget == null) {
            val defaultCar = DeviceFilter.pickDefaultCar(sorted)
            if (defaultCar != null) {
                app.prefs.targetDevice = ProbePrefs.TargetDevice(
                    name = defaultCar.name ?: defaultCar.address,
                    address = defaultCar.address,
                )
            }
        }

        targetOptions = options
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targetOptions)
        suppressTargetSelection = true
        binding.spinnerTargetCar.adapter = adapter

        val currentTarget = app.prefs.targetDevice
        val selectedIdx = if (currentTarget != null) {
            targetOptions.indexOfFirst {
                it.device?.address.equals(currentTarget.address, ignoreCase = true)
            }.takeIf { it >= 0 } ?: 0
        } else {
            0
        }
        binding.spinnerTargetCar.setSelection(selectedIdx)
        suppressTargetSelection = false

        binding.spinnerTargetCar.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressTargetSelection) return
                app.prefs.targetDevice = targetOptions.getOrNull(position)?.device
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun showManualWifiDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_manual_wifi, null)
        val editSsid = view.findViewById<EditText>(R.id.editManualSsid)
        val editPass = view.findViewById<EditText>(R.id.editManualPass)
        val spinnerSec = view.findViewById<Spinner>(R.id.spinnerManualSecurity)

        val secOptions = listOf(
            Iap2Parsers.WifiSecurityType.WPA_WPA2,
            Iap2Parsers.WifiSecurityType.WPA3,
            Iap2Parsers.WifiSecurityType.NONE,
        )
        spinnerSec.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            secOptions.map { it.labelHr },
        )

        val st = app.orchestrator.state.value
        st.detectedWifiSsid?.let { lastSsid ->
            editSsid.setText(lastSsid)
        }
        st.detectedWifiPassphrase?.let { lastPass ->
            editPass.setText(lastPass)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_manual_wifi_title)
            .setView(view)
            .setPositiveButton(R.string.btn_connect_now) { _, _ ->
                val ssid = editSsid.text.toString().trim()
                val pass = editPass.text.toString()
                val sec = secOptions.getOrElse(spinnerSec.selectedItemPosition) {
                    Iap2Parsers.WifiSecurityType.WPA_WPA2
                }
                if (ssid.isEmpty()) {
                    Toast.makeText(this, "Unesi SSID mreže auta", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ProbeForegroundService.startManualWifi(this, ssid, pass, sec.code)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showPastSessionsDialog() {
        val files = app.logger.listSessionFiles()
        if (files.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_sessions_title)
                .setMessage(R.string.dialog_no_sessions)
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val labels = files.map { file ->
            val kb = (file.length() + 1023L) / 1024L
            "${file.name} (${kb} KB)"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_sessions_title)
            .setItems(labels) { _, which ->
                showSingleSessionViewer(files[which])
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showSingleSessionViewer(file: File) {
        val content = app.logger.readSessionFile(file)
        val preview = if (content.length > 30_000) {
            "… (prikazano zadnjih 30 000 znakova; Podijeli šalje cijelu datoteku)\n\n" +
                content.takeLast(30_000)
        } else {
            content
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setMessage(preview)
            .setPositiveButton(R.string.btn_share_log) { _, _ -> shareLogFile(file) }
            .setNeutralButton(R.string.btn_copy_log) { _, _ ->
                copyToClipboard(file.name, content)
                Toast.makeText(this, "Sesija ${file.name} kopirana", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zatvori", null)
            .show()
    }

    private fun shareLogFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "BMW CarPlay Probe — ${file.name}")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Podijeli dijagnostički log"))
        } catch (e: Exception) {
            shareLogText(app.logger.readSessionFile(file))
        }
    }

    private fun shareLogText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BMW CarPlay Probe dijagnostički log")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Podijeli dijagnostički log"))
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun requiredRuntimePermissions(): List<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.BLUETOOTH_CONNECT)
        list.add(Manifest.permission.BLUETOOTH_SCAN)
        list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list
    }

    private fun getMissingPermissions(): List<String> =
        requiredRuntimePermissions().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }

    private fun refreshPermissionsUi() {
        val missing = getMissingPermissions()
        if (missing.isEmpty()) {
            binding.cardPermissions.visibility = View.GONE
        } else {
            binding.cardPermissions.visibility = View.VISIBLE
            val shortNames = missing.joinToString(", ") { it.substringAfterLast('.') }
            binding.txtMissingPermissions.text =
                "${getString(R.string.permissions_missing_desc)}\nNedostaje: $shortNames"
        }
    }
}

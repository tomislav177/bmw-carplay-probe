package io.bimmergestalt.bmwcarplay.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.bimmergestalt.bmwcarplay.BuildConfig
import io.bimmergestalt.bmwcarplay.ProbeApp
import io.bimmergestalt.bmwcarplay.R
import io.bimmergestalt.bmwcarplay.bt.BluetoothConstants
import io.bimmergestalt.bmwcarplay.bt.DeviceFilter
import io.bimmergestalt.bmwcarplay.config.ProbePrefs
import io.bimmergestalt.bmwcarplay.databinding.ActivityMainBinding
import io.bimmergestalt.bmwcarplay.databinding.ItemDockSlotBinding
import io.bimmergestalt.bmwcarplay.databinding.ItemProbeStepBinding
import io.bimmergestalt.bmwcarplay.iap2.csm.Iap2Parsers
import io.bimmergestalt.bmwcarplay.input.BmwTouchAccessibilityService
import io.bimmergestalt.bmwcarplay.mirror.DockAppCatalog
import io.bimmergestalt.bmwcarplay.mirror.DockSide
import io.bimmergestalt.bmwcarplay.mirror.MirrorDisplayConfig
import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMath
import io.bimmergestalt.bmwcarplay.mirror.MirrorLayoutMode
import io.bimmergestalt.bmwcarplay.mirror.ScreenCaptureManager
import io.bimmergestalt.bmwcarplay.service.ProbeForegroundService
import io.bimmergestalt.bmwcarplay.session.ProbeSessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main Material 3 Croatian dashboard for BMW CarPlay Probe (Phase 4A: Screen Mirroring + OEM CarPlay Dock).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val CROP_STEP_PX = 5
    }

    private sealed class PendingSessionStart {
        data object Full : PendingSessionStart()
        data class ManualWifi(
            val ssid: String,
            val passphrase: String,
            val securityCode: Int,
        ) : PendingSessionStart()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: ProbeApp
    private val stepBindings = ArrayList<ItemProbeStepBinding>()
    private val dockSlotBindings = ArrayList<ItemDockSlotBinding>()

    private data class TargetOption(
        val label: String,
        val device: ProbePrefs.TargetDevice?,
    ) {
        override fun toString(): String = label
    }

    private var targetOptions: List<TargetOption> = emptyList()
    private var suppressTargetSelection = false
    private var suppressCropCallbacks = false
    private var advancedExpanded = false
    private var pendingStart: PendingSessionStart? = null
    private var mediaProjectionPromptInFlight = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionsUi()
            refreshBondedCars()
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            mediaProjectionPromptInFlight = false
            val startReq = pendingStart ?: PendingSessionStart.Full
            pendingStart = null

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                when (startReq) {
                    PendingSessionStart.Full -> {
                        ProbeForegroundService.startFull(this, result.resultCode, result.data)
                    }

                    is PendingSessionStart.ManualWifi -> {
                        ProbeForegroundService.startManualWifi(
                            context = this,
                            ssid = startReq.ssid,
                            passphrase = startReq.passphrase,
                            securityCode = startReq.securityCode,
                            mpResultCode = result.resultCode,
                            mpData = result.data,
                        )
                    }
                }
            } else {
                Toast.makeText(
                    this,
                    "Prijenos zaslona otkazan — za dijagnostiku bez zrcaljenja odaberi Testna slika",
                    Toast.LENGTH_LONG,
                ).show()
            }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as ProbeApp
        binding.txtHeaderSubtitle.text =
            "Faza 4A · v${BuildConfig.VERSION_NAME} · Zrcaljenje zaslona + CarPlay Dock (HU_ENTRYNAV2)"

        setupStepViews()
        setupMirrorAndDockCardUi()
        setupSettingsUi()
        setupActionButtons()
        applyKeepScreenOn(app.prefs.keepScreenOn)
        refreshPermissionsUi()
        refreshBondedCars()
        refreshAccessibilityUi(BmwTouchAccessibilityService.isActive.value)
        observeFlows()

        if (savedInstanceState == null) {
            maybeAutoConnectOnOpen()
        }
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
        refreshAccessibilityUi(BmwTouchAccessibilityService.isActive.value)
        refreshDockSlotsUi()
        updateMirrorGeometryPreview()
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

    private fun setupMirrorAndDockCardUi() {
        // 1. Video Source spinner (Screen Mirror + Dock vs Diagnostic Test Pattern)
        val sources = ProbePrefs.VideoSourceMode.entries
        binding.spinnerVideoSource.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sources.map { it.labelHr },
        )
        binding.spinnerVideoSource.setSelection(sources.indexOf(app.prefs.videoSourceMode).coerceAtLeast(0))
        updateMirrorControlsVisibility(app.prefs.videoSourceMode)
        binding.spinnerVideoSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = sources[position]
                app.prefs.videoSourceMode = chosen
                updateMirrorControlsVisibility(chosen)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 2. Layout Mode spinner (Smart Crop 122px vs 1:1 Fit + Wide Dock 240px)
        val layoutModes = MirrorLayoutMode.entries
        binding.spinnerMirrorLayout.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            layoutModes.map { it.labelHr },
        )
        binding.spinnerMirrorLayout.setSelection(layoutModes.indexOf(app.prefs.mirrorLayoutMode).coerceAtLeast(0))
        binding.spinnerMirrorLayout.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val mode = layoutModes[position]
                if (app.prefs.mirrorLayoutMode != mode) {
                    app.prefs.mirrorLayoutMode = mode
                    syncCropSlidersFromPrefs()
                    updateMirrorGeometryPreview()
                    app.orchestrator.notifyMirrorConfigChanged()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 3. Dock Side spinner (Left / Right)
        val dockSides = DockSide.entries
        binding.spinnerDockSide.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            dockSides.map { it.labelHr },
        )
        binding.spinnerDockSide.setSelection(dockSides.indexOf(app.prefs.dockSide).coerceAtLeast(0))
        binding.spinnerDockSide.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val side = dockSides[position]
                if (app.prefs.dockSide != side) {
                    app.prefs.dockSide = side
                    updateMirrorGeometryPreview()
                    app.orchestrator.notifyMirrorConfigChanged()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // 4. Top & Bottom Crop Sliders (0..200 px in 5 px steps)
        binding.seekCropTop.max = MirrorDisplayConfig.MAX_CROP_PX / CROP_STEP_PX
        binding.seekCropBottom.max = MirrorDisplayConfig.MAX_CROP_PX / CROP_STEP_PX
        syncCropSlidersFromPrefs()

        binding.seekCropTop.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val px = progress * CROP_STEP_PX
                binding.txtCropTopValue.text = "$px px"
                if (fromUser && !suppressCropCallbacks) {
                    app.prefs.setCropTopPx(px)
                    updateMirrorGeometryPreview()
                    app.orchestrator.notifyMirrorConfigChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekCropBottom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val px = progress * CROP_STEP_PX
                binding.txtCropBottomValue.text = "$px px"
                if (fromUser && !suppressCropCallbacks) {
                    app.prefs.setCropBottomPx(px)
                    updateMirrorGeometryPreview()
                    app.orchestrator.notifyMirrorConfigChanged()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnResetCrop.setOnClickListener {
            val mode = app.prefs.mirrorLayoutMode
            app.prefs.resetCropDefaults(mode)
            syncCropSlidersFromPrefs()
            updateMirrorGeometryPreview()
            app.orchestrator.notifyMirrorConfigChanged()
            Toast.makeText(
                this,
                "Vraćeno preporučeno rezanje (${mode.defaultCropTopPx}/${mode.defaultCropBottomPx} px)",
                Toast.LENGTH_SHORT,
            ).show()
        }

        // 5. 4 User-Customizable CarPlay Dock App Slots
        setupDockSlotViews()
        refreshDockSlotsUi()

        // 6. AccessibilityService shortcuts
        binding.btnEnableAccessibility.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.onFailure {
                Toast.makeText(this, "Otvori Postavke → Pristupačnost → Instalirane aplikacije", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnAppInfoRestricted.setOnClickListener {
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }.onFailure {
                Toast.makeText(this, "Otvori Info aplikacije → ⋮ → Dopusti ograničene postavke", Toast.LENGTH_LONG).show()
            }
        }

        // 7. Auto-connect on app open switch
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

        updateMirrorGeometryPreview()
    }

    private fun updateMirrorControlsVisibility(sourceMode: ProbePrefs.VideoSourceMode) {
        binding.layoutMirrorControls.visibility =
            if (sourceMode == ProbePrefs.VideoSourceMode.SCREEN_MIRROR) View.VISIBLE else View.GONE
    }

    private fun syncCropSlidersFromPrefs() {
        suppressCropCallbacks = true
        val topPx = app.prefs.getCropTopPx()
        val bottomPx = app.prefs.getCropBottomPx()
        binding.seekCropTop.progress = topPx / CROP_STEP_PX
        binding.seekCropBottom.progress = bottomPx / CROP_STEP_PX
        binding.txtCropTopValue.text = "$topPx px"
        binding.txtCropBottomValue.text = "$bottomPx px"
        suppressCropCallbacks = false
    }

    private fun updateMirrorGeometryPreview() {
        val metrics = ScreenCaptureManager.readPhoneDisplayMetrics(this)
        val landscapeW = maxOf(metrics.realWidth, metrics.realHeight)
        val landscapeH = minOf(metrics.realWidth, metrics.realHeight)
        val cfg = app.prefs.readMirrorDisplayConfig()
        val layout = MirrorLayoutMath.computeLayout(
            bmwWidth = 1280,
            bmwHeight = 480,
            phoneWidth = landscapeW,
            phoneHeight = landscapeH,
            config = cfg,
        )
        val sideShort = if (cfg.dockSide == DockSide.LEFT) "Lijevo" else "Desno"
        binding.txtMirrorGeometryPreview.text =
            "BMW 1280×480: karta ${layout.videoDrawRect.width}×${layout.videoDrawRect.height} px " +
                "(${layout.videoScreenPercent} %) · Dock ${layout.dockRect.width} px ($sideShort) · 0 % distorzije"
    }

    private fun setupDockSlotViews() {
        binding.dockSlotsContainer.removeAllViews()
        dockSlotBindings.clear()
        val inflater = LayoutInflater.from(this)
        for (slot in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) {
            val slotBinding = ItemDockSlotBinding.inflate(inflater, binding.dockSlotsContainer, false)
            slotBinding.root.setOnClickListener {
                showDockAppPicker(slot)
            }
            binding.dockSlotsContainer.addView(slotBinding.root)
            dockSlotBindings.add(slotBinding)
        }
    }

    private fun refreshDockSlotsUi() {
        for (slot in 0 until MirrorDisplayConfig.DOCK_SLOT_COUNT) {
            val item = dockSlotBindings.getOrNull(slot) ?: continue
            val pkg = app.prefs.getDockAppPackage(slot)
            if (pkg.isNullOrBlank()) {
                item.txtSlotLabel.text = getString(R.string.dock_slot_empty)
                item.imgSlotIcon.setImageResource(android.R.drawable.ic_input_add)
            } else {
                item.txtSlotLabel.text = DockAppCatalog.getAppLabel(this, pkg)
                val icon = DockAppCatalog.loadAppIconDrawable(this, pkg)
                if (icon != null) {
                    item.imgSlotIcon.setImageDrawable(icon)
                } else {
                    item.imgSlotIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }
            }
        }
    }

    private fun showDockAppPicker(slotIndex: Int) {
        lifecycleScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                DockAppCatalog.listLaunchableApps(this@MainActivity)
            }
            if (isFinishing || isDestroyed) return@launch

            val emptyOption = DockAppCatalog.InstalledApp("", "— Ostavi prazno —")
            val fullList = listOf(emptyOption) + allApps
            val filtered = ArrayList(fullList)

            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }

            val searchBox = EditText(this@MainActivity).apply {
                hint = getString(R.string.hint_search_apps)
                isSingleLine = true
            }
            val listView = ListView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (360 * resources.displayMetrics.density).toInt(),
                )
            }

            val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()
            val adapter = object : ArrayAdapter<DockAppCatalog.InstalledApp>(
                this@MainActivity,
                0,
                filtered,
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        val p = (10 * resources.displayMetrics.density).toInt()
                        setPadding(p / 2, p, p / 2, p)
                        addView(
                            ImageView(context).apply {
                                id = android.R.id.icon
                                val sz = (34 * resources.displayMetrics.density).toInt()
                                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                                    marginEnd = (12 * resources.displayMetrics.density).toInt()
                                }
                            },
                        )
                        addView(
                            TextView(context).apply {
                                id = android.R.id.text1
                                textSize = 15f
                            },
                        )
                    }
                    val item = getItem(position)
                    val img = row.findViewById<ImageView>(android.R.id.icon)
                    val txt = row.findViewById<TextView>(android.R.id.text1)
                    txt.text = item?.label.orEmpty()
                    if (item == null || item.packageName.isEmpty()) {
                        img.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    } else {
                        val icon = iconCache.getOrPut(item.packageName) {
                            DockAppCatalog.loadAppIconDrawable(context, item.packageName)
                        }
                        if (icon != null) {
                            img.setImageDrawable(icon)
                        } else {
                            img.setImageResource(android.R.drawable.sym_def_app_icon)
                        }
                    }
                    return row
                }
            }
            listView.adapter = adapter

            searchBox.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString().orEmpty().trim().lowercase()
                    filtered.clear()
                    if (q.isEmpty()) {
                        filtered.addAll(fullList)
                    } else {
                        filtered.add(emptyOption)
                        filtered.addAll(
                            allApps.filter {
                                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                            },
                        )
                    }
                    adapter.notifyDataSetChanged()
                }
            })

            container.addView(searchBox)
            container.addView(listView)

            val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.dialog_pick_app_title, slotIndex + 1))
                .setView(container)
                .setNegativeButton(R.string.btn_cancel, null)
                .create()

            listView.setOnItemClickListener { _, _, position, _ ->
                val chosen = filtered.getOrNull(position) ?: return@setOnItemClickListener
                app.prefs.setDockAppPackage(slotIndex, chosen.packageName.ifBlank { null })
                refreshDockSlotsUi()
                app.orchestrator.notifyMirrorConfigChanged()
                dialog.dismiss()
            }
            dialog.show()
        }
    }

    private fun refreshAccessibilityUi(active: Boolean) {
        if (active) {
            binding.layoutAccessibilityBox.setBackgroundColor(ContextCompat.getColor(this, R.color.status_success_bg))
            binding.txtAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
            binding.txtAccessibilityStatus.text = "✓ BMW dodir i iDrive su aktivni (Pristupačnost uključena)"
            binding.txtAccessibilityTip.visibility = View.GONE
            binding.layoutAccessibilityButtons.visibility = View.GONE
        } else {
            binding.layoutAccessibilityBox.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warning_bg))
            binding.txtAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warning))
            binding.txtAccessibilityStatus.text = "⚠ BMW dodir isključen — uključi Pristupačnost za upravljanje Google Kartama s BMW ekrana"
            binding.txtAccessibilityTip.visibility = View.VISIBLE
            binding.layoutAccessibilityButtons.visibility = View.VISIBLE
        }
    }

    private fun maybeAutoConnectOnOpen() {
        if (!app.prefs.autoConnectOnOpen) return
        if (app.orchestrator.state.value.isRunning) return
        if (mediaProjectionPromptInFlight) return
        if (getMissingPermissions().isNotEmpty()) return
        val mac = app.prefs.phoneBluetoothMac
        if (!ProbePrefs.isValidMac(mac)) return
        app.logger.log("SESIJA", "Automatsko spajanje pri otvaranju aktivno — pokrećem BMW vezu…")
        launchSession(PendingSessionStart.Full)
    }

    private fun launchSession(request: PendingSessionStart) {
        if (app.prefs.videoSourceMode == ProbePrefs.VideoSourceMode.SCREEN_MIRROR) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            if (mpm == null) {
                Toast.makeText(this, "MediaProjectionManager nije dostupan na ovom uređaju", Toast.LENGTH_LONG).show()
                return
            }
            pendingStart = request
            mediaProjectionPromptInFlight = true
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(captureIntent)
        } else {
            when (request) {
                PendingSessionStart.Full -> ProbeForegroundService.startFull(this)
                is PendingSessionStart.ManualWifi -> ProbeForegroundService.startManualWifi(
                    context = this,
                    ssid = request.ssid,
                    passphrase = request.passphrase,
                    securityCode = request.securityCode,
                )
            }
        }
    }

    private fun setupSettingsUi() {
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

        binding.btnToggleAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.layoutAdvancedOptions.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.btnToggleAdvanced.text =
                if (advancedExpanded) "▾ Napredne testne opcije" else "▸ Napredne testne opcije"
        }

        val mfiModes = ProbePrefs.MfiChallengeMode.entries
        binding.spinnerMfiLength.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            mfiModes.map { it.labelHr },
        )
        binding.spinnerMfiLength.setSelection(mfiModes.indexOf(app.prefs.mfiChallengeMode).coerceAtLeast(0))
        binding.spinnerMfiLength.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.prefs.mfiChallengeMode = mfiModes[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val rfcommModes = ProbePrefs.RfcommMode.entries
        binding.spinnerRfcommMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            rfcommModes.map { it.labelHr },
        )
        binding.spinnerRfcommMode.setSelection(rfcommModes.indexOf(app.prefs.rfcommMode).coerceAtLeast(0))
        binding.spinnerRfcommMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                app.prefs.rfcommMode = rfcommModes[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

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
            launchSession(PendingSessionStart.Full)
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
                    BmwTouchAccessibilityService.isActive.collect { active ->
                        refreshAccessibilityUi(active)
                    }
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
        st.detectedWifiSsid?.let { editSsid.setText(it) }
        st.detectedWifiPassphrase?.let { editPass.setText(it) }

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
                launchSession(PendingSessionStart.ManualWifi(ssid, pass, sec.code))
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

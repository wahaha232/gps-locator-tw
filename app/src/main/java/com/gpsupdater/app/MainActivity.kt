package com.gpsupdater.app

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Patterns
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gpsupdater.app.databinding.ActivityMainBinding
import com.gpsupdater.app.databinding.DialogFavoritesBinding
import com.gpsupdater.app.databinding.DialogJoystickBinding
import com.gpsupdater.app.databinding.DialogCloudSyncBinding
import com.gpsupdater.app.databinding.DialogSaveFavoriteBinding
import com.gpsupdater.app.databinding.ItemFavoriteBinding
import com.gpsupdater.app.favorites.FavoriteLocation
import com.gpsupdater.app.favorites.FavoriteLocationRepository
import com.gpsupdater.app.geocoding.GeocodingResult
import com.gpsupdater.app.geocoding.NominatimGeocoder
import com.gpsupdater.app.geocoding.RegionSearchDialog
import com.gpsupdater.app.geocoding.MapServiceConfiguration
import com.gpsupdater.app.history.LocationHistoryEntry
import com.gpsupdater.app.history.LocationHistoryRepository
import com.gpsupdater.app.location.LocationHint
import com.gpsupdater.app.location.LocationHintPolicy
import com.gpsupdater.app.location.MockLocationAccess
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.location.MockLocationSetupPolicy
import com.gpsupdater.app.location.MockLocationSetupStep
import com.gpsupdater.app.location.MockLocationService
import com.gpsupdater.app.map.MapFollowPolicy
import com.gpsupdater.app.map.OsmdroidConfiguration
import com.gpsupdater.app.map.TrailDecimator
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.util.BoundedIo
import com.gpsupdater.app.product.EntitlementRepository
import com.gpsupdater.app.product.ProductFeature
import com.gpsupdater.app.privacy.LocalTestDataCleaner
import com.gpsupdater.app.routes.RouteEditorActivity
import com.gpsupdater.app.update.UpdateInfo
import com.gpsupdater.app.update.UpdateManager
import com.gpsupdater.app.update.UpdateVerificationException
import com.gpsupdater.app.routes.GeoMath
import com.gpsupdater.app.sync.CloudCipher
import com.gpsupdater.app.sync.CloudSyncClient
import com.gpsupdater.app.sync.CloudSyncConfiguration
import com.gpsupdater.app.sync.CloudSyncSettings
import com.gpsupdater.app.templates.TeamTemplateBundle
import com.gpsupdater.app.templates.TeamTemplateBundleCodec
import com.gpsupdater.app.templates.TeamTemplateBundleRepository
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.io.ByteArrayOutputStream
import java.util.Date

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val favoriteRepository by lazy { FavoriteLocationRepository(this) }
    private val geocoder by lazy { NominatimGeocoder() }
    private val entitlementRepository by lazy { EntitlementRepository(this) }
    private val historyRepository by lazy { LocationHistoryRepository(this) }
    private val cloudSyncSettings by lazy { CloudSyncSettings(this) }
    private val teamTemplateRepository by lazy { TeamTemplateBundleRepository(this) }
    private var cloudSyncClient: CloudSyncClient? = null
    private var receiverRegistered = false
    private var pendingPermissionAction: PermissionAction? = null
    private var pendingRouteId: String? = null
    private var selectedPlaceName: String? = null
    private var pendingLocalDataClear = false
    private var pendingTeamTemplateFavoriteIds: Set<String>? = null
    private var pendingTeamTemplateRouteIds: Set<String>? = null
    // 啟動時的定位提示（含逾時）同一次啟動只跳一次。
    private var startupLocationHintShown = false
    private var startupPermissionRequestPending = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        when (pendingPermissionAction) {
            PermissionAction.START_MOCK -> {
                val coordinates = selectedCoordinates()
                if (hasLocationPermission() && coordinates != null) {
                    startMockService(coordinates)
                } else {
                    showMessage(getString(R.string.location_permission_required))
                }
            }

            PermissionAction.CENTER_ON_DEVICE -> {
                when {
                    hasLocationPermission() && startupPermissionRequestPending ->
                        centerOnCurrentLocationAtStart()

                    hasLocationPermission() -> centerOnDeviceLocation()
                    startupPermissionRequestPending ->
                        showStartupLocationHint(LocationHint.MISSING_PERMISSION)

                    else -> showMessage(getString(R.string.location_permission_required))
                }
            }

            PermissionAction.START_ROUTE -> {
                val routeId = pendingRouteId
                if (hasLocationPermission() && routeId != null) {
                    startRouteService(routeId)
                } else {
                    showMessage(getString(R.string.location_permission_required))
                }
            }

            null -> Unit
        }
        pendingPermissionAction = null
        pendingRouteId = null
        startupPermissionRequestPending = false
    }

    private val routeEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(RouteEditorActivity.EXTRA_ROUTE_ID)
                ?.let(::startSelectedRoute)
        }
    }

    private val enterpriseLicenseLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val document = runCatching {
            contentResolver.openInputStream(uri)
                ?.use { BoundedIo.readText(it, BoundedIo.LICENSE_DOCUMENT_MAX_BYTES) }
                ?: error("Empty license")
        }.getOrNull()
        val result = document?.let(entitlementRepository::activateEnterpriseLicense)
        result?.onSuccess { license ->
            showMessage(getString(R.string.enterprise_license_active, license.organization))
        }?.onFailure {
            showMessage(getString(R.string.enterprise_license_invalid))
        } ?: showMessage(getString(R.string.enterprise_license_invalid))
    }

    private val teamTemplateImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val document = readDocument(uri, TeamTemplateBundleCodec.MAX_DOCUMENT_BYTES)
            TeamTemplateBundleCodec.decode(document)
        }.onSuccess { bundle ->
            showTeamTemplateImportPreview(bundle)
        }.onFailure {
            showMessage(getString(R.string.team_templates_import_failed))
        }
    }

    private val teamTemplateExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val favoriteIds = pendingTeamTemplateFavoriteIds
        val routeIds = pendingTeamTemplateRouteIds
        pendingTeamTemplateFavoriteIds = null
        pendingTeamTemplateRouteIds = null
        if (uri == null) return@registerForActivityResult
        if (favoriteIds == null || routeIds == null) {
            showMessage(getString(R.string.team_templates_export_failed))
            return@registerForActivityResult
        }
        runCatching {
            val bundle = teamTemplateRepository.export(favoriteIds, routeIds)
            check(bundle.favorites.isNotEmpty() || bundle.routes.isNotEmpty())
            val document = TeamTemplateBundleCodec.encode(bundle)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(document) }
                ?: error("Could not open template output")
        }.onSuccess {
            showMessage(getString(R.string.team_templates_exported))
        }.onFailure {
            showMessage(getString(R.string.team_templates_export_failed))
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(MockLocationService.EXTRA_STATUS)) {
                MockLocationService.STATUS_RUNNING -> {
                    binding.playbackPanel.visibility = View.GONE
                    setPanelDetailsCollapsed(false)
                    clearSimulationMarker()
                    updateServiceState(ServiceState.RUNNING)
                    showMessage(
                        if (binding.startButton.text == getString(R.string.update_mock)) {
                            getString(R.string.mock_updated)
                        } else {
                            getString(R.string.mock_started)
                        },
                    )
                }

                MockLocationService.STATUS_STOPPED -> {
                    binding.playbackPanel.visibility = View.GONE
                    setPanelDetailsCollapsed(false)
                    clearSimulationMarker()
                    updateServiceState(ServiceState.STOPPED)
                    if (pendingLocalDataClear) clearLocalTestData()
                }
                MockLocationService.STATUS_ROUTE_RUNNING -> {
                    updateServiceState(ServiceState.RUNNING)
                    updatePlaybackPanel(intent)
                }
                MockLocationService.STATUS_ROUTE_COMPLETED -> {
                    binding.playbackPanel.visibility = View.GONE
                    setPanelDetailsCollapsed(false)
                    clearSimulationMarker()
                    updateServiceState(ServiceState.STOPPED)
                    showMessage(getString(R.string.route_completed))
                }
                MockLocationService.STATUS_ERROR -> {
                    binding.playbackPanel.visibility = View.GONE
                    setPanelDetailsCollapsed(false)
                    clearSimulationMarker()
                    updateServiceState(ServiceState.ERROR)
                    showMessage(
                        intent.getStringExtra(MockLocationService.EXTRA_MESSAGE)
                            ?: getString(R.string.service_failed),
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingTeamTemplateFavoriteIds = savedInstanceState
            ?.getStringArrayList(STATE_TEAM_TEMPLATE_FAVORITE_IDS)
            ?.toSet()
        pendingTeamTemplateRouteIds = savedInstanceState
            ?.getStringArrayList(STATE_TEAM_TEMPLATE_ROUTE_IDS)
            ?.toSet()
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE),
        )
        OsmdroidConfiguration.configure()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureSystemBars()
        configureMap()
        if (savedInstanceState == null) {
            centerOnCurrentLocationAtStart()
            maybeAutoCheckUpdate()
        }
        configureInputs()
        configureActions()
        configureFloatingControls()
        updateServiceState(
            if (MockLocationPreferences.isActive(this)) ServiceState.RUNNING
            else ServiceState.STOPPED,
        )
        updateMockLocationSetup()
        maybeShowSetupGuide()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingTeamTemplateFavoriteIds?.let {
            outState.putStringArrayList(STATE_TEAM_TEMPLATE_FAVORITE_IDS, ArrayList(it))
        }
        pendingTeamTemplateRouteIds?.let {
            outState.putStringArrayList(STATE_TEAM_TEMPLATE_ROUTE_IDS, ArrayList(it))
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                statusReceiver,
                IntentFilter(MockLocationService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        mapActive = true
        binding.map.onResume()
        refreshMapAfterResume()
        updateServiceState(
            if (MockLocationPreferences.isActive(this)) ServiceState.RUNNING
            else ServiceState.STOPPED,
        )
        updateMockLocationSetup()
    }

    override fun onPause() {
        mapActive = false
        binding.map.removeCallbacks(trailInvalidateRunnable)
        selectedCoordinates()?.let { MockLocationPreferences.saveCoordinates(this, it) }
        binding.map.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapActive = false
        binding.map.removeCallbacks(trailInvalidateRunnable)
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        binding.map.removeCallbacks(trailInvalidateRunnable)
        cloudSyncClient?.close()
        geocoder.close()
        updateCancelled.set(true)
        updateExecutor.shutdownNow()
        binding.map.onDetach()
        super.onDestroy()
    }

    private var lockHintShown = false

    private fun configureMap() {
        val initialCoordinates = MockLocationPreferences.coordinates(this)
        binding.map.apply {
            setTileSource(MapServiceConfiguration.tileSource())
            setMultiTouchControls(true)
            // 播放路線時使用者一碰地圖就暫停「跟隨模擬位置」，不然地圖會一直被拉回去。
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN &&
                    binding.playbackPanel.visibility == View.VISIBLE && followSimulation
                ) {
                    setFollowSimulation(false)
                }
                if (event.action == android.view.MotionEvent.ACTION_DOWN && isLocationLocked() && !lockHintShown) {
                    lockHintShown = true
                    showMessage(getString(R.string.location_locked_hint))
                }
                false
            }
            isTilesScaledToDpi = false
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(initialCoordinates.latitude, initialCoordinates.longitude))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    clearSelectedPlaceName()
                    updateInputsFromMap()
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    updateInputsFromMap()
                    return false
                }
            })
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                        controller.animateTo(point)
                        return true
                    }

                    override fun longPressHelper(point: GeoPoint): Boolean {
                        controller.animateTo(point)
                        return true
                    }
                }),
            )
        }
        showCoordinates(initialCoordinates)
        binding.attributionText.text = BuildConfig.MAP_ATTRIBUTION
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(12) + systemBars.top
            }
            binding.controlPanel.updatePadding(bottom = dp(18) + systemBars.bottom)
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(294) + systemBars.bottom
            }
            windowInsets
        }
    }

    private fun configureInputs() {
        binding.longitudeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyInputCoordinates()
                true
            } else {
                false
            }
        }
        binding.latitudeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.longitudeInput.requestFocus()
                true
            } else {
                false
            }
        }
        binding.latitudeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !binding.longitudeInput.hasFocus()) applyInputCoordinates(false)
        }
        binding.longitudeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !binding.latitudeInput.hasFocus()) applyInputCoordinates(false)
        }
    }

    private fun configureActions() {
        binding.startButton.setOnClickListener { startSelectedLocation() }
        binding.stopButton.setOnClickListener {
            startService(MockLocationService.stopIntent(this))
            MockLocationPreferences.setActive(this, false)
            lockHintShown = false
            setPanelDetailsCollapsed(false)
            clearSimulationMarker()
            updateServiceState(ServiceState.STOPPED)
            showMessage(getString(R.string.mock_stopped))
        }
        binding.developerSettingsButton.setOnClickListener { showAppSettings() }
        binding.titleBlock.setOnClickListener { showRegionSearch() }
        binding.regionSearchButton.setOnClickListener { showRegionSearch() }
        binding.routeButton.setOnClickListener { openRouteEditor() }
        binding.favoritesButton.setOnClickListener { showFavorites() }
        binding.favoriteCurrentButton.setOnClickListener { showSaveFavorite() }
        binding.setupMockLocationButton.setOnClickListener { showMockLocationDialog() }
        binding.myLocationButton.setOnClickListener {
            if (isLocationLocked()) {
                val at = MockLocationPreferences.coordinates(this)
                binding.map.controller.animateTo(GeoPoint(at.latitude, at.longitude))
            } else if (hasLocationPermission()) {
                centerOnDeviceLocation()
            } else {
                requestPermissions(PermissionAction.CENTER_ON_DEVICE)
            }
        }
    }

    private fun startSelectedLocation() {
        val coordinates = selectedCoordinates(showError = true) ?: return
        hideKeyboard()
        binding.latitudeInput.clearFocus()
        binding.longitudeInput.clearFocus()

        if (!MockLocationAccess.isEnabled(this)) {
            showMockLocationDialog()
            return
        }
        if (!hasLocationPermission()) {
            requestPermissions(PermissionAction.START_MOCK)
            return
        }
        if (isRoutePlaying()) {
            // 改成固定位置會停止目前的路線；不能靜默蓋掉。
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.replace_route_title)
                .setMessage(R.string.replace_route_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.switch_to_static) { _, _ -> startMockService(coordinates) }
                .show()
            return
        }
        // 鎖定位置前先確認，避免拖地圖途中誤按；確定後座標欄不再跟著地圖移動。
        val label = selectedPlaceName?.let { "$it (${coordinates.formattedLatitude()}, ${coordinates.formattedLongitude()})" }
            ?: "${coordinates.formattedLatitude()}, ${coordinates.formattedLongitude()}"
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lock_confirm_title)
            .setMessage(getString(R.string.lock_confirm_message, label))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.lock_confirm_action) { _, _ -> startMockService(coordinates) }
            .show()
    }

    private var lockMarker: org.osmdroid.views.overlay.Marker? = null

    /** 鎖定時：中央大頭針收起，改在鎖定的地點留一個固定標記（隨地圖移動），「目前位置」鈕變成「回到鎖定位置」。 */
    private fun refreshLockMarker() {
        val locked = isLocationLocked()
        lockMarker?.let { binding.map.overlays.remove(it) }
        lockMarker = null
        if (locked) {
            val at = MockLocationPreferences.coordinates(this)
            lockMarker = org.osmdroid.views.overlay.Marker(binding.map).apply {
                position = GeoPoint(at.latitude, at.longitude)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_map_pin)
                setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> true }
            }.also { binding.map.overlays.add(it) }
        }
        binding.centerPin.visibility = if (locked || simulationMarker != null) View.INVISIBLE else View.VISIBLE
        binding.myLocationButton.setText(if (locked) R.string.return_to_lock else R.string.my_location_label)
        if (mapActive) binding.map.invalidate()
    }

    /** 固定位置模擬進行中：座標欄鎖定，不隨地圖拖曳改變。 */
    private fun isLocationLocked(): Boolean =
        MockLocationPreferences.isActive(this) && !isRoutePlaying()

    private fun isRoutePlaying(): Boolean =
        binding.playbackPanel.visibility == View.VISIBLE || MockLocationPreferences.activeRouteId(this) != null

    private fun startMockService(coordinates: Coordinates) {
        updateServiceState(ServiceState.STARTING)
        ContextCompat.startForegroundService(
            this,
            MockLocationService.startIntent(this, coordinates, selectedPlaceName),
        )
    }

    private fun showMockLocationDialog() {
        val step = currentMockLocationSetupStep()
        if (step == MockLocationSetupStep.READY) return
        val title = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.enable_developer_options_title
        } else {
            R.string.mock_permission_title
        }
        val message = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.enable_developer_options_message
        } else {
            R.string.mock_permission_message
        }
        val action = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            R.string.open_about_phone
        } else {
            R.string.go_to_settings
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> openDeveloperOptions(step) }
            .show()
    }

    private fun updateMockLocationSetup() {
        when (currentMockLocationSetupStep()) {
            MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS -> {
                binding.setupMockLocationButton.visibility = View.VISIBLE
                binding.setupMockLocationButton.setText(R.string.enable_developer_options)
            }
            MockLocationSetupStep.SELECT_MOCK_LOCATION_APP -> {
                binding.setupMockLocationButton.visibility = View.VISIBLE
                binding.setupMockLocationButton.setText(R.string.select_mock_location_app)
            }
            MockLocationSetupStep.READY -> binding.setupMockLocationButton.visibility = View.GONE
        }
    }

    private fun currentMockLocationSetupStep(): MockLocationSetupStep =
        MockLocationSetupPolicy.resolve(
            developerOptionsEnabled = Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            ) == 1,
            mockLocationEnabled = MockLocationAccess.isEnabled(this),
        )

    private fun maybeShowSetupGuide() {
        if (BuildConfig.DEBUG || currentMockLocationSetupStep() == MockLocationSetupStep.READY) return
        val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(KEY_SETUP_GUIDE_SHOWN, false)) return
        preferences.edit().putBoolean(KEY_SETUP_GUIDE_SHOWN, true).apply()
        binding.root.post {
            if (!isFinishing && currentMockLocationSetupStep() != MockLocationSetupStep.READY) {
                showMockLocationDialog()
            }
        }
    }

    private fun configureFloatingControls() {
        configurePlaybackControls()
        binding.panelHandle.setOnClickListener {
            setPanelDetailsCollapsed(!panelDetailsCollapsed)
        }
        setPanelDetailsCollapsed(false)
        binding.zoomInButton.setOnClickListener { binding.map.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.map.controller.zoomOut() }
        binding.controlPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            binding.myLocationButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(12)
            }
            // 縮放鈕疊在「目前位置」按鈕正上方，一起跟著底部面板的實際高度走；
            // 不能再用「垂直置中」，螢幕比較矮或面板比較高時會和「目前位置」重疊。
            binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(12) + dp(52) + dp(12)
            }
            // 地圖只佔底部面板上方的空間：圖釘（地圖中央）永遠看得到，座標也永遠是畫面上看到的位置；
            // 面板在小螢幕或播放路線時變高，也不會再蓋住圖釘。改變大小前記住中心，之後放回去，座標不會因此跳動。
            val mapParams = binding.map.layoutParams as FrameLayout.LayoutParams
            if (mapParams.bottomMargin != panelHeight) {
                val center = binding.map.mapCenter
                binding.map.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = panelHeight }
                binding.centerPin.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = panelHeight }
                binding.map.post { binding.map.controller.setCenter(center) }
            }
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(16)
            }
        }
    }

    /** 收合底部面板時只留狀態列與播放控制，讓地圖保留約 2/3 畫面。 */
    private fun setPanelDetailsCollapsed(collapsed: Boolean) {
        panelDetailsCollapsed = collapsed
        if (collapsed) {
            binding.latitudeInput.clearFocus()
            binding.longitudeInput.clearFocus()
            hideKeyboard()
        }
        binding.panelDetails.visibility = if (collapsed) View.GONE else View.VISIBLE
        binding.panelToggleIcon.rotation = if (collapsed) 0f else 180f
        binding.panelHandle.contentDescription = getString(
            if (collapsed) R.string.panel_expand else R.string.panel_collapse,
        )
    }

    private fun openDeveloperOptions(
        step: MockLocationSetupStep = currentMockLocationSetupStep(),
    ) {
        val action = if (step == MockLocationSetupStep.ENABLE_DEVELOPER_OPTIONS) {
            Settings.ACTION_DEVICE_INFO_SETTINGS
        } else {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        }
        val intent = Intent(action)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    /** 設定頁的「模擬位置設定」：還沒設定好就走引導流程，已設定好就說明現況並可前往開發人員選項更換。 */
    private fun showMockLocationSettings() {
        if (currentMockLocationSetupStep() != MockLocationSetupStep.READY) {
            showMockLocationDialog()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mock_location_ready_title)
            .setMessage(R.string.mock_location_ready_message)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(R.string.open_developer_options_action) { _, _ -> openDeveloperOptions() }
            .show()
    }

    /** 設定頁的「無障礙設定」：直接開啟系統的無障礙設定頁。 */
    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun showAppSettings() {
        val options = arrayOf(
            getString(R.string.routes),
            getString(R.string.history),
            getString(R.string.joystick),
            getString(R.string.cloud_sync),
            getString(R.string.team_templates),
            getString(R.string.language),
            getString(R.string.enterprise_license),
            getString(R.string.privacy_and_data),
            getString(R.string.about_and_support),
            getString(R.string.mock_location_settings),
            getString(R.string.accessibility_settings),
            getString(R.string.open_developer_options),
            getString(R.string.check_update),
        )
        MaterialAlertDialogBuilder(this)
            // 版本號直接顯示在設定視窗標題，不用再進「關於與支援」才看得到。
            .setTitle(getString(R.string.app_settings) + " · " + BuildConfig.VERSION_NAME)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> openRouteEditor()
                    1 -> showHistory()
                    2 -> showJoystick()
                    3 -> showCloudSync()
                    4 -> showTeamTemplates()
                    5 -> showLanguageDialog()
                    6 -> showEnterpriseLicense()
                    7 -> showPrivacyAndData()
                    8 -> showAboutAndSupport()
                    9 -> showMockLocationSettings()
                    10 -> openAccessibilitySettings()
                    11 -> openDeveloperOptions()
                    12 -> showUpdateSettings()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private val updateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val updateCancelled = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 依使用者設定的間隔在啟動時自動查一次（預設每天）。沒有新版完全不打擾；有新版才跳出「可更新」提示。
     * 間隔 0＝每次開啟、負數＝不自動檢查（只能手動）。
     */
    private fun maybeAutoCheckUpdate() {
        val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
        val hours = preferences.getInt(KEY_UPDATE_INTERVAL_HOURS, DEFAULT_UPDATE_INTERVAL_HOURS)
        if (hours < 0) return
        val now = System.currentTimeMillis()
        if (hours > 0 && now - preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L) < hours * 60 * 60 * 1000L) return
        preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()
        checkForUpdate(silent = true)
    }

    private fun updateIntervalOptions(): List<Pair<Int, String>> = listOf(
        0 to getString(R.string.update_interval_launch),
        6 to getString(R.string.update_interval_6h),
        24 to getString(R.string.update_interval_daily),
        72 to getString(R.string.update_interval_3d),
        168 to getString(R.string.update_interval_weekly),
        -1 to getString(R.string.update_interval_never),
    )

    /** 「檢查更新」：可以立即手動檢查，也可以設定自動檢查的間隔。 */
    private fun showUpdateSettings() {
        val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
        val hours = preferences.getInt(KEY_UPDATE_INTERVAL_HOURS, DEFAULT_UPDATE_INTERVAL_HOURS)
        val label = updateIntervalOptions().firstOrNull { it.first == hours }?.second ?: getString(R.string.update_interval_daily)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.check_update)
            .setMessage(getString(R.string.update_interval_current, label))
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.update_interval_title) { _, _ -> showUpdateIntervalDialog() }
            .setPositiveButton(R.string.update_check_now) { _, _ -> checkForUpdate(silent = false) }
            .show()
    }

    private fun showUpdateIntervalDialog() {
        val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
        val options = updateIntervalOptions()
        val current = options.indexOfFirst { it.first == preferences.getInt(KEY_UPDATE_INTERVAL_HOURS, DEFAULT_UPDATE_INTERVAL_HOURS) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_interval_title)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                preferences.edit().putInt(KEY_UPDATE_INTERVAL_HOURS, options[which].first).apply()
                dialog.dismiss()
                showMessage(getString(R.string.update_interval_saved, options[which].second))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkForUpdate(silent: Boolean) {
        if (!silent) showMessage(getString(R.string.update_checking))
        updateExecutor.execute {
            val result = runCatching { UpdateManager.checkLatest() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { info ->
                    if (info != null) showUpdateAvailable(info)
                    else if (!silent) showMessage(getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
                }.onFailure {
                    if (!silent) showMessage(getString(R.string.update_check_failed))
                }
            }
        }
    }

    private fun showUpdateAvailable(info: UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, info.versionName, BuildConfig.VERSION_NAME))
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_action) { _, _ -> startUpdateDownload(info) }
            .show()
    }

    private fun startUpdateDownload(info: UpdateInfo) {
        if (!UpdateManager.canInstall(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.update_allow_install)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.go_to_settings) { _, _ -> UpdateManager.openInstallPermissionSettings(this) }
                .show()
            return
        }
        updateCancelled.set(false)
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max = 100
            isIndeterminate = true
        }
        val container = android.widget.LinearLayout(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(
                bar,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_downloading)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> updateCancelled.set(true) }
            .show()
        updateExecutor.execute {
            val result = runCatching {
                UpdateManager.download(applicationContext, info, { updateCancelled.get() }) { percent ->
                    runOnUiThread {
                        bar.isIndeterminate = false
                        bar.setProgressCompat(percent, true)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { UpdateManager.install(this, it) }.onFailure { error ->
                    when {
                        updateCancelled.get() -> Unit
                        error is UpdateVerificationException -> showMessage(getString(R.string.update_checksum_failed))
                        else -> showMessage(
                            getString(R.string.update_failed, (error.message ?: error.javaClass.simpleName).take(100)),
                        )
                    }
                }
            }
        }
    }

    private fun showPrivacyAndData() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_and_data)
            .setMessage(R.string.privacy_and_data_summary)
            .setNegativeButton(R.string.close, null)
            .setNeutralButton(R.string.clear_local_test_data) { _, _ ->
                confirmClearLocalTestData()
            }
        configuredPrivacyPolicyUri()?.let { policyUri ->
            builder.setPositiveButton(R.string.open_privacy_policy) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, policyUri)) }
                    .onFailure { showMessage(getString(R.string.privacy_policy_open_failed)) }
            }
        }
        builder.show()
    }

    private fun confirmClearLocalTestData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_local_test_data_title)
            .setMessage(R.string.clear_local_test_data_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> requestLocalTestDataClear() }
            .show()
    }

    private fun requestLocalTestDataClear() {
        if (MockLocationPreferences.isActive(this)) {
            pendingLocalDataClear = true
            startService(MockLocationService.stopIntent(this))
        } else {
            clearLocalTestData()
        }
    }

    private fun clearLocalTestData() {
        pendingLocalDataClear = false
        runCatching { LocalTestDataCleaner(this).clear() }
            .onSuccess {
                moveMapTo(Coordinates.DEFAULT, FAVORITE_ZOOM, null)
                updateServiceState(ServiceState.STOPPED)
                showMessage(getString(R.string.local_test_data_cleared))
            }
            .onFailure {
                showMessage(getString(R.string.local_test_data_clear_failed))
            }
    }

    private fun configuredPrivacyPolicyUri(): Uri? {
        val uri = Uri.parse(BuildConfig.PRIVACY_POLICY_URL)
        return uri.takeIf {
            it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()
        }
    }

    private fun showAboutAndSupport() {
        val license = entitlementRepository.enterpriseStatus()?.takeIf { it.isActive() }
        val edition = if (license == null) {
            getString(R.string.edition_open_source)
        } else {
            getString(R.string.edition_licensed, license.organization)
        }
        val supportAddress = configuredSupportEmail()
        val message = listOf(
            getString(R.string.about_version, BuildConfig.VERSION_NAME),
            getString(R.string.about_edition, edition),
            if (supportAddress == null) {
                getString(R.string.support_contact_unavailable)
            } else {
                getString(R.string.support_contact, supportAddress)
            },
        ).joinToString("\n\n")
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_and_support)
            .setMessage(message)
            .setNegativeButton(R.string.close, null)
        supportAddress?.let { email ->
            builder.setPositiveButton(R.string.contact_support) { _, _ ->
                val mailUri = Uri.Builder()
                    .scheme("mailto")
                    .opaquePart(email)
                    .appendQueryParameter(
                        "subject",
                        getString(R.string.support_email_subject, BuildConfig.VERSION_NAME),
                    )
                    .build()
                runCatching { startActivity(Intent(Intent.ACTION_SENDTO, mailUri)) }
                    .onFailure { showMessage(getString(R.string.support_email_open_failed)) }
            }
        }
        builder.show()
    }

    private fun configuredSupportEmail(): String? = BuildConfig.SUPPORT_EMAIL
        .trim()
        .takeIf { it.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(it).matches() }

    private fun importEnterpriseLicense() {
        enterpriseLicenseLauncher.launch(arrayOf("application/json", "text/plain"))
    }

    private fun showEnterpriseLicense() {
        val status = entitlementRepository.enterpriseStatus()
        val message: String
        val action: Int
        when {
            status == null -> {
                message = getString(R.string.enterprise_license_missing_message)
                action = R.string.import_license
            }

            !status.isActive() -> {
                message = getString(
                    R.string.enterprise_license_status_expired,
                    status.organization,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(status.expiresAt)),
                )
                action = R.string.renew_license
            }

            status.expiresAt == 0L -> {
                message = getString(
                    R.string.enterprise_license_status_perpetual,
                    status.organization,
                )
                action = R.string.replace_license
            }

            else -> {
                message = getString(
                    R.string.enterprise_license_status_active,
                    status.organization,
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(status.expiresAt)),
                )
                action = R.string.replace_license
            }
        }
        val deviceBinding = status?.deviceId?.let {
            "\n${getString(R.string.enterprise_license_device_binding, it)}"
        }.orEmpty()
        val currentDeviceId = entitlementRepository.currentDeviceId()
        val currentDevice = if (status?.deviceId == null && currentDeviceId.isNotBlank()) {
            "\n${getString(R.string.enterprise_license_current_device, currentDeviceId)}"
        } else {
            ""
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enterprise_license)
            .setMessage(message + deviceBinding + currentDevice)
            .setNegativeButton(R.string.close, null)
            .setPositiveButton(action) { _, _ -> importEnterpriseLicense() }
        if (currentDeviceId.isNotBlank()) {
            builder.setNeutralButton(R.string.copy_device_id) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.device_id), currentDeviceId),
                )
                showMessage(getString(R.string.device_id_copied))
            }
        }
        builder.show()
    }

    private fun showCloudSync() {
        val syncBinding = DialogCloudSyncBinding.inflate(layoutInflater)
        val saved = cloudSyncSettings.load()
        syncBinding.endpointInput.setText(saved.endpoint)
        syncBinding.syncKeyInput.setText(saved.syncKey)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cloud_sync)
            .setView(syncBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        fun configuration(): CloudSyncConfiguration? {
            val endpoint = syncBinding.endpointInput.text?.toString()?.trim().orEmpty()
            val syncKey = syncBinding.syncKeyInput.text?.toString()?.trim().orEmpty()
            syncBinding.endpointLayout.error = null
            syncBinding.syncKeyLayout.error = null
            if (!endpoint.startsWith("https://")) {
                syncBinding.endpointLayout.error = getString(R.string.sync_endpoint_required)
                return null
            }
            if (syncKey.length < 20) {
                syncBinding.syncKeyLayout.error = getString(R.string.sync_key_required)
                return null
            }
            return CloudSyncConfiguration(endpoint.trimEnd('/'), syncKey)
        }

        fun setBusy(busy: Boolean) {
            syncBinding.progress.visibility = if (busy) View.VISIBLE else View.GONE
            syncBinding.backupButton.isEnabled = !busy
            syncBinding.restoreButton.isEnabled = !busy
            syncBinding.generateKeyButton.isEnabled = !busy
            syncBinding.deleteBackupButton.isEnabled = !busy
        }

        fun sync(restore: Boolean) {
            val configuration = configuration() ?: return
            cloudSyncSettings.save(configuration)
            setBusy(true)
            syncBinding.statusText.text = ""
            val client = cloudSyncClient ?: CloudSyncClient(this).also { cloudSyncClient = it }
            val callback: (Result<Unit>) -> Unit = { result ->
                if (dialog.isShowing) {
                    setBusy(false)
                    syncBinding.statusText.text = if (result.isSuccess) {
                        getString(if (restore) R.string.restore_complete else R.string.backup_complete)
                    } else {
                        getString(R.string.cloud_sync_failed)
                    }
                }
            }
            if (restore) client.restore(configuration, callback)
            else client.backup(configuration, callback)
        }

        syncBinding.generateKeyButton.setOnClickListener {
            syncBinding.syncKeyInput.setText(CloudCipher.generateSyncKey())
            syncBinding.syncKeyInput.selectAll()
        }
        syncBinding.backupButton.setOnClickListener { sync(restore = false) }
        syncBinding.restoreButton.setOnClickListener { sync(restore = true) }
        syncBinding.deleteBackupButton.setOnClickListener {
            val configuration = configuration() ?: return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_cloud_backup)
                .setMessage(R.string.delete_cloud_backup_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete) { _, _ ->
                    cloudSyncSettings.save(configuration)
                    setBusy(true)
                    val client = cloudSyncClient ?: CloudSyncClient(this).also {
                        cloudSyncClient = it
                    }
                    client.deleteBackup(configuration) { result ->
                        if (dialog.isShowing) {
                            setBusy(false)
                            syncBinding.statusText.text = getString(
                                if (result.isSuccess) R.string.cloud_backup_deleted
                                else R.string.cloud_sync_failed,
                            )
                        }
                    }
                }
                .show()
        }
        dialog.show()
    }

    private fun showTeamTemplates() {
        val options = arrayOf(
            getString(R.string.import_team_templates),
            getString(R.string.export_team_templates),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.team_templates)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> teamTemplateImportLauncher.launch(arrayOf("application/json", "text/plain"))
                    1 -> showTeamTemplateExportSelection()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showTeamTemplateExportSelection() {
        val available = teamTemplateRepository.export()
        val items = buildList {
            available.favorites.forEach { favorite ->
                add(
                    TeamTemplateSelectionItem(
                        id = favorite.id,
                        type = TeamTemplateItemType.FAVORITE,
                        label = getString(
                            R.string.team_template_location_option,
                            favorite.name,
                            favorite.coordinates.formattedLatitude(),
                            favorite.coordinates.formattedLongitude(),
                        ),
                    ),
                )
            }
            available.routes.forEach { route ->
                val waypointCount = resources.getQuantityString(
                    R.plurals.waypoint_count,
                    route.waypoints.size,
                    route.waypoints.size,
                )
                add(
                    TeamTemplateSelectionItem(
                        id = route.id,
                        type = TeamTemplateItemType.ROUTE,
                        label = getString(
                            R.string.team_template_route_option,
                            route.name,
                            waypointCount,
                        ),
                    ),
                )
            }
        }
        if (items.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.export_team_templates)
                .setMessage(R.string.team_templates_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        val checked = BooleanArray(items.size) { true }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_team_templates)
            .setMultiChoiceItems(items.map { it.label }.toTypedArray(), checked) { _, index, selected ->
                checked[index] = selected
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_team_templates) { _, _ ->
                val selected = items.filterIndexed { index, _ -> checked[index] }
                if (selected.isEmpty()) {
                    showMessage(getString(R.string.team_template_selection_required))
                    return@setPositiveButton
                }
                pendingTeamTemplateFavoriteIds = selected
                    .filter { it.type == TeamTemplateItemType.FAVORITE }
                    .map { it.id }
                    .toSet()
                pendingTeamTemplateRouteIds = selected
                    .filter { it.type == TeamTemplateItemType.ROUTE }
                    .map { it.id }
                    .toSet()
                teamTemplateExportLauncher.launch(getString(R.string.team_template_file_name))
            }
            .show()
    }

    private fun showTeamTemplateImportPreview(bundle: TeamTemplateBundle) {
        if (bundle.favorites.isEmpty() && bundle.routes.isEmpty()) {
            showMessage(getString(R.string.team_templates_empty))
            return
        }
        val preview = teamTemplateRepository.previewImport(bundle)
        val favoriteCount = resources.getQuantityString(
            R.plurals.team_template_saved_location_count,
            preview.favoriteCount,
            preview.favoriteCount,
        )
        val routeCount = resources.getQuantityString(
            R.plurals.team_template_route_count,
            preview.routeCount,
            preview.routeCount,
        )
        val message = getString(
            R.string.team_templates_import_preview,
            favoriteCount,
            formatTeamTemplateReplacementCount(preview.favoriteReplacementCount),
            routeCount,
            formatTeamTemplateReplacementCount(preview.routeReplacementCount),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_import_team_templates)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.import_team_templates) { _, _ ->
                teamTemplateRepository.merge(bundle)
                showTeamTemplateImportSuccess(bundle)
            }
            .show()
    }

    private fun formatTeamTemplateReplacementCount(count: Int): String = if (count == 0) {
        getString(R.string.team_template_no_replacements)
    } else {
        resources.getQuantityString(
            R.plurals.team_template_replacement_count,
            count,
            count,
        )
    }

    private fun showTeamTemplateImportSuccess(bundle: TeamTemplateBundle) {
        val savedLocationCount = resources.getQuantityString(
            R.plurals.team_template_saved_location_count,
            bundle.favorites.size,
            bundle.favorites.size,
        )
        val routeCount = resources.getQuantityString(
            R.plurals.team_template_route_count,
            bundle.routes.size,
            bundle.routes.size,
        )
        showMessage(
            getString(
                R.string.team_templates_imported,
                savedLocationCount,
                routeCount,
            ),
        )
    }

    private fun readDocument(uri: Uri, maximumBytes: Int): String {
        val input = contentResolver.openInputStream(uri) ?: error("Could not open document")
        return input.use { com.gpsupdater.app.util.BoundedIo.readText(it, maximumBytes) }
    }

    private fun showJoystick() {
        val joystickBinding = DialogJoystickBinding.inflate(layoutInflater)
        val steps = intArrayOf(1, 5, 10, 50, 100)
        val labels = steps.map { getString(R.string.distance_meters, it) }
        joystickBinding.stepInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels),
        )
        joystickBinding.stepInput.setText(labels[2], false)

        fun selectedStep(): Double {
            val index = labels.indexOf(joystickBinding.stepInput.text?.toString())
            return steps.getOrElse(index) { steps[2] }.toDouble()
        }

        fun move(bearing: Double) {
            val current = selectedCoordinates(showError = true) ?: return
            val moved = GeoMath.destination(current, selectedStep(), bearing)
            moveMapTo(moved, binding.map.zoomLevelDouble, null)
            joystickBinding.coordinateText.text = getString(
                R.string.coordinates_format,
                moved.formattedLatitude(),
                moved.formattedLongitude(),
            )
            if (MockLocationPreferences.isActive(this)) {
                ContextCompat.startForegroundService(
                    this,
                    MockLocationService.updateIntent(this, moved),
                )
            }
        }

        selectedCoordinates()?.let { coordinates ->
            joystickBinding.coordinateText.text = getString(
                R.string.coordinates_format,
                coordinates.formattedLatitude(),
                coordinates.formattedLongitude(),
            )
        }
        joystickBinding.northButton.setOnClickListener { move(0.0) }
        joystickBinding.eastButton.setOnClickListener { move(90.0) }
        joystickBinding.southButton.setOnClickListener { move(180.0) }
        joystickBinding.westButton.setOnClickListener { move(270.0) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.joystick)
            .setView(joystickBinding.root)
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHistory() {
        val entries = historyRepository.all()
        if (entries.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history)
                .setMessage(R.string.history_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = entries.map { entry ->
            "${entry.name}\n${getString(
                R.string.history_details,
                dateFormat.format(Date(entry.createdAt)),
                entry.coordinates.formattedLatitude(),
                entry.coordinates.formattedLongitude(),
            )}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history)
            .setItems(labels) { _, index -> showHistoryActions(entries[index]) }
            .setNeutralButton(R.string.clear_history) { _, _ -> confirmClearHistory() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showHistoryActions(entry: LocationHistoryEntry) {
        val options = arrayOf(
            getString(R.string.use_history),
            getString(R.string.save_history_favorite),
            getString(R.string.delete_history),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        if (entry.routeId != null) {
                            startSelectedRoute(entry.routeId)
                        } else {
                            moveMapTo(entry.coordinates, FAVORITE_ZOOM, entry.name)
                            binding.root.post { startSelectedLocation() }
                        }
                    }

                    1 -> {
                        favoriteRepository.save(entry.name, getString(R.string.history), entry.coordinates)
                        showMessage(getString(R.string.favorite_saved, entry.name))
                    }

                    2 -> {
                        historyRepository.delete(entry.id)
                        showMessage(getString(R.string.favorite_deleted))
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_history)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> historyRepository.clear() }
            .show()
    }

    private fun openRouteEditor() {
        // 把目前標記的位置交給編輯器：路線播放中是模擬位置，否則是地圖中央的標記；隨機生成軌跡就從這裡開始。
        val current = if (binding.playbackPanel.visibility == View.VISIBLE &&
            !simulationLatitude.isNaN() && !simulationLongitude.isNaN()
        ) {
            Coordinates(simulationLatitude, simulationLongitude)
        } else {
            selectedCoordinates()
        }
        val intent = Intent(this, RouteEditorActivity::class.java)
        current?.let {
            intent.putExtra(RouteEditorActivity.EXTRA_START_LAT, it.latitude)
            intent.putExtra(RouteEditorActivity.EXTRA_START_LON, it.longitude)
        }
        routeEditorLauncher.launch(intent)
    }

    private fun startSelectedRoute(routeId: String) {
        if (!MockLocationAccess.isEnabled(this)) {
            showMockLocationDialog()
            return
        }
        if (!hasLocationPermission()) {
            pendingRouteId = routeId
            requestPermissions(PermissionAction.START_ROUTE)
            return
        }
        startRouteService(routeId)
    }

    private fun startRouteService(routeId: String) {
        updateServiceState(ServiceState.STARTING)
        ContextCompat.startForegroundService(
            this,
            MockLocationService.startRouteIntent(this, routeId),
        )
    }

    private fun showLanguageDialog() {
        val languages = listOf(
            "" to R.string.follow_system,
            "zh-TW" to R.string.traditional_chinese,
            "zh-CN" to R.string.simplified_chinese,
            "en" to R.string.english,
            "ja" to R.string.japanese,
            "es" to R.string.spanish,
        )
        val options = languages.map { getString(it.second) }.toTypedArray()
        val currentLanguage = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .lowercase()
        val selectedIndex = languages.indexOfFirst { (tag, _) ->
            tag.isNotEmpty() && (
                currentLanguage == tag.lowercase() ||
                (!tag.contains('-') && currentLanguage.startsWith(tag.lowercase()))
            )
        }.coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(options, selectedIndex) { dialog, index ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(languages[index].first),
                )
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestPermissions(action: PermissionAction) {
        pendingPermissionAction = action
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    /**
     * 把地圖移到手機「現在的真實位置」。
     * 先用夠新的最後已知位置（2 分鐘內）；沒有就要一次新的定位（最多等 10 秒）。模擬位置不算真實位置，會被排除。
     * [quiet] 為 true（啟動時自動定位）時，失敗改由 [showStartupLocationHint] 顯示簡短提示，並在同一次啟動只提示一次。
     */
    private fun centerOnDeviceLocation(quiet: Boolean = false) {
        // 模擬開著時 Android 只會回傳模擬位置，讀不到真實位置；手動按「目前位置」時直接說明原因。
        if (!quiet && MockLocationPreferences.isActive(this)) {
            showMessage(getString(R.string.current_location_blocked_by_mock))
            return
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val lastKnown = runCatching {
            locationManager.getProviders(true)
                .asSequence()
                .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                .filterNot { it.isFromMockProvider }
                .maxByOrNull { it.time }
        }.getOrNull()
        val fresh = lastKnown != null && System.currentTimeMillis() - lastKnown.time <= FRESH_LOCATION_MAX_AGE_MS
        if (fresh) {
            moveToDevice(lastKnown!!)
            return
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }
        fun giveUp() {
            if (quiet) {
                // 啟動時無聲失敗會讓使用者以為定位壞了；依當下狀態顯示原因（取不到新位置就算逾時）。
                showStartupLocationHint(
                    LocationHintPolicy.resolve(
                        isMocking = MockLocationPreferences.isActive(this),
                        hasPermission = hasLocationPermission(),
                        locationServicesEnabled = providers.isNotEmpty(),
                        timedOut = true,
                    ),
                )
                lastKnown?.let(::moveToDevice)
                return
            }
            when {
                lastKnown != null -> moveToDevice(lastKnown)
                MockLocationPreferences.isActive(this) -> showMessage(getString(R.string.current_location_blocked_by_mock))
                else -> showMessage(getString(R.string.current_location_unavailable))
            }
        }
        if (providers.isEmpty()) {
            giveUp()
            return
        }
        var finished = false
        val cancellation = android.os.CancellationSignal()
        val timeout = Runnable {
            if (!finished) {
                finished = true
                cancellation.cancel()
                giveUp()
            }
        }
        val deliver = { location: android.location.Location? ->
            if (!finished && location != null && !location.isFromMockProvider) {
                finished = true
                binding.root.removeCallbacks(timeout)
                moveToDevice(location)
            }
        }
        binding.root.postDelayed(timeout, FRESH_LOCATION_TIMEOUT_MS)
        providers.forEach { provider ->
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(this)) { deliver(it) }
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(
                        provider,
                        object : android.location.LocationListener {
                            override fun onLocationChanged(location: android.location.Location) = deliver(location)
                            @Deprecated("Deprecated in Java")
                            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) = Unit
                        },
                        Looper.getMainLooper(),
                    )
                }
            }
        }
    }

    private fun moveToDevice(location: android.location.Location) {
        moveMapTo(Coordinates(location.latitude, location.longitude), binding.map.zoomLevelDouble, null)
    }

    /**
     * 啟動時自動定位到現在位置：模擬進行中就維持模擬的位置，並提示地圖顯示的是模擬位置；
     * 還沒授權定位的話只在「第一次啟動」問一次，之後以 Snackbar 提示可手動授權。
     */
    private fun centerOnCurrentLocationAtStart() {
        val hint = LocationHintPolicy.resolve(
            isMocking = MockLocationPreferences.isActive(this),
            hasPermission = hasLocationPermission(),
            locationServicesEnabled = isLocationServiceEnabled(),
            timedOut = false,
        )
        when (hint) {
            LocationHint.MOCK_SIMULATION, LocationHint.LOCATION_SERVICES_DISABLED ->
                showStartupLocationHint(hint)

            LocationHint.MISSING_PERMISSION -> {
                val preferences = getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
                if (preferences.getBoolean(KEY_STARTUP_LOCATION_ASKED, false)) {
                    showStartupLocationHint(hint)
                } else {
                    preferences.edit().putBoolean(KEY_STARTUP_LOCATION_ASKED, true).apply()
                    startupPermissionRequestPending = true
                    requestPermissions(PermissionAction.CENTER_ON_DEVICE)
                }
            }

            LocationHint.NONE -> centerOnDeviceLocation(quiet = true)
            LocationHint.TIMEOUT -> Unit
        }
    }

    /** 顯示啟動定位的提示；同一次啟動只顯示一次，避免逾時和權限結果重複跳出。 */
    private fun showStartupLocationHint(hint: LocationHint) {
        if (hint == LocationHint.NONE || startupLocationHintShown) return
        startupLocationHintShown = true
        when (hint) {
            LocationHint.MOCK_SIMULATION ->
                showMessage(getString(R.string.startup_location_mock_hint))

            LocationHint.MISSING_PERMISSION -> showMessage(
                getString(R.string.startup_location_permission_missing),
                R.string.grant_location_permission,
            ) { requestPermissions(PermissionAction.CENTER_ON_DEVICE) }

            LocationHint.LOCATION_SERVICES_DISABLED -> showMessage(
                getString(R.string.startup_location_services_disabled),
                R.string.open_location_settings,
            ) { openLocationSettings() }

            LocationHint.TIMEOUT -> showMessage(getString(R.string.startup_location_timeout))
            LocationHint.NONE -> Unit
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val locationManager = getSystemService(LocationManager::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(false)
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    private fun applyInputCoordinates(showError: Boolean = true): Boolean {
        val coordinates = selectedCoordinates(showError) ?: return false
        moveMapTo(coordinates, binding.map.zoomLevelDouble, null)
        MockLocationPreferences.saveCoordinates(this, coordinates)
        binding.latitudeLayout.error = null
        binding.longitudeLayout.error = null
        hideKeyboard()
        return true
    }

    private fun selectedCoordinates(showError: Boolean = false): Coordinates? {
        val coordinates = Coordinates.parse(
            binding.latitudeInput.text?.toString().orEmpty(),
            binding.longitudeInput.text?.toString().orEmpty(),
        )
        if (coordinates == null && showError) {
            binding.latitudeLayout.error = getString(R.string.invalid_coordinates)
            binding.longitudeLayout.error = " "
        }
        return coordinates
    }

    private fun updateInputsFromMap() {
        if (binding.latitudeInput.hasFocus() || binding.longitudeInput.hasFocus()) return
        if (isLocationLocked()) return
        // 路線播放且地圖跟隨中：座標欄由模擬位置決定（updatePlaybackPanel），拖動中的地圖不能蓋掉它。
        if (binding.playbackPanel.visibility == View.VISIBLE && followSimulation) return
        val center = binding.map.mapCenter
        showCoordinates(Coordinates(center.latitude, center.longitude))
    }

    private fun showCoordinates(coordinates: Coordinates) {
        binding.latitudeInput.setText(coordinates.formattedLatitude())
        binding.longitudeInput.setText(coordinates.formattedLongitude())
        binding.latitudeLayout.error = null
        binding.longitudeLayout.error = null
    }

    private fun showRegionSearch() {
        RegionSearchDialog(this, geocoder) { result -> selectGeocodingResult(result) }.show()
    }

    private fun selectGeocodingResult(result: GeocodingResult) {
        moveMapTo(result.coordinates, result.zoomLevel, result.name)
        showMessage(getString(R.string.selected_region, result.name))
    }

    private fun moveMapTo(coordinates: Coordinates, zoom: Double, placeName: String?) {
        showCoordinates(coordinates)
        binding.map.controller.setZoom(zoom.coerceIn(binding.map.minZoomLevel, binding.map.maxZoomLevel))
        binding.map.controller.animateTo(GeoPoint(coordinates.latitude, coordinates.longitude))
        setSelectedPlaceName(placeName)
        if (placeName != null) {
            binding.root.postDelayed({ setSelectedPlaceName(placeName) }, MAP_ANIMATION_MS)
        }
    }

    private fun setSelectedPlaceName(name: String?) {
        selectedPlaceName = name
        binding.screenSubtitle.text = name ?: getString(R.string.screen_subtitle)
    }

    private fun clearSelectedPlaceName() {
        if (selectedPlaceName != null) setSelectedPlaceName(null)
    }

    private fun showSaveFavorite() {
        val coordinates = selectedCoordinates(showError = true) ?: return
        val existingFavorite = favoriteRepository.all().any { it.coordinates == coordinates }
        if (!existingFavorite && favoriteRepository.all().size >= entitlementRepository.favoriteLimit()) {
            showMessage(getString(R.string.favorite_limit_reached))
            return
        }
        val favoriteBinding = DialogSaveFavoriteBinding.inflate(layoutInflater)
        favoriteBinding.groupLayout.visibility = if (
            entitlementRepository.canUse(ProductFeature.FAVORITE_GROUPS)
        ) View.VISIBLE else View.GONE
        val fallbackName = getString(
            R.string.coordinate_favorite_name,
            coordinates.formattedLatitude(),
            coordinates.formattedLongitude(),
        )
        favoriteBinding.nameInput.setText(selectedPlaceName ?: fallbackName)
        favoriteBinding.nameInput.selectAll()
        favoriteBinding.coordinateText.text = getString(
            R.string.coordinates_format,
            coordinates.formattedLatitude(),
            coordinates.formattedLongitude(),
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorite_current)
            .setView(favoriteBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = favoriteBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    favoriteBinding.nameLayout.error = getString(R.string.favorite_name_required)
                    return@setOnClickListener
                }
                favoriteRepository.save(
                    name,
                    favoriteBinding.groupInput.text?.toString().orEmpty(),
                    coordinates,
                )
                setSelectedPlaceName(name)
                dialog.dismiss()
                showMessage(getString(R.string.favorite_saved, name))
            }
        }
        dialog.show()
    }

    private fun showFavorites() {
        val favoritesBinding = DialogFavoritesBinding.inflate(layoutInflater)
        favoritesBinding.searchLayout.visibility = if (
            entitlementRepository.canUse(ProductFeature.FAVORITE_GROUPS)
        ) View.VISIBLE else View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.favorites)
            .setView(favoritesBinding.root)
            .setNegativeButton(R.string.close, null)
            .create()

        fun renderFavorites() {
            val query = favoritesBinding.searchInput.text?.toString()?.trim().orEmpty()
            val favorites = favoriteRepository.all().filter { favorite ->
                query.isBlank() || favorite.name.contains(query, ignoreCase = true) ||
                    favorite.group.contains(query, ignoreCase = true) ||
                    favorite.coordinates.formattedLatitude().contains(query) ||
                    favorite.coordinates.formattedLongitude().contains(query)
            }
            favoritesBinding.emptyText.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE
            favoritesBinding.favoritesScroll.visibility =
                if (favorites.isEmpty()) View.GONE else View.VISIBLE
            favoritesBinding.favoritesContainer.removeAllViews()
            favorites.forEachIndexed { index, favorite ->
                val itemBinding = ItemFavoriteBinding.inflate(
                    layoutInflater,
                    favoritesBinding.favoritesContainer,
                    false,
                )
                itemBinding.nameText.text = favorite.name
                itemBinding.groupText.text = favorite.group
                itemBinding.groupText.visibility =
                    if (favorite.group.isBlank()) View.GONE else View.VISIBLE
                itemBinding.coordinateText.text = getString(
                    R.string.coordinates_format,
                    favorite.coordinates.formattedLatitude(),
                    favorite.coordinates.formattedLongitude(),
                )
                itemBinding.useButton.contentDescription =
                    getString(R.string.use_favorite, favorite.name)
                itemBinding.deleteButton.contentDescription =
                    getString(R.string.delete_favorite, favorite.name)
                itemBinding.root.setOnClickListener {
                    useFavorite(favorite, startImmediately = false)
                    dialog.dismiss()
                }
                itemBinding.useButton.setOnClickListener {
                    useFavorite(favorite, startImmediately = true)
                    dialog.dismiss()
                }
                itemBinding.deleteButton.setOnClickListener {
                    confirmDeleteFavorite(favorite) { renderFavorites() }
                }
                favoritesBinding.favoritesContainer.addView(itemBinding.root)
                if (index < favorites.lastIndex) {
                    favoritesBinding.favoritesContainer.addView(View(this).apply {
                        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply {
                            marginStart = dp(12)
                            marginEnd = dp(12)
                        }
                    })
                }
            }
        }

        renderFavorites()
        favoritesBinding.searchInput.doAfterTextChanged { renderFavorites() }
        dialog.show()
    }

    private fun useFavorite(favorite: FavoriteLocation, startImmediately: Boolean) {
        moveMapTo(favorite.coordinates, FAVORITE_ZOOM, favorite.name)
        if (startImmediately) binding.root.post { startSelectedLocation() }
    }

    private fun confirmDeleteFavorite(favorite: FavoriteLocation, onDeleted: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_favorite_title)
            .setMessage(getString(R.string.delete_favorite_message, favorite.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                favoriteRepository.delete(favorite.id)
                onDeleted()
                showMessage(getString(R.string.favorite_deleted))
            }
            .show()
    }

    private var playbackPaused = false
    private var playbackRate = 1

    // 地圖是否跟著模擬位置走：路線開始播放時預設開啟；使用者自己拖曳地圖就暫停，按「跟隨」再回到模擬位置。
    private var followSimulation = true
    private var simulationLatitude = Double.NaN
    private var simulationLongitude = Double.NaN

    // App 在前景時才更新地圖；背景時模擬服務照常運作，回到前景再一次補畫。
    private var mapActive = false
    private var lastFollowPanMs = 0L
    private var lastTrailInvalidateMs = 0L

    // 路線播放時在地圖上畫出已走過的軌跡；播放結束後保留，下一次開始播放才清掉。
    // simulationTrailPoints 保留完整點列（不丟舊點）；畫面用的 Polyline 太長時抽稀。
    private var simulationTrail: Polyline? = null
    private val simulationTrailPoints = ArrayList<GeoPoint>()
    private var trailPointsSinceRebuild = 0
    private var simulationMarker: org.osmdroid.views.overlay.Marker? = null
    private var panelDetailsCollapsed = false

    private val trailInvalidateRunnable = Runnable {
        if (!mapActive) return@Runnable
        lastTrailInvalidateMs = SystemClock.elapsedRealtime()
        binding.map.invalidate()
    }

    private fun clearSimulationTrail() {
        simulationTrailPoints.clear()
        trailPointsSinceRebuild = 0
        simulationTrail?.let { binding.map.overlays.remove(it) }
        simulationTrail = null
        clearSimulationMarker()
        requestTrailRedraw(force = true)
    }

    private fun appendSimulationTrail(lat: Double, lon: Double) {
        val point = GeoPoint(lat, lon)
        val last = simulationTrailPoints.lastOrNull()
        // 暫停或停留時位置不動，不重複加點；完整點列不設上限，避免長路線的舊段落消失。
        if (last != null && last.distanceToAsDouble(point) < TRAIL_MIN_STEP_METERS) return
        simulationTrailPoints += point
        trailPointsSinceRebuild++
        if (!mapActive) return

        val trailWasCreated = simulationTrail == null
        val trail = ensureSimulationTrail()
        val needsDecimation = simulationTrailPoints.size > TRAIL_DISPLAY_MAX_POINTS &&
            trailPointsSinceRebuild >= TRAIL_REBUILD_EVERY_POINTS
        if (trailWasCreated || needsDecimation) {
            // 新建立或累積到需要抽稀時才整條重設；其餘情況只 addPoint。
            val displayed = if (simulationTrailPoints.size > TRAIL_DISPLAY_MAX_POINTS) {
                TrailDecimator.decimate(simulationTrailPoints, TRAIL_DISPLAY_MAX_POINTS)
            } else {
                simulationTrailPoints
            }
            trail.setPoints(ArrayList(displayed))
            trailPointsSinceRebuild = 0
        } else {
            trail.addPoint(point)
        }
        requestTrailRedraw()
    }

    private fun ensureSimulationTrail(): Polyline =
        simulationTrail ?: Polyline(binding.map).apply {
            outlinePaint.color = getColor(R.color.accent)
            outlinePaint.strokeWidth = dp(5).toFloat()
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            simulationTrail = this
            binding.map.overlays.add(this)
        }

    /** 目前模擬位置的大頭針；跟隨時固定大頭針會藏在頂欄下方，改用會跟著座標移動的標記。 */
    private fun updateSimulationMarker(lat: Double, lon: Double) {
        if (!mapActive) return
        val marker = simulationMarker ?: org.osmdroid.views.overlay.Marker(binding.map).apply {
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_map_pin)
            setAnchor(
                org.osmdroid.views.overlay.Marker.ANCHOR_CENTER,
                org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM,
            )
            setOnMarkerClickListener { _, _ -> true }
            simulationMarker = this
            binding.map.overlays.add(this)
        }
        marker.position = GeoPoint(lat, lon)
        binding.centerPin.visibility = View.INVISIBLE
        requestTrailRedraw()
    }

    private fun clearSimulationMarker() {
        simulationMarker?.let { binding.map.overlays.remove(it) }
        simulationMarker = null
        refreshLockMarker()
    }

    /** 回到前景時用完整點列補畫一次；長軌跡只抽稀顯示，不會丟掉舊點。 */
    private fun rebuildSimulationTrailForMap() {
        if (!mapActive || simulationTrailPoints.isEmpty()) return
        val displayed = if (simulationTrailPoints.size > TRAIL_DISPLAY_MAX_POINTS) {
            TrailDecimator.decimate(simulationTrailPoints, TRAIL_DISPLAY_MAX_POINTS)
        } else {
            simulationTrailPoints
        }
        ensureSimulationTrail().setPoints(ArrayList(displayed))
        trailPointsSinceRebuild = 0
    }

    private fun requestTrailRedraw(force: Boolean = false) {
        if (!mapActive) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTrailInvalidateMs
        if (force || elapsed >= TRAIL_REDRAW_INTERVAL_MS) {
            lastTrailInvalidateMs = now
            binding.map.removeCallbacks(trailInvalidateRunnable)
            binding.map.invalidate()
        } else {
            binding.map.removeCallbacks(trailInvalidateRunnable)
            binding.map.postDelayed(trailInvalidateRunnable, TRAIL_REDRAW_INTERVAL_MS - elapsed)
        }
    }

    /** 回到前景：補畫軌跡，跟隨開啟時把鏡頭帶回目前模擬位置（只移動一次，不改縮放）。 */
    private fun refreshMapAfterResume() {
        // 背景期間服務仍會把最新位置寫進偏好設定；回到前景先用它補上目前位置。
        val routeActive = MockLocationPreferences.activeRouteId(this) != null
        if (routeActive) {
            val current = MockLocationPreferences.coordinates(this)
            simulationLatitude = current.latitude
            simulationLongitude = current.longitude
            appendSimulationTrail(current.latitude, current.longitude)
            updateSimulationMarker(current.latitude, current.longitude)
        } else if (binding.playbackPanel.visibility == View.VISIBLE) {
            // 背景期間路線已結束，回到前景把殘留的播放面板收起。
            binding.playbackPanel.visibility = View.GONE
            setPanelDetailsCollapsed(false)
            clearSimulationMarker()
        }
        rebuildSimulationTrailForMap()
        if (routeActive && followSimulation && !simulationLatitude.isNaN() && !simulationLongitude.isNaN()) {
            binding.map.controller.animateTo(GeoPoint(simulationLatitude, simulationLongitude))
            lastFollowPanMs = SystemClock.elapsedRealtime()
        }
        lastTrailInvalidateMs = SystemClock.elapsedRealtime()
        binding.map.invalidate()
    }

    /** 跟隨模式只在這一瞬間的大頭針進入外圍 15% 時平移，避免每秒移動鏡頭。 */
    private fun followSimulationPosition(lat: Double, lon: Double) {
        if (!mapActive) return
        if (binding.map.width <= 0 || binding.map.height <= 0) return
        val point = GeoPoint(lat, lon)
        val screenPoint = Point()
        binding.map.projection.toPixels(point, screenPoint)
        if (!MapFollowPolicy.shouldRecenter(
                screenPoint.x.toFloat(),
                screenPoint.y.toFloat(),
                binding.root.width,
                binding.root.height,
                MAP_FOLLOW_EDGE_FRACTION,
            )
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFollowPanMs < MAP_FOLLOW_PAN_COOLDOWN_MS) return
        lastFollowPanMs = now
        binding.map.controller.animateTo(point)
    }

    private fun setFollowSimulation(follow: Boolean) {
        followSimulation = follow
        binding.playbackFollowButton.setText(if (follow) R.string.playback_follow_on else R.string.playback_follow_off)
        if (follow && !simulationLatitude.isNaN() && !simulationLongitude.isNaN()) {
            lastFollowPanMs = SystemClock.elapsedRealtime()
            binding.map.controller.animateTo(GeoPoint(simulationLatitude, simulationLongitude))
        }
    }

    /** 路線播放中的進度列：進度、已播放／剩餘時間、目前速度，以及暫停和倍速按鈕。 */
    private fun updatePlaybackPanel(intent: Intent) {
        val elapsed = intent.getDoubleExtra(MockLocationService.EXTRA_ROUTE_ELAPSED, 0.0)
        val total = intent.getDoubleExtra(MockLocationService.EXTRA_ROUTE_TOTAL, -1.0)
        val speedKmh = intent.getDoubleExtra(MockLocationService.EXTRA_ROUTE_SPEED_KMH, 0.0)
        val progress = intent.getDoubleExtra(MockLocationService.EXTRA_ROUTE_PROGRESS, 0.0)
        playbackPaused = intent.getBooleanExtra(MockLocationService.EXTRA_ROUTE_PAUSED, false)
        playbackRate = intent.getIntExtra(MockLocationService.EXTRA_ROUTE_RATE, 1)
        val panelWasHidden = binding.playbackPanel.visibility != View.VISIBLE
        binding.playbackPanel.visibility = View.VISIBLE
        // 路線播放中，「更新位置」會停掉路線改成固定位置：按鈕文字要讓人看得出來。
        binding.startButton.setText(R.string.switch_to_static)
        if (panelWasHidden) {
            followSimulation = true
            lastFollowPanMs = 0L
            setPanelDetailsCollapsed(true)
            clearSimulationTrail()
        }
        val lat = intent.getDoubleExtra(MockLocationService.EXTRA_POSITION_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(MockLocationService.EXTRA_POSITION_LON, Double.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
            simulationLatitude = lat
            simulationLongitude = lon
            appendSimulationTrail(lat, lon)
            updateSimulationMarker(lat, lon)
            // 跟隨開啟時，座標欄顯示「模擬走到的位置」，不是地圖碰巧停在哪裡；關閉跟隨（自己拖地圖）才改回地圖中央。
            if (followSimulation && !binding.latitudeInput.hasFocus() && !binding.longitudeInput.hasFocus()) {
                showCoordinates(Coordinates(lat, lon))
            }
            if (followSimulation) followSimulationPosition(lat, lon)
        }
        binding.playbackFollowButton.setText(
            if (followSimulation) R.string.playback_follow_on else R.string.playback_follow_off,
        )
        binding.playbackProgress.max = 1000
        binding.playbackProgress.setProgressCompat((progress * 1000).toInt().coerceIn(0, 1000), true)
        val speed = Math.round(speedKmh).toInt()
        var info = if (total > 0) {
            getString(
                R.string.playback_info_total,
                formatPlaybackClock(elapsed),
                formatPlaybackClock(total),
                formatPlaybackClock((total - elapsed).coerceAtLeast(0.0)),
                speed,
            )
        } else {
            getString(R.string.playback_info_open, formatPlaybackClock(elapsed), speed)
        }
        if (playbackPaused) info += " · " + getString(R.string.playback_paused)
        binding.playbackInfo.text = info
        binding.playbackPauseButton.setText(if (playbackPaused) R.string.playback_resume else R.string.playback_pause)
        binding.playbackRateButton.text = getString(R.string.playback_rate, playbackRate)
    }

    private fun formatPlaybackClock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    private fun configurePlaybackControls() {
        binding.playbackFollowButton.setOnClickListener { setFollowSimulation(!followSimulation) }
        binding.playbackPauseButton.setOnClickListener {
            startService(
                if (playbackPaused) MockLocationService.resumeIntent(this) else MockLocationService.pauseIntent(this),
            )
        }
        binding.playbackRateButton.setOnClickListener {
            val rates = intArrayOf(1, 2, 4, 8)
            val next = rates[(rates.indexOf(playbackRate) + 1).mod(rates.size)]
            startService(MockLocationService.rateIntent(this, next))
        }
    }

    private fun updateServiceState(state: ServiceState) {
        refreshLockMarkerSoon()
        when (state) {
            ServiceState.STOPPED -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_stopped)
                binding.statusText.setText(R.string.status_stopped)
                binding.startButton.setText(R.string.start_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = false
            }

            ServiceState.STARTING -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_stopped)
                binding.statusText.setText(R.string.status_starting)
                binding.startButton.isEnabled = false
                binding.stopButton.isEnabled = true
            }

            ServiceState.RUNNING -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_running)
                binding.statusText.setText(R.string.status_running)
                binding.startButton.setText(R.string.update_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = true
            }

            ServiceState.ERROR -> {
                binding.statusDot.setBackgroundResource(R.drawable.bg_status_error)
                binding.statusText.setText(R.string.status_error)
                binding.startButton.setText(R.string.start_mock)
                binding.startButton.isEnabled = true
                binding.stopButton.isEnabled = false
            }
        }
    }

    // 狀態廣播和偏好寫入可能差一點點，延後一拍再依最新狀態重畫標記。
    private fun refreshLockMarkerSoon() {
        refreshLockMarker()
        binding.root.postDelayed({ refreshLockMarker() }, 600L)
    }

    private fun showMessage(
        message: String,
        actionTextResId: Int? = null,
        action: (() -> Unit)? = null,
    ) {
        val snackbar = Snackbar.make(binding.controlPanel, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.controlPanel)
        if (actionTextResId != null && action != null) {
            snackbar.setAction(actionTextResId) { action() }
        }
        snackbar.show()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class PermissionAction {
        START_MOCK,
        CENTER_ON_DEVICE,
        START_ROUTE,
    }

    private enum class ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR,
    }

    private data class TeamTemplateSelectionItem(
        val id: String,
        val type: TeamTemplateItemType,
        val label: String,
    )

    private enum class TeamTemplateItemType {
        FAVORITE,
        ROUTE,
    }

    companion object {
        private const val MAP_ANIMATION_MS = 600L
        private const val FAVORITE_ZOOM = 16.0
        private const val ONBOARDING_PREFERENCES = "onboarding"
        private const val KEY_SETUP_GUIDE_SHOWN = "mock_location_setup_guide_shown"
        private const val KEY_STARTUP_LOCATION_ASKED = "startup_location_permission_asked"
        private const val FRESH_LOCATION_MAX_AGE_MS = 2 * 60 * 1000L
        private const val FRESH_LOCATION_TIMEOUT_MS = 10_000L
        private const val STATE_TEAM_TEMPLATE_FAVORITE_IDS = "team_template_favorite_ids"
        private const val STATE_TEAM_TEMPLATE_ROUTE_IDS = "team_template_route_ids"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_UPDATE_INTERVAL_HOURS = "update_interval_hours"
        private const val DEFAULT_UPDATE_INTERVAL_HOURS = 24
        private const val TRAIL_MIN_STEP_METERS = 2.0
        private const val TRAIL_DISPLAY_MAX_POINTS = TrailDecimator.DEFAULT_MAX_POINTS
        private const val TRAIL_REBUILD_EVERY_POINTS = 500
        private const val TRAIL_REDRAW_INTERVAL_MS = 1000L
        private const val MAP_FOLLOW_EDGE_FRACTION = MapFollowPolicy.DEFAULT_EDGE_FRACTION
        private const val MAP_FOLLOW_PAN_COOLDOWN_MS = MAP_ANIMATION_MS
    }
}

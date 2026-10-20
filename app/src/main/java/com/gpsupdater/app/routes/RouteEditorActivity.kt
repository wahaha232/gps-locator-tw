package com.gpsupdater.app.routes

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.R
import com.gpsupdater.app.databinding.ActivityRouteEditorBinding
import com.gpsupdater.app.databinding.DialogWaypointBinding
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.scan.GpxScanActivity
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.geocoding.MapServiceConfiguration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

class RouteEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRouteEditorBinding
    private val repository by lazy { RouteRepository(this) }
    private val waypoints = mutableListOf<RouteWaypoint>()
    private val routeOverlays = mutableListOf<Overlay>()
    private val outages = mutableListOf<RouteOutage>()
    private var editingRouteId: String? = null
    private var editingCreatedAt = System.currentTimeMillis()
    private var pendingExportContent: String? = null

    private val importExecutor = Executors.newSingleThreadExecutor()
    private val importCancelled = AtomicBoolean(false)

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importFromUri(uri)
    }

    /** 從「掃描手機中的 GPX」挑一個檔案回來：走同一條匯入流程（背景串流、可取消）。 */
    private val scanGpxLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.data?.takeIf { result.resultCode == RESULT_OK }?.let(::importFromUri)
    }

    private fun importFromUri(uri: android.net.Uri) {
        val fallbackName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            .orEmpty()
            .ifBlank { getString(R.string.new_route_name) }
        importCancelled.set(false)
        val progress = showImportProgress()
        // 大檔案（幾十 MB、十萬點以上的 GPX）要邊讀邊簡化，放在背景執行，畫面才不會卡住；使用者可以取消。
        importExecutor.execute {
            val result = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    LocationDocumentCodec.importStream(input, fallbackName) { importCancelled.get() }
                } ?: error("Empty document")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                progress.dismiss()
                result.onSuccess { imported ->
                    repository.save(imported.route)
                    loadRoute(imported.route)
                    showMessage(
                        if (imported.wasSimplified) {
                            getString(R.string.route_import_simplified, imported.originalPointCount, imported.keptPointCount)
                        } else {
                            getString(R.string.route_imported)
                        },
                    )
                }.onFailure { error ->
                    if (error is ImportCancelledException) {
                        showMessage(getString(R.string.route_import_cancelled))
                    } else {
                        android.util.Log.e("GpsLocatorImport", "route import failed: $uri", error)
                        val reason = (error.message ?: error.javaClass.simpleName).lineSequence().first().take(120)
                        showMessage(getString(R.string.route_import_failed_reason, reason))
                    }
                }
            }
        }
    }

    private fun showImportProgress(): androidx.appcompat.app.AlertDialog {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        container.addView(
            com.google.android.material.progressindicator.CircularProgressIndicator(this).apply { isIndeterminate = true },
        )
        container.addView(
            android.widget.TextView(this).apply {
                setText(R.string.route_importing)
                setPadding(dp(16), 0, 0, 0)
            },
        )
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_route)
            .setView(container)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> importCancelled.set(true) }
            .show()
    }

    private val exportDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml"),
    ) { uri ->
        val content = pendingExportContent
        pendingExportContent = null
        if (uri == null || content == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                ?: error("Could not open document")
        }.onSuccess {
            showMessage(getString(R.string.route_exported))
        }.onFailure {
            showMessage(getString(R.string.route_export_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        binding = ActivityRouteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configureSystemBars()
        configureMap()
        configureControls()
        configureFloatingControls()
        binding.routeNameInput.setText(getString(R.string.new_route_name))
        updateEditorState()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        importCancelled.set(true)
        importExecutor.shutdownNow()
        binding.map.onDetach()
        super.onDestroy()
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = dp(12) + bars.top
            }
            binding.controlPanel.updatePadding(bottom = dp(18) + bars.bottom)
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = dp(318) + bars.bottom
            }
            insets
        }
    }

    /** 主畫面交過來的目前標記位置；沒有就用最後儲存的位置。 */
    private var startCoordinates: Coordinates = Coordinates.DEFAULT

    // 鎖定：在地圖上留一個固定標記當隨機生成軌跡的起點，拖地圖時標記跟著地圖走，座標不變。
    private var locked = false
    private var lockMarker: Marker? = null

    private fun setLocked(lock: Boolean) {
        locked = lock
        if (lock) {
            val center = binding.map.mapCenter
            startCoordinates = Coordinates(center.latitude, center.longitude)
        }
        refreshLockMarker()
    }

    private fun refreshLockMarker() {
        lockMarker?.let { binding.map.overlays.remove(it) }
        lockMarker = null
        if (locked) {
            lockMarker = Marker(binding.map).apply {
                position = GeoPoint(startCoordinates.latitude, startCoordinates.longitude)
                icon = androidx.core.content.ContextCompat.getDrawable(this@RouteEditorActivity, R.drawable.ic_map_pin)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { _, _ -> true }
            }.also { binding.map.overlays.add(it) }
        }
        binding.lockButton.setText(if (locked) R.string.unlock_here else R.string.lock_here)
        binding.map.invalidate()
    }
    private fun resolveStartCoordinates(): Coordinates {
        val lat = intent.getDoubleExtra(EXTRA_START_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_START_LON, Double.NaN)
        return if (!lat.isNaN() && !lon.isNaN()) Coordinates(lat, lon) else MockLocationPreferences.coordinates(this)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) centerOnDeviceLocation() else showMessage(getString(R.string.current_location_unavailable))
    }

    private fun hasLocationPermission(): Boolean =
        listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION).any {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** 「目前位置」：取得手機的真實位置，地圖移過去，之後隨機生成軌跡也從這裡出發。模擬進行中讀不到真實位置。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun centerOnDeviceLocation() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            return
        }
        val manager = getSystemService(android.location.LocationManager::class.java)
        fun moveTo(location: android.location.Location) {
            startCoordinates = Coordinates(location.latitude, location.longitude)
            if (locked) refreshLockMarker()
            binding.map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        }
        fun giveUp() = showMessage(
            getString(
                if (MockLocationPreferences.isActive(this)) R.string.current_location_blocked_by_mock
                else R.string.current_location_unavailable,
            ),
        )
        val lastKnown = runCatching {
            manager.getProviders(true)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .filterNot { it.isFromMockProvider }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time <= 2 * 60 * 1000L) {
            moveTo(lastKnown)
            return
        }
        val providers = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty() || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            lastKnown?.let(::moveTo) ?: giveUp()
            return
        }
        var finished = false
        val cancellation = android.os.CancellationSignal()
        val timeout = Runnable {
            if (!finished) {
                finished = true
                cancellation.cancel()
                lastKnown?.let(::moveTo) ?: giveUp()
            }
        }
        binding.root.postDelayed(timeout, 10_000L)
        providers.forEach { provider ->
            runCatching {
                manager.getCurrentLocation(provider, cancellation, androidx.core.content.ContextCompat.getMainExecutor(this)) { location ->
                    if (!finished && location != null && !location.isFromMockProvider) {
                        finished = true
                        binding.root.removeCallbacks(timeout)
                        moveTo(location)
                    }
                }
            }
        }
    }

    private fun configureMap() {
        startCoordinates = resolveStartCoordinates()
        val initial = startCoordinates
        binding.map.apply {
            setTileSource(MapServiceConfiguration.tileSource())
            setMultiTouchControls(true)
            isTilesScaledToDpi = false
            minZoomLevel = 3.0
            maxZoomLevel = 20.0
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(initial.latitude, initial.longitude))
            overlays.add(
                MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(point: GeoPoint): Boolean {
                        controller.animateTo(point)
                        return true
                    }

                    override fun longPressHelper(point: GeoPoint): Boolean {
                        // 長按地圖直接在該處加途經點；單點仍是把地圖移過去。
                        addWaypointAt(Coordinates(point.latitude, point.longitude))
                        return true
                    }
                }),
            )
        }
        binding.attributionText.text = BuildConfig.MAP_ATTRIBUTION
    }

    private fun configureControls() {
        binding.backButton.setOnClickListener { finish() }
        binding.savedRoutesButton.setOnClickListener { showSavedRoutes() }
        binding.sampleRouteButton.setOnClickListener { loadSampleRoute() }
        binding.zoomInButton.setOnClickListener { binding.map.controller.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.map.controller.zoomOut() }
        binding.generateRouteButton.setOnClickListener { showRandomRouteDialog() }
        binding.scanGpxButton.setOnClickListener { scanGpxLauncher.launch(GpxScanActivity.intent(this)) }
        binding.addWaypointButton.setOnClickListener {
            val center = binding.map.mapCenter
            addWaypointAt(Coordinates(center.latitude, center.longitude))
        }
        binding.undoButton.setOnClickListener {
            if (waypoints.isNotEmpty()) {
                waypoints.removeAt(waypoints.lastIndex)
                updateEditorState()
            }
        }
        binding.speedSlider.addOnChangeListener { _, value, _ ->
            binding.speedText.text = getString(R.string.route_speed, value.toInt())
            highlightSpeedPreset()
        }
        // 速度預設值：走路 5、跑步 10、騎車 18、開車 40 公里/小時；選完仍可用滑桿微調。
        binding.presetWalkButton.setOnClickListener { binding.speedSlider.value = SPEED_PRESET_WALK_KPH }
        binding.presetRunButton.setOnClickListener { binding.speedSlider.value = SPEED_PRESET_RUN_KPH }
        binding.presetBikeButton.setOnClickListener { binding.speedSlider.value = SPEED_PRESET_BIKE_KPH }
        binding.presetCarButton.setOnClickListener { binding.speedSlider.value = SPEED_PRESET_CAR_KPH }
        binding.timingSwitch.setOnCheckedChangeListener { _, _ -> refreshTimingUi() }
        binding.outageButton.setOnClickListener { showOutages() }
        binding.saveStartButton.setOnClickListener { saveAndStart() }
    }

    private fun configureFloatingControls() {
        binding.controlPanel.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val panelHeight = bottom - top
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(16)
            }
            binding.myLocationButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(16)
            }
            binding.lockButton.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = panelHeight + dp(16) + dp(60)
            }
            // 地圖只佔面板上方的空間，這樣大頭針（地圖中央）不會被面板蓋住，「新增地圖中心」才對得準。
            val mapHeightChanged = (binding.map.layoutParams as FrameLayout.LayoutParams).bottomMargin != panelHeight
            if (mapHeightChanged) {
                binding.map.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = panelHeight }
                binding.mapPin.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = panelHeight }
                binding.zoomControls.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = panelHeight }
            }
        }
        binding.myLocationButton.setOnClickListener { centerOnDeviceLocation() }
        binding.lockButton.setOnClickListener { setLocked(!locked) }
        binding.panelHandleArea.setOnClickListener { setPanelExpanded(!panelExpanded) }
        setPanelExpanded(false)
    }

    // 預設收合：只留新增／復原途經點與「儲存並開始」，地圖才有足夠空間定位；點面板上方的把手展開速度與名稱設定。
    private var panelExpanded = false

    private fun setPanelExpanded(expanded: Boolean) {
        panelExpanded = expanded
        val visibility = if (expanded) View.VISIBLE else View.GONE
        binding.nameGroup.visibility = visibility
        binding.detailsGroup.visibility = visibility
        binding.panelToggleText.text = if (expanded) "▼" else "▲"
    }

    /** 直接加一個途經點：名稱自動編號、停留 0 秒；要改名稱或停留時間，點地圖上的標記。 */
    private fun addWaypointAt(coordinates: Coordinates) {
        waypoints += RouteWaypoint(
            name = getString(R.string.waypoint_default_name, waypoints.size + 1),
            coordinates = coordinates,
        )
        updateEditorState()
    }

    /** 點標記：改名稱與停留秒數，或刪除這個途經點。 */
    private fun showEditWaypointDialog(index: Int) {
        val waypoint = waypoints.getOrNull(index) ?: return
        val waypointBinding = DialogWaypointBinding.inflate(LayoutInflater.from(this))
        waypointBinding.nameInput.setText(waypoint.name)
        waypointBinding.stopInput.setText(waypoint.stopDurationSeconds.toString())
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.waypoint_edit_title) + " · ${index + 1}")
            .setView(waypointBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                waypoints.removeAt(index)
                updateEditorState()
            }
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = waypointBinding.nameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    waypointBinding.nameLayout.error = getString(R.string.favorite_name_required)
                    return@setOnClickListener
                }
                val stopSeconds = waypointBinding.stopInput.text?.toString()?.toLongOrNull()
                if (stopSeconds == null || stopSeconds !in 0..86_400) {
                    waypointBinding.stopLayout.error = getString(R.string.invalid_stop_duration)
                    return@setOnClickListener
                }
                waypoints[index] = waypoint.copy(name = name, stopDurationSeconds = stopSeconds)
                dialog.dismiss()
                updateEditorState()
            }
        }
        dialog.show()
    }

    private fun loadSampleRoute() {
        val center = binding.map.mapCenter.let { Coordinates(it.latitude, it.longitude) }
        val names = listOf(
            getString(R.string.sample_route_start),
            getString(R.string.sample_route_checkpoint_a),
            getString(R.string.sample_route_checkpoint_b),
            getString(R.string.sample_route_finish),
        )
        editingRouteId = null
        editingCreatedAt = System.currentTimeMillis()
        waypoints.clear()
        waypoints.addAll(SampleRouteFactory.createWaypoints(center, names))
        binding.routeNameInput.setText(R.string.sample_route_name)
        binding.speedSlider.value = SAMPLE_ROUTE_SPEED_KPH
        binding.loopSwitch.isChecked = false
        binding.naturalSwitch.isChecked = false
        outages.clear()
        updateEditorState()
        zoomToWaypoints()
        showMessage(getString(R.string.sample_route_loaded))
    }

    /** 隨機生成軌跡：從地圖中心出發，依點數、範圍、型態（隨機漫步／環形迴路）產生途經點，可指定種子以重現同一條軌跡。 */
    private fun showRandomRouteDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        if (waypoints.isNotEmpty()) {
            container.addView(
                android.widget.TextView(this).apply {
                    setText(R.string.random_route_replace_note)
                    setPadding(0, 0, 0, dp(8))
                },
            )
        }
        // 欄位填了預設值後 hint 會看不見，所以每個欄位上方都放一行標籤。
        fun label(textRes: Int) = container.addView(
            android.widget.TextView(this).apply {
                setText(textRes)
                textSize = 13f
                setPadding(0, dp(8), 0, 0)
            },
        )
        fun field(hintRes: Int, default: String): EditText {
            label(hintRes)
            return EditText(this).apply {
                hint = getString(hintRes)
                setText(default)
                inputType = InputType.TYPE_CLASS_NUMBER
                container.addView(this)
            }
        }
        val points = field(R.string.random_route_points_hint, "30")
        val radius = field(R.string.random_route_radius_hint, "1000")
        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val walk = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.random_route_shape_walk)
            isChecked = true
        }
        val loop = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            setText(R.string.random_route_shape_loop)
        }
        group.addView(walk)
        group.addView(loop)
        container.addView(group)
        label(R.string.random_route_seed_hint)
        val seed = EditText(this).apply {
            hint = getString(R.string.random_route_seed_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            container.addView(this)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.random_route_generate)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (waypoints.isEmpty()) R.string.random_route_action else R.string.random_route_action_replace) { _, _ ->
                val count = points.text.toString().trim().toIntOrNull()
                val meters = radius.text.toString().trim().toIntOrNull()
                val seedValue = seed.text.toString().trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                val valid = count in RandomRouteGenerator.MIN_POINTS..RandomRouteGenerator.MAX_POINTS &&
                    meters in RandomRouteGenerator.MIN_RADIUS_METERS..RandomRouteGenerator.MAX_RADIUS_METERS &&
                    (seed.text.isNullOrBlank() || seedValue != null)
                if (!valid) {
                    showMessage(
                        getString(
                            R.string.random_route_invalid,
                            RandomRouteGenerator.MIN_POINTS, RandomRouteGenerator.MAX_POINTS,
                            RandomRouteGenerator.MIN_RADIUS_METERS, RandomRouteGenerator.MAX_RADIUS_METERS,
                        ),
                    )
                } else {
                    applyRandomRoute(count!!, meters!!, if (loop.isChecked) RandomRouteShape.LOOP else RandomRouteShape.WALK, seedValue)
                }
            }
            .show()
    }

    private fun applyRandomRoute(count: Int, meters: Int, shape: RandomRouteShape, seed: Long?) {
        // 從目前標記的位置出發，不是地圖現在碰巧停在哪裡；使用者拖過地圖也不會跑到別處。
        val center = startCoordinates
        val random = if (seed != null) kotlin.random.Random(seed) else kotlin.random.Random.Default
        val coordinates = RandomRouteGenerator.generate(center, count, meters.toDouble(), shape, random)
        editingRouteId = null
        editingCreatedAt = System.currentTimeMillis()
        waypoints.clear()
        coordinates.forEachIndexed { index, point ->
            waypoints += RouteWaypoint(name = getString(R.string.waypoint_default_name, index + 1), coordinates = point)
        }
        binding.routeNameInput.setText(
            getString(R.string.random_route_name, java.text.SimpleDateFormat("MMdd-HHmm", Locale.US).format(java.util.Date())),
        )
        binding.loopSwitch.isChecked = shape == RandomRouteShape.LOOP
        outages.clear()
        updateEditorState()
        zoomToWaypoints()
        showMessage(getString(R.string.random_route_generated, coordinates.size, meters))
    }

    private fun saveAndStart() {
        val routeName = binding.routeNameInput.text?.toString()?.trim().orEmpty()
        if (routeName.isBlank()) {
            binding.routeNameLayout.error = getString(R.string.route_name_required)
            setPanelExpanded(true)
            return
        }
        val route = runCatching {
            SimulationRoute(
                id = editingRouteId ?: java.util.UUID.randomUUID().toString(),
                name = routeName,
                waypoints = waypoints.toList(),
                speedKph = binding.speedSlider.value.toDouble(),
                loop = binding.loopSwitch.isChecked,
                createdAt = editingCreatedAt,
                useGpxTiming = SimulationRoute.hasTiming(waypoints) && binding.timingSwitch.isChecked,
                outages = outages.toList(),
                naturalSpeed = binding.naturalSwitch.isChecked,
            )
        }.getOrElse {
            showMessage(getString(R.string.route_requires_points))
            return
        }
        repository.save(route)
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_ROUTE_ID, route.id),
        )
        finish()
    }

    private fun updateEditorState() {
        binding.waypointCountText.text = resources.getQuantityString(
            R.plurals.waypoint_count,
            waypoints.size,
            waypoints.size,
        )
        binding.speedText.text = getString(R.string.route_speed, binding.speedSlider.value.toInt())
        binding.undoButton.isEnabled = waypoints.isNotEmpty()
        binding.sampleRouteButton.visibility = if (waypoints.isEmpty()) View.VISIBLE else View.GONE
        refreshTimingUi()
        renderRoute()
    }

    private fun renderRoute() {
        binding.map.overlays.removeAll(routeOverlays.toSet())
        routeOverlays.clear()
        if (waypoints.size >= 2) {
            routeOverlays += Polyline(binding.map).apply {
                setPoints(waypoints.map { GeoPoint(it.coordinates.latitude, it.coordinates.longitude) })
                outlinePaint.color = getColor(R.color.brand)
                outlinePaint.strokeWidth = dp(5).toFloat()
            }
        }
        waypoints.forEachIndexed { index, waypoint ->
            if (waypoints.size > MARKER_LIMIT && index != 0 && index != waypoints.lastIndex) return@forEachIndexed
            routeOverlays += Marker(binding.map).apply {
                position = GeoPoint(waypoint.coordinates.latitude, waypoint.coordinates.longitude)
                title = "${index + 1}. ${waypoint.name}"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                // 點一下編輯／刪除；長按後拖曳可以移動位置。
                setOnMarkerClickListener { _, _ ->
                    showEditWaypointDialog(index)
                    true
                }
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDrag(marker: Marker) = Unit
                    override fun onMarkerDragStart(marker: Marker) = Unit
                    override fun onMarkerDragEnd(marker: Marker) {
                        val moved = waypoints.getOrNull(index) ?: return
                        waypoints[index] = moved.copy(
                            coordinates = Coordinates(marker.position.latitude, marker.position.longitude),
                        )
                        updateEditorState()
                    }
                })
            }
        }
        binding.map.overlays.addAll(routeOverlays)
        binding.map.invalidate()
    }

    private fun showSavedRoutes() {
        val routes = repository.all()
        if (routes.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.saved_routes)
                .setMessage(R.string.saved_routes_empty)
                .setNeutralButton(R.string.import_route) { _, _ -> importRoute() }
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val labels = routes.map { route ->
            val details = resources.getQuantityString(
                R.plurals.route_details,
                route.waypoints.size,
                route.waypoints.size,
                route.speedKph,
            )
            "${route.name}\n$details"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.saved_routes)
            .setItems(labels) { _, index -> showRouteActions(routes[index]) }
            .setNeutralButton(R.string.import_route) { _, _ -> importRoute() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showRouteActions(route: SimulationRoute) {
        val options = arrayOf(
            getString(R.string.start_route),
            getString(R.string.load_route),
            getString(R.string.export_gpx),
            getString(R.string.export_kml),
            getString(R.string.delete_route),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(route.name)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_ROUTE_ID, route.id))
                        finish()
                    }

                    1 -> loadRoute(route)
                    2 -> exportRoute(route, LocationDocumentFormat.GPX)
                    3 -> exportRoute(route, LocationDocumentFormat.KML)
                    4 -> confirmDeleteRoute(route)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun importRoute() {
        // 很多手機把 .gpx 判成 application/octet-stream（或其他怪類型），只列幾種 MIME 的話那些檔案在選擇畫面裡會是灰色、點不了。
        // 所以不限制類型，讓使用者看得到所有檔案；內容不是支援的格式時，匯入會說明原因。
        importDocumentLauncher.launch(arrayOf("*/*"))
    }

    private fun exportRoute(route: SimulationRoute, format: LocationDocumentFormat) {
        pendingExportContent = LocationDocumentCodec.export(route, format)
        val extension = if (format == LocationDocumentFormat.GPX) "gpx" else "kml"
        val safeName = route.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "route" }
        exportDocumentLauncher.launch("$safeName.$extension")
    }

    private fun loadRoute(route: SimulationRoute) {
        editingRouteId = route.id
        editingCreatedAt = route.createdAt
        waypoints.clear()
        waypoints.addAll(route.waypoints)
        binding.routeNameInput.setText(route.name)
        binding.speedSlider.value = route.speedKph.toFloat().coerceIn(1f, 160f)
        binding.loopSwitch.isChecked = route.loop
        outages.clear()
        outages.addAll(route.outages)
        binding.naturalSwitch.isChecked = route.naturalSpeed
        updateEditorState()
        binding.timingSwitch.isChecked = route.useGpxTiming
        refreshTimingUi()
        zoomToWaypoints()
    }

    /** 有時間戳記才顯示「依 GPX 時間播放」；開啟時速度由時間決定，滑桿與預設值按鈕停用。 */
    private fun refreshTimingUi() {
        val hasTiming = SimulationRoute.hasTiming(waypoints)
        binding.timingSwitch.visibility = if (hasTiming) View.VISIBLE else View.GONE
        if (!hasTiming && binding.timingSwitch.isChecked) binding.timingSwitch.isChecked = false
        val fixedSpeed = !(hasTiming && binding.timingSwitch.isChecked)
        listOf(
            binding.speedSlider,
            binding.presetWalkButton,
            binding.presetRunButton,
            binding.presetBikeButton,
            binding.presetCarButton,
        ).forEach {
            it.isEnabled = fixedSpeed
            it.alpha = if (fixedSpeed) 1f else 0.4f
        }
        binding.speedText.alpha = if (fixedSpeed) 1f else 0.4f
        binding.naturalSwitch.isEnabled = fixedSpeed
        binding.naturalSwitch.alpha = if (fixedSpeed) 1f else 0.4f
        binding.outageButton.text = getString(R.string.route_outages, outages.size)
        highlightSpeedPreset()
    }

    /** 目前速度剛好等於某個預設值時，把那顆按鈕標成選取狀態。 */
    private fun highlightSpeedPreset() {
        val value = binding.speedSlider.value
        listOf(
            binding.presetWalkButton to SPEED_PRESET_WALK_KPH,
            binding.presetRunButton to SPEED_PRESET_RUN_KPH,
            binding.presetBikeButton to SPEED_PRESET_BIKE_KPH,
            binding.presetCarButton to SPEED_PRESET_CAR_KPH,
        ).forEach { (button, kph) ->
            val selected = binding.speedSlider.isEnabled && kotlin.math.abs(value - kph) < 0.5f
            button.backgroundTintList = ColorStateList.valueOf(getColor(if (selected) R.color.brand else R.color.brand_container))
            button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.on_brand_container))
        }
    }

    private fun showOutages() {
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_outages_title)
            .setPositiveButton(R.string.route_outage_add) { _, _ -> showAddOutage() }
            .setNegativeButton(R.string.close, null)
        if (outages.isEmpty()) {
            builder.setMessage(R.string.route_outages_hint)
        } else {
            val labels = outages.map {
                getString(R.string.route_outage_item, formatClock(it.startSeconds), formatClock(it.durationSeconds))
            }.toTypedArray()
            builder.setItems(labels) { _, index ->
                outages.removeAt(index)
                updateEditorState()
                showMessage(getString(R.string.route_outage_removed))
            }
        }
        builder.show()
    }

    private fun showAddOutage() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val start = EditText(this).apply {
            hint = getString(R.string.route_outage_start_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val duration = EditText(this).apply {
            hint = getString(R.string.route_outage_duration_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        container.addView(start)
        container.addView(duration)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_outage_add)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add) { _, _ ->
                val outage = runCatching {
                    RouteOutage(start.text.toString().trim().toLong(), duration.text.toString().trim().toLong())
                }.getOrNull()
                if (outage == null) {
                    showMessage(getString(R.string.route_outage_invalid))
                } else {
                    outages.add(outage)
                    outages.sortBy { it.startSeconds }
                    updateEditorState()
                }
            }
            .show()
    }

    private fun formatClock(seconds: Long): String =
        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)

    private fun zoomToWaypoints() {
        val points = waypoints.map { GeoPoint(it.coordinates.latitude, it.coordinates.longitude) }
        if (points.size < 2) return
        binding.map.post {
            binding.map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points), true, dp(64))
        }
    }

    private fun confirmDeleteRoute(route: SimulationRoute) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_route)
            .setMessage(getString(R.string.delete_route_message, route.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                repository.delete(route.id)
                if (editingRouteId == route.id) {
                    editingRouteId = null
                    editingCreatedAt = System.currentTimeMillis()
                }
            }
            .show()
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.controlPanel, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.controlPanel)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_START_LAT = "start_lat"
        const val EXTRA_START_LON = "start_lon"
        private const val SAMPLE_ROUTE_SPEED_KPH = 24f
        private const val MARKER_LIMIT = 200
        private const val SPEED_PRESET_WALK_KPH = 5f
        private const val SPEED_PRESET_RUN_KPH = 10f
        private const val SPEED_PRESET_BIKE_KPH = 18f
        private const val SPEED_PRESET_CAR_KPH = 40f
    }
}

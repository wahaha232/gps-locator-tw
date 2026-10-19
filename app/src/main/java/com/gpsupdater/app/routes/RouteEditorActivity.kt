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
import com.gpsupdater.app.location.MockLocationPreferences
import com.gpsupdater.app.geocoding.MapServiceConfiguration
import java.util.Locale
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

    private val importDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("Empty document")
            val fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                .orEmpty()
                .ifBlank { getString(R.string.new_route_name) }
            LocationDocumentCodec.import(content, fallbackName)
        }.onSuccess { route ->
            repository.save(route)
            loadRoute(route)
            showMessage(getString(R.string.route_imported))
        }.onFailure {
            showMessage(getString(R.string.route_import_failed))
        }
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

    private fun configureMap() {
        val initial = MockLocationPreferences.coordinates(this)
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
                        controller.animateTo(point)
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
        binding.addWaypointButton.setOnClickListener { showAddWaypointDialog() }
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
            binding.attributionText.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = bottom - top + dp(16)
            }
        }
    }

    private fun showAddWaypointDialog() {
        val waypointBinding = DialogWaypointBinding.inflate(LayoutInflater.from(this))
        waypointBinding.nameInput.setText(
            getString(R.string.waypoint_default_name, waypoints.size + 1),
        )
        waypointBinding.stopInput.setText("0")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_waypoint)
            .setView(waypointBinding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.add, null)
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
                val center = binding.map.mapCenter
                waypoints += RouteWaypoint(
                    name = name,
                    coordinates = Coordinates(
                        center.latitude,
                        center.longitude,
                    ),
                    stopDurationSeconds = stopSeconds,
                )
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

    private fun saveAndStart() {
        val routeName = binding.routeNameInput.text?.toString()?.trim().orEmpty()
        if (routeName.isBlank()) {
            binding.routeNameLayout.error = getString(R.string.route_name_required)
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
        importDocumentLauncher.launch(
            arrayOf(
                "application/gpx+xml",
                "application/vnd.google-earth.kml+xml",
                "application/xml",
                "text/xml",
                "text/plain",
            ),
        )
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
        private const val SAMPLE_ROUTE_SPEED_KPH = 24f
        private const val MARKER_LIMIT = 200
        private const val SPEED_PRESET_WALK_KPH = 5f
        private const val SPEED_PRESET_RUN_KPH = 10f
        private const val SPEED_PRESET_BIKE_KPH = 18f
        private const val SPEED_PRESET_CAR_KPH = 40f
    }
}

package com.gpsupdater.app.location

import android.content.Context
import com.gpsupdater.app.model.Coordinates
import com.gpsupdater.app.routes.RouteSimulator
import org.json.JSONObject

/** 最近一次模擬的播放進度；updatedAt 用於顯示中斷時間。 */
data class PlaybackState(
    val routeId: String?,
    val snapshot: RouteSimulator.Snapshot?,
    val rate: Int,
    val paused: Boolean,
    val updatedAt: Long,
    val coordinates: Coordinates,
)

/**
 * 把路線／固定位置的播放進度存在本機，讓 App 被系統中止後可以詢問使用者是否續跑。
 * 只存顯示與續跑所需的最小狀態，不包含任何金鑰。
 */
class PlaybackStateRepository(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun save(
        routeId: String?,
        snapshot: RouteSimulator.Snapshot?,
        rate: Int,
        paused: Boolean,
        coordinates: Coordinates,
    ) {
        val json = JSONObject()
            .put("routeId", routeId)
            .put("rate", rate)
            .put("paused", paused)
            .put("updatedAt", System.currentTimeMillis())
            .put("latitude", coordinates.latitude)
            .put("longitude", coordinates.longitude)
        snapshot?.let { state ->
            json.put(
                "snapshot",
                JSONObject()
                    .put("currentWaypointIndex", state.currentWaypointIndex)
                    .put("distanceIntoLeg", state.distanceIntoLeg)
                    .put("distanceCompletedInLap", state.distanceCompletedInLap)
                    .put("stopRemainingSeconds", state.stopRemainingSeconds)
                    .put("elapsedSeconds", state.elapsedSeconds)
                    .put("lap", state.lap)
                    .put("complete", state.complete)
                    .put("timeIntoLeg", state.timeIntoLeg),
            )
        }
        preferences.edit().putString(KEY_STATE, json.toString()).apply()
    }

    fun state(): PlaybackState? {
        val stored = preferences.getString(KEY_STATE, null) ?: return null
        return runCatching {
            val json = JSONObject(stored)
            val snapshotJson = json.optJSONObject("snapshot")
            PlaybackState(
                routeId = json.optString("routeId").takeIf { it.isNotBlank() && it != "null" },
                snapshot = snapshotJson?.let { state ->
                    RouteSimulator.Snapshot(
                        currentWaypointIndex = state.getInt("currentWaypointIndex"),
                        distanceIntoLeg = state.getDouble("distanceIntoLeg"),
                        distanceCompletedInLap = state.getDouble("distanceCompletedInLap"),
                        stopRemainingSeconds = state.getDouble("stopRemainingSeconds"),
                        elapsedSeconds = state.getDouble("elapsedSeconds"),
                        lap = state.getInt("lap"),
                        complete = state.getBoolean("complete"),
                        timeIntoLeg = state.getDouble("timeIntoLeg"),
                    )
                },
                rate = json.optInt("rate", 1).coerceIn(1, 8),
                paused = json.optBoolean("paused", false),
                updatedAt = json.optLong("updatedAt", 0L),
                coordinates = Coordinates(
                    json.getDouble("latitude"),
                    json.getDouble("longitude"),
                ),
            )
        }.getOrNull()
    }

    fun clear() {
        preferences.edit().remove(KEY_STATE).apply()
    }

    companion object {
        private const val FILE_NAME = "playback_state"
        private const val KEY_STATE = "state"
    }
}

package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import java.util.UUID

data class RouteWaypoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coordinates: Coordinates,
    val stopDurationSeconds: Long = 0,
    /** 從路線起點算起的時間（秒）；來自 GPX 的時間戳記，沒有就是 null。 */
    val timeOffsetSeconds: Long? = null,
) {
    init {
        require(name.isNotBlank())
        require(stopDurationSeconds in 0..MAX_STOP_DURATION_SECONDS)
        require(timeOffsetSeconds == null || timeOffsetSeconds >= 0)
    }

    companion object {
        private const val MAX_STOP_DURATION_SECONDS = 86_400L
    }
}

/**
 * 模擬 GPS 中斷（例如搭捷運、進隧道）：播放到第 [startSeconds] 秒起，連續 [durationSeconds] 秒不送出任何位置，
 * 但模擬的人仍然照常往前走，所以中斷結束時位置會「跳」到前進後的地方，和真實訊號中斷一樣。
 */
data class RouteOutage(
    val startSeconds: Long,
    val durationSeconds: Long,
) {
    init {
        require(startSeconds >= 0)
        require(durationSeconds in 1..MAX_DURATION_SECONDS)
    }

    val endSeconds: Long get() = startSeconds + durationSeconds

    companion object {
        const val MAX_DURATION_SECONDS = 86_400L
    }
}

object GpsOutagePolicy {
    /** [elapsedSeconds]（模擬已經過的時間）是否落在任何一段中斷內。 */
    fun isActive(outages: List<RouteOutage>, elapsedSeconds: Double): Boolean =
        outages.any { elapsedSeconds >= it.startSeconds && elapsedSeconds < it.endSeconds }
}

data class SimulationRoute(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val waypoints: List<RouteWaypoint>,
    val speedKph: Double,
    val loop: Boolean,
    val createdAt: Long = System.currentTimeMillis(),
    /** 依各點的時間戳記播放（保留原本走走停停的快慢）；false 就用 [speedKph] 等速。需要 [hasTiming]。 */
    val useGpxTiming: Boolean = false,
    val outages: List<RouteOutage> = emptyList(),
) {
    init {
        require(name.isNotBlank())
        require(waypoints.size >= 2)
        require(speedKph.isFinite() && speedKph in MIN_SPEED_KPH..MAX_SPEED_KPH)
        require(waypoints.map { it.coordinates }.distinct().size >= 2)
        require(!useGpxTiming || hasTiming(waypoints))
    }

    companion object {
        const val MIN_SPEED_KPH = 0.5
        const val MAX_SPEED_KPH = 500.0

        /** 每個點都有時間、時間不倒退，而且總時間大於 0，才能依時間戳記播放。 */
        fun hasTiming(waypoints: List<RouteWaypoint>): Boolean {
            if (waypoints.size < 2) return false
            val times = waypoints.map { it.timeOffsetSeconds ?: return false }
            return times.zipWithNext().all { (a, b) -> b >= a } && times.last() > times.first()
        }
    }
}

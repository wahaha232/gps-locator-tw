package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates

data class RoutePosition(
    val coordinates: Coordinates,
    val speedMetersPerSecond: Float,
    val bearingDegrees: Float,
    val progress: Double,
    val currentWaypointIndex: Int,
    val lap: Int,
    val isStopped: Boolean,
    val isComplete: Boolean,
    val elapsedSeconds: Double,
)

class RouteSimulator(private val route: SimulationRoute) {
    private val speedMetersPerSecond = route.speedKph / 3.6

    /** 依時間戳記播放：每一段走多久由 GPX 決定，速度因此隨段落變化（含原地停留）。 */
    private val timed = route.useGpxTiming && SimulationRoute.hasTiming(route.waypoints)
    private val legDistances = buildList {
        for (index in 0 until route.waypoints.lastIndex) {
            add(
                GeoMath.distanceMeters(
                    route.waypoints[index].coordinates,
                    route.waypoints[index + 1].coordinates,
                ),
            )
        }
        if (route.loop) {
            add(
                GeoMath.distanceMeters(
                    route.waypoints.last().coordinates,
                    route.waypoints.first().coordinates,
                ),
            )
        }
    }
    private val totalDistance = legDistances.sum()

    private val timedLegSeconds: List<Double> = if (!timed) {
        emptyList()
    } else {
        buildList {
            val open = route.waypoints.zipWithNext { a, b -> (b.timeOffsetSeconds!! - a.timeOffsetSeconds!!).toDouble() }
            addAll(open)
            if (route.loop) {
                // 循環路線的「回到起點」那一段 GPX 沒有時間，用整段的平均速度估。
                val openDistance = legDistances.take(open.size).sum()
                val openSeconds = open.sum()
                val average = if (openSeconds > 0 && openDistance > 0) openDistance / openSeconds else speedMetersPerSecond
                add(legDistances.last() / average)
            }
        }
    }
    private var timeIntoLeg = 0.0

    /** 目前這一步實際使用的速度（等速模式下，自然起伏會讓它隨時間變化）。 */
    private var effectiveSpeed = speedMetersPerSecond * speedFactor(0.0)

    /** 預估整趟要多久（秒）；循環路線沒有終點，回傳 null。 */
    val estimatedTotalSeconds: Double? = when {
        route.loop -> null
        timed -> timedLegSeconds.sum()
        else -> totalDistance / speedMetersPerSecond + route.waypoints.sumOf { it.stopDurationSeconds.toDouble() }
    }

    private var currentWaypointIndex = 0
    private var distanceIntoLeg = 0.0
    private var distanceCompletedInLap = 0.0
    private var stopRemainingSeconds = if (timed) 0.0 else route.waypoints.first().stopDurationSeconds.toDouble()
    private var elapsedSeconds = 0.0
    private var lap = 0
    private var complete = false

    fun current(): RoutePosition = buildPosition()

    fun advance(deltaSeconds: Double): RoutePosition {
        require(deltaSeconds.isFinite() && deltaSeconds >= 0)
        if (timed) return advanceTimed(deltaSeconds)
        effectiveSpeed = speedMetersPerSecond * speedFactor(elapsedSeconds)
        var remainingSeconds = deltaSeconds
        var iterations = 0
        while (remainingSeconds > 1e-9 && !complete) {
            check(iterations++ < MAX_ADVANCE_ITERATIONS) { "Route contains too many zero-length steps" }

            if (stopRemainingSeconds > 0) {
                val consumed = minOf(stopRemainingSeconds, remainingSeconds)
                stopRemainingSeconds -= consumed
                remainingSeconds -= consumed
                elapsedSeconds += consumed
                if (stopRemainingSeconds > 1e-9) break
            }

            if (!route.loop && currentWaypointIndex == route.waypoints.lastIndex) {
                complete = true
                break
            }

            val targetWaypointIndex = (currentWaypointIndex + 1) % route.waypoints.size
            val legIndex = if (currentWaypointIndex == route.waypoints.lastIndex) {
                legDistances.lastIndex
            } else {
                currentWaypointIndex
            }
            val legDistance = legDistances[legIndex]
            val remainingLegDistance = (legDistance - distanceIntoLeg).coerceAtLeast(0.0)
            if (remainingLegDistance <= 1e-6) {
                arriveAt(targetWaypointIndex, legDistance)
                continue
            }

            val travelSeconds = remainingLegDistance / effectiveSpeed
            if (remainingSeconds < travelSeconds) {
                distanceIntoLeg += remainingSeconds * effectiveSpeed
                elapsedSeconds += remainingSeconds
                remainingSeconds = 0.0
            } else {
                remainingSeconds -= travelSeconds
                elapsedSeconds += travelSeconds
                arriveAt(targetWaypointIndex, legDistance)
            }
        }
        return buildPosition()
    }

    /**
     * 自然起伏：起步 6 秒內由 35% 加速到全速，之後在 ±17% 內緩慢變化（幾個週期不同的正弦疊加，固定不隨機，方便重現與測試）。
     * 只用在等速模式；依時間戳記播放時速度由 GPX 決定，不套用。
     */
    private fun speedFactor(elapsed: Double): Double {
        if (!route.naturalSpeed || timed) return 1.0
        val wobble = 1.0 + 0.10 * kotlin.math.sin(elapsed / 9.0) +
            0.05 * kotlin.math.sin(elapsed / 3.7 + 1.3) +
            0.02 * kotlin.math.sin(elapsed / 1.9 + 0.4)
        val ramp = (0.35 + 0.65 * (elapsed / 6.0)).coerceIn(0.35, 1.0)
        return wobble.coerceIn(0.8, 1.2) * ramp
    }

    private fun advanceTimed(deltaSeconds: Double): RoutePosition {
        var remainingSeconds = deltaSeconds
        var iterations = 0
        while (remainingSeconds > 1e-9 && !complete) {
            check(iterations++ < MAX_ADVANCE_ITERATIONS) { "Route contains too many zero-length steps" }
            if (!route.loop && currentWaypointIndex == route.waypoints.lastIndex) {
                complete = true
                break
            }
            val targetWaypointIndex = (currentWaypointIndex + 1) % route.waypoints.size
            val legIndex = if (currentWaypointIndex == route.waypoints.lastIndex) {
                legDistances.lastIndex
            } else {
                currentWaypointIndex
            }
            val legSeconds = timedLegSeconds[legIndex]
            val secondsLeft = (legSeconds - timeIntoLeg).coerceAtLeast(0.0)
            if (secondsLeft <= 1e-9) {
                arriveTimed(targetWaypointIndex, legDistances[legIndex])
                continue
            }
            if (remainingSeconds < secondsLeft) {
                timeIntoLeg += remainingSeconds
                elapsedSeconds += remainingSeconds
                remainingSeconds = 0.0
                distanceIntoLeg = legDistances[legIndex] * (timeIntoLeg / legSeconds)
            } else {
                remainingSeconds -= secondsLeft
                elapsedSeconds += secondsLeft
                arriveTimed(targetWaypointIndex, legDistances[legIndex])
            }
        }
        return buildPosition()
    }

    private fun arriveTimed(targetWaypointIndex: Int, completedLegDistance: Double) {
        arriveAt(targetWaypointIndex, completedLegDistance)
        timeIntoLeg = 0.0
        stopRemainingSeconds = 0.0
        if (!route.loop && targetWaypointIndex == route.waypoints.lastIndex) complete = true
    }

    private fun arriveAt(targetWaypointIndex: Int, completedLegDistance: Double) {
        distanceCompletedInLap += completedLegDistance
        currentWaypointIndex = targetWaypointIndex
        distanceIntoLeg = 0.0
        stopRemainingSeconds = route.waypoints[targetWaypointIndex].stopDurationSeconds.toDouble()
        if (route.loop && targetWaypointIndex == 0) {
            lap += 1
            distanceCompletedInLap = 0.0
        } else if (!route.loop && targetWaypointIndex == route.waypoints.lastIndex &&
            stopRemainingSeconds == 0.0
        ) {
            complete = true
        }
    }

    private fun buildPosition(): RoutePosition {
        val currentWaypoint = route.waypoints[currentWaypointIndex]
        val atTerminal = !route.loop && currentWaypointIndex == route.waypoints.lastIndex
        val targetWaypointIndex = if (atTerminal) {
            currentWaypointIndex
        } else {
            (currentWaypointIndex + 1) % route.waypoints.size
        }
        val targetWaypoint = route.waypoints[targetWaypointIndex]
        val legIndex = when {
            atTerminal -> -1
            currentWaypointIndex == route.waypoints.lastIndex -> legDistances.lastIndex
            else -> currentWaypointIndex
        }
        val legDistance = if (legIndex >= 0) legDistances[legIndex] else 0.0
        val fraction = if (legDistance <= 1e-9) 0.0 else distanceIntoLeg / legDistance
        val coordinates = if (legIndex < 0 || stopRemainingSeconds > 0) {
            currentWaypoint.coordinates
        } else {
            GeoMath.interpolate(currentWaypoint.coordinates, targetWaypoint.coordinates, fraction)
        }
        val progress = when {
            complete -> 1.0
            totalDistance <= 1e-9 -> 0.0
            else -> ((distanceCompletedInLap + distanceIntoLeg) / totalDistance).coerceIn(0.0, 1.0)
        }
        val legSpeed = if (timed && legIndex >= 0) {
            val legSeconds = timedLegSeconds[legIndex]
            if (legSeconds > 1e-9) legDistance / legSeconds else 0.0
        } else {
            effectiveSpeed
        }
        val isStopped = stopRemainingSeconds > 1e-9 || complete || (timed && legSpeed < STOPPED_SPEED_MPS)
        return RoutePosition(
            coordinates = coordinates,
            speedMetersPerSecond = if (isStopped) 0f else legSpeed.toFloat(),
            bearingDegrees = if (isStopped || legIndex < 0) {
                0f
            } else {
                GeoMath.initialBearingDegrees(currentWaypoint.coordinates, targetWaypoint.coordinates)
            },
            progress = progress,
            currentWaypointIndex = currentWaypointIndex,
            lap = lap,
            isStopped = isStopped,
            isComplete = complete,
            elapsedSeconds = elapsedSeconds,
        )
    }

    companion object {
        private const val MAX_ADVANCE_ITERATIONS = 10_000

        /** 依時間播放時，低於這個速度就視為原地停留。 */
        private const val STOPPED_SPEED_MPS = 0.05
    }
}

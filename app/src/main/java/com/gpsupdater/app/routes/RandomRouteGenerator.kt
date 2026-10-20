package com.gpsupdater.app.routes

import com.gpsupdater.app.model.Coordinates
import kotlin.math.min
import kotlin.random.Random

enum class RandomRouteShape {
    /** 隨機漫步：從中心出發，一步一步稍微轉彎地走，超出範圍就被拉回來。 */
    WALK,

    /** 環形迴路：從中心出發繞一圈（路線設成「循環」，最後一點會接回起點）。 */
    LOOP,
}

/**
 * 隨機生成軌跡點。純 Kotlin（只用 [GeoMath]），可用 JVM 單元測試。
 * 傳入固定種子的 [Random] 就會得到完全相同的軌跡，方便重現測試。
 * 第一個點一律就是 [center]（地圖中心）。
 */
object RandomRouteGenerator {
    const val MIN_POINTS = 2
    const val MAX_POINTS = 500
    const val MIN_RADIUS_METERS = 50
    const val MAX_RADIUS_METERS = 50_000

    fun generate(
        center: Coordinates,
        pointCount: Int,
        radiusMeters: Double,
        shape: RandomRouteShape,
        random: Random = Random.Default,
    ): List<Coordinates> {
        require(pointCount in MIN_POINTS..MAX_POINTS) { "pointCount out of range" }
        require(radiusMeters in MIN_RADIUS_METERS.toDouble()..MAX_RADIUS_METERS.toDouble()) { "radius out of range" }
        return when (shape) {
            RandomRouteShape.WALK -> randomWalk(center, pointCount, radiusMeters, random)
            RandomRouteShape.LOOP -> loop(center, pointCount, radiusMeters, random)
        }
    }

    private fun randomWalk(center: Coordinates, count: Int, radius: Double, random: Random): List<Coordinates> {
        // 平均步長讓整條路線大約走出範圍直徑的 1.5 倍；點很少時限制單步不要超過半徑的一半。
        val meanStep = min(radius * 3.0 / count, radius * 0.4)
        var bearing = random.nextDouble() * 360.0
        var current = center
        val points = ArrayList<Coordinates>(count)
        points += center
        repeat(count - 1) {
            // 大多數時候小幅轉彎，偶爾大轉彎（像真的在街上走）
            bearing += if (random.nextDouble() < 0.1) random.nextDouble(-90.0, 90.0) else random.nextDouble(-35.0, 35.0)
            // 走到接近範圍邊緣就朝中心方向拉回來
            if (GeoMath.distanceMeters(center, current) > radius * 0.9) {
                bearing = GeoMath.initialBearingDegrees(current, center).toDouble() + random.nextDouble(-45.0, 45.0)
            }
            current = GeoMath.destination(current, meanStep * random.nextDouble(0.5, 1.5), normalize(bearing))
            points += current
        }
        return points
    }

    private fun loop(center: Coordinates, count: Int, radius: Double, random: Random): List<Coordinates> {
        // 圓周通過起點、直徑等於範圍半徑：離起點最遠的點大約就是 radius。
        val circleRadius = radius / 2.0
        val startToCircleCenter = random.nextDouble() * 360.0
        val circleCenter = GeoMath.destination(center, circleRadius, startToCircleCenter)
        val centerToStart = normalize(startToCircleCenter + 180.0)
        val angleStep = 360.0 / count
        val points = ArrayList<Coordinates>(count)
        points += center
        for (i in 1 until count) {
            val angle = angleStep * i + random.nextDouble(-0.3, 0.3) * angleStep
            val distance = circleRadius * (1.0 + random.nextDouble(-0.12, 0.12))
            points += GeoMath.destination(circleCenter, distance, normalize(centerToStart + angle))
        }
        return points
    }

    private fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}

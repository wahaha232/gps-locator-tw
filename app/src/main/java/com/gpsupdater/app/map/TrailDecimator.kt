package com.gpsupdater.app.map

import kotlin.math.roundToInt

/**
 * 長軌跡的顯示抽稀：保留最近一半的點，較舊的前段均勻抽稀。
 * 只回傳新的清單，不會修改傳入的點列；首尾一定保留。
 */
object TrailDecimator {
    const val DEFAULT_MAX_POINTS = 3000

    fun <T> decimate(points: List<T>, maxPoints: Int = DEFAULT_MAX_POINTS): List<T> {
        require(maxPoints >= 2) { "maxPoints 至少要是 2" }
        if (points.size <= maxPoints) return points.toList()

        val recentCount = maxPoints / 2
        val oldCount = points.size - recentCount
        val oldBudget = maxPoints - recentCount
        val result = ArrayList<T>(maxPoints)

        if (oldBudget <= 1) {
            result.add(points.first())
        } else {
            val step = (oldCount - 1).toDouble() / (oldBudget - 1)
            for (i in 0 until oldBudget) {
                val index = (i * step).roundToInt().coerceIn(0, oldCount - 1)
                result.add(points[index])
            }
        }
        result.addAll(points.subList(points.size - recentCount, points.size))
        return result
    }
}

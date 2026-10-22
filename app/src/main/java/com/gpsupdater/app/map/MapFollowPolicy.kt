package com.gpsupdater.app.map

/**
 * 跟隨模式的鏡頭平移判斷：只有大頭針離開畫面中央安全區（外圍 15%）時才需要平移。
 * 這裡只做純計算，不碰 Android／osmdroid，方便 JVM 單元測試。
 */
object MapFollowPolicy {
    const val DEFAULT_EDGE_FRACTION = 0.15

    fun shouldRecenter(
        pinX: Float,
        pinY: Float,
        width: Int,
        height: Int,
        edgeFraction: Double = DEFAULT_EDGE_FRACTION,
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        if (!pinX.isFinite() || !pinY.isFinite()) return false
        if (edgeFraction <= 0.0 || edgeFraction >= 0.5) return false
        val x = pinX.toDouble()
        val y = pinY.toDouble()
        val marginX = width * edgeFraction
        val marginY = height * edgeFraction
        return x < marginX || x > width - marginX ||
            y < marginY || y > height - marginY
    }
}

package com.gpsupdater.app.model

import java.util.Locale

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude.isFinite() && latitude in LATITUDE_RANGE)
        require(longitude.isFinite() && longitude in LONGITUDE_RANGE)
    }

    fun formattedLatitude(): String = String.format(Locale.US, "%.6f", latitude)

    fun formattedLongitude(): String = String.format(Locale.US, "%.6f", longitude)

    companion object {
        private val LATITUDE_RANGE = -90.0..90.0
        private val LONGITUDE_RANGE = -180.0..180.0

        /** 第一次啟動（還沒有任何已儲存位置）時地圖的起點：台北 101 一帶。上游的預設是北京天安門。 */
        val DEFAULT = Coordinates(25.033964, 121.564472)

        fun parse(latitude: String, longitude: String): Coordinates? {
            val parsedLatitude = latitude.trim().toDoubleOrNull() ?: return null
            val parsedLongitude = longitude.trim().toDoubleOrNull() ?: return null
            if (!parsedLatitude.isFinite() || parsedLatitude !in LATITUDE_RANGE) return null
            if (!parsedLongitude.isFinite() || parsedLongitude !in LONGITUDE_RANGE) return null
            return Coordinates(parsedLatitude, parsedLongitude)
        }
    }
}

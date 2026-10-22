package com.gpsupdater.app.location

/** 啟動定位或按「目前位置」時可能需要顯示的提示種類。 */
enum class LocationHint {
    NONE,
    MOCK_SIMULATION,
    MISSING_PERMISSION,
    LOCATION_SERVICES_DISABLED,
    TIMEOUT,
}

/**
 * 依模擬狀態、定位權限、定位服務開關與是否逾時，決定該顯示哪一種提示。
 * 這裡只做純判斷，不碰 Android API，方便在 JVM 單元測試裡驗證。
 */
object LocationHintPolicy {
    fun resolve(
        isMocking: Boolean,
        hasPermission: Boolean,
        locationServicesEnabled: Boolean,
        timedOut: Boolean,
    ): LocationHint = when {
        isMocking -> LocationHint.MOCK_SIMULATION
        !hasPermission -> LocationHint.MISSING_PERMISSION
        !locationServicesEnabled -> LocationHint.LOCATION_SERVICES_DISABLED
        timedOut -> LocationHint.TIMEOUT
        else -> LocationHint.NONE
    }
}

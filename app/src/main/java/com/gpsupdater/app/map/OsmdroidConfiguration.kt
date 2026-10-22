package com.gpsupdater.app.map

import com.gpsupdater.app.BuildConfig
import org.osmdroid.config.Configuration

/**
 * osmdroid 的全域設定：圖磚快取上限與 User-Agent。
 * 下載執行緒維持 osmdroid 預設值，不加大，避免增加流量與耗電。
 */
object OsmdroidConfiguration {
    private const val TILE_CACHE_MAX_BYTES = 200L * 1024 * 1024
    private const val TILE_CACHE_TRIM_BYTES = 150L * 1024 * 1024

    fun configure() {
        Configuration.getInstance().apply {
            // OSM 圖磚使用規定要求可辨識的 User-Agent；帶入 App 名稱、版本與套件名稱。
            userAgentValue = "GpsLocatorTw/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
            tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        }
    }
}

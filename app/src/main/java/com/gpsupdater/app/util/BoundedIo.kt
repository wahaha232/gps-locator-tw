package com.gpsupdater.app.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** 讀取外部來源（使用者選的檔案、網路回應）時一律設上限，避免超大檔案把記憶體吃光。 */
object BoundedIo {
    /** 路線檔（GPX／KML／本 App 的 JSON）上限。 */
    const val ROUTE_DOCUMENT_MAX_BYTES = 10 * 1024 * 1024

    /** 授權檔上限（只是一小段簽章 JSON）。 */
    const val LICENSE_DOCUMENT_MAX_BYTES = 1024 * 1024

    /** 地點搜尋回應上限。 */
    const val GEOCODER_RESPONSE_MAX_BYTES = 2 * 1024 * 1024

    /** 雲端同步回應上限：略高於伺服器允許的最大請求本文（64 MiB）。 */
    const val CLOUD_RESPONSE_MAX_BYTES = 70 * 1024 * 1024

    /** 以 UTF-8 讀完 [stream]；超過 [maximumBytes] 就丟 [IOException]，不會讀進超過上限的資料。 */
    fun readText(stream: InputStream, maximumBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > maximumBytes) {
                throw IOException("Content is larger than $maximumBytes bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

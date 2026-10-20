package com.gpsupdater.app.update

/** 版本比較與校驗碼解析。純 Kotlin，可用 JVM 單元測試。 */
object UpdateVersion {
    /** 取出版本字串裡所有數字依序比較：「2.0.0-tw.7」和「v2.0.0-tw.8」分別是 [2,0,0,7]、[2,0,0,8]。 */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = numbers(current)
        val b = numbers(candidate)
        if (b.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (y != x) return y > x
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()

    /** 解析 `sha256sum` 輸出（「<64 位十六進位> *檔名」）；格式不對回傳 null。 */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()
            ?.lowercase()
            ?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
}

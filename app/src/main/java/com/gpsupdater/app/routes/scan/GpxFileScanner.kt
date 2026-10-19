package com.gpsupdater.app.routes.scan

import java.io.File
import java.nio.file.Files

/** 掃描到的一個 GPX 檔。[uri] 可以直接交給 ContentResolver 開啟（file:// 或 SAF 的 content://）。 */
data class GpxFileEntry(
    val uri: String,
    val name: String,
    /** 顯示用的所在位置（資料夾路徑），不含檔名。 */
    val location: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
)

data class ScanProgress(
    val directoriesVisited: Int,
    val found: Int,
    /** 找到的檔案太多、已達上限而提前停止：結果可能不完整。 */
    val limitReached: Boolean,
)

/**
 * 遞迴掃描資料夾找 `.gpx` 檔案（不分大小寫）。純 Kotlin、不依賴 Android，可用 JVM 單元測試。
 * 略過：隱藏資料夾（以 . 開頭，例如縮圖與快取）、符號連結（避免繞圈）、Android/data 與 Android/obb（系統不讓一般 App 讀）、
 * 沒有讀取權限的資料夾、空檔案。
 */
object GpxFileScanner {
    const val MAX_RESULTS = 5_000
    const val MAX_DEPTH = 24
    private const val PROGRESS_EVERY_DIRECTORIES = 40

    fun scan(
        roots: List<File>,
        isCancelled: () -> Boolean = { false },
        onProgress: (ScanProgress) -> Unit = {},
        onFound: (GpxFileEntry) -> Unit,
    ): ScanProgress {
        val visitedPaths = HashSet<String>()
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.distinctBy { it.absolutePath }.forEach { stack.addLast(it to 0) }
        var directories = 0
        var found = 0
        var limitReached = false

        while (stack.isNotEmpty() && !limitReached) {
            if (isCancelled()) break
            val (directory, depth) = stack.removeLast()
            if (!visitedPaths.add(directory.absolutePath)) continue
            val children = directory.listFiles() ?: continue // 沒有讀取權限或不是資料夾
            directories++
            if (directories % PROGRESS_EVERY_DIRECTORIES == 0) onProgress(ScanProgress(directories, found, false))

            for (child in children) {
                if (isCancelled()) break
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH && shouldEnter(child)) stack.addLast(child to depth + 1)
                } else if (child.name.endsWith(".gpx", ignoreCase = true) && child.isFile) {
                    val size = child.length()
                    if (size <= 0L) continue
                    if (found >= MAX_RESULTS) {
                        limitReached = true
                        break
                    }
                    found++
                    onFound(
                        GpxFileEntry(
                            uri = child.toURI().toString(),
                            name = child.name,
                            location = directory.absolutePath,
                            sizeBytes = size,
                            lastModifiedMs = child.lastModified(),
                        ),
                    )
                }
            }
        }
        val summary = ScanProgress(directories, found, limitReached)
        onProgress(summary)
        return summary
    }

    private fun shouldEnter(directory: File): Boolean {
        if (directory.name.startsWith(".")) return false
        val path = directory.absolutePath.replace('\\', '/')
        if (path.endsWith("/Android/data") || path.endsWith("/Android/obb")) return false
        return !runCatching { Files.isSymbolicLink(directory.toPath()) }.getOrDefault(false)
    }
}

/** 搜尋框的篩選與排序：多個關鍵字以空白分隔，每個都要出現在「檔名＋位置」裡（不分大小寫）；最近修改的排最前面。 */
object GpxFileFilter {
    fun apply(entries: List<GpxFileEntry>, query: String): List<GpxFileEntry> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return entries
            .filter { entry ->
                if (tokens.isEmpty()) return@filter true
                val haystack = (entry.name + " " + entry.location).lowercase()
                tokens.all { haystack.contains(it) }
            }
            .sortedWith(compareByDescending<GpxFileEntry> { it.lastModifiedMs }.thenBy { it.name.lowercase() })
    }
}

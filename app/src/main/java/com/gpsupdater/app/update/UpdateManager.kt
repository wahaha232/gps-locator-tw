package com.gpsupdater.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gpsupdater.app.BuildConfig
import com.gpsupdater.app.util.BoundedIo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val sha256Url: String,
    val notes: String,
)

class UpdateVerificationException : IOException("Checksum mismatch")

/**
 * App 內更新：查 GitHub 最新 Release → 下載 APK → 用 Release 附的 .sha256 驗證 → 交給系統安裝器。
 * 只接受本專案 Release 的下載網址；Android 一定會再跳出系統的安裝確認，這步無法省略。
 */
object UpdateManager {
    private const val REPO = "wahaha232/gps-locator-tw"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private const val APK_MAX_BYTES = 120L * 1024 * 1024
    private const val JSON_MAX_BYTES = 512 * 1024
    private const val TIMEOUT_MS = 15_000

    /** 回傳比目前新的版本；已是最新回傳 null。要在背景執行緒呼叫。 */
    fun checkLatest(): UpdateInfo? {
        val json = JSONObject(
            BoundedIo.readText(connect(LATEST_URL, "application/vnd.github+json").inputStream, JSON_MAX_BYTES),
        )
        val tag = json.getString("tag_name")
        if (!UpdateVersion.isNewer(BuildConfig.VERSION_NAME, tag)) return null
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            val url = asset.getString("browser_download_url")
            if (!url.startsWith(DOWNLOAD_PREFIX)) continue
            if (name.endsWith(".apk")) apk = url
            if (name.endsWith(".apk.sha256")) sha = url
        }
        if (apk == null || sha == null) throw IOException("Release has no verified APK")
        return UpdateInfo(tag.removePrefix("v"), apk, sha, json.optString("body"))
    }

    /** 下載並驗證，回傳 APK 檔。[onProgress] 為 0..100，[isCancelled] 為 true 就中止。要在背景執行緒呼叫。 */
    fun download(context: Context, info: UpdateInfo, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        val expected = UpdateVersion.parseSha256(
            BoundedIo.readText(connect(info.sha256Url, "*/*").inputStream, 4096),
        ) ?: throw IOException("Invalid checksum file")
        val directory = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(directory, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = connect(info.apkUrl, "application/octet-stream")
        val total = connection.contentLengthLong
        if (total > APK_MAX_BYTES) throw IOException("APK too large")
        BoundedIo.limit(connection.inputStream, APK_MAX_BYTES).use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var done = 0L
                while (true) {
                    if (isCancelled()) {
                        target.delete()
                        throw IOException("Cancelled")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    done += count
                    if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw UpdateVerificationException()
        }
        return target
    }

    /** 這個 App 是否已被允許安裝其他 APK（Android 8 以上要使用者自己在系統設定打開）。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 開啟系統裡「允許這個 App 安裝未知應用程式」的頁面，由使用者自己決定。 */
    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun connect(url: String, accept: String): HttpURLConnection {
        require(url.startsWith("https://"))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "gps-locator-tw/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept", accept)
        if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
        return connection
    }
}

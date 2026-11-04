package com.cornming.iccard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A release published on GitHub that is newer than the installed app. */
data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val percent: Int) : UpdateState

    /** The APK is downloaded and checked, but Android has not yet allowed this app to install packages. */
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer APK, downloads it, verifies it and hands it to the system installer.
 * Android always shows its own confirmation; the app cannot install silently.
 */
class UpdateManager(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /** Latest release if it is newer than the running app, otherwise null. Throws on network or format errors. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "ic-card-home/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode == 404) return@withContext null // no release published yet
            if (conn.responseCode != 200) error("GitHub 回應 ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val remoteVersion = tag.removePrefix("v")
            if (!isNewer(remoteVersion, BuildConfig.VERSION_NAME)) return@withContext null

            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: error("這個版本沒有附上 APK 檔")
            ReleaseInfo(
                tag = tag,
                versionName = remoteVersion,
                notes = json.optString("body", ""),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1L),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the APK into the app's cache and checks that it really is an update of this app. */
    suspend fun download(info: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "ic-card-home-${info.versionName}.apk")
        val part = File(dir, target.name + ".part")

        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ic-card-home/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode != 200) error("下載失敗：${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (info.apkSize > 0 && part.length() != info.apkSize) error("檔案大小不符，下載可能不完整")
            part.renameTo(target)
        } finally {
            conn.disconnect()
        }
        verify(target)
        target
    }

    /**
     * Refuses anything that is not a newer build of this app signed with the same key.
     * Android would reject it at install time anyway; checking here gives a clear message.
     */
    private fun verify(apk: File) {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: error("下載的檔案不是有效的 APK")
        if (archive.packageName != context.packageName) error("APK 的套件名稱不符：${archive.packageName}")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) error("APK 不是比目前更新的版本")
        val newSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val oldSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (newSigners.isEmpty() || newSigners != oldSigners) error("APK 的簽章和目前安裝的不同，無法更新")
    }

    fun canInstall(): Boolean = pm.canRequestPackageInstalls()

    /** Opens the system screen where the user allows this app to install updates. */
    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Streams the APK into a PackageInstaller session. The system then asks the user to confirm. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // After the first manual confirmation Android may skip the prompt for updates by the same installer.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    companion object {
        private val _installEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

        /** Messages from [UpdateInstallReceiver] (failures only), shown in the log. */
        val installEvents: SharedFlow<String> = _installEvents

        fun postInstallEvent(message: String) {
            _installEvents.tryEmit(message)
        }

        /** "0.10.0" is newer than "0.9.0": compare numeric parts, not strings. */
        fun isNewer(remote: String, local: String): Boolean {
            val a = remote.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val b = local.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

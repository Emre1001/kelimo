package app.kelimo.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import app.kelimo.KelimoApp
import app.kelimo.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val release: Release?, val reason: String) : UpdateState
    /** Banner closed by the user; the update is still offered in the profile tab. */
    data class Dismissed(val release: Release) : UpdateState
}

/**
 * The app's only network use: asks GitHub for the latest release and downloads its APK.
 * Sends nothing but the request itself. Disabled entirely when Settings.updateCheck is off.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Emre1001/kelimo/releases/latest"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** True when [a] is a higher version than [b] ("1.10.0" > "1.9.2"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun check(context: Context, force: Boolean = false) {
        if (!KelimoApp.instance.store.value.settings.updateCheck) return
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Ready) return
        // One small request per app start (or when the user asks); a dismissed banner stays dismissed until then.
        if (!force && s !is UpdateState.Idle) return
        _state.value = UpdateState.Checking
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val c = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Kelimo")
                }
                try {
                    if (c.responseCode != 200) error("http ${c.responseCode}")
                    parse(c.inputStream.bufferedReader().readText())
                } finally { c.disconnect() }
            }.onFailure { Log.i("Updater", "check failed: ${it.message}") }.getOrNull()
        }
        _state.value = when {
            r == null -> if (force) UpdateState.Failed(null, "network") else UpdateState.Idle
            newer(r.version, currentVersion(context)) -> UpdateState.Available(r)
            else -> UpdateState.UpToDate
        }
    }

    fun parse(json: String): Release? {
        val o = AppJson.parseToJsonElement(json).jsonObject
        val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val apk = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull?.endsWith(".apk") == true } ?: return null
        val url = (apk["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return null
        // Only ever download from GitHub over HTTPS.
        if (!url.startsWith("https://github.com/")) return null
        return Release(
            version = tag.removePrefix("v"),
            notes = (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            apkUrl = url,
            sizeBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: -1,
        )
    }

    suspend fun download(context: Context, release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "kelimo-${release.version}.apk")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val c = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Kelimo")
                }
                try {
                    if (c.responseCode != 200) error("http ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                    c.inputStream.use { input ->
                        out.outputStream().buffered().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n); done += n
                                if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                } finally { c.disconnect() }
                verify(context, out)
            }.onFailure { Log.w("Updater", "download failed", it) }.getOrDefault(false)
        }
        _state.value = if (ok) UpdateState.Ready(release, out) else { out.delete(); UpdateState.Failed(release, "download") }
    }

    /** The downloaded APK must be this app, signed with the same key. Anything else is rejected. */
    private fun verify(context: Context, apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val new = pm.getPackageArchiveInfo(apk.path, flags) ?: return false
        if (new.packageName != context.packageName) return false
        val mine = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return false
        val theirs = new.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet() ?: return false
        return mine == theirs
    }

    /** Opens the system installer. First asks for the "install unknown apps" permission if missing. */
    fun install(context: Context, apk: File) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() { (_state.value as? UpdateState.Available)?.let { _state.value = UpdateState.Dismissed(it.release) } }
}

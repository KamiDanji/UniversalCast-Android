package com.universalcast.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * @param repo "owner/name" of the GitHub repository whose releases carry the APKs.
 * @param assetPrefix APK file-name prefix identifying this app's asset in a release (e.g. "universalcast-receiver").
 * @param currentVersion the running app's versionName.
 * @param enabled false for debug builds: their applicationId differs from the release one, so they can't be updated in place.
 */
data class UpdateConfig(val repo: String, val assetPrefix: String, val currentVersion: String, val enabled: Boolean)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    /** Only produced by a manual check. */
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
    /** Android blocks installs from this app until the user allows it in system settings. */
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateState
    /** Handed to the system installer, waiting for the user to confirm there. */
    data class Installing(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo? = null) : UpdateState
}

class UpdateManager(context: Context, private val config: UpdateConfig) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    val currentVersion: String get() = config.currentVersion
    val enabled: Boolean get() = config.enabled

    @Volatile private var lastCheckMs = 0L
    private var job: Job? = null

    init { active = this }

    /** Startup/resume check: silent unless a newer release exists. Skipped if one ran within [maxAgeMs]. */
    fun checkIfStale(maxAgeMs: Long = 60 * 60_000L) {
        if (System.currentTimeMillis() - lastCheckMs < maxAgeMs) return
        check(manual = false)
    }

    /** "Check for updates" button: also reports "up to date" and failures. */
    fun checkNow() = check(manual = true)

    private fun check(manual: Boolean) {
        if (!config.enabled) {
            if (manual) _state.value = UpdateState.Failed("Updates are disabled in debug builds.")
            return
        }
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        if (!manual && s is UpdateState.Available) return // already showing one
        lastCheckMs = System.currentTimeMillis()
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                val json = fetchText("https://api.github.com/repos/${config.repo}/releases/latest")
                val rel = ReleaseParser.parse(json, config.assetPrefix)
                _state.value = when {
                    rel != null && Versions.isNewer(rel.version, config.currentVersion) -> UpdateState.Available(rel)
                    manual -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                // A failed background check (offline, rate limit, no release yet) must never bother the user.
                _state.value = if (manual) UpdateState.Failed(describe(e)) else UpdateState.Idle
            }
        }
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Idle
    }

    /** Downloads the offered release, verifies it, and opens the system installer. */
    fun install() {
        val rel = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.NeedsInstallPermission -> s.release
            is UpdateState.Failed -> s.release ?: return
            else -> return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsInstallPermission(rel)
            return
        }
        _state.value = UpdateState.Downloading(rel, 0)
        job = scope.launch {
            try {
                val apk = download(rel)
                verifyArchive(apk)
                _state.value = UpdateState.Installing(rel)
                commit(apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(describe(e), rel)
            }
        }
    }

    /** Opens the system screen where the user allows this app to install packages. */
    fun openInstallPermissionSettings() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            app.startActivity(i)
        } catch (_: Exception) {
            _state.value = UpdateState.Failed(
                "This device has no screen to allow installs. Run once from a computer:  adb shell appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow",
                (_state.value as? UpdateState.NeedsInstallPermission)?.release,
            )
        }
    }

    // --- network -------------------------------------------------------------------------------------------------

    private fun fetchText(url: String): String =
        open(url, "application/vnd.github+json").use { it.readBytes().toString(Charsets.UTF_8) }

    /** GET with manual redirect handling so every hop stays on https and a trusted host. */
    private fun open(url: String, accept: String): java.io.InputStream {
        var current = URL(url)
        repeat(6) {
            require(current.protocol == "https" && TrustedHosts.allowed(current.host)) { "Refusing to fetch from ${current.host}" }
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", accept)
            c.setRequestProperty("User-Agent", "UniversalCast-Updater")
            when (val code = c.responseCode) {
                200 -> return c.inputStream
                301, 302, 303, 307, 308 -> {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without location")
                    c.disconnect()
                    current = URL(current, loc)
                }
                404 -> throw IOException("No release found (is the repository public and does it have a release?)")
                403, 429 -> throw IOException("GitHub is rate limiting requests; try again later")
                else -> throw IOException("Server answered $code")
            }
        }
        throw IOException("Too many redirects")
    }

    private fun download(rel: ReleaseInfo): File {
        val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        var read = 0L
        open(rel.downloadUrl, "application/octet-stream").use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    md.update(buf, 0, n)
                    read += n
                    if (rel.sizeBytes > 0) {
                        val pct = (read * 100 / rel.sizeBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(rel, pct) }
                    }
                }
            }
        }
        if (rel.sizeBytes > 0 && read != rel.sizeBytes) { out.delete(); throw IOException("Download incomplete") }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (rel.sha256 != null && !sha.equals(rel.sha256, ignoreCase = true)) {
            out.delete()
            throw IOException("Downloaded file failed its integrity check")
        }
        return out
    }

    /** The file must be this same app and newer. (Android additionally requires the same signing key to install.) */
    private fun verifyArchive(apk: File) {
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: throw IOException("Downloaded file is not a valid APK")
        if (info.packageName != app.packageName) throw IOException("Downloaded APK is for a different app (${info.packageName})")
        val installed = pm.getPackageInfo(app.packageName, 0)
        if (versionCode(info) <= versionCode(installed)) throw IOException("Downloaded APK is not newer than the installed version")
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    // --- install -------------------------------------------------------------------------------------------------

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { os ->
                    input.copyTo(os)
                    session.fsync(os)
                }
            }
            val cb = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(app, id, cb, flags).intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return
                try {
                    app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    _state.value = UpdateState.Failed("Could not open the Android installer.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // normally the process is replaced right here
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Idle // the user said no
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> _state.value = UpdateState.Failed(
                "Android refused the update because it was signed with a different key than the installed app. " +
                    "Uninstall the app and install the release version once; later updates then work.",
            )
            else -> _state.value = UpdateState.Failed(message ?: "Installation failed (code $status).")
        }
    }

    private fun describe(e: Exception) = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "Can't reach GitHub. Check the internet connection."
        else -> e.message ?: "Update failed."
    }

    companion object {
        @Volatile internal var active: UpdateManager? = null
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        UpdateManager.active?.onInstallResult(status, message, confirm)
    }
}

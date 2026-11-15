package com.jsnto.dashhome.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.jsnto.dashhome.BuildConfig
import com.jsnto.dashhome.data.LatestRelease
import com.jsnto.dashhome.data.parseLatestRelease
import com.jsnto.dashhome.data.pickApkAsset
import com.jsnto.dashhome.data.releaseIsNewer
import com.jsnto.dashhome.data.releaseVersion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The settings "Update" section's engine: checks the public GitHub
 * releases of the app's own repository for a newer build, downloads the
 * newest release's APK asset to the app's storage with progress, and hands
 * it to the platform's installer (gated on the phone's "install unknown
 * apps" permission).
 *
 * The app is sideloaded as an APK (there is no store), so this is the only
 * update path. The version ordering and the response parse are the pure
 * domain in `data/UpdateCheck.kt` ([parseLatestRelease], [releaseVersion],
 * [releaseIsNewer], [pickApkAsset]); this class is the
 * Android-aware plumbing around them (the OkHttp fetch + streamed
 * download, the install-settings page's open with its fallback chain,
 * the [PackageInstaller] session the APK is staged into, the platform
 * confirmation-ui launch over that session, the session outcome's
 * surfacing, and the
 * [android.Manifest.permission.REQUEST_INSTALL_PACKAGES] gate).
 *
 * The check is unauthenticated (no token): the `jsnto/dashhome` repo is
 * public, so the plain `api.github.com` read is the whole flow.
 *
 * The pure domain is what the unit tests pin; the network + install here
 * are driven by the UI (see [AppUpdateSection]) and never unit-tested with
 * a live network.
 */
class UpdateManager(
    /** The hosting activity (a [ComponentActivity] - the app's single activity is one). */
    private val activity: ComponentActivity,
    /** The activity-scoped scope by default (cancelled on activity destroy). */
    private val scope: CoroutineScope = activity.lifecycleScope,
    /** Test seam: the HTTP client (a real [OkHttpClient] by default). */
    private val client: OkHttpClient = OkHttpClient(),
    /**
     * Test seam: whether the phone has an activity for the install-settings
     * action (the real resolver by default - the package manager's intent
     * resolution). The device round 2026-11-14
     * (Pixel 7 Pro, API 37): the modern per-app action does not resolve
     * on the platform build while the legacy action opens the same
     * per-app page - a test drives the seam to simulate the device's
     * resolver.
     */
    private val installSettingsResolver: (String) -> Boolean = { action ->
        activity.packageManager
            .queryIntentActivities(installSettingsIntentFor(action, activity.packageName), 0)
            .isNotEmpty()
    },
    /**
     * Test seam: hands the downloaded APK to the platform installer
     * (the real [stageInstallSession] by default - the APK's bytes
     * written into a fresh [PackageInstaller] session, committed with
     * the commit status receiver, the session's id returned). A test may
     * pass a fake (a session id, or `null` for a refused staging): the
     * platform's session machinery is the on-device boundary the unit
     * tests are out of reach of (like the install hop), and the app-side
     * contract (the confirmation-ui intent the session id is launched
     * through, the session outcome's handling) is what the tests assert.
     *
     * The real hand-off is blocking (the APK's copy + fsync + commit)
     * and runs inside [launchInstall]'s [Dispatchers.IO] hop: it is not
     * cancellable mid-staging, so if the hosting scope is cancelled (or
     * the process is killed) while a staging is in flight, a committed
     * session may be left without its confirmation UI. The consequence
     * is bounded - no install can happen (the platform's confirmation
     * is still required), and the platform cleans the session up on
     * uninstall - and is deliberately accepted.
     */
    private val handToInstaller: (File) -> Int? = {
        apk -> stageInstallSession(activity, apk, installStatusSender(activity))
    },
    /** Surfaces a plain message (a toast) to the UI. */
    private val onEvent: (String) -> Unit = {
        Toast.makeText(activity, it, Toast.LENGTH_LONG).show()
    },
) {

    // ---- state (the section renders this) ----

    /**
     * The area's lifecycle (see [UpdateState]). The section reads it to
     * pick its copy and which buttons are live.
     */
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * The download's progress, `0f` to `1f` (meaningful only while the
     * state is [UpdateState.Downloading]; a response with no known length
     * jumps straight to `1f` when it lands).
     */
    private val _progress = MutableStateFlow(0f)

    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * Whether the user went to the phone's "install unknown apps" settings
     * and has not yet come back: set when [requestInstall] has to open
     * those settings instead of installing, consumed (and acted on) by
     * [onActivityResumed] when the activity returns.
     */
    @Volatile
    private var awaitingPermission = false

    // ---- the check ----

    /**
     * Checks the releases page for a newer build. The running version is
     * [BuildConfig.VERSION_NAME]; the newest release's tag (a leading
     * `v`/`V` stripped) is compared to it NUMERICALLY per component
     * (never by string, so `0.10.0 > 0.9.0`). The result settles to
     * [UpdateState.UpToDate] (equal or older - always naming the RUNNING
     * version) or [UpdateState.UpdateAvailable] (strictly newer), or
     * [UpdateState.Error] on a network failure, no published release, or
     * an unexpected response.
     */
    fun checkForLatest() {
        if (_state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val release = fetchLatestRelease()
                val current = BuildConfig.VERSION_NAME
                val latest = releaseVersion(release.tagName)
                _state.value = if (releaseIsNewer(release.tagName, current)) {
                    UpdateState.UpdateAvailable(current, latest, release)
                } else {
                    // Equal or older: the running build is the newest, so
                    // both fields carry the RUNNING version (the release's
                    // version is never printed as the current one).
                    UpdateState.UpToDate(current, current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(checkErrorMessage(e))
            }
        }
    }

    // ---- the download + install ----

    /**
     * Downloads the newest release's APK asset (the [pickApkAsset] pick -
     * the first `.apk` in the release's assets, never a hard-coded file
     * name) into the app's storage, reporting [progress]. On success the
     * state settles to [UpdateState.ReadyToInstall] with the APK's path;
     * on a missing asset or a download failure it settles to
     * [UpdateState.Error]. Only callable while [UpdateState.UpdateAvailable].
     */
    fun updateToLatest() {
        val available = _state.value as? UpdateState.UpdateAvailable ?: return
        val asset = pickApkAsset(available.release)
        if (asset == null) {
            _state.value = UpdateState.Error("That release has no APK to install.")
            return
        }
        _state.value = UpdateState.Downloading(available.latestVersion)
        _progress.value = 0f
        scope.launch {
            try {
                val file = downloadTo(asset.downloadUrl, apkFile()) { p -> _progress.value = p }
                _state.value = UpdateState.ReadyToInstall(available.latestVersion, file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(downloadErrorMessage(e))
            }
        }
    }

    /**
     * The user tapped the install action while [UpdateState.ReadyToInstall]:
     * when the phone's "install unknown apps" permission is granted, hand
     * the APK to the platform installer ([launchInstall]); when it is not,
     * explain the requirement in a plain message and open the phone's own
     * install-settings screen ([openInstallSettings] - the download is
     * kept and [onActivityResumed] offers the retry path on the return).
     */
    fun requestInstall() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (canInstallPackages()) {
            launchInstall(File(ready.apkPath), ready)
        } else {
            awaitingPermission = true
            onEvent(
                "Android requires the \"install unknown apps\" permission for this app. " +
                    "Allow it in the phone's settings, then come back and tap Install again.",
            )
            if (!openInstallSettings()) {
                // The settings page could not be opened (no candidate
                // action resolved - the round's dead-end, now impossible
                // to hit silently): the user is told the manual path.
                onEvent(
                    "The install-settings page didn't open. Open the phone's Settings > Apps > " +
                        "dashhome > Install unknown apps and turn it on, then come back and tap " +
                        "Install again.",
                )
            }
        }
    }

    /**
     * Whether a staging (the [handToInstaller] hand-off) is in flight:
     * a second install attempt while it is is a no-op (at most one
     * session per attempt is armed). Set and cleared on the main thread
     * only (the attempt's entry, and the staging's completion).
     */
    private var stagingInFlight = false

    /**
     * Hands the downloaded APK to the platform installer (the
     * [handToInstaller] seam - the real [stageInstallSession] by
     * default): the staging writes the APK's bytes into a fresh
     * [PackageInstaller] session and commits it, and the platform's own
     * confirmation UI ("Update this app?", with the Play Protect scan
     * under it) is launched over the session's id. The session's outcome
     * - the platform's install result, including its failure reason -
     * arrives through [installStatusReceiver] -> [onInstallResult]. A
     * fresh attempt clears a previous attempt's failure copy; a refused
     * staging (or a missing file) settles its own plain message instead
     * of silently idling. The pane is unchanged until the confirmation
     * UI launches, so a second attempt inside the in-flight staging's
     * window is possible: it is a no-op - at most one session per
     * attempt is armed ([stagingInFlight]).
     */
    private fun launchInstall(apk: File, ready: UpdateState.ReadyToInstall) {
        if (stagingInFlight) return
        if (!apk.exists()) {
            _state.value = UpdateState.Error("The downloaded file is missing. Download it again to install.")
            return
        }
        _state.value = ready.copy(installFailure = null)
        // The staging's heavy IO (the APK's copy + fsync + commit) runs
        // off the main thread; only the id's arming, the pane's update,
        // and the confirmation-ui launch run on it.
        stagingInFlight = true
        scope.launch {
            try {
                val sessionId = withContext(Dispatchers.IO) { handToInstaller(apk) }
                if (sessionId == null) {
                    _state.value = ready.copy(
                        installFailure = "Couldn't hand the download to the phone's installer. Try again.",
                    )
                    return@launch
                }
                installingSessionId = sessionId
                activity.startActivity(installUiIntent(sessionId))
            } finally {
                stagingInFlight = false
            }
        }
    }

    /**
     * Opens the phone's "install unknown apps" screen for this app (the
     * step the user takes before the first install): the candidate
     * actions ([INSTALL_SETTINGS_ACTIONS], the modern per-app action
     * first) are tried in order and the first one the resolver
     * ([installSettingsResolver]) accepts is launched. The device round
     * 2026-11-14 (Pixel 7 Pro, API 37): the modern action did not resolve
     * (the launch failed with "unable to resolve Intent" and the old
     * runCatching swallowed it - the user saw only the toast and the page
     * never opened), while the legacy action opened the same per-app page.
     * `false` when no candidate resolves (the caller tells the user the
     * manual path instead of silently idling).
     */
    private fun openInstallSettings(): Boolean {
        val action = pickInstallSettingsAction(INSTALL_SETTINGS_ACTIONS, installSettingsResolver)
            ?: return false
        activity.startActivity(installSettingsIntentFor(action, activity.packageName))
        return true
    }

    /**
     * Called from the activity's onResume (the return from the install
     * settings, or any resume - the lifecycle observer in [init] drives
     * it). Only acts when [requestInstall] sent the user to the settings
     * screen: if the permission is now granted the install is launched
     * automatically (the kept APK), otherwise the download is kept and
     * the retry path (the Install button) is offered.
     */
    fun onActivityResumed() {
        if (!awaitingPermission) return
        awaitingPermission = false
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (canInstallPackages()) {
            launchInstall(File(ready.apkPath), ready)
        } else {
            onEvent(
                "The \"install unknown apps\" permission is still off. " +
                    "The download is kept - tap Install to try again.",
            )
        }
    }

    // ---- the platform seams (kept here so the section stays presentational) ----

    /** Whether the phone allows this app to install packages (API 26+; the app's min). */
    fun canInstallPackages(): Boolean =
        activity.packageManager.canRequestPackageInstalls()

    /**
     * The platform's own confirmation UI ("Update this app?") over the
     * staged install session: the [Intent.ACTION_INSTALL_PACKAGE] with
     * the session's id (the platform installs the session's APK - with
     * the Play Protect scan under it - and reports the session's outcome
     * through the commit status receiver's broadcast,
     * [installStatusReceiver]).
     */
    private fun installUiIntent(sessionId: Int): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(Uri.parse("package:" + activity.packageName), APK_MIME_TYPE)
            putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        }

    /** The install session's id the current [launchInstall] launched (the outcome's filter). */
    @Volatile
    private var installingSessionId: Int? = null

    /**
     * The staged install session's outcome (the commit status
     * receiver's broadcast): when the session's install completes (the
     * confirmation dialog's outcome), the platform fills it with the
     * session's id ([PackageInstaller.EXTRA_SESSION_ID]), the install's
     * result status ([PackageInstaller.EXTRA_STATUS]), and the platform's
     * reason ([PackageInstaller.EXTRA_STATUS_MESSAGE]). A broadcast for a
     * session the app did not launch (a different id) is ignored; a
     * success (the new version replaces this build) and a user abort (a
     * decline) consume the session's id, but a pending broadcast (the
     * confirmation is still open - the terminal outcome for the same
     * session follows) does not, and a new install attempt overwrites
     * the id.
     */
    val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (sessionId != installingSessionId) return
            val status =
                intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            if (status == PackageInstaller.STATUS_SUCCESS ||
                status == PackageInstaller.STATUS_FAILURE_ABORTED
            ) {
                installingSessionId = null
            }
            onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    init {
        // The one-time "install unknown apps" permission flow: the return
        // from the phone's install-settings screen (the activity resumes)
        // re-checks the permission and launches the kept install when it
        // was granted. A no-op on every other resume (the flag above only
        // arms after this engine sent the user to the settings screen).
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onActivityResumed()
        })
        // The staged install session's outcome (the commit status
        // receiver's broadcast: the platform fills it with the session's
        // id, the install's result status, and the platform's reason):
        // a rejected install's failure must surface (see
        // [onInstallResult]) - the fire-and-forget install launch this
        // engine used to do could never see it (the device round
        // 2026-11-14: the app sat at "ready to install" after the
        // platform's rejection).
        ContextCompat.registerReceiver(
            activity,
            installStatusReceiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * The staged install session's outcome ([PackageInstaller.STATUS_*]
     * status + the platform's reason, when the broadcast carried it): a
     * failure surfaces its plain what-to-do copy on the kept
     * [UpdateState.ReadyToInstall] (the download is kept and the Install
     * action stays live - the retry); a success is the new version
     * replacing this build, a user abort is a decline, and a pending
     * session is still waiting - all leave the state untouched.
     * [installFailureMessage] returns `null` for those.
     */
    private fun onInstallResult(status: Int, statusMessage: String? = null) {
        if (status == PackageInstaller.STATUS_SUCCESS ||
            status == PackageInstaller.STATUS_FAILURE_ABORTED ||
            status == PackageInstaller.STATUS_PENDING_USER_ACTION
        ) return
        val message = installFailureMessage(status, statusMessage) ?: return
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        // A no-reason failure never overwrites a failure copy already
        // surfaced (a concrete one, with the platform's reason).
        if (ready.installFailure != null && statusMessage == null) return
        _state.value = ready.copy(installFailure = message)
    }

    /** Where a downloaded APK lives (the app-internal updates/ folder). */
    private fun apkFile(): File {
        val dir = File(activity.filesDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "dashhome-update.apk")
    }

    // ---- the network (the pure parse lives in data) ----

    /**
     * Fetches the `/releases/latest` response and parses it (on the IO
     * dispatcher; the body parse is the pure [parseLatestRelease]). A 404
     * (no published release) is its own error, and any other non-2xx
     * answer or network failure is an IO error.
     */
    private suspend fun fetchLatestRelease(): LatestRelease =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "dashhome")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseLatestRelease(body)
                    response.code == 404 -> throw NoReleasesException()
                    else -> throw IOException("GitHub answered with code ${response.code}")
                }
            }
        }

    /**
     * Downloads [url] to [target] (on the IO dispatcher), streaming it and
     * reporting [onProgress] (`0f`..`1f`; a response with no known length
     * reports `1f` when it lands). A non-2xx answer or an empty body is an
     * IO error.
     */
    private suspend fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", "dashhome").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download answered with code ${response.code}")
                }
                val body = response.body ?: throw IOException("The download came back empty")
                val total = body.contentLength()
                var received = 0L
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            received += n
                            if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total <= 0) onProgress(1f)
                target
            }
        }

    // ---- plain error copy (a failure never crashes; it is a message) ----

    private fun checkErrorMessage(e: Throwable): String =
        when (e) {
            is NoReleasesException -> "There are no published releases yet. Check back later."
            is IOException -> "Couldn't reach the releases page. Check the connection and try again."
            else -> "Couldn't check for updates. Try again."
        }

    private fun downloadErrorMessage(e: Throwable): String =
        when (e) {
            is IOException -> "The download didn't finish. Check the connection and try again."
            else -> "The download didn't finish. Try again."
        }

    /** No published release exists (the GitHub API answers 404 for /releases/latest). */
    private class NoReleasesException : IOException("No releases published yet")

    private companion object {
        /** The app's own public releases (unauthenticated; the repo is public). */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/jsnto/dashhome/releases/latest"

        /** The APK's mime type (the install-ui intent's data type). */
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** The download's stream-buffer size (64 KiB). */
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
    }
}

/**
 * Stages [apk] into a fresh platform install session for [activity] and
 * commits it (the platform boundary: [PackageInstaller.createSession],
 * the APK's bytes written into the session with the fsync, the commit
 * with [statusSender]): the session's id, for the confirmation-ui launch
 * (the install-ui intent carries it). `null` when the platform refused
 * the staging (a session-create or write failure - rare; the caller
 * settles its plain message), or when the platform predates the
 * session's staging API (the caller's same plain message - the app's
 * min is API 26, the API's floor is API 28). The session's outcome (the
 * platform's install result) arrives through the app's registered
 * outcome channel (the commit status receiver's broadcast), not here.
 */
private fun stageInstallSession(
    activity: ComponentActivity,
    apk: File,
    statusSender: IntentSender,
): Int? {
    // The session's staging API (createSession with params) is API 28+;
    // below it the staging is unavailable (the caller's plain message -
    // never a silent idle).
    if (Build.VERSION.SDK_INT < 28) return null
    var session: PackageInstaller.Session? = null
    val staged = runCatching {
        val installer = activity.packageManager.packageInstaller
        // A full install that requires the user's confirmation (the
        // platform's "Update this app?" dialog, launched over the
        // session's id by the install-ui intent).
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
        )
        session = installer.openSession(sessionId)
        val size = apk.length()
        val out = session.openWrite(APK_SESSION_NAME, 0, size)
        try {
            apk.inputStream().use { input -> input.copyTo(out) }
            session.fsync(out)
        } finally {
            out.close()
        }
        // The outcome channel: when the session's install completes (the
        // confirmation dialog's outcome), the platform fills [statusSender]
        // with the session's id, the result status, and the platform's
        // reason and broadcasts it.
        session.commit(statusSender)
        sessionId
    }
    staged.exceptionOrNull()?.let {
        Log.w(STAGING_LOG_TAG, "The install session's staging failed", it)
        // A refused staging must not leave a half-written session on
        // the platform (the abandon's own failure is swallowed: the
        // seam's contract is to never throw).
        runCatching { session?.abandon() }
    }
    staged.getOrNull()?.let {
        // The commit succeeded (the committed session stays on the
        // platform until installed): close the handle, best-effort - a
        // close failure here must not turn it into a refusal.
        runCatching { session?.close() }
    }
    return staged.getOrNull()
}

/** The log's tag for the staging's refusal (a stable tag for the device rounds' trace). */
private const val STAGING_LOG_TAG = "UpdateManager"

/** The staged APK's session file name (the session's apk, the platform's label for it). */
private const val APK_SESSION_NAME = "dashhome-update.apk"

/**
 * The commit status receiver's broadcast action (private to the app:
 * the sender is a [PendingIntent] scoped to the app's own package,
 * [PendingIntent.FLAG_IMMUTABLE] - the platform only fills the extras,
 * it never launches this receiver itself).
 */
private const val INSTALL_STATUS_ACTION = "com.jsnto.dashhome.INSTALL_STATUS"

/** The commit status receiver's [IntentSender] (the outcome channel). */
private fun installStatusSender(activity: ComponentActivity): IntentSender =
    PendingIntent.getBroadcast(
        activity,
        0,
        Intent(INSTALL_STATUS_ACTION).setPackage(activity.packageName),
        PendingIntent.FLAG_IMMUTABLE,
    ).getIntentSender()

/**
 * The actions that open the phone's "install unknown apps" screen for an
 * app, in fallback order (the device round 2026-11-14, Pixel 7 Pro,
 * API 37: the modern per-app action does not resolve on that platform
 * build - the launch failed with "unable to resolve Intent
 * { act=android.settings.MANAGE_APP_INSTALL_SETTINGS
 * dat=package:<pkg> }" - while the legacy action opens the same per-app
 * page, and the app's own info page - always present - carries the
 * "Install unknown apps" row as the manual path). The per-app
 * install-settings page itself is API 26+ (the app's min).
 */
val INSTALL_SETTINGS_ACTIONS: List<String> = listOf(
    /** The modern per-app action (API 30+; does not resolve on some platform builds). */
    "android.settings.MANAGE_APP_INSTALL_SETTINGS",
    /** The legacy per-app action (API 26+; the round's workaround that opened the page). */
    "android.settings.MANAGE_UNKNOWN_APP_SOURCES",
    /** The app's own info page (always resolvable; the manual path). */
    "android.settings.APPLICATION_DETAILS_SETTINGS",
)

/**
 * The install-settings action to launch: the first [candidate] whose
 * action [resolves] (the platform reports an activity for it). `null`
 * when none does - the caller tells the user the manual path instead of
 * silently idling (the round's dead-end: the toast fired, the page never
 * opened, and nothing said so).
 */
fun pickInstallSettingsAction(candidates: List<String>, resolves: (String) -> Boolean): String? =
    candidates.firstOrNull { resolves(it) }

/**
 * The install-settings intent for [action] (the per-app page for
 * [packageName] - the `package:` data the per-app settings pages take).
 */
fun installSettingsIntentFor(action: String, packageName: String): Intent =
    Intent(action).apply { data = Uri.parse("package:" + packageName) }

/**
 * The plain message for a failed platform install (the install session's
 * result status - the [PackageInstaller.STATUS_FAILURE_*] constants -
 * plus the platform's reason, when the outcome broadcast carried it):
 * the concrete failure, plus what to do. The device round 2026-11-14
 * (the polex round): the platform rejected the install
 * (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` - the platform's signature wall)
 * and the app sat at "ready to install" with no error; the failure must
 * surface, not be swallowed. `null` when there is no failure to report:
 * the install succeeded (the new version replaces this build - there is
 * nothing left to say), the user aborted the confirmation dialog (a
 * decline, not a failure - the download is kept and the install action
 * stays live), or the session is still waiting on the user's
 * confirmation.
 */
fun installFailureMessage(status: Int, statusMessage: String? = null): String? {
    if (status == PackageInstaller.STATUS_SUCCESS ||
        status == PackageInstaller.STATUS_FAILURE_ABORTED ||
        status == PackageInstaller.STATUS_PENDING_USER_ACTION
    ) return null
    val kind = when (status) {
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "The phone refused the update: the new version can't be installed over the one " +
                "you have (a different signature or a conflicting version)."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "The phone refused the update: it conflicts with the installed dashhome."
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "The phone blocked the update (the device's app-verification or policy blocked it)."
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "The phone ran out of storage for the update."
        PackageInstaller.STATUS_FAILURE_TIMEOUT ->
            "The phone's installer timed out."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "The phone's installer rejected the APK as invalid."
        else ->
            "The phone's installer rejected the update (installer status $status)."
    }
    val detail = if (statusMessage != null && statusMessage.isNotBlank()) {
        " The installer said: \"$statusMessage\"."
    } else {
        ""
    }
    val whatToDo = when (status) {
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Uninstall dashhome first (Settings > Apps > dashhome > Uninstall), then install " +
                "the new version again."
        PackageInstaller.STATUS_FAILURE_INVALID ->
            "Download the new version again (Check for latest version, then Update to latest), " +
                "then install it."
        else ->
            "Tap Install again - the download is kept; if it keeps failing, uninstall dashhome " +
                "first (Settings > Apps > dashhome > Uninstall), then install the new version again."
    }
    return kind + detail + " " + whatToDo
}

/**
 * The settings "Update" section's lifecycle. From an unchecked [Idle], a
 * check settles to [UpToDate] (the running build is the newest, so
 * [currentVersion] == [latestVersion]) or [UpdateAvailable] (a newer
 * release exists and can be downloaded); the download runs through
 * [Downloading] (with a separate progress flow) to [ReadyToInstall] (the
 * APK is on disk, ready for the platform installer - and, when the
 * platform rejected an install attempt, [ReadyToInstall.installFailure]
 * carries the failure's plain what-to-do copy, the download kept and the
 * install action live); any step can settle
 * to [Error] with a plain user-facing message (a network failure, no
 * published release, no APK asset, or a missing download).
 */
sealed class UpdateState {
    /** No check has been made yet. */
    object Idle : UpdateState()

    /** A check is in flight. */
    object Checking : UpdateState()

    /** Checked; the running build is the newest ([currentVersion] == [latestVersion]). */
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateState()

    /** Checked; [latestVersion] is newer than [currentVersion] and [release] carries its APK. */
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val release: LatestRelease,
    ) : UpdateState()

    /** Downloading [latestVersion] (the progress is the manager's [UpdateManager.progress]). */
    data class Downloading(val latestVersion: String) : UpdateState()

    /**
     * [latestVersion] is downloaded to [apkPath]; install it to update.
     * [installFailure] (set when the platform rejected the last install
     * attempt) carries the failure's plain what-to-do copy - the download
     * is kept and the install action stays live (the retry).
     */
    data class ReadyToInstall(
        val latestVersion: String,
        val apkPath: String,
        val installFailure: String? = null,
    ) : UpdateState()

    /** A step failed; [message] is the plain user-facing copy. */
    data class Error(val message: String) : UpdateState()
}

package com.jsnto.dashhome.ui

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the settings "Update" section driven against the
 * REAL product: the real [DashhomeRoot] (phone layout) -> the real
 * settings screen -> the real [AppUpdateSection] -> the real
 * [UpdateManager] (its fetch, parse, the streamed download, the
 * install-settings page's open with its fallback chain, the staged
 * install session's launch, and the "install unknown apps" gate) -> the
 * real platform install intents.
 *
 * The one seam is the manager's own documented one (its `client`
 * constructor parameter, "a real [OkHttpClient] by default", passed
 * through [DashhomeRoot]'s `updateManager` parameter): the client routes
 * the product's hardcoded `api.github.com` url over loopback to a
 * DISPOSABLE local stand-in of the releases API ([FakeReleasesApi]) that
 * serves GitHub-shaped responses, so every branch is drivable
 * deterministically while the product code (the url, the request, the
 * parse, the download, the state, the intents) runs unmodified. Two more
 * documented seams drive the platform's own behavior the JVM cannot
 * provide: the `installSettingsResolver` (the package manager's intent
 * resolution - the tests simulate the round's device, where the modern
 * per-app action does not resolve) and the `handToInstaller` (the
 * platform's [PackageInstaller] session machinery - the fake returns the
 * session id the app-side contract is asserted on). A real `startActivity`
 * on the real activity records the intent the shadow sees (the
 * install-settings jump and the installer launch are asserted on the
 * recorded intents). The on-device install hop (the platform installer
 * replacing the app) is out of reach here (no device/emulator in the
 * test); the session's outcome (the platform's install result) is
 * delivered through the app's own outcome channel (the commit status
 * receiver's broadcast), the way the platform would.
 *
 * The section's buttons sit low on a short screen, so the screen is tall
 * (h900dp) and every row is visible without scrolling (the phone layout:
 * the screen width stays below the wide-display threshold).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateSectionUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var server: FakeDashServer
    private var releasesApi: FakeReleasesApi? = null

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        releasesApi?.stop()
        // The shadow's "install unknown apps" permission is STATIC process
        // state (the install tests grant it): reset it so no test leaks
        // the grant into the next one (the happy path's settings gate
        // needs the permission OFF).
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setCanRequestPackageInstalls(false)
    }

    // ------------------------------------------------------------------
    // The happy path: check -> available update -> streamed download ->
    // the install-settings gate -> the permission grant -> the installer
    // launch.
    // ------------------------------------------------------------------

    @Test
    fun updateAvailableDownloadsTheApkAndInstallsItAfterThePermissionGrant() {
        val events = eventList()
        val api = startReleasesApi()
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset("dashhome-android-debug.apk", "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )

        startServer()
        val manager = testManager(api.port, events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        val activity = compose.activity

        // The section renders the running build and the unchecked state.
        compose.onNodeWithText("dashhome v0.3.2").assertExists()
        waitForText("Check the releases page to see if a newer version is out.", "the unchecked state")

        // The check fetches the (stand-in) releases page and settles to an
        // available update: the published v0.4.0 against the running 0.3.2.
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")

        // The download streams the asset (the throttled transfer keeps the
        // Downloading state + progress observable), then settles ready.
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloading v0.4.0", substring = true, what = "the download state")
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        // The APK landed on disk with the asset's exact bytes, and the
        // progress flow reached the end of the transfer.
        val apkFile = File(compose.activity.filesDir, "updates/$APK_NAME")
        check(apkFile.readBytes().contentEquals(apkBytes)) {
            "the downloaded APK must carry the served asset's bytes"
        }
        waitUntil({ manager.progress.value == 1f }, "the progress to reach 1f")

        // The permission is still off: tapping Install explains the
        // requirement and opens the phone's own "install unknown apps"
        // screen for this app (the download is kept) - the MODERN
        // per-app action (the resolver seam resolves everything, the
        // real device's modern resolver by default).
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the install-settings intent to launch")
        val settingsIntent = shadow.getNextStartedActivity()
        check(settingsIntent.action == ACTION_MANAGE_APP_INSTALL_SETTINGS) {
            "the engine must open the phone's install-settings screen, got ${settingsIntent.action}"
        }
        check(settingsIntent.dataString == "package:com.jsnto.dashhome") {
            "the install-settings screen must be the one for this app, got ${settingsIntent.dataString}"
        }
        check(events.any { it.contains("install unknown apps") }) {
            "the plain message must name the missing permission, got $events"
        }

        // The user grants the permission and comes back: the engine's
        // resume hook (the return from the install settings) hands the
        // kept APK to the platform installer (the staged session's
        // confirmation-ui launch over the session's id).
        shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
        manager.onActivityResumed()
        waitUntil(
            {
                shadow.peekNextStartedActivity() != null &&
                    shadow.peekNextStartedActivity() != settingsIntent
            },
            "the installer intent to launch",
        )
        val installIntent = shadow.getNextStartedActivity()
        check(installIntent.action == Intent.ACTION_INSTALL_PACKAGE) {
            "the installer is launched over the staged session (ACTION_INSTALL_PACKAGE), got ${installIntent.action}"
        }
        check(installIntent.dataString == "package:com.jsnto.dashhome") {
            "the install-ui intent must carry the app's package, got ${installIntent.dataString}"
        }
        check(installIntent.type == "application/vnd.android.package-archive") {
            "the install-ui intent must type the APK, got ${installIntent.type}"
        }
        check(installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == FAKE_INSTALL_SESSION_ID) {
            "the install-ui intent must carry the staged session's id, got ${installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)}"
        }

        // The platform's session outcome: the success (the new version
        // replaces this build - the process is torn down; nothing the
        // pane must say - the ready state stays, the download kept).
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
            },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
    }

    // ------------------------------------------------------------------
    // The device round 2026-11-14 (Pixel 7 Pro, API 37): the modern
    // per-app install-settings action does not resolve on the platform
    // build (the launch failed with "unable to resolve Intent" and the
    // old runCatching swallowed it - the toast fired, the page never
    // opened, and the flow dead-ended). The engine's fallback chain now
    // launches the legacy action, which opens the same per-app page.
    // ------------------------------------------------------------------

    @Test
    fun anUnresolvedModernSettingsActionFallsBackToTheLegacyAction() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        // The round's device resolver: the modern action does not resolve,
        // the legacy one does.
        val manager = testManager(
            api.port,
            events,
            installSettingsResolver = { it == ACTION_MANAGE_UNKNOWN_APP_SOURCES },
        )
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        // The permission is still off: tapping Install explains the
        // requirement and opens the phone's install-settings screen -
        // the LEGACY action this time (the modern one unresolved).
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the install-settings intent to launch")
        val settingsIntent = shadow.getNextStartedActivity()
        check(settingsIntent.action == ACTION_MANAGE_UNKNOWN_APP_SOURCES) {
            "the modern action unresolved: the engine must fall back to the legacy install-settings action, got ${settingsIntent.action}"
        }
        check(settingsIntent.dataString == "package:com.jsnto.dashhome") {
            "the install-settings screen must be the one for this app, got ${settingsIntent.dataString}"
        }
        check(events.any { it.contains("install unknown apps") }) {
            "the plain message must name the missing permission, got $events"
        }
    }

    // ------------------------------------------------------------------
    // No install-settings action resolves (a platform with no such page
    // at all - not the round's device): the engine tells the user the
    // manual path instead of silently idling (the round's dead-end - the
    // toast fired, the page never opened, and nothing said so).
    // ------------------------------------------------------------------

    @Test
    fun noResolvingSettingsActionTellsTheManualPathInsteadOfSilentlyIdling() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        val manager = testManager(api.port, events, installSettingsResolver = { false })
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        compose.onNodeWithText("Install now").performClick()
        compose.waitForIdle()
        check(shadowOf(compose.activity).peekNextStartedActivity() == null) {
            "nothing may be launched when no install-settings action resolves"
        }
        check(events.any { it.contains("The install-settings page didn't open") }) {
            "the engine must tell the user the manual path, got $events"
        }
    }

    // ------------------------------------------------------------------
    // The device round 2026-11-14 (the polex round): the platform
    // rejected the install (INSTALL_FAILED_UPDATE_INCOMPATIBLE - the
    // platform's signature wall) and the app sat at "ready to install"
    // with no error. The staged session's outcome now surfaces the
    // failure on the kept ready state: the download is kept and
    // the Install action stays live (the retry).
    // ------------------------------------------------------------------

    @Test
    fun aRejectedInstallSurfacesTheFailuresCopyAndKeepsTheInstallLive() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        val manager = testManager(api.port, events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        // The permission is granted up front: the install goes straight
        // to the platform (the staged session's confirmation-ui launch).
        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the installer intent to launch")
        val installIntent = shadow.getNextStartedActivity()
        check(installIntent.action == Intent.ACTION_INSTALL_PACKAGE) {
            "the installer is launched over the staged session, got ${installIntent.action}"
        }
        check(installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == FAKE_INSTALL_SESSION_ID) {
            "the install-ui intent must carry the staged session's id, got ${installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)}"
        }

        // The platform rejects the install (the round's signature wall):
        // the session's outcome arrives on the commit status receiver's
        // broadcast (the platform's status + its reason) and surfaces
        // (no more silent idle).
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                putExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE,
                    "Existing package com.jsnto.dashhome signatures do not match newer version; ignoring!",
                )
            },
        )
        compose.waitForIdle()
        waitForText(
            "The phone refused the update: the new version can't be installed over the one you have",
            substring = true,
            what = "the surfaced install failure",
        )
        waitForText("The installer said:", substring = true, what = "the quoted platform reason")
        // The pane is not idling silently: the ready copy is still there
        // (the download is kept) and the Install action is still live
        // (the retry).
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        assertSemanticsEnabled("Install now")
    }

    // ------------------------------------------------------------------
    // A session the app did not launch (not the staged install session)
    // is ignored, and the user's decline (the confirmation dialog's back)
    // is not a failure: no error surfaces, the pane stays ready (the
    // retry).
    // ------------------------------------------------------------------

    @Test
    fun aUserAbortedInstallKeepsThePaneReadyWithoutAnError() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        val manager = testManager(api.port, events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the installer intent to launch")

        // A session the app did not launch (not the staged install
        // session) is ignored - even with a failure status: no error
        // surfaces, the pane stays ready.
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, 999)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        check(compose.onAllNodesWithText("The phone", substring = true).fetchSemanticsNodes().isEmpty()) {
            "a broadcast for a foreign session must not surface an error in the pane"
        }

        // The user backs out of the confirmation dialog: a decline, not a
        // failure (the outcome broadcast's abort status) - no error
        // surfaces, the pane stays ready (the retry).
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)
            },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        check(compose.onAllNodesWithText("The phone", substring = true).fetchSemanticsNodes().isEmpty()) {
            "a user abort must not surface an error in the pane"
        }
    }

    // ------------------------------------------------------------------
    // A failed install without the platform's reason (no
    // EXTRA_STATUS_MESSAGE in the outcome broadcast): it still surfaces
    // (no more silent idle) - the plain rejection copy + the what-to-do,
    // the download kept, the Install action live (the retry).
    // ------------------------------------------------------------------

    @Test
    fun aFailedInstallWithoutThePlatformsReasonSurfacesThePlainFailure() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        val manager = testManager(api.port, events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the installer intent to launch")

        // The platform's failure arrives without its reason (no
        // EXTRA_STATUS_MESSAGE in the outcome broadcast): the plain
        // rejection copy surfaces.
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            },
        )
        compose.waitForIdle()
        waitForText(
            "The phone's installer rejected the update (installer status 1)",
            substring = true,
            what = "the plain failure copy",
        )
        // The pane is not idling silently: the ready copy is still there
        // and the Install action is still live (the retry).
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        assertSemanticsEnabled("Install now")
    }

    // ------------------------------------------------------------------
    // A pending outcome (the confirmation is still open) is not a
    // terminal result: it must not surface anything, and it must not
    // consume the staged session's id - the terminal outcome for the
    // same session follows, and if the pending consumed the id the
    // terminal failure would be dropped and the pane would idle with
    // no error (the silent idle the round's polex finding fixed).
    // ------------------------------------------------------------------

    @Test
    fun aPendingThenTerminalFailureBroadcastStillSurfacesTheFailure() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        val manager = testManager(api.port, events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)
        compose.onNodeWithText("Install now").performClick()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the installer intent to launch")

        // The commit broadcast reports the session as pending (the
        // confirmation is still open): no outcome yet - the pane is
        // untouched (no error surfaces).
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            },
        )
        compose.waitForIdle()
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        check(compose.onAllNodesWithText("The phone", substring = true).fetchSemanticsNodes().isEmpty()) {
            "a pending outcome must not surface an error in the pane"
        }

        // The user confirms, the platform rejects the install (the
        // round's signature wall): the terminal outcome for the same
        // session must still reach the receiver (the pending must not
        // have consumed the session's id) and surface.
        manager.installStatusReceiver.onReceive(
            compose.activity,
            Intent().apply {
                putExtra(PackageInstaller.EXTRA_SESSION_ID, FAKE_INSTALL_SESSION_ID)
                putExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                )
                putExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE,
                    "Existing package com.jsnto.dashhome signatures do not match newer version; ignoring!",
                )
            },
        )
        compose.waitForIdle()
        waitForText(
            "The phone refused the update: the new version can't be installed over the one you have",
            substring = true,
            what = "the surfaced install failure after the pending outcome",
        )
    }

    // ------------------------------------------------------------------
    // The pane is unchanged until the confirmation UI launches, so a
    // second Install attempt can fall inside the in-flight staging's
    // window: it must be a no-op - exactly one session is armed and the
    // confirmation UI is launched exactly once (a second would leave the
    // first session's outcome unattributed by the id filter, and a
    // zombie committed session on the platform).
    // ------------------------------------------------------------------

    @Test
    fun aSecondInstallAttemptDuringTheInFlightStagingIsANoOp() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.4.0",
            assets = listOf(asset(APK_NAME, "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )
        val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
        api.downloads[APK_NAME] = apkBytes

        startServer()
        // A seam that blocks while the staging is in flight (the window
        // a second attempt falls into): the second attempt must not
        // reach it.
        val stagingEntered = CountDownLatch(1)
        val stagingGate = CountDownLatch(1)
        val manager = testManager(
            api.port,
            events,
            handToInstaller = {
                stagingEntered.countDown()
                stagingGate.await()
                FAKE_INSTALL_SESSION_ID
            },
        )
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.4.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("Downloaded v0.4.0. Install it to update.", "the ready-to-install state")

        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(true)

        // The first attempt starts the staging (blocked inside the seam
        // - the in-flight window is open).
        manager.requestInstall()
        check(stagingEntered.await(5, TimeUnit.SECONDS)) {
            "the first install attempt must reach the installer seam"
        }
        // The second attempt while the staging is in flight: a no-op.
        manager.requestInstall()
        // The staging completes: exactly one session is armed and the
        // confirmation UI is launched exactly once.
        stagingGate.countDown()
        val shadow = shadowOf(compose.activity)
        waitUntil({ shadow.peekNextStartedActivity() != null }, "the installer intent to launch")
        val installIntent = shadow.getNextStartedActivity()
        check(installIntent.action == Intent.ACTION_INSTALL_PACKAGE) {
            "the installer is launched over the staged session, got ${installIntent.action}"
        }
        check(installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == FAKE_INSTALL_SESSION_ID) {
            "the install-ui intent must carry the staged session's id, got ${installIntent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)}"
        }
        compose.waitForIdle()
        check(shadow.peekNextStartedActivity() == null) {
            "a second attempt during the in-flight staging must not launch a second confirmation ui"
        }
        // The pane is untouched: the download is kept, no error.
        compose.onNodeWithText("Downloaded v0.4.0. Install it to update.").assertExists()
        check(compose.onAllNodesWithText("The phone", substring = true).fetchSemanticsNodes().isEmpty()) {
            "a second attempt during the in-flight staging must not surface an error"
        }
    }

    // ------------------------------------------------------------------
    // The running build is AHEAD of the published release (the live
    // real-world state: the only published release is v0.1.0 while this
    // build is 0.3.2): the check settles UpToDate and the copy names the
    // RUNNING version - the release's version is never mislabeled as the
    // current one.
    // ------------------------------------------------------------------

    @Test
    fun upToDateWhenThePublishedReleaseIsOlderNamesTheRunningVersion() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.1.0",
            assets = listOf(asset("dashhome-android-debug.apk", "http://127.0.0.1:${api.port}/download/$APK_NAME")),
        )

        startServer()
        setContentWith(testManager(api.port, events))
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()

        // The published v0.1.0 is older than the running 0.3.2: UpToDate,
        // and the copy prints the RUNNING version (not the release's).
        waitForText("You're up to date (current version v0.3.2).", "the up-to-date state")
        // No update to take: the download and install actions stay
        // disabled (Compose marks a disabled button's node with the
        // Disabled semantics property - it keeps the click action on it).
        assertSemanticsDisabled("Update to latest")
        assertSemanticsDisabled("Install now")
    }

    // ------------------------------------------------------------------
    // No published release exists: the GitHub API answers 404 for
    // /releases/latest - its own plain message, not a crash.
    // ------------------------------------------------------------------

    @Test
    fun noPublishedReleasesSurfacesThePlainMessage() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestStatus = 404
        api.latestBody = """{"message":"Not Found","documentation_url":"https://docs.github.com"}"""

        startServer()
        setContentWith(testManager(api.port, events))
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("There are no published releases yet. Check back later.", "the no-releases state")
    }

    // ------------------------------------------------------------------
    // A newer release that carries no APK asset: the check reports the
    // update, and the download action refuses with its own message (no
    // write attempted).
    // ------------------------------------------------------------------

    @Test
    fun aNewerReleaseWithoutAnApkAssetRefusesTheDownload() {
        val events = eventList()
        val api = startReleasesApi()
        api.latestBody = releaseJson(
            tag = "v0.5.0",
            assets = listOf(asset("release-notes.txt", "http://127.0.0.1:${api.port}/download/release-notes.txt")),
        )

        startServer()
        setContentWith(testManager(api.port, events))
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Update available: v0.5.0 (you have v0.3.2).", "the available-update state")
        compose.onNodeWithText("Update to latest").performClick()
        waitForText("That release has no APK to install.", "the refusal message")
    }

    // ------------------------------------------------------------------
    // The releases page is unreachable (connection refused): the check
    // settles to the connection-failure message, never a crash.
    // ------------------------------------------------------------------

    @Test
    fun anUnreachableReleasesPageSurfacesTheConnectionMessage() {
        val events = eventList()
        // Point the routing client at a port that refuses connections.
        startServer()
        val manager = testManager(deadPort(), events)
        setContentWith(manager)
        openSettingsAndUpdateChip()
        compose.onNodeWithText("Check for latest version").performClick()
        waitForText("Couldn't reach the releases page. Check the connection and try again.", "the connection-failure state")
    }

    // ------------------------------------------------------------------
    // The fixtures + helpers
    // ------------------------------------------------------------------

    private fun eventList(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Stands up the fake dashhome backend + the real app (phone layout). */
    /** Stands up the fake dashhome backend (the Prefs + DashApi state) - no composition. */
    private fun startServer() {
        server = FakeDashServer()
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(compose.activity, server.baseUrl)
    }

    /**
     * The test [UpdateManager] (the documented `client` constructor seam
     * pointed over loopback to [port], plus the `installSettingsResolver`
     * + `handToInstaller` platform seams): built before the single
     * composition of the test. The resolver seam resolves everything by
     * default (the modern action is picked first); the stager seam
     * returns the fake session id (no real session machinery in the test
     * - the platform's boundary).
     */
    private fun testManager(
        port: Int,
        events: MutableList<String>,
        installSettingsResolver: (String) -> Boolean = { true },
        handToInstaller: (File) -> Int? = { FAKE_INSTALL_SESSION_ID },
    ): UpdateManager =
        UpdateManager(
            compose.activity,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            routingClient(port),
            installSettingsResolver = installSettingsResolver,
            handToInstaller = handToInstaller,
            onEvent = { events += it },
        )

    /** The single composition of the test: the real app with the test manager (DashhomeRoot's documented test seam). */
    private fun setContentWith(manager: UpdateManager) {
        compose.setContent { DashhomeRoot(updateManager = manager) }
        compose.waitForIdle()
    }

    /** A REAL [OkHttpClient] whose one interceptor re-routes every request over loopback (only the network route is swapped). */
    private fun routingClient(port: Int): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val routed = chain.request().url.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()
            chain.proceed(chain.request().newBuilder().url(routed).build())
        }.build()

    /** A port that refuses connections (a bound-then-closed server socket). */
    private fun deadPort(): Int {
        ServerSocket(0).use { socket -> return socket.localPort }
    }

    /** Starts the disposable releases-API stand-in (one per test). */
    private fun startReleasesApi(): FakeReleasesApi {
        val api = FakeReleasesApi()
        api.start()
        releasesApi = api
        return api
    }

    /** Opens the settings screen (the top-bar icon) and the "Update" chip. */
    private fun openSettingsAndUpdateChip() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Update").performScrollTo()
        compose.onNodeWithText("Update").performClick()
        compose.waitForIdle()
    }

    /** Polls [condition] (with the looper settled between polls) until it holds. */
    private fun waitUntil(condition: () -> Boolean, what: String) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    /** Polls for a text node (exact, or [substring]) until it appears. */
    private fun waitForText(text: String, what: String, substring: Boolean = false) {
        waitUntil(
            {
                compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
            },
            "$what (text: $text)",
        )
    }

    /** A disabled Compose button keeps its click action; the node carries the Disabled semantics property instead. */
    private fun assertSemanticsDisabled(text: String) {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        check(node.config.any { it.key == SemanticsProperties.Disabled }) {
            "the '$text' button must be disabled (the Disabled semantics property is missing)"
        }
    }

    /** A live Compose button node carries no Disabled semantics property. */
    private fun assertSemanticsEnabled(text: String) {
        val node = compose.onNodeWithText(text).fetchSemanticsNode()
        check(node.config.none { it.key == SemanticsProperties.Disabled }) {
            "the '$text' button must be live (the Disabled semantics property is present)"
        }
    }

    /** GitHub `/releases/latest`-shaped JSON (extra fields, like the real API's, must be tolerated). */
    private fun releaseJson(tag: String, assets: List<Pair<String, String>>): String =
        """{"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,"created_at":"2026-11-01T00:00:00Z","assets":[""" +
            assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"$url","size":1,"state":"uploaded"}""" } +
            "]}"

    /** One release asset: its file name + the url to download it from. */
    private fun asset(name: String, url: String): Pair<String, String> = name to url

    /**
     * The disposable local stand-in of the GitHub releases API: a minimal
     * HTTP/1.1 server on a private loopback port serving the
     * `/releases/latest` JSON (configurable status + body) and the download
     * assets, the download throttled in chunks so the download's progress
     * state is observable on the UI. One request at a time (the product's
     * requests are sequential), `Connection: close` per response.
     */
    private class FakeReleasesApi {
        private val serverSocket = ServerSocket(0)
        private var thread: Thread? = null

        val port: Int get() = serverSocket.localPort

        @Volatile var latestStatus: Int = 200
        @Volatile var latestBody: String = ""
        val downloads = HashMap<String, ByteArray>()

        fun start() {
            thread = Thread {
                while (true) {
                    val client = try {
                        serverSocket.accept()
                    } catch (e: java.net.SocketException) {
                        break // the socket was closed by stop()
                    }
                    handle(client)
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }

        fun stop() {
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // already closed
            }
            thread?.join(2_000)
        }

        private fun handle(client: Socket) {
            client.use { socket ->
                val input = socket.getInputStream()
                val head = StringBuilder()
                val buffer = ByteArray(1024)
                while (!head.toString().contains("\r\n\r\n")) {
                    val n = input.read(buffer)
                    if (n == -1) return
                    head.append(String(buffer, 0, n, Charsets.ISO_8859_1))
                }
                val path = head.toString().substringBefore("\r\n").split(" ").getOrNull(1) ?: "/"
                var status = 404
                var body: ByteArray = ByteArray(0)
                var contentType = "application/json"
                var throttle = false
                if (path == LATEST_PATH) {
                    status = latestStatus
                    body = latestBody.toByteArray()
                } else {
                    val data = downloads[path.removePrefix("/download/")]
                    if (data != null) {
                        status = 200
                        body = data
                        contentType = "application/vnd.android.package-archive"
                        throttle = true
                    }
                }
                val output = socket.getOutputStream()
                output.write("HTTP/1.1 $status OK\r\n".toByteArray())
                output.write("Content-Type: $contentType\r\n".toByteArray())
                output.write("Content-Length: ${body.size}\r\n".toByteArray())
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.flush()
                if (throttle) {
                    var offset = 0
                    while (offset < body.size) {
                        val chunk = minOf(CHUNK_BYTES, body.size - offset)
                        output.write(body, offset, chunk)
                        output.flush()
                        offset += chunk
                        Thread.sleep(CHUNK_DELAY_MS)
                    }
                } else {
                    output.write(body)
                    output.flush()
                }
            }
        }

        private companion object {
            const val LATEST_PATH = "/repos/jsnto/dashhome/releases/latest"
            const val CHUNK_BYTES = 8 * 1024
            const val CHUNK_DELAY_MS = 15L
        }
    }

    private companion object {
        const val APK_NAME = "dashhome-update.apk"
        const val APK_SIZE_BYTES = 256 * 1024
        const val ACTION_MANAGE_APP_INSTALL_SETTINGS = "android.settings.MANAGE_APP_INSTALL_SETTINGS"
        const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
        const val FAKE_INSTALL_SESSION_ID = 42
        const val WAIT_TIMEOUT_MS = 20_000L
    }
}

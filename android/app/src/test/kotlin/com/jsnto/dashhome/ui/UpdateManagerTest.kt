package com.jsnto.dashhome.ui

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure install-support domain in [UpdateManager.kt] (no Android
 * runtime, no network): the install-settings action's fallback selection
 * (the device round 2026-11-14: the modern per-app action does not
 * resolve on the Pixel 7 Pro, API 37, while the legacy action opens the
 * same per-app page) and the platform install failure's surfacing copy
 * (the round's polex finding: a rejected install's platform error must
 * surface with what to do, not be swallowed - the app must not sit at
 * "ready to install" after a rejected install).
 *
 * The [PackageInstaller.STATUS_*] references are compile-time constants
 * (inlined by the compiler), so this file stays a plain JVM test (no
 * Robolectric, no emulator).
 */
class UpdateManagerTest {

    // ---- the install-settings fallback selection ----

    @Test
    fun aResolvingModernActionIsPickedFirst() {
        val action = pickInstallSettingsAction(INSTALL_SETTINGS_ACTIONS) { true }
        assertEquals("android.settings.MANAGE_APP_INSTALL_SETTINGS", action)
    }

    @Test
    fun anUnresolvedModernActionFallsBackToTheLegacyAction() {
        // The round's device (Pixel 7 Pro, API 37): the modern action
        // does not resolve, the legacy one does.
        val action = pickInstallSettingsAction(INSTALL_SETTINGS_ACTIONS) {
            it != "android.settings.MANAGE_APP_INSTALL_SETTINGS"
        }
        assertEquals("android.settings.MANAGE_UNKNOWN_APP_SOURCES", action)
    }

    @Test
    fun anUnresolvedModernAndLegacyActionFallsBackToTheAppInfoPage() {
        val action = pickInstallSettingsAction(INSTALL_SETTINGS_ACTIONS) {
            it == "android.settings.APPLICATION_DETAILS_SETTINGS"
        }
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", action)
    }

    @Test
    fun noResolvingActionPicksNothing() {
        val action = pickInstallSettingsAction(INSTALL_SETTINGS_ACTIONS) { false }
        assertNull(action)
    }

    // ---- the install failure's surfacing copy ----

    @Test
    fun aSuccessfulInstallHasNoMessage() {
        // The new version replaces this build (the process is torn down) -
        // there is nothing left to say.
        assertNull(installFailureMessage(PackageInstaller.STATUS_SUCCESS))
    }

    @Test
    fun aUserAbortedInstallHasNoMessage() {
        // A decline (the confirmation dialog's back), not a failure - the
        // download is kept and the install action stays live.
        assertNull(installFailureMessage(PackageInstaller.STATUS_FAILURE_ABORTED))
    }

    @Test
    fun aPendingSessionHasNoMessage() {
        // Still waiting on the user's confirmation - not an outcome yet.
        assertNull(installFailureMessage(PackageInstaller.STATUS_PENDING_USER_ACTION))
    }

    @Test
    fun anIncompatibleInstallNamesTheSignatureWallAndTheUninstallPath() {
        // The round's polex finding: INSTALL_FAILED_UPDATE_INCOMPATIBLE
        // (the platform's signature rejection) must surface with what to
        // do, not be swallowed.
        val message = installFailureMessage(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)!!
        assertEquals(
            "The phone refused the update: the new version can't be installed over the one " +
                "you have (a different signature or a conflicting version). Uninstall dashhome " +
                "first (Settings > Apps > dashhome > Uninstall), then install the new version again.",
            message,
        )
    }

    @Test
    fun anIncompatibleInstallQuotesThePlatformsReasonWhenItCarriesOne() {
        // The concrete channel (the commit status receiver's broadcast)
        // carries the platform's own reason - it surfaces, quoted.
        val reason =
            "Existing package com.jsnto.dashhome signatures do not match newer version; ignoring!"
        val message = installFailureMessage(
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            reason,
        )!!
        assertEquals(
            "The phone refused the update: the new version can't be installed over the one " +
                "you have (a different signature or a conflicting version). " +
                "The installer said: \"$reason\". " +
                "Uninstall dashhome first (Settings > Apps > dashhome > Uninstall), then " +
                "install the new version again.",
            message,
        )
    }

    @Test
    fun aConflictingInstallNamesTheConflictAndTheUninstallPath() {
        val message = installFailureMessage(PackageInstaller.STATUS_FAILURE_CONFLICT)!!
        assertEquals(
            "The phone refused the update: it conflicts with the installed dashhome. " +
                "Uninstall dashhome first (Settings > Apps > dashhome > Uninstall), then install " +
                "the new version again.",
            message,
        )
    }

    @Test
    fun aBlockedInstallNamesTheBlockAndKeepsTheRetryLive() {
        val message = installFailureMessage(PackageInstaller.STATUS_FAILURE_BLOCKED)!!
        assertEquals(
            "The phone blocked the update (the device's app-verification or policy blocked it). " +
                "Tap Install again - the download is kept; if it keeps failing, uninstall " +
                "dashhome first (Settings > Apps > dashhome > Uninstall), then install the new " +
                "version again.",
            message,
        )
    }

    @Test
    fun everyOtherFailureStatusSurfacesItsConcreteStatusAndWhatToDo() {
        // Every failure status the platform can report (the
        // PackageInstaller.STATUS_FAILURE_* constants) surfaces a message
        // that names a concrete kind, says what to do, and never a null.
        val cases = mapOf(
            PackageInstaller.STATUS_FAILURE to "installer status ${PackageInstaller.STATUS_FAILURE}",
            PackageInstaller.STATUS_FAILURE_STORAGE to "ran out of storage",
            PackageInstaller.STATUS_FAILURE_TIMEOUT to "timed out",
            PackageInstaller.STATUS_FAILURE_INVALID to "rejected the APK as invalid",
        )
        for ((status, what) in cases) {
            val message = installFailureMessage(status)
            assertNotNull("status $status must surface a message", message)
            assertTrue(
                "status $status must name its concrete kind, got $message",
                message!!.contains(what),
            )
            assertTrue(
                "status $status must say what to do, got $message",
                message.contains("Install") || message.contains("install"),
            )
        }
        // The platform's reason, when carried, refines any failure's copy.
        val generic = installFailureMessage(PackageInstaller.STATUS_FAILURE, "some reason")!!
        assertTrue(
            "the platform's reason must surface, quoted, got $generic",
            generic.contains("The installer said: \"some reason\"."),
        )
    }

    @Test
    fun aBlankPlatformReasonIsNotQuoted() {
        assertFalse(
            installFailureMessage(PackageInstaller.STATUS_FAILURE, "")!!
                .contains("The installer said"),
        )
    }
}

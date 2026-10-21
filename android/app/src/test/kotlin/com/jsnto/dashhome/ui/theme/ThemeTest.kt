package com.jsnto.dashhome.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.jsnto.dashhome.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The light/dark theme persistence logic (parity with the web's
 * useTheme: the explicit stored choice wins, the OS preference applies
 * and is followed live until the user picks one, and the choice persists
 * in client-side storage).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = composeRule.activity

    // --- the effective theme (the web's initialTheme) -----------------------

    @Test
    fun theExplicitChoiceWinsOverTheSystemPreference() {
        assertTrue(effectiveTheme("dark", false))
        assertFalse(effectiveTheme("light", true))
    }

    @Test
    fun noStoredChoiceFollowsTheSystemPreference() {
        assertTrue(effectiveTheme(null, true))
        assertFalse(effectiveTheme(null, false))
    }

    @Test
    fun aGarbageStoredValueFallsBackToTheSystemPreference() {
        // The storage is client-side only, but anything other than the
        // two valid values is treated as "never picked".
        assertTrue(effectiveTheme("purple", true))
        assertFalse(effectiveTheme("", false))
    }

    // --- the persistence (the web's localStorage round trip) ----------------

    @Test
    fun setThemePersistsTheChoiceAndThemeReadsItBack() {
        assertNull("no theme is stored before the user picks", Prefs.theme(context))
        Prefs.setTheme(context, "dark")
        assertEquals("dark", Prefs.theme(context))
        Prefs.setTheme(context, "light")
        assertEquals("light", Prefs.theme(context))
    }

    @Test
    fun setThemeIgnoresAnythingOtherThanLightAndDark() {
        Prefs.setTheme(context, "purple")
        assertNull(Prefs.theme(context))
        Prefs.setTheme(context, "")
        assertNull(Prefs.theme(context))
    }
}

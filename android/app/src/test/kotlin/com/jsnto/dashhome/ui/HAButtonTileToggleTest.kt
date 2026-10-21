package com.jsnto.dashhome.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A `button` Home Assistant tile on the board fires the board's
 * allowlisted toggle and then refreshes the entity states, driven end
 * to end on the real UI against the in-test FakeDashServer (which
 * records every request):
 *
 *  - a board seeded with a `button` HA tile for light.kitchen (state
 *    "off") renders the tile with the "Turn on" action;
 *  - tapping it POSTs the allowlisted toggle to /api/ha/service (the
 *    body carries the entity and the fixed "toggle" action, never a
 *    free-form service);
 *  - the board re-fetches the entity states right after the action
 *    (the web's onService -> reload), and a state change that lands
 *    there re-renders the tile wearing the new state ("Turn off").
 */
@RunWith(RobolectricTestRunner::class)
class HAButtonTileToggleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var server: FakeDashServer

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    /** The board carries a button tile for light.kitchen from the start
     *  (no Settings round trip): the views patch shape the backend
     *  stores for an added tile. */
    private fun startAppWithButtonTile() {
        server = FakeDashServer().also { s ->
            s.haEntitiesJson = """
                [
                  {"entity_id": "light.kitchen", "label": "Kitchen", "friendly": "Kitchen Light", "state": "off"}
                ]
            """.trimIndent()
            s.boardJson.getJSONObject("views").put(
                "ha_tiles",
                org.json.JSONArray().put(
                    JSONObject().put("id", "ha-1").put("entity", "light.kitchen").put("kind", "button"),
                ),
            )
        }
        DashApi.baseUrl = server.baseUrl
        Prefs.setBase(composeRule.activity, server.baseUrl)
        composeRule.setContent {
            DashhomeRoot()
        }
        composeRule.waitForIdle()
        // The board adopted its first GET /api/config (calendar tile laid
        // out) and the HA tile auto-placed at the first free 2x2 cell.
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("Turn on").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Config(sdk = [35], qualifiers = "w1200dp-h1000dp")
    @Test
    fun buttonTileFiresTheToggleAndRefreshesTheStates() {
        startAppWithButtonTile()

        // The button wears the entity's off state: it offers the turn-on.
        composeRule.onNodeWithText("Turn on").assertExists()

        // Tap it: the allowlisted toggle goes out for this entity.
        composeRule.onNodeWithText("Turn on").performClick()
        composeRule.waitUntil(10_000) {
            server.requests.any {
                it.method == "POST" && it.path == "/api/ha/service"
            }
        }
        val post = server.requests.first {
            it.method == "POST" && it.path == "/api/ha/service"
        }
        val body = JSONObject(post.body)
        assertEquals("light.kitchen", body.getString("entity_id"))
        assertEquals("toggle", body.getString("action"))

        // The board re-fetched the entity states right after the action
        // (the refresh lands after the POST in the request log - it is
        // the action's onService reload, not the 10 s poll).
        val postIdx = server.requests.indexOfFirst {
            it.method == "POST" && it.path == "/api/ha/service"
        }
        val lastEntitiesIdx = server.requests.indexOfLast {
            it.method == "GET" && it.path == "/api/ha/entities"
        }
        assertTrue(
            "the post-action state refresh never happened (last /api/ha/entities at $lastEntitiesIdx, POST at $postIdx)",
            lastEntitiesIdx > postIdx,
        )

        // The toggle lands server-side (the fake entity flips to "on");
        // the app backgrounds and regains visibility, and the tile
        // re-renders wearing the refreshed state.
        server.haEntitiesJson = """
            [
              {"entity_id": "light.kitchen", "label": "Kitchen", "friendly": "Kitchen Light", "state": "on"}
            ]
        """.trimIndent()
        val registry = composeRule.activity.lifecycle as LifecycleRegistry
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Turn off").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Turn off").assertExists()
    }
}

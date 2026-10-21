package com.jsnto.dashhome.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParsersTest {

    // --- /api/config ------------------------------------------------------

    @Test
    fun `config parses the full shape`() {
        val body = """
            {
              "board": {
                "ha_entities": ["climate.thermostat", "sensor.front_door"],
                "ha_labels": { "climate.thermostat": "Thermostat" },
                "calendars": [
                  { "id": "family@gmail.com", "label": "Family" },
                  { "id": "work@gmail.com", "label": "Work" }
                ],
                "refresh_seconds": 45,
                "ha_embed": { "url_path": "lovelace", "token_url": true }
              },
              "integrations": {
                "homeassistant": true,
                "google": {
                  "configured": true,
                  "mode": "oauth",
                  "login_required": true,
                  "login_url": "http://example/api/auth/google/login"
                },
                "hermes_chat": true,
                "hermes_push": false
              }
            }
        """.trimIndent()
        val cfg = Parsers.config(body)
        assertEquals(2, cfg.board.haEntities.size)
        assertEquals("climate.thermostat", cfg.board.haEntities[0])
        assertEquals("Thermostat", cfg.board.haLabels["climate.thermostat"])
        assertEquals(2, cfg.board.calendars.size)
        assertEquals("family@gmail.com", cfg.board.calendars[0].id)
        assertEquals("Family", cfg.board.calendars[0].label)
        assertEquals(45, cfg.board.refreshSeconds)
        assertEquals("lovelace", cfg.board.haEmbedUrlPath)
        assertTrue(cfg.board.haEmbedTokenUrl)
        assertTrue(cfg.homeAssistant)
        assertTrue(cfg.google.configured)
        assertEquals("oauth", cfg.google.mode)
        assertTrue(cfg.google.loginRequired)
        assertEquals("http://example/api/auth/google/login", cfg.google.loginUrl)
        assertTrue(cfg.hermesChat)
        assertEquals(false, cfg.hermesPush)
    }

    @Test
    fun `config tolerates missing optionals with defaults`() {
        val cfg = Parsers.config("""{"board": {}, "integrations": {}}""")
        assertEquals(emptyList<String>(), cfg.board.haEntities)
        assertEquals(emptyMap<String, String>(), cfg.board.haLabels)
        assertEquals(emptyList<CalendarRef>(), cfg.board.calendars)
        assertEquals(30, cfg.board.refreshSeconds)
        assertEquals(null, cfg.board.haEmbedUrlPath)
        assertEquals(false, cfg.board.haEmbedTokenUrl)
        assertEquals(false, cfg.homeAssistant)
        assertEquals(false, cfg.google.configured)
        assertEquals(false, cfg.hermesChat)
        assertEquals(false, cfg.hermesPush)
    }

    @Test
    fun `config zero refresh_seconds is the 30 second default`() {
        val cfg = Parsers.config("""{"board": {"refresh_seconds": 0}, "integrations": {}}""")
        assertEquals(30, cfg.board.refreshSeconds)
    }

    @Test
    fun `board zero refresh_seconds is the 30 second default`() {
        val board = Parsers.board(
            """{"board": {"ha_entities": [], "ha_labels": {}, "calendars": [], "refresh_seconds": 0}}""",
        )
        assertEquals(30, board.refreshSeconds)
    }

    @Test
    fun `config null ha_embed url_path stays null`() {
        val cfg = Parsers.config(
            """
            {"board": {"ha_embed": {"url_path": null, "token_url": false}}, "integrations": {}}
            """.trimIndent(),
        )
        assertEquals(null, cfg.board.haEmbedUrlPath)
    }

    @Test
    fun `config bad json throws bad_response`() {
        try {
            Parsers.config("not json")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(0, e.status)
            assertEquals("bad_response", e.code)
        }
    }

    // --- /api/config/refresh ------------------------------------------------

    @Test
    fun `board parses the refresh response`() {
        val board = Parsers.board(
            """{"board": {"ha_entities": [], "ha_labels": {}, "calendars": [], "refresh_seconds": 15}}""",
        )
        assertEquals(15, board.refreshSeconds)
    }

    @Test
    fun `board missing object throws`() {
        try {
            Parsers.board("""{"other": 1}""")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("bad_response", e.code)
        }
    }

    // --- /api/calendars ------------------------------------------------------

    @Test
    fun `calendars parses items`() {
        val cals = Parsers.calendars(
            """
            {"calendars": [
              {"id": "a@gmail.com", "summary": "A", "label": "A", "primary": true},
              {"id": "b@gmail.com", "summary": "B", "label": "B"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, cals.size)
        assertEquals("a@gmail.com", cals[0].id)
        assertTrue(cals[0].primary)
        assertEquals(false, cals[1].primary)
        assertEquals("B", cals[1].label)
    }

    @Test
    fun `calendars empty list stays a list`() {
        assertEquals(emptyList<CalendarInfo>(), Parsers.calendars("""{"calendars": []}"""))
    }

    // --- /api/events -----------------------------------------------------------

    @Test
    fun `events parses timed and all-day shapes`() {
        val evs = Parsers.events(
            """
            {"events": [
              {
                "calendar_id": "a", "calendar_label": "A", "id": "e1",
                "summary": "Standup", "description": "Daily", "location": "Zoom",
                "start": "2026-10-03T01:00:00Z", "end": "2026-10-03T01:30:00Z",
                "all_day": false
              },
              {
                "calendar_id": "b", "calendar_label": "B", "id": "e2",
                "summary": "Trip",
                "start": "2026-10-05T00:00:00Z", "end": "2026-10-08T00:00:00Z",
                "all_day": true
              }
            ]}
            """.trimIndent(),
        )
        assertEquals(2, evs.size)
        assertEquals("Daily", evs[0].description)
        assertEquals("Zoom", evs[0].location)
        assertEquals(false, evs[0].allDay)
        assertEquals("Trip", evs[1].summary)
        assertEquals(null, evs[1].description)
        assertEquals(null, evs[1].location)
        assertEquals(true, evs[1].allDay)
    }

    @Test
    fun `events missing required field throws`() {
        try {
            Parsers.events("""{"events": [{"id": "x"}]}""")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("bad_response", e.code)
        }
    }

    // --- /api/ha/embed-url -------------------------------------------------

    @Test
    fun `embedUrl returns the url`() {
        assertEquals(
            "http://ha.local:8123/?auth_long_lived=tok",
            Parsers.embedUrl("""{"url": "http://ha.local:8123/?auth_long_lived=tok"}"""),
        )
    }

    @Test
    fun `embedUrl blank url throws`() {
        try {
            Parsers.embedUrl("""{"url": ""}""")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("bad_response", e.code)
        }
    }

    // --- /api/hermes/chat -----------------------------------------------------

    @Test
    fun `chatReply returns the reply text`() {
        assertEquals("all quiet", Parsers.chatReply("""{"reply": "all quiet"}"""))
    }

    // --- /api/hermes/pushes ---------------------------------------------------

    @Test
    fun `pushes parses the list`() {
        val ps = Parsers.pushes(
            """
            {"pushes": [
              {"id": "1-a", "received_at": "2026-10-03T09:00:00Z", "source": "hermes", "markdown": "# hi"},
              {"id": "0-b", "received_at": "2026-10-02T09:00:00Z", "markdown": "older"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, ps.size)
        assertEquals("hermes", ps[0].source)
        assertEquals("# hi", ps[0].markdown)
        assertEquals(null, ps[1].source)
    }

    @Test
    fun `push parses the single record`() {
        val p = Parsers.push(
            """{"push": {"id": "9", "received_at": "2026-10-03T09:00:00Z", "source": "s", "markdown": "md"}}""",
        )
        assertEquals("9", p.id)
        assertEquals("md", p.markdown)
    }

    // --- board grid & views (the tile board's layout surface) ---------------

    @Test
    fun `board parses grid and views with geometry and ha tiles`() {
        val board = Parsers.board(
            """
            {
              "board": {
                "refresh_seconds": 30,
                "grid": { "cols": 12, "rows": 8 },
                "views": {
                  "order": ["calendar", "chat", "home", "push", "ha-1"],
                  "hidden": ["push"],
                  "calendar_mode": "schedule",
                  "sizes": { "calendar": "l", "ha-1": "m" },
                  "geometry": {
                    "calendar": { "x": 1, "y": 1, "w": 8, "h": 2 },
                    "ha-1": { "x": 9, "y": 1, "w": 3, "h": 2 }
                  },
                  "ha_tiles": [
                    { "id": "ha-1", "entity": "light.kitchen", "kind": "button", "label": "Kitchen" },
                    { "id": "ha-2", "entity": "sensor.temperature", "kind": "visual" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(GridDims(12, 8), board.grid)
        val v = board.views!!
        assertEquals(listOf("calendar", "chat", "home", "push", "ha-1"), v.order)
        assertEquals(listOf("push"), v.hidden)
        assertEquals("schedule", v.calendarMode)
        assertEquals("l", v.sizes["calendar"])
        assertEquals(TileGeometry(1, 1, 8, 2), v.geometry["calendar"])
        assertEquals(TileGeometry(9, 1, 3, 2), v.geometry["ha-1"])
        assertEquals(2, v.haTiles.size)
        assertEquals("light.kitchen", v.haTiles[0].entity)
        assertEquals("button", v.haTiles[0].kind)
        assertEquals("Kitchen", v.haTiles[0].label)
        assertEquals(null, v.haTiles[1].label)
    }

    @Test
    fun `board views tolerate a freehand board file`() {
        // The board file is freehand-editable: a string where a cell
        // belongs, a fractional cell, a malformed ha_tiles entry - all
        // dropped, nothing crashed on.
        val board = Parsers.board(
            """
            {
              "board": {
                "grid": { "cols": "12", "rows": 12 },
                "views": {
                  "order": ["calendar", "chat"],
                  "geometry": {
                    "calendar": "a string",
                    "chat": { "x": 1.5, "y": 1, "w": 2, "h": 1 }
                  },
                  "ha_tiles": [
                    { "id": "ha-1", "entity": "light.x", "kind": "button" },
                    { "entity": "light.y", "kind": "button" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        // "cols": "12" is not an integer -> the grid parses as absent
        // (null); normalizeGrid then repairs it to the 12x12 default.
        assertEquals(null, board.grid)
        assertEquals(GridDims(12, 12), com.jsnto.dashhome.ui.state.normalizeGrid(board.grid))
        val v = board.views!!
        // The fractional cell is not an integer cell: dropped.
        assertEquals(emptyMap<String, TileGeometry>(), v.geometry)
        // The malformed ha_tiles entry (missing id) is dropped.
        assertEquals(1, v.haTiles.size)
        assertEquals("ha-1", v.haTiles[0].id)
    }

    @Test
    fun `board missing grid and views stays null`() {
        val board = Parsers.board("""{"board": {}}""")
        assertEquals(null, board.grid)
        assertEquals(null, board.views)
    }

    // --- /api/ha/entities -----------------------------------------------------

    @Test
    fun `haEntities parses the entity list`() {
        val es = Parsers.haEntities(
            """
            {"entities": [
              {
                "entity_id": "light.kitchen",
                "label": "Kitchen",
                "friendly": "Kitchen Light",
                "state": "off",
                "last_updated": "2026-10-03T09:00:00+00:00",
                "last_changed": "2026-10-03T08:00:00+00:00"
              },
              {
                "entity_id": "sensor.temperature",
                "state": "21.5"
              }
            ]}
            """.trimIndent(),
        )
        assertEquals(2, es.size)
        assertEquals("light.kitchen", es[0].entityId)
        assertEquals("Kitchen", es[0].label)
        assertEquals("Kitchen Light", es[0].friendly)
        assertEquals("off", es[0].state)
        assertEquals("2026-10-03T09:00:00+00:00", es[0].lastUpdated)
        assertEquals("21.5", es[1].state)
        assertEquals("", es[1].label)
        assertEquals(null, es[1].lastUpdated)
    }

    @Test
    fun `haEntities empty list stays a list`() {
        assertEquals(emptyList<HAEntity>(), Parsers.haEntities("""{"entities": []}"""))
    }

    // --- /api/ha/service -------------------------------------------------------

    @Test
    fun `haService parses the allowlisted action result`() {
        val r = Parsers.haService(
            """{"ok": true, "entity_id": "light.kitchen", "action": "turn_on", "result": {"state": "on"}}""",
        )
        assertTrue(r.ok)
        assertEquals("light.kitchen", r.entityId)
        assertEquals("turn_on", r.action)
        val result = r.result
        org.junit.Assert.assertNotNull(result)
        assertEquals("on", result!!["state"])
    }

    @Test
    fun `haService without a result object keeps a null result`() {
        val r = Parsers.haService("""{"ok": true, "entity_id": "light.kitchen", "action": "turn_off"}""")
        assertTrue(r.ok)
        assertEquals(null, r.result)
    }
}

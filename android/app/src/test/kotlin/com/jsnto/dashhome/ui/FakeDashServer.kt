package com.jsnto.dashhome.ui

import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * A tiny in-test HTTP server standing in for the dashhome backend: it
 * serves the API's JSON endpoints from an in-memory board state and
 * records every request (method, path, body) so tests can assert on
 * what the app sent. Single-threaded (one request at a time,
 * Connection: close per request) — the app's requests queue briefly,
 * which is fine for tests.
 *
 * The board state follows the real backend's merge semantics for
 * PUT /api/config/views: order/hidden/ha_tiles are full replacements,
 * sizes and geometry merge field-wise (a null geometry entry clears a
 * tile's placement), and calendar_mode replaces the stored mode. The
 * response is always `{"board": ...}` with the effective (normalized)
 * layout, which the app adopts.
 */
class FakeDashServer {

    data class Request(val method: String, val path: String, val body: String)

    /** The board object the server holds (the wire shape of GET /api/config .board). */
    @Volatile
    var boardJson: org.json.JSONObject = defaultBoard()

    /**
     * If non-null, GET /api/ha/entities answers 200 with this entities
     * JSON (the array content of "entities"); otherwise it answers 503
     * like the backend when Home Assistant is not configured.
     */
    @Volatile
    var haEntitiesJson: String? = null

    val requests = Collections.synchronizedList(mutableListOf<Request>())
    private val socket = ServerSocket(0)
    private var running = true
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    private val thread = Thread(::serve, "fake-dash-server").apply {
        isDaemon = true
        start()
    }

    /** The requests recorded for [path] (path without query string). */
    fun requestsTo(path: String): List<Request> =
        requests.filter { it.path == path }

    /** The last request body for [path], if any. */
    fun lastBodyTo(path: String): String? =
        requests.lastOrNull { it.path == path }?.body

    fun stop() {
        running = false
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    private fun serve() {
        while (running) {
            try {
                socket.accept().use { handle(it) }
            } catch (e: Exception) {
                if (running) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handle(s: Socket) {
        val inStream = s.getInputStream()
        val line = readLine(inStream) ?: return
        val parts = line.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var contentLength = 0
        var header = readLine(inStream)
        while (header != null && header.isNotEmpty()) {
            if (header.startsWith("Content-Length:")) {
                contentLength = header.substringAfter(':').trim().toInt()
            }
            header = readLine(inStream)
        }
        val body = if (contentLength > 0) {
            val buf = ByteArray(contentLength)
            var off = 0
            while (off < contentLength) {
                val n = inStream.read(buf, off, contentLength - off)
                if (n == -1) break
                off += n
            }
            buf.decodeToString()
        } else ""
        requests.add(Request(method, path, body))
        val (status, payload) = route(method, path, body)
        val payloadBytes = payload.toByteArray()
        val out = s.getOutputStream()
        out.write(
            "HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${payloadBytes.size}\r\nConnection: close\r\n\r\n"
                .toByteArray(),
        )
        out.write(payloadBytes)
        out.flush()
    }

    private fun route(method: String, path: String, body: String): Pair<Int, String> {
        val b = boardJson
        return when {
            method == "GET" && path == "/api/config" ->
                200 to """{"board": ${b.toString()}, "integrations": {"homeassistant": false, "google": {"configured": true, "login_required": false}, "hermes_chat": true, "hermes_push": true}}"""

            method == "GET" && path == "/api/calendars" ->
                200 to """{"calendars": []}"""

            method == "GET" && path.startsWith("/api/events") ->
                200 to """{"events": []}"""

            method == "GET" && path.startsWith("/api/hermes/pushes") ->
                if (path.endsWith("/latest")) 404 to """{"error": "not_found"}"""
                else 200 to """{"pushes": []}"""

            method == "POST" && path == "/api/hermes/chat" ->
                200 to """{"reply": "ok"}"""

            method == "GET" && path == "/api/ha/embed-url" ->
                503 to """{"error": "homeassistant_not_configured", "detail": "home assistant is not configured"}"""

            method == "GET" && path == "/api/ha/entities" ->
                haEntitiesJson?.let { 200 to """{"entities": $it}""" }
                    ?: 503 to """{"error": "homeassistant_not_configured", "detail": "home assistant is not configured"}"""

            method == "POST" && path == "/api/ha/service" ->
                200 to """{"ok": true, "entity_id": "light.x", "action": "turn_on", "result": {}}"""

            method == "PUT" && path == "/api/config/refresh" -> {
                b.put("refresh_seconds", org.json.JSONObject(body).optInt("refresh_seconds", 30))
                200 to """{"board": ${b.toString()}}"""
            }

            method == "PUT" && path == "/api/config/grid" -> {
                val req = org.json.JSONObject(body)
                b.put("grid", org.json.JSONObject().put("cols", req.optInt("cols", 12)).put("rows", req.optInt("rows", 12)))
                200 to """{"board": ${b.toString()}}"""
            }

            method == "PUT" && path == "/api/config/views" -> {
                applyViewsPatch(org.json.JSONObject(body))
                200 to """{"board": ${b.toString()}}"""
            }

            else -> 404 to """{"error": "not_found"}"""
        }
    }

    /** The backend's views merge (see the class docs). */
    private fun applyViewsPatch(patch: org.json.JSONObject) {
        val b = boardJson
        val views = b.optJSONObject("views") ?: org.json.JSONObject().also { b.put("views", it) }
        if (patch.has("order")) views.put("order", patch.getJSONArray("order"))
        if (patch.has("hidden")) views.put("hidden", patch.getJSONArray("hidden"))
        if (patch.has("calendar_mode")) views.put("calendar_mode", patch.getString("calendar_mode"))
        if (patch.has("sizes")) {
            val sizes = views.optJSONObject("sizes") ?: org.json.JSONObject().also { views.put("sizes", it) }
            for (k in patch.getJSONObject("sizes").keys()) sizes.put(k, patch.getJSONObject("sizes").get(k))
        }
        if (patch.has("geometry")) {
            val geometry = views.optJSONObject("geometry") ?: org.json.JSONObject().also { views.put("geometry", it) }
            for (k in patch.getJSONObject("geometry").keys()) {
                val v = patch.getJSONObject("geometry").opt(k)
                if (v == org.json.JSONObject.NULL) geometry.remove(k) else geometry.put(k, v)
            }
        }
        if (patch.has("ha_tiles")) views.put("ha_tiles", patch.getJSONArray("ha_tiles"))
    }

    private fun readLine(s: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = s.read()
            if (c == -1) return sb.toString().takeIf { it.isNotEmpty() }
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    /**
     * A default board: the four views in the default order, all medium,
     * no explicit geometry, a 12x8 grid (8 rows keeps the test tiles tall
     * enough for the title plus the edit-mode row), 30s refresh.
     */
    private fun defaultBoard(): org.json.JSONObject {
        val views = org.json.JSONObject()
            .put("order", org.json.JSONArray().put("calendar").put("chat").put("home").put("push"))
            .put("hidden", org.json.JSONArray())
            .put("calendar_mode", "month")
            .put("sizes", org.json.JSONObject())
            .put("geometry", org.json.JSONObject())
            .put("ha_tiles", org.json.JSONArray())
        return org.json.JSONObject()
            .put("ha_entities", org.json.JSONArray())
            .put("ha_labels", org.json.JSONObject())
            .put("calendars", org.json.JSONArray())
            .put("refresh_seconds", 30)
            .put("ha_embed", org.json.JSONObject().put("url_path", org.json.JSONObject.NULL).put("token_url", false))
            .put("grid", org.json.JSONObject().put("cols", 12).put("rows", 8))
            .put("views", views)
    }
}

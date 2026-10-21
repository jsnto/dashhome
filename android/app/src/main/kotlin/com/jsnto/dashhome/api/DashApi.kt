package com.jsnto.dashhome.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp client for the dashhome backend API. House pattern: blocking
 * requests run on Dispatchers.IO via [safe]; every typed call throws
 * [ApiException] (status -1 for network failures), so callers branch on
 * one exception type.
 */
object DashApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** Base URL of the dashhome server (e.g. http://10.0.0.4:3000). */
    @Volatile
    var baseUrl: String = ""

    /** One raw HTTP result; code -1 means the request never completed. */
    class Result(val code: Int, val body: String)

    fun request(method: String, url: String, json: String? = null): Result {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (method == "GET") builder.get() else {
            val body = (json ?: "{}").toRequestBody(JSON)
            builder.method(method, body).header("Content-Type", "application/json")
        }
        client.newCall(builder.build()).execute().use { resp ->
            return Result(resp.code, resp.body?.string() ?: "")
        }
    }

    /** Runs a blocking call on IO; failures become Result(-1, message). */
    suspend fun safe(block: () -> Result): Result =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                Result(-1, e.message ?: "connection failed")
            }
        }

    private fun apiPath(path: String): String =
        baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"

    /** 2xx GET as a parsed JSON object. */
    suspend fun get(path: String): JSONObject =
        parseOk(safe { request("GET", apiPath(path)) }, "GET $path")

    /** 2xx POST with a JSON body. */
    suspend fun post(path: String, body: JSONObject): JSONObject =
        parseOk(safe { request("POST", apiPath(path), body.toString()) }, "POST $path")

    /** 2xx PUT with a JSON body. */
    suspend fun put(path: String, body: JSONObject): JSONObject =
        parseOk(safe { request("PUT", apiPath(path), body.toString()) }, "PUT $path")

    private fun parseOk(r: Result, what: String): JSONObject {
        if (r.code == -1) {
            throw ApiException(-1, "network", r.body.ifBlank { "cannot reach the dashhome server" })
        }
        if (r.code < 200 || r.code >= 300) {
            val j = runCatching { JSONObject(r.body) }.getOrNull()
            val code = j?.optString("error")?.ifBlank { null } ?: "request_failed"
            val detail = j?.optString("detail")?.ifBlank { null } ?: snippet(r.body)
            throw ApiException(r.code, code, detail)
        }
        return runCatching { JSONObject(r.body) }
            .getOrElse { throw ApiException(0, "bad_response", "$what returned a non-JSON body") }
    }

    private fun snippet(s: String): String {
        val t = s.trim()
        return if (t.length > 300) t.substring(0, 300) + "..." else t
    }

    // --- typed endpoints ------------------------------------------------

    /** GET /api/config */
    suspend fun getConfig(): ApiConfig = Parsers.config(get("/api/config").toString())

    /** GET /api/calendars */
    suspend fun getCalendars(): List<CalendarInfo> = Parsers.calendars(get("/api/calendars").toString())

    /** GET /api/events?start=&end=[&calendar=] (RFC3339 or YYYY-MM-DD). */
    suspend fun getEvents(start: String, end: String, calendar: String? = null): List<ApiEvent> {
        var path = "/api/events?start=${enc(start)}&end=${enc(end)}"
        if (!calendar.isNullOrBlank()) path += "&calendar=${enc(calendar)}"
        return Parsers.events(get(path).toString())
    }

    /** GET /api/ha/embed-url → the fully-formed embed URL. */
    suspend fun getHAAEmbedUrl(): String = Parsers.embedUrl(get("/api/ha/embed-url").toString())

    /** POST /api/hermes/chat → the reply text. [conversation] is oldest first. */
    suspend fun chat(message: String, conversation: List<ChatMessage>): String {
        val conv = JSONArray()
        conversation.forEach { conv.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val body = JSONObject().put("message", message).put("conversation", conv)
        return Parsers.chatReply(post("/api/hermes/chat", body).toString())
    }

    /** GET /api/hermes/pushes?limit= (newest first). */
    suspend fun getPushes(limit: Int = 50): List<Push> =
        Parsers.pushes(get("/api/hermes/pushes?limit=$limit").toString())

    /** GET /api/hermes/pushes/:id */
    suspend fun getPush(id: String): Push =
        Parsers.push(get("/api/hermes/pushes/${enc(id)}").toString())

    /** PUT /api/config/refresh → the updated board. */
    suspend fun setRefreshSeconds(seconds: Int): Board =
        Parsers.board(put("/api/config/refresh", JSONObject().put("refresh_seconds", seconds)).toString())

    /**
     * Persist the board's tile grid dimensions (board.grid, cols x rows
     * units) server-side → the updated board.
     */
    suspend fun setGrid(cols: Int, rows: Int): Board =
        Parsers.board(
            put(
                "/api/config/grid",
                JSONObject().put("cols", cols).put("rows", rows),
            ).toString(),
        )

    /**
     * Persist part of the tile layout (board.views) server-side:
     * [order] the board order of the tiles, [hidden] which are hidden,
     * [calendarMode] how the Calendar tile renders, [sizes] the per-tile
     * size steps (tiles omitted keep their current size), [geometry] the
     * per-tile grid placements (a null entry clears that tile's
     * placement, restoring automatic placement), and [haTiles] the Home
     * Assistant tiles (the full list, a replacement). Omitted (null)
     * fields keep their current server-side value. Returns the updated
     * board (adopt it).
     */
    suspend fun setViews(
        order: List<String>? = null,
        hidden: List<String>? = null,
        calendarMode: String? = null,
        sizes: Map<String, String>? = null,
        geometry: Map<String, TileGeometry?>? = null,
        haTiles: List<HABoardTile>? = null,
    ): Board {
        val body = JSONObject()
        if (order != null) {
            body.put("order", JSONArray().apply { order.forEach { put(it) } })
        }
        if (hidden != null) {
            body.put("hidden", JSONArray().apply { hidden.forEach { put(it) } })
        }
        if (calendarMode != null) {
            body.put("calendar_mode", calendarMode)
        }
        if (sizes != null) {
            body.put("sizes", JSONObject().apply { sizes.forEach { (k, v) -> put(k, v) } })
        }
        if (geometry != null) {
            body.put("geometry", JSONObject().apply {
                geometry.forEach { (k, v) ->
                    put(
                        k,
                        v?.let { JSONObject().put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h) }
                            ?: JSONObject.NULL,
                    )
                }
            })
        }
        if (haTiles != null) {
            body.put("ha_tiles", JSONArray().apply {
                haTiles.forEach { t ->
                    val o = JSONObject().put("id", t.id).put("entity", t.entity).put("kind", t.kind)
                    if (!t.label.isNullOrBlank()) o.put("label", t.label)
                    put(o)
                }
            })
        }
        return Parsers.board(put("/api/config/views", body).toString())
    }

    /** Home Assistant entities (GET /api/ha/entities). */
    suspend fun getHAEntities(): List<HAEntity> =
        Parsers.haEntities(get("/api/ha/entities").toString())

    /**
     * Fire an allowlisted service action on one entity (the board's
     * button tiles). The backend accepts only the fixed
     * toggle/turn_on/turn_off actions - never a free-form service.
     */
    suspend fun callHAService(entityId: String, action: String): HAServiceResult =
        Parsers.haService(
            post(
                "/api/ha/service",
                JSONObject().put("entity_id", entityId).put("action", action),
            ).toString(),
        )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

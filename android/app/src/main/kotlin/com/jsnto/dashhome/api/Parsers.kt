package com.jsnto.dashhome.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * Error returned by the backend as `{"error": code, "detail": ...}`, or a
 * client-side failure (network, bad response). [status] is the HTTP status;
 * -1 means the request never completed.
 */
class ApiException(
    val status: Int,
    val code: String,
    val detail: String? = null,
) : Exception(if (detail.isNullOrBlank()) code else "$code: $detail")

/**
 * Pure JSON parsing of the dashhome API response bodies. Kept separate
 * from the HTTP layer so the parsing/state logic is unit-testable with
 * plain strings.
 */
object Parsers {

    private fun bad(what: String, cause: Throwable) =
        ApiException(0, "bad_response", "$what: ${cause.message}")

    private inline fun <T> parse(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw bad(what, e)
        }

    /** GET /api/config */
    fun config(body: String): ApiConfig = parse("config") {
        val j = JSONObject(body)
        val b = j.optJSONObject("board")
        val board = Board(
            haEntities = b?.optJSONArray("ha_entities")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            haLabels = b?.optJSONObject("ha_labels")?.let { o ->
                buildMap { for (k in o.keys()) put(k, o.getString(k)) }
            } ?: emptyMap(),
            calendars = b?.optJSONArray("calendars")?.let { arr ->
                (0 until arr.length()).map {
                    val c = arr.getJSONObject(it)
                    CalendarRef(c.optString("id"), c.optString("label"))
                }
            } ?: emptyList(),
            refreshSeconds = (b?.optInt("refresh_seconds", 30) ?: 30).takeIf { it > 0 } ?: 30,
            haEmbedUrlPath = b?.optJSONObject("ha_embed")
                ?.let { if (it.isNull("url_path")) null else it.optString("url_path", "").ifEmpty { null } },
            haEmbedTokenUrl = b?.optJSONObject("ha_embed")?.optBoolean("token_url", false) ?: false,
        )
        val g = j.optJSONObject("integrations")?.optJSONObject("google")
        ApiConfig(
            board = board,
            homeAssistant = j.optJSONObject("integrations")?.optBoolean("homeassistant", false) ?: false,
            google = if (g != null) {
                GoogleStatus(
                    configured = g.optBoolean("configured", false),
                    mode = g.optString("mode", "").ifEmpty { null },
                    loginRequired = g.optBoolean("login_required", false),
                    loginUrl = g.optString("login_url", "").ifEmpty { null },
                )
            } else {
                GoogleStatus(configured = false)
            },
            hermesChat = j.optJSONObject("integrations")?.optBoolean("hermes_chat", false) ?: false,
            hermesPush = j.optJSONObject("integrations")?.optBoolean("hermes_push", false) ?: false,
        )
    }

    /** The board object as returned by PUT /api/config/refresh ({board}). */
    fun board(body: String): Board = parse("board") {
        val j = JSONObject(body).optJSONObject("board")
            ?: throw Exception("missing board object")
        Board(
            haEntities = j.optJSONArray("ha_entities")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList(),
            haLabels = j.optJSONObject("ha_labels")?.let { o ->
                buildMap { for (k in o.keys()) put(k, o.getString(k)) }
            } ?: emptyMap(),
            calendars = j.optJSONArray("calendars")?.let { arr ->
                (0 until arr.length()).map {
                    val c = arr.getJSONObject(it)
                    CalendarRef(c.optString("id"), c.optString("label"))
                }
            } ?: emptyList(),
            refreshSeconds = j.optInt("refresh_seconds", 30).takeIf { it > 0 } ?: 30,
            haEmbedUrlPath = j.optJSONObject("ha_embed")
                ?.let { if (it.isNull("url_path")) null else it.optString("url_path", "").ifEmpty { null } },
            haEmbedTokenUrl = j.optJSONObject("ha_embed")?.optBoolean("token_url", false) ?: false,
        )
    }

    /** GET /api/calendars → the calendars list. */
    fun calendars(body: String): List<CalendarInfo> = parse("calendars") {
        val arr = JSONObject(body).getJSONArray("calendars")
        (0 until arr.length()).map {
            val c = arr.getJSONObject(it)
            CalendarInfo(
                id = c.getString("id"),
                summary = c.optString("summary"),
                label = c.optString("label"),
                primary = c.optBoolean("primary", false),
            )
        }
    }

    /** GET /api/events → the events list. */
    fun events(body: String): List<ApiEvent> = parse("events") {
        val arr = JSONObject(body).getJSONArray("events")
        (0 until arr.length()).map {
            val e = arr.getJSONObject(it)
            ApiEvent(
                calendarId = e.getString("calendar_id"),
                calendarLabel = e.optString("calendar_label"),
                id = e.getString("id"),
                summary = e.optString("summary"),
                description = e.optString("description", "").ifEmpty { null },
                location = e.optString("location", "").ifEmpty { null },
                start = e.getString("start"),
                end = e.getString("end"),
                allDay = e.optBoolean("all_day", false),
            )
        }
    }

    /** GET /api/ha/embed-url → the fully-formed embed URL. */
    fun embedUrl(body: String): String = parse("embed_url") {
        val url = JSONObject(body).getString("url")
        if (url.isBlank()) throw Exception("empty url")
        url
    }

    /** POST /api/hermes/chat → the reply text. */
    fun chatReply(body: String): String = parse("chat_reply") {
        val reply = JSONObject(body).getString("reply")
        if (reply.isBlank()) throw Exception("empty reply")
        reply
    }

    /** GET /api/hermes/pushes → the pushes list (newest first per the API). */
    fun pushes(body: String): List<Push> = parse("pushes") {
        val arr = JSONObject(body).getJSONArray("pushes")
        (0 until arr.length()).map { push(arr.getJSONObject(it)) }
    }

    /** GET /api/hermes/pushes/latest and /api/hermes/pushes/:id. */
    fun push(body: String): Push = parse("push") {
        push(JSONObject(body).getJSONObject("push"))
    }

    private fun push(j: JSONObject): Push = Push(
        id = j.getString("id"),
        receivedAt = j.getString("received_at"),
        source = j.optString("source", "").ifEmpty { null },
        markdown = j.getString("markdown"),
    )
}

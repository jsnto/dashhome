package com.jsnto.dashhome.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.ui.state.haTileLabel
import com.jsnto.dashhome.ui.state.haTileState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A Home Assistant board tile: first-class like the view tiles (geometry,
 * order, hide all apply) but bound to one HA entity (the web's HATile).
 * `button` tiles fire the board's allowlisted action (toggle) through
 * POST /api/ha/service and the board refreshes the states after; `visual`
 * tiles show the entity's state (ON/OFF or the raw state) plus the
 * last-changed time. The button's styling wears the entity's on/off
 * state the way the web's switch does: ON reads dark and offers the
 * turn-off, OFF reads bright and offers the turn-on (the web's
 * --ha-toggle-on/off pairs, which differ per theme - [dark] picks the
 * right pair).
 */
@Composable
fun HATileView(
    tile: HABoardTile,
    entity: HAEntity?,
    haOff: Boolean,
    onService: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<ApiException?>(null) }
    val label = haTileLabel(tile, entity)
    val zone = remember { ZoneId.systemDefault() }
    // The web's stamp: last_changed first, then last_updated.
    val updated = remember(entity) {
        stamp(entity?.lastChanged, zone) ?: stamp(entity?.lastUpdated, zone)
    }

    fun run(action: String) {
        if (busy || entity == null || haOff) return
        busy = true
        err = null
        scope.launch {
            try {
                DashApi.callHAService(tile.entity, action)
                onService() // the board refreshes the states after the action
            } catch (e: ApiException) {
                err = e
            } finally {
                busy = false
            }
        }
    }

    val stateOn = entity?.state == "on"
    val stateOff = entity?.state == "off"
    val actionDisabled = busy || entity == null || haOff
    val actionLabel = when {
        entity == null -> "Toggle"
        stateOn -> "Turn off"
        else -> "Turn on"
    }

    Column(modifier = modifier.fillMaxSize()) {
        err?.let { ErrorNote(it) }
        if (tile.kind == "button") {
            Button(
                onClick = { run("toggle") },
                enabled = !actionDisabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        stateOn -> HA_ON_BG(dark)
                        stateOff -> HA_OFF_BG(dark)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    contentColor = when {
                        stateOn -> HA_ON_FG(dark)
                        stateOff -> HA_OFF_FG(dark)
                        else -> MaterialTheme.colorScheme.onPrimary
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(actionLabel)
            }
        } else {
            Text(
                text = when {
                    entity != null -> haTileState(entity.state)
                    haOff -> "Not connected"
                    else -> "—"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            updated?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (haOff) {
            Note(
                text = "Home Assistant is not configured. Set the Home Assistant URL and token in the web dashboard's Settings - Services.",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Note(
            text = tile.entity,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "9:41 AM" from an RFC3339 timestamp in [zone] (the web's
 *  toLocaleTimeString hour/minute), or null when unparseable. */
private val stampFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun stamp(iso: String?, zone: ZoneId): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso).atZone(zone).toLocalTime().format(stampFmt)
    } catch (e: Exception) {
        null
    }
}

// The web's --ha-toggle pairs (frontend/src/index.css): the dark theme's
// near-black ON / near-white OFF, and the light theme's navy ON / white
// OFF. The ON state reads dark (it offers the turn-off); the OFF state
// reads bright (it offers the turn-on).
private fun HA_ON_BG(dark: Boolean) =
    if (dark) Color(0xFF0A0F1A) else Color(0xFF101623)
private fun HA_ON_FG(dark: Boolean) =
    if (dark) Color(0xFFE6EBF5) else Color(0xFFF2F6FD)
private fun HA_OFF_BG(dark: Boolean) =
    if (dark) Color(0xFFF2F6FD) else Color(0xFFFFFFFF)
private fun HA_OFF_FG(dark: Boolean) = Color(0xFF101623)

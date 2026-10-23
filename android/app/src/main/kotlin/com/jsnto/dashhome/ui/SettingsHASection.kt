package com.jsnto.dashhome.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiConfig
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.api.TileGeometry
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.state.GRID_MAX
import com.jsnto.dashhome.ui.state.GRID_MIN
import com.jsnto.dashhome.ui.state.NEW_TILE_DEFAULT_SIZE
import com.jsnto.dashhome.ui.state.TILE_SIZE_LABELS
import com.jsnto.dashhome.ui.state.firstFreeCell
import com.jsnto.dashhome.ui.state.isHATileId
import com.jsnto.dashhome.ui.state.nextHATileId
import com.jsnto.dashhome.ui.state.normalizeGrid
import com.jsnto.dashhome.ui.state.normalizeViews
import com.jsnto.dashhome.ui.state.tileName
import kotlinx.coroutines.launch

/**
 * The Home Assistant section: adds and removes the HA-backed board tiles
 * (views.ha_tiles) - first-class tiles bound to exactly one HA entity
 * each, from the entities HA reports (GET /api/ha/entities). A button
 * tile fires the allowlisted action (toggle) from the board; a visual
 * tile shows the entity's state. The new tile's default placement is the
 * first free 2x2 cell on the board's effective layout.
 */
@Composable
internal fun HASection(
    views: BoardViews,
    grid: com.jsnto.dashhome.api.GridDims,
    onBoard: (Board) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tiles by remember { mutableStateOf(views.haTiles) }
    LaunchedEffect(views) { tiles = views.haTiles }
    // The entity picker's source (and the honest "not configured" state).
    var entities by remember { mutableStateOf<List<HAEntity>?>(null) }
    var haOff by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            entities = DashApi.getHAEntities()
            haOff = false
        } catch (e: ApiException) {
            if (e.code == "homeassistant_not_configured") haOff = true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<ApiException?>(null) }
    var entity by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("button") }
    var label by remember { mutableStateOf("") }

    // Persist the full ha_tiles list (the endpoint replaces the list),
    // optionally together with the new tile's default placement; the
    // server re-normalizes the order/hidden/geometry around the new set.
    fun persist(
        next: List<HABoardTile>,
        geometry: Map<String, TileGeometry?>? = null,
    ) {
        if (busy) return
        val before = tiles
        busy = true
        err = null
        tiles = next
        scope.launch {
            try {
                onBoard(DashApi.setViews(haTiles = next, geometry = geometry))
                entity = ""
                label = ""
            } catch (e: ApiException) {
                tiles = before
                err = e
            } finally {
                busy = false
            }
        }
    }

    // The new tile's default placement: a 2x2 cell on the board's grid,
    // the first one (in reading order) that is free of the board's
    // effective layout (the stored placements plus the auto flow, hidden
    // tiles included), computed over the layout the screen last adopted
    // from the server with the optimistic tile list applied. A grid too
    // small for the span, or a board with no free cell, leaves the tile
    // to auto placement (no geometry in the save).
    fun add() {
        val entityId = entity.trim()
        if (entityId.isEmpty() || haOff) return
        val id = nextHATileId(tiles)
        val nv = normalizeViews(views.copy(haTiles = tiles), grid)
        val cell = firstFreeCell(
            nv.order,
            nv.sizes,
            nv.geometry,
            NEW_TILE_DEFAULT_SIZE.w,
            NEW_TILE_DEFAULT_SIZE.h,
            grid.cols,
            grid.rows,
        )
        persist(
            tiles + HABoardTile(
                id = id,
                entity = entityId,
                label = label.trim().ifEmpty { null },
                kind = kind,
            ),
            cell?.let { mapOf(id to it) },
        )
    }

    Text(
        text = "Board tiles bound to Home Assistant entities: a button tile fires the allowlisted action (toggle) from the board, a visual tile shows the entity's state. Each tile is bound to exactly one entity; states are polled, never stored.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )

    if (tiles.isNotEmpty()) {
        tiles.forEach { t ->
            val name = tileName(t.id, views.copy(haTiles = tiles))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("settingsHaTile-${t.id}"),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(text = name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = t.kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    Text(
                        text = t.entity,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = { persist(tiles.filter { x -> x.id != t.id }) },
                    enabled = !busy,
                ) {
                    Text("Remove")
                }
            }
        }
    }

    if (haOff) {
        Note(
            text = "Home Assistant is not configured. Set the Home Assistant URL and token in the web dashboard's Settings - Services to add tiles.",
            modifier = Modifier.padding(top = 8.dp),
        )
    } else {
        Column(modifier = Modifier.padding(top = 8.dp)) {
            Text(
                text = "Entity",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "The HA entity this tile binds to (type to search the entity list).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = entity,
                onValueChange = { entity = it },
                placeholder = { Text("light.kitchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            EntityPickerChips(
                entities = entities.orEmpty(),
                onPick = { entity = it.entityId },
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "Tile kind",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(modifier = Modifier.padding(top = 6.dp)) {
                TabChip(
                    label = "Button",
                    selected = kind == "button",
                    onClick = { kind = "button" },
                )
                TabChip(
                    label = "Visual",
                    selected = kind == "visual",
                    onClick = { kind = "visual" },
                )
            }
            Text(
                text = "Label (optional)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = "Shown on the tile instead of the entity's own label.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                placeholder = { Text("Kitchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Button(
                onClick = { add() },
                enabled = !busy && entity.isNotBlank(),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(if (busy) "Saving…" else "Add tile")
            }
        }
    }
    err?.let { ErrorNote(it, modifier = Modifier.padding(top = 6.dp)) }
}

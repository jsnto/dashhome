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
 * The Tiles section: which dashboard tiles are on the main screen
 * (show/hide each tile - the four views and any Home Assistant tiles),
 * in what board order, the per-tile size step label, how the Calendar
 * tile renders, and the board's grid dimensions. Every change is
 * persisted to the board file as it happens (partial updates; the server
 * keeps the fields the request omits). Tile size and placement are
 * edited on the board itself (the Edit tiles mode), not here.
 */
@Composable
internal fun TilesSection(
    views: BoardViews,
    grid: com.jsnto.dashhome.api.GridDims,
    onBoard: (Board) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Optimistic local state: updated immediately, reverted if the save
    // fails, re-synced from the server's answer on success.
    var local by remember { mutableStateOf(views) }
    LaunchedEffect(views) { local = views }
    var saving by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<ApiException?>(null) }

    // The board's tile grid dimensions, edited here and persisted
    // server-side (board.grid). Adopted only while the fields are
    // untouched (the web's gridSeedRef pattern).
    var gridCols by remember { mutableStateOf(grid.cols.toString()) }
    var gridRows by remember { mutableStateOf(grid.rows.toString()) }
    var gridSeed by remember { mutableStateOf("${grid.cols}x${grid.rows}") }
    var gridSaving by remember { mutableStateOf(false) }
    var gridSavedMsg by remember { mutableStateOf<String?>(null) }
    var gridErr by remember { mutableStateOf<ApiException?>(null) }
    LaunchedEffect(grid) {
        val v = "${grid.cols}x${grid.rows}"
        if (gridSeed == "${gridCols}x${gridRows}") {
            gridSeed = v
            gridCols = grid.cols.toString()
            gridRows = grid.rows.toString()
        }
    }

    fun saveGrid() {
        val cols = gridCols.trim().toIntOrNull()
        val rows = gridRows.trim().toIntOrNull()
        if (
            cols == null || rows == null ||
            cols !in GRID_MIN..GRID_MAX || rows !in GRID_MIN..GRID_MAX
        ) {
            gridErr = ApiException(
                0,
                "bad_grid",
                "Grid dimensions must be whole numbers between $GRID_MIN and $GRID_MAX.",
            )
            return
        }
        gridSaving = true
        gridErr = null
        gridSavedMsg = null
        scope.launch {
            try {
                val board = DashApi.setGrid(cols, rows)
                val applied = normalizeGrid(board.grid)
                gridCols = applied.cols.toString()
                gridRows = applied.rows.toString()
                gridSeed = "${applied.cols}x${applied.rows}"
                onBoard(board)
                gridSavedMsg = "Saved — the board grid is now ${applied.cols} by ${applied.rows} units."
            } catch (e: ApiException) {
                gridErr = e
            } finally {
                gridSaving = false
            }
        }
    }

    fun apply(
        order: List<String>?,
        hidden: List<String>?,
        calendarMode: String?,
        next: BoardViews,
    ) {
        if (saving) return
        val before = local
        saving = true
        err = null
        local = next
        scope.launch {
            try {
                onBoard(DashApi.setViews(order, hidden, calendarMode))
            } catch (e: ApiException) {
                local = before
                err = e
            } finally {
                saving = false
            }
        }
    }

    Text(
        text = "Which dashboard tiles are on the main screen and in what board order. Changes save immediately. Resize a tile or place it in the grid from the board's Edit tiles mode.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )

    local.order.forEachIndexed { i, id ->
        val name = tileName(id, local)
        val hidden = id in local.hidden
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("settingsTile-$id"),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    if (isHATileId(id)) {
                        Text(
                            text = "HA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Text(
                    text = TILE_SIZE_LABELS[local.sizes[id]] ?: TILE_SIZE_LABELS["m"]!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(
                    text = if (hidden) "Hidden" else "Visible",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                Switch(
                    checked = !hidden,
                    onCheckedChange = {
                        val nextHidden = if (id in local.hidden) {
                            local.hidden.filter { h -> h != id }
                        } else {
                            local.hidden + id
                        }
                        apply(null, nextHidden, null, local.copy(hidden = nextHidden))
                    },
                    enabled = !saving,
                    modifier = Modifier.testTag("settingsTileHide-$id"),
                )
                Row {
                    OutlinedButton(
                        onClick = {
                            val j = i - 1
                            if (j < 0) return@OutlinedButton
                            val order = local.order.toMutableList()
                            val t = order[i]
                            order[i] = order[j]
                            order[j] = t
                            apply(order, null, null, local.copy(order = order))
                        },
                        enabled = !saving && i > 0,
                    ) {
                        Text("↑")
                    }
                    OutlinedButton(
                        onClick = {
                            val j = i + 1
                            if (j >= local.order.size) return@OutlinedButton
                            val order = local.order.toMutableList()
                            val t = order[i]
                            order[i] = order[j]
                            order[j] = t
                            apply(order, null, null, local.copy(order = order))
                        },
                        enabled = !saving && i < local.order.size - 1,
                        modifier = Modifier.padding(start = 4.dp),
                    ) {
                        Text("↓")
                    }
                }
            }
        }
    }

    // The Calendar tile's render mode (the web's calendar_mode).
    Text(
        text = "Calendar view",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        text = "How the Calendar tile renders: the month grid, or a schedule summary - a chronological list of upcoming events.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        TabChip(
            label = "Calendar",
            selected = local.calendarMode == "month",
            onClick = { if (local.calendarMode != "month") apply(null, null, "month", local.copy(calendarMode = "month")) },
            modifier = Modifier.testTag("calendarMode-month"),
        )
        TabChip(
            label = "Schedule summary",
            selected = local.calendarMode == "schedule",
            onClick = { if (local.calendarMode != "schedule") apply(null, null, "schedule", local.copy(calendarMode = "schedule")) },
            modifier = Modifier.testTag("calendarMode-schedule"),
        )
    }
    err?.let { ErrorNote(it, modifier = Modifier.padding(top = 6.dp)) }

    // The board's grid dimensions (PUT /api/config/grid).
    Text(
        text = "Board grid",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp),
    )
    Text(
        text = "The board is split evenly into this many units across (columns) and down (rows); a tile spans whole units. $GRID_MIN–$GRID_MAX each. Placements that no longer fit a smaller grid fall back to automatic placement.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
    ) {
        OutlinedTextField(
            value = gridCols,
            onValueChange = { gridCols = it; gridErr = null },
            label = { Text("Columns") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "×",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        OutlinedTextField(
            value = gridRows,
            onValueChange = { gridRows = it; gridErr = null },
            label = { Text("Rows") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { saveGrid() },
            enabled = !gridSaving,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(if (gridSaving) "Saving…" else "Save")
        }
    }
    gridSavedMsg?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
    gridErr?.let {
        ErrorNote(it, modifier = Modifier.padding(top = 6.dp))
    }
}

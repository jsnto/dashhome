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

private const val SECTION_APPEARANCE = "Appearance"
private const val SECTION_TILES = "Tiles"
private const val SECTION_HA = "Home Assistant"
private const val SECTION_SERVER = "Server"
private const val SECTION_INTEGRATIONS = "Integrations"
private const val SECTION_UPDATES = "Updates"
private const val SECTION_DANGER = "Danger zone"

/**
 * The settings screen: the web's settings panel sections - Appearance
 * (the light/dark theme pick, persisted client-side), Tiles (which
 * dashboard tiles are on the board, their board order, the size steps,
 * the Calendar view mode, and the board grid dimensions), Home Assistant
 * (the HA-backed board tiles, with the entity picker), plus the app's
 * own Server, the read-only integration summary, the live-update
 * interval, and the danger zone.
 *
 * [theme] is the currently effective theme ("light"/"dark") and
 * [onThemePick] persists the user's choice; [onBoard] adopts a board a
 * save just persisted server-side (the web's onBoard, so the board
 * underneath - when open - stays in sync).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearServer: () -> Unit,
    theme: String,
    onThemePick: (String) -> Unit,
    onBoard: (Board) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var section by remember { mutableStateOf(SECTION_APPEARANCE) }

    var url by remember { mutableStateOf(DashApi.baseUrl) }
    var urlMsg by remember { mutableStateOf<String?>(null) }

    var seconds by remember { mutableStateOf("") }
    var secMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    var cfg by remember { mutableStateOf<ApiConfig?>(null) }
    var cfgErr by remember { mutableStateOf<ApiException?>(null) }
    LaunchedEffect(DashApi.baseUrl) {
        try {
            cfg = DashApi.getConfig()
            cfgErr = null
            if (seconds.isEmpty()) {
                seconds = (cfg!!.board.refreshSeconds).toString()
            }
        } catch (e: ApiException) {
            cfgErr = e
        }
    }

    // The board's effective layout (the Tiles and Home Assistant
    // sections' source), derived from the config the same way the board
    // derives it.
    val grid = remember(cfg) { normalizeGrid(cfg?.board?.grid) }
    val views = remember(cfg, grid) { normalizeViews(cfg?.board?.views, grid) }

    Scaffold(
        topBar = { BackAppBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                // Centered, width-capped column: on a tablet the form
                // reads as a proper panel instead of a full-width stretch.
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterStart)
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // The web's settings section nav: the sections are
                // switched, not stacked.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    listOf(
                        SECTION_APPEARANCE,
                        SECTION_TILES,
                        SECTION_HA,
                        SECTION_SERVER,
                        SECTION_INTEGRATIONS,
                        SECTION_UPDATES,
                        SECTION_DANGER,
                    ).forEach { s ->
                        TabChip(
                            label = s,
                            selected = section == s,
                            onClick = { section = s },
                        )
                    }
                }

                when (section) {
                    SECTION_APPEARANCE -> {
                        SectionHeader(SECTION_APPEARANCE)
                        AppearanceSection(theme = theme, onThemePick = onThemePick)
                    }
                    SECTION_TILES -> {
                        SectionHeader(SECTION_TILES)
                        if (cfg == null && cfgErr != null) {
                            ErrorNote(cfgErr!!)
                        } else {
                            TilesSection(views = views, grid = grid, onBoard = onBoard)
                        }
                    }
                    SECTION_HA -> {
                        SectionHeader(SECTION_HA)
                        HASection(views = views, grid = grid, onBoard = onBoard)
                    }
                    SECTION_SERVER -> {
                        SectionHeader(SECTION_SERVER)
                        ServerSection(
                            url = url,
                            urlMsg = urlMsg,
                            cfgErr = cfgErr,
                            context = context,
                            scope = scope,
                            onSaved = { v, msg ->
                                url = v
                                urlMsg = msg
                            },
                        )
                    }
                    SECTION_INTEGRATIONS -> {
                        SectionHeader(SECTION_INTEGRATIONS)
                        IntegrationsSection(cfg = cfg, context = context)
                    }
                    SECTION_UPDATES -> {
                        SectionHeader(SECTION_UPDATES)
                        UpdatesSection(
                            seconds = seconds,
                            secMsg = secMsg,
                            saving = saving,
                            onSecondsChange = { seconds = it; secMsg = null },
                            onSave = { n ->
                                val v = n.trim().toIntOrNull()
                                if (v == null) {
                                    secMsg = "Enter a whole number of seconds."
                                    return@UpdatesSection
                                }
                                saving = true
                                secMsg = null
                                scope.launch {
                                    try {
                                        val board = DashApi.setRefreshSeconds(v)
                                        seconds = board.refreshSeconds.toString()
                                        secMsg = "Saved — the push feed now polls every ${board.refreshSeconds} seconds."
                                    } catch (e: ApiException) {
                                        secMsg = if (e.detail.isNullOrBlank()) e.code else "${e.code} — ${e.detail}"
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                        )
                    }
                    SECTION_DANGER -> {
                        SectionHeader(SECTION_DANGER)
                        OutlinedButton(
                            onClick = onClearServer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Forget this server")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Appearance section (the web's): the light/dark theme tabs. The
 * pick persists in SharedPreferences; until the user picks, the OS
 * preference applies and is followed live.
 */
@Composable
private fun AppearanceSection(theme: String, onThemePick: (String) -> Unit) {
    Text(
        text = "Theme",
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = "Follows your OS preference until you pick one here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(modifier = Modifier.padding(top = 8.dp)) {
        TabChip(
            label = "Light",
            selected = theme == "light",
            onClick = { onThemePick("light") },
            modifier = Modifier.testTag("themeTab-light"),
        )
        TabChip(
            label = "Dark",
            selected = theme == "dark",
            onClick = { onThemePick("dark") },
            modifier = Modifier.testTag("themeTab-dark"),
        )
    }
}

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
private fun TilesSection(
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

/**
 * The Home Assistant section: adds and removes the HA-backed board tiles
 * (views.ha_tiles) - first-class tiles bound to exactly one HA entity
 * each, from the entities HA reports (GET /api/ha/entities). A button
 * tile fires the allowlisted action (toggle) from the board; a visual
 * tile shows the entity's state. The new tile's default placement is the
 * first free 2x2 cell on the board's effective layout.
 */
@Composable
private fun HASection(
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
    fun persist(next: List<HABoardTile>, geometry: Map<String, TileGeometry?>? = null) {
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
    // tiles included). A grid too small for the span, or a board with no
    // free cell, leaves the tile to auto placement (no geometry in the
    // save).
    fun add() {
        val entityId = entity.trim()
        if (entityId.isEmpty() || haOff) return
        val id = nextHATileId(tiles)
        val nv = normalizeViews(views, grid)
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
            text = "Home Assistant is not configured. Set the Home Assistant URL and token in Settings → Services to add tiles.",
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

/** The Server section (the app's own: the dashhome server URL). */
@Composable
private fun ServerSection(
    url: String,
    urlMsg: String?,
    cfgErr: ApiException?,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onSaved: (String, String?) -> Unit,
) {
    var draft by remember { mutableStateOf(url) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Server URL") },
        placeholder = { Text("http://10.0.0.4:3000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val trimmed = draft.trim().trimEnd('/')
            if (trimmed.isBlank()) {
                onSaved(draft, "Enter the dashhome server URL.")
                return@Button
            }
            scope.launch {
                val msg = if (trimmed == DashApi.baseUrl) {
                    "Saved."
                } else {
                    val prev = DashApi.baseUrl
                    DashApi.baseUrl = trimmed
                    try {
                        DashApi.getConfig()
                        Prefs.setBase(context, trimmed)
                        "Saved — reconnected to $trimmed"
                    } catch (e: ApiException) {
                        DashApi.baseUrl = prev
                        if (e.status == -1) "Cannot reach $trimmed" else e.code
                    }
                }
                onSaved(trimmed, msg)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text("Save")
    }
    urlMsg?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
    cfgErr?.let {
        ErrorNote(it, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The read-only integration summary (the web's services status view). */
@Composable
private fun IntegrationsSection(cfg: ApiConfig?, context: android.content.Context) {
    Text(
        text = "Configured on the server (read-only).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    cfg?.let { c ->
        IntegrationRow(
            label = "Google Calendar",
            stateText = when {
                !c.google.configured -> "Not configured"
                c.google.loginRequired -> "Sign-in required"
                else -> "Configured"
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (c.google.configured && c.google.loginRequired) {
            OutlinedButton(
                onClick = {
                    val u = c.google.loginUrl ?: (DashApi.baseUrl + "/api/auth/google/login")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    } catch (e: Exception) {
                        // No browser available; the web dashboard's sign-in still works.
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Sign in to Google")
            }
        }
        IntegrationRow(
            label = "Home Assistant",
            stateText = if (c.homeAssistant) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        IntegrationRow(
            label = "Hermes chat",
            stateText = if (c.hermesChat) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        IntegrationRow(
            label = "Hermes push",
            stateText = if (c.hermesPush) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

/** The live-update interval (PUT /api/config/refresh). */
@Composable
private fun UpdatesSection(
    seconds: String,
    secMsg: String?,
    saving: Boolean,
    onSecondsChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    Text(
        text = "How often the Hermes push panel polls the server.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = seconds,
            onValueChange = onSecondsChange,
            label = { Text("Seconds (5–3600)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onSave(seconds) },
            enabled = !saving,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(if (saving) "Saving…" else "Save")
        }
    }
    secMsg?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SectionHeader(title: String, top: Dp = 0.dp) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (top == 0.dp) 0.dp else top, bottom = 6.dp),
    )
}

@Composable
private fun IntegrationRow(
    label: String,
    stateText: String,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stateText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stateText == "Not configured") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

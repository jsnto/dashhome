package com.jsnto.dashhome.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiConfig
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.normalizeGrid
import com.jsnto.dashhome.ui.state.normalizeViews
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
 * save just persisted server-side - into this screen's cfg (so the
 * sections re-derive their views/grid from the answer after every save,
 * the web's adopt-after-save) and to the board underneath, when open.
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

    // Every save persists server-side and returns the updated board:
    // adopt it into this screen's cfg (the web's App adopts the answer
    // via onBoard, so the re-derived views/grid stay current while the
    // panel is open), then hand it on.
    val adoptBoard: (Board) -> Unit = { board ->
        cfg = cfg?.copy(board = board)
        onBoard(board)
    }

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
                            TilesSection(views = views, grid = grid, onBoard = adoptBoard)
                        }
                    }
                    SECTION_HA -> {
                        SectionHeader(SECTION_HA)
                        HASection(views = views, grid = grid, onBoard = adoptBoard)
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

@Composable
private fun SectionHeader(title: String, top: Dp = 0.dp) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (top == 0.dp) 0.dp else top, bottom = 6.dp),
    )
}

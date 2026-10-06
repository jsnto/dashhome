package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.ChatState
import com.jsnto.dashhome.ui.state.TabletSectionState
import kotlinx.coroutines.CoroutineScope

/**
 * The sections the tablet board can drill down into: the three dashboard
 * sections plus Settings (what the previous navigation rail showed).
 * Every board window maps to one of these existing full-screen views -
 * no new sections.
 */
enum class TabletItem(val title: String) {
    CALENDAR("Calendar"),
    HOME("Home"),
    HERMES("Hermes"),
    SETTINGS("Settings"),
}

/**
 * The tablet one-page board: everything visible on one screen at a time,
 * like the web dashboard (frontend/src/App.tsx) - a single screen of live
 * content windows, not placeholder cards. The windows are composed the
 * way the web composes them: the main column (7/12 of the width, the
 * web's 7fr/5fr default) is the Calendar section over the Hermes chat
 * panel; the side column (5/12) is the Home/HA embed over the Hermes push
 * panel. The drag handles between the windows mirror the web's
 * drag-resize dividers: the column handle splits the columns and each
 * in-column handle splits its column's windows, the split moving by the
 * pointer's travel over the split's total size and clamped to
 * [MIN_FRAC]..[MAX_FRAC] of the split (the web's MIN_FRAC/MAX_FRAC) so a
 * window can never be dragged away. Like the web's React state, the
 * proportions are session-only: they reset to the defaults on restart.
 *
 * Every window carries the section's real live content (the same views
 * the full-screen views use: the wide Calendar section, the chat pane,
 * the Home WebView embed, the push pane) and a title bar mirroring the
 * web's card-head title. Tapping a window's title opens that section's
 * existing full-screen view ([TabletDrillDown]); back returns to the
 * board. Long content (the day list, the chat feed, the push feed)
 * scrolls inside its own window, and the board itself fits the display
 * with no board-level scrolling. Settings is reachable from the top bar,
 * the way the web's header carries its Settings button.
 *
 * [sections] is the shared section state the tablet root also hands the
 * drill-downs, so a tapped window's full-screen view opens on exactly
 * the state the window was showing, and the board (kept composed under
 * the drill-down) and the drill-down always agree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletBoard(
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    onOpen: (TabletItem) -> Unit,
) {
    // Integration flags from /api/config (the same call the Hermes section
    // makes) drive the panes' friendly setup notes.
    var hermesChat by remember { mutableStateOf(true) }
    var hermesPush by remember { mutableStateOf(true) }
    LaunchedEffect(DashApi.baseUrl) {
        hermesChat = try { DashApi.getConfig().hermesChat } catch (e: ApiException) { true }
        hermesPush = try { DashApi.getConfig().hermesPush } catch (e: ApiException) { true }
    }

    // Window split proportions - the board's counterpart of the web's
    // colFrac/mainFrac/sideFrac React state: the left column's share of
    // the board width and the top window's share in each column.
    // Session-only (they reset to the defaults on restart) and kept in
    // [MIN_FRAC]..[MAX_FRAC] by the drag handles.
    var colFrac by remember { mutableFloatStateOf(DEFAULT_COL_FRAC) }
    var mainFrac by remember { mutableFloatStateOf(DEFAULT_ROW_FRAC) }
    var sideFrac by remember { mutableFloatStateOf(DEFAULT_ROW_FRAC) }

    // The split sizes the handles divide pointer travel by (the web
    // divides by the layout rect for the column handle and by the column
    // rects for the in-column handles).
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }
    var mainColSize by remember { mutableStateOf(IntSize.Zero) }
    var sideColSize by remember { mutableStateOf(IntSize.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("dashhome") },
                actions = {
                    IconButton(onClick = { onOpen(TabletItem.SETTINGS) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .onSizeChanged { layoutSize = it }
                .testTag("boardLayout"),
        ) {
            // Main column: Calendar over the Hermes chat panel.
            Column(
                modifier = Modifier
                    .weight(colFrac)
                    .fillMaxHeight()
                    .onSizeChanged { mainColSize = it }
                    .testTag("boardCol-main"),
            ) {
                BoardWindow(
                    win = "calendar",
                    title = "Calendar",
                    onClick = { onOpen(TabletItem.CALENDAR) },
                    modifier = Modifier.weight(mainFrac),
                ) {
                    CalendarScreen(wide = true, shared = sections)
                }
                BoardHandle(
                    handle = "main",
                    axis = BoardAxis.Y,
                    label = "Resize Calendar and Hermes",
                    initialFrac = { mainFrac },
                    splitSizePx = { mainColSize.height },
                    onFracChange = { mainFrac = it },
                )
                BoardWindow(
                    win = "hermes",
                    title = "Hermes",
                    onClick = { onOpen(TabletItem.HERMES) },
                    modifier = Modifier.weight(1f - mainFrac),
                ) {
                    ChatPane(state = chat, scope = chatScope, configured = hermesChat)
                }
            }
            BoardHandle(
                handle = "col",
                axis = BoardAxis.X,
                label = "Resize columns",
                initialFrac = { colFrac },
                splitSizePx = { layoutSize.width },
                onFracChange = { colFrac = it },
            )
            // Side column: Home/HA embed over the Hermes push panel.
            Column(
                modifier = Modifier
                    .weight(1f - colFrac)
                    .fillMaxHeight()
                    .onSizeChanged { sideColSize = it }
                    .testTag("boardCol-side"),
            ) {
                BoardWindow(
                    win = "home",
                    title = "Home",
                    onClick = { onOpen(TabletItem.HOME) },
                    modifier = Modifier.weight(sideFrac),
                ) {
                    HomeScreen(showTitle = false)
                }
                BoardHandle(
                    handle = "side",
                    axis = BoardAxis.Y,
                    label = "Resize Home and Hermes push",
                    initialFrac = { sideFrac },
                    splitSizePx = { sideColSize.height },
                    onFracChange = { sideFrac = it },
                )
                BoardWindow(
                    win = "push",
                    title = "Hermes push",
                    onClick = { onOpen(TabletItem.HERMES) },
                    modifier = Modifier.weight(1f - sideFrac),
                ) {
                    PushPane(configured = hermesPush, shared = sections)
                }
            }
        }
    }
}

/**
 * The default split proportions, the web's defaults: the 7fr/5fr column
 * split and the 1fr/1fr in-column splits.
 */
private const val DEFAULT_COL_FRAC = 7f / 12f
private const val DEFAULT_ROW_FRAC = 0.5f

/**
 * The web's MIN_FRAC/MAX_FRAC: a split proportion is clamped between 10%
 * and 90% of the split, so a window can never be dragged away.
 */
private const val MIN_FRAC = 0.1f
private const val MAX_FRAC = 0.9f

/**
 * The drag handle's footprint: the board's window gap, so the composition
 * is unchanged when no drag happens.
 */
private val HANDLE_GAP = 16.dp

/** The drag axis of a board handle: the axis the split moves along. */
private enum class BoardAxis {
    /** The column handle: the columns split left/right. */
    X,

    /** An in-column handle: the column's windows split top/bottom. */
    Y,
}

/**
 * A drag handle between two board windows - the Android counterpart of
 * the web dashboard's draggable dividers (frontend/src/App.tsx). It takes
 * the board's window gap as its footprint, so the composition is
 * unchanged when no drag happens. Dragging it along [axis] moves the
 * split exactly the way the web's dividers do: by the pointer's travel
 * over the split's total size ([splitSizePx]), clamped to [MIN_FRAC]..
 * [MAX_FRAC] so a window can never be dragged away. While a drag is
 * active it shows the web's accent line.
 *
 * [label] is the handle's screen-reader name - the same aria-labels the
 * web's dividers carry - so a screen reader can discover and operate
 * the resize.
 *
 * [initialFrac] and [splitSizePx] are read when the drag starts, so the
 * drag always starts from the proportions the board is showing (the web
 * keeps the drag session's startFrac the same way).
 */
@Composable
private fun BoardHandle(
    handle: String,
    axis: BoardAxis,
    label: String,
    initialFrac: () -> Float,
    splitSizePx: () -> Int,
    onFracChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val hit = if (axis == BoardAxis.X) {
        Modifier.fillMaxHeight().width(HANDLE_GAP)
    } else {
        Modifier.fillMaxWidth().height(HANDLE_GAP)
    }
    Box(
        modifier = hit
            .pointerInput(Unit) {
                var startFrac: Float? = null
                var totalDrag = 0f
                detectDragGestures(
                    onDragStart = {
                        startFrac = initialFrac()
                        totalDrag = 0f
                        dragging = true
                    },
                    onDragEnd = {
                        startFrac = null
                        dragging = false
                    },
                    onDrag = { _, dragAmount ->
                        val base = startFrac ?: return@detectDragGestures
                        val size = splitSizePx()
                        if (size <= 0) return@detectDragGestures
                        totalDrag += if (axis == BoardAxis.X) dragAmount.x else dragAmount.y
                        onFracChange((base + totalDrag / size).coerceIn(MIN_FRAC, MAX_FRAC))
                    },
                )
            }
            .semantics { contentDescription = label }
            .testTag("boardHandle-$handle"),
    ) {
        if (dragging) {
            // The web's divider shows its accent line while active.
            val line = if (axis == BoardAxis.X) {
                Modifier.width(2.dp).fillMaxHeight()
            } else {
                Modifier.height(2.dp).fillMaxWidth()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(line)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * One board window: the web dashboard's `.win`/`.card` - a card filled
 * with a section's live content under a title bar (the web's card-head
 * title, whose h2 is the maximize affordance there). The title row is the
 * maximize affordance here: tapping it opens the section's existing
 * full-screen view. The content below is live and interactive in place;
 * long content scrolls inside the window.
 */
@Composable
private fun BoardWindow(
    win: String,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = modifier.testTag("boardWin-$win"),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .testTag("boardTitle-$win")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                // The content keeps the window's inset; the section views
                // bring their own padding, so no uniform extra padding here.
                Box(modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    content()
                }
            }
        }
    }
}

/**
 * The full-screen drill-down for a tapped board window: the same view the
 * app has today - the Calendar's wide two-pane layout, the Home WebView,
 * the Hermes wide layout, or the Settings screen - now with a back
 * button (and system back) that returns to the board. It composes over
 * the still-mounted board and reads the shared [sections], so it opens
 * on the state the tapped window was showing, and the board's live
 * windows survive the round trip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletDrillDown(
    item: TabletItem,
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    onBack: () -> Unit,
    onClearServer: () -> Unit,
) {
    if (item == TabletItem.SETTINGS) {
        // Settings brings its own Scaffold, BackAppBar, and BackHandler.
        Box(modifier = Modifier.fillMaxSize().testTag("drilldown-settings")) {
            SettingsScreen(onBack = onBack, onClearServer = onClearServer)
        }
        return
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = { BackAppBar(title = item.title, onBack = onBack) },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("drilldown-${item.name.lowercase()}"),
        ) {
            when (item) {
                TabletItem.CALENDAR -> CalendarScreen(wide = true, shared = sections)
                TabletItem.HOME -> HomeScreen(showTitle = false)
                TabletItem.HERMES -> HermesScreen(
                    chat = chat,
                    chatScope = chatScope,
                    wide = true,
                    shared = sections,
                )
                TabletItem.SETTINGS -> Unit
            }
        }
    }
}

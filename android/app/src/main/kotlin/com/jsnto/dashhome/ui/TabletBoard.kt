package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.ui.state.ChatState
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
 * panel. Fixed tablet-appropriate proportions replace the web's
 * drag-resize dividers.
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletBoard(
    chat: ChatState,
    chatScope: CoroutineScope,
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
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Main column: Calendar over the Hermes chat panel.
            Column(
                modifier = Modifier
                    .weight(7f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BoardWindow(
                    win = "calendar",
                    title = "Calendar",
                    onClick = { onOpen(TabletItem.CALENDAR) },
                    modifier = Modifier.weight(1f),
                ) {
                    CalendarScreen(wide = true)
                }
                BoardWindow(
                    win = "hermes",
                    title = "Hermes",
                    onClick = { onOpen(TabletItem.HERMES) },
                    modifier = Modifier.weight(1f),
                ) {
                    ChatPane(state = chat, scope = chatScope, configured = hermesChat)
                }
            }
            // Side column: Home/HA embed over the Hermes push panel.
            Column(
                modifier = Modifier
                    .weight(5f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BoardWindow(
                    win = "home",
                    title = "Home",
                    onClick = { onOpen(TabletItem.HOME) },
                    modifier = Modifier.weight(1f),
                ) {
                    HomeScreen(showTitle = false)
                }
                BoardWindow(
                    win = "push",
                    title = "Hermes push",
                    onClick = { onOpen(TabletItem.HERMES) },
                    modifier = Modifier.weight(1f),
                ) {
                    PushPane(configured = hermesPush)
                }
            }
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
 * button (and system back) that returns to the board.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletDrillDown(
    item: TabletItem,
    chat: ChatState,
    chatScope: CoroutineScope,
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
                TabletItem.CALENDAR -> CalendarScreen(wide = true)
                TabletItem.HOME -> HomeScreen(showTitle = false)
                TabletItem.HERMES -> HermesScreen(chat = chat, chatScope = chatScope, wide = true)
                TabletItem.SETTINGS -> Unit
            }
        }
    }
}

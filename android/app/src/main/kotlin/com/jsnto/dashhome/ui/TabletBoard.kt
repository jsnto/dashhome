package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.ui.state.ChatState
import kotlinx.coroutines.CoroutineScope

/**
 * The four items on the tablet one-page board, in the same order the
 * navigation rail showed them today: the three dashboard sections plus
 * Settings. The board is pure navigation - each card is a placeholder
 * (icon, title, one-line summary) and makes no API calls; the live
 * content, including the Home Assistant WebView, belongs to the
 * full-screen drill-down view.
 */
enum class TabletItem(val title: String, val subtitle: String) {
    CALENDAR("Calendar", "Month grid, day detail, and per-calendar tabs"),
    HOME("Home", "The Home Assistant dashboard"),
    HERMES("Hermes", "Chat and the markdown push feed"),
    SETTINGS("Settings", "Server, live updates, and integrations"),
    ;

    val icon: ImageVector
        get() = when (this) {
            CALENDAR -> Icons.Filled.CalendarMonth
            HOME -> Icons.Filled.Home
            HERMES -> Icons.Filled.SmartToy
            SETTINGS -> Icons.Filled.Settings
        }
}

/**
 * The tablet one-page board: every section/item at a glance as tappable
 * cards in a 2x2 grid - the four items read as a proper board at tablet
 * density in both orientations (a 3+1 adaptive row would leave one card
 * stranded on the 1280x800 dp canvas). Tapping a card opens that
 * item's existing full-screen view ([TabletDrillDown]); back returns to
 * the board.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletBoard(onOpen: (TabletItem) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("dashhome") })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(TabletItem.entries) { item ->
                BoardCard(item = item, onClick = { onOpen(item) })
            }
        }
    }
}

/**
 * One board card: a section icon, the section name, a short summary, and
 * an affordance arrow. Sized for the tablet canvas - large enough to
 * read at density without filling the screen.
 */
@Composable
private fun BoardCard(item: TabletItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
        modifier = Modifier
            .testTag("boardItem-${item.name.lowercase()}")
            .height(320.dp)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The full-screen drill-down for a tapped board item: the same view the
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

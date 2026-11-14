package com.jsnto.dashhome.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.state.BoardState
import com.jsnto.dashhome.ui.state.ChatState
import com.jsnto.dashhome.ui.state.rememberTabletSectionState
import com.jsnto.dashhome.ui.theme.DashhomeTheme
import com.jsnto.dashhome.ui.theme.effectiveTheme
import kotlinx.coroutines.CoroutineScope

/** The three dashboard sections, mirroring the web dashboard's cards. */
private enum class Section { CALENDAR, HOME, HERMES }

/**
 * App root: a setup screen until a server URL is saved, then the sections
 * under the app's theme.
 *
 * The theme mirrors the web's useTheme: the user's explicit pick (saved
 * in [Prefs]) wins; while the user has never picked, the OS preference
 * applies and is followed live (a system light/dark change recreates the
 * activity, which re-resolves the theme). The pick flows to the settings
 * screen, which persists it.
 *
 * Phones use a bottom navigation bar; wide displays (tablets,
 * screenWidthDp >= 600) get a one-page tile board instead (the web
 * dashboard's board, data-driven from the board file's grid and views -
 * see [TabletBoard]), every section visible at once, with a window title
 * tap opening the section's existing full-screen view over the still
 * mounted board (drill down, back returns to the board) - and the
 * Calendar and Hermes sections splitting into side-by-side panes inside
 * that view so the layout reads as a wide dashboard rather than a
 * stretched phone screen.
 *
 * [updateManager] is the app's self-update engine (the settings "Update"
 * area - see [UpdateManager]); a test may pass its own manager (e.g. with
 * a test HTTP client), and `null` builds the production one for the
 * hosting activity.
 */
private const val WIDE_SCREEN_DP = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashhomeRoot(updateManager: UpdateManager? = null) {
    val context = LocalContext.current
    // The self-update engine lives at the root (the activity-scoped
    // owner): the settings "Update" area reads its state and the install
    // permission flow hooks into the activity's resume, so the manager
    // outlives any single screen.
    val manager = updateManager
        ?: remember(context as ComponentActivity) {
            UpdateManager(context as ComponentActivity)
        }
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP

    // The theme (the web's useTheme): the persisted pick wins; while the
    // user has never picked one, the OS preference applies and is
    // followed live (a system change recreates the activity and
    // re-resolves this).
    var themeChoice by remember { mutableStateOf(Prefs.theme(context)) }
    val systemDark = isSystemInDarkTheme()
    val dark = effectiveTheme(themeChoice, systemDark)
    val themeName = if (dark) "dark" else "light"
    val pickTheme: (String) -> Unit = { t ->
        Prefs.setTheme(context, t)
        themeChoice = t
    }

    DashhomeTheme(dark = dark) {
        DashhomeShell(
            context = context,
            wide = wide,
            themeName = themeName,
            onThemePick = pickTheme,
            updateManager = manager,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashhomeShell(
    context: android.content.Context,
    wide: Boolean,
    themeName: String,
    onThemePick: (String) -> Unit,
    updateManager: UpdateManager,
) {
    var connected by rememberSaveable { mutableStateOf(Prefs.hasServer(context)) }
    if (!connected) {
        SetupScreen(
            onConnected = {
                DashApi.baseUrl = Prefs.base(context)
                connected = true
            },
        )
        return
    }

    var section by rememberSaveable { mutableIntStateOf(Section.CALENDAR.ordinal) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Tablet drill-down: -1 = the one-page board, otherwise the ordinal of
    // the open [TabletItem]. Saved so rotation/process death keeps the
    // opened full-screen view.
    var openItem by rememberSaveable { mutableIntStateOf(-1) }

    // The chat thread and its send scope live at the root, not in the
    // Hermes section, so an in-flight reply lands even when the section
    // (or just its chat pane) leaves composition.
    val chat = remember { ChatState() }
    val chatScope = rememberCoroutineScope()

    if (showSettings) {
        SettingsScreen(
            onBack = {
                showSettings = false
            },
            onClearServer = {
                Prefs.clear(context)
                DashApi.baseUrl = ""
                showSettings = false
                connected = false
            },
            theme = themeName,
            onThemePick = onThemePick,
            updateManager = updateManager,
        )
        return
    }

    if (wide) {
        // Tablet: the one-page tile board (the web dashboard's board,
        // data-driven from the board file): tapping a window's title
        // opens the section's existing full-screen view (the web's
        // maximize), and back returns to the board. The board keeps
        // composing under the drill-down (the web keeps a maximized
        // window's live content mounted the same way), so the board's
        // windows - the HA WebView, the push poll, the calendar's month -
        // survive the round trip without reload or re-prompt, and the
        // drill-down opens on the state the tapped window was showing
        // (both read [sections]). The board and its settings drill-down
        // share [board]: every board the settings screen persists is
        // adopted (the web's onBoard).
        val sections = rememberTabletSectionState()
        val board = remember { BoardState() }
        val open = if (openItem in 0 until TabletItem.entries.size) TabletItem.entries[openItem] else null
        Box(modifier = Modifier.fillMaxSize()) {
            TabletBoard(
                state = board,
                chat = chat,
                chatScope = chatScope,
                sections = sections,
                onOpen = { openItem = it.ordinal },
                dark = (themeName == "dark"),
            )
            if (open != null) {
                TabletDrillDown(
                    item = open,
                    chat = chat,
                    chatScope = chatScope,
                    sections = sections,
                    theme = themeName,
                    onThemePick = onThemePick,
                    updateManager = updateManager,
                    onBoard = { board.adopt(it) },
                    onBack = { openItem = -1 },
                    onClearServer = {
                        Prefs.clear(context)
                        DashApi.baseUrl = ""
                        openItem = -1
                        connected = false
                    },
                )
            }
        }
    } else {
        // Phone: bottom navigation bar.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("dashhome") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = section == Section.CALENDAR.ordinal,
                        onClick = { section = Section.CALENDAR.ordinal },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.CalendarMonth,
                                contentDescription = "Calendar",
                            )
                        },
                        label = { Text("Calendar") },
                    )
                    NavigationBarItem(
                        selected = section == Section.HOME.ordinal,
                        onClick = { section = Section.HOME.ordinal },
                        icon = {
                            Icon(imageVector = Icons.Filled.Home, contentDescription = "Home")
                        },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = section == Section.HERMES.ordinal,
                        onClick = { section = Section.HERMES.ordinal },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.SmartToy,
                                contentDescription = "Hermes",
                            )
                        },
                        label = { Text("Hermes") },
                    )
                }
            },
        ) { padding ->
            Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                SectionContent(
                    section,
                    chat,
                    chatScope,
                    wide = false,
                )
            }
        }
    }
}

/** The three sections, with wide (tablet) layouts when [wide]. */
@Composable
private fun SectionContent(
    section: Int,
    chat: ChatState,
    chatScope: CoroutineScope,
    wide: Boolean,
) {
    when (Section.values()[section]) {
        Section.CALENDAR -> CalendarScreen(wide = wide)
        Section.HOME -> HomeScreen()
        Section.HERMES ->
            HermesScreen(
                chat = chat,
                chatScope = chatScope,
                wide = wide,
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackAppBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        },
        actions = actions,
    )
}

@Composable
fun SectionCard(
    title: String,
    actions: @Composable () -> Unit = {},
    modifier: Modifier = Modifier.fillMaxWidth(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = modifier,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    actions()
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

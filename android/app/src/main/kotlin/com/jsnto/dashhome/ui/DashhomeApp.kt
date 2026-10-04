package com.jsnto.dashhome.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs

/** The three dashboard sections, mirroring the web dashboard's cards. */
private enum class Section { CALENDAR, HOME, HERMES }

/**
 * App root: a setup screen until a server URL is saved, then the three
 * sections. Phones use a bottom navigation bar; wide displays (tablets,
 * screenWidthDp >= 600) get a navigation rail instead, and the Calendar
 * and Hermes sections split into side-by-side panes so the layout reads
 * as a wide dashboard rather than a stretched phone screen.
 */
private const val WIDE_SCREEN_DP = 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashhomeRoot() {
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= WIDE_SCREEN_DP
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

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onClearServer = {
                Prefs.clear(context)
                DashApi.baseUrl = ""
                showSettings = false
                connected = false
            },
        )
        return
    }

    if (wide) {
        // Tablet: a navigation rail (M3's Scaffold has no rail slot, so the
        // rail sits inside the content area) + wide section layouts.
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("dashhome") })
            },
        ) { padding ->
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(modifier = Modifier.fillMaxHeight()) {
                    RailSectionItem(
                        selected = section == Section.CALENDAR.ordinal,
                        onClick = { section = Section.CALENDAR.ordinal },
                        icon = Icons.Filled.CalendarMonth,
                        label = "Calendar",
                    )
                    RailSectionItem(
                        selected = section == Section.HOME.ordinal,
                        onClick = { section = Section.HOME.ordinal },
                        icon = Icons.Filled.Home,
                        label = "Home",
                    )
                    RailSectionItem(
                        selected = section == Section.HERMES.ordinal,
                        onClick = { section = Section.HERMES.ordinal },
                        icon = Icons.Filled.SmartToy,
                        label = "Hermes",
                    )
                    RailSectionItem(
                        selected = false,
                        onClick = { showSettings = true },
                        icon = Icons.Filled.Settings,
                        label = "Settings",
                    )
                }
                Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
                    SectionContent(section, wide = true)
                }
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
                SectionContent(section, wide = false)
            }
        }
    }
}

/** The three sections, with wide (tablet) layouts when [wide]. */
@Composable
private fun SectionContent(section: Int, wide: Boolean) {
    when (Section.values()[section]) {
        Section.CALENDAR -> CalendarScreen(wide = wide)
        Section.HOME -> HomeScreen()
        Section.HERMES -> HermesScreen(wide = wide)
    }
}

/** A navigation rail entry for one of the dashboard sections. */
@Composable
private fun RailSectionItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = label) },
        label = { Text(label) },
    )
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
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 0.dp) {
            Column(modifier = modifier.padding(12.dp)) {
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

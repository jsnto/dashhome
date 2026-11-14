package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.GridDims
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.api.TileGeometry
import com.jsnto.dashhome.ui.state.BoardState
import com.jsnto.dashhome.ui.state.ChatState
import com.jsnto.dashhome.ui.state.DragMode
import com.jsnto.dashhome.ui.state.TabletSectionState
import com.jsnto.dashhome.ui.state.VIEW_IDS
import com.jsnto.dashhome.ui.state.autoLayout
import com.jsnto.dashhome.ui.state.dragCell
import com.jsnto.dashhome.ui.state.dropRefused
import com.jsnto.dashhome.ui.state.gridMetrics
import com.jsnto.dashhome.ui.state.haTileLabel
import com.jsnto.dashhome.ui.state.normalizeGrid
import com.jsnto.dashhome.ui.state.normalizeViews
import com.jsnto.dashhome.ui.state.renderedRows
import com.jsnto.dashhome.ui.state.tileName
import com.jsnto.dashhome.ui.state.visibleOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

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
    theme: String,
    onThemePick: (String) -> Unit,
    updateManager: UpdateManager,
    onBoard: (Board) -> Unit,
    onBack: () -> Unit,
    onClearServer: () -> Unit,
) {
    if (item == TabletItem.SETTINGS) {
        // Settings brings its own Scaffold, BackAppBar, and BackHandler.
        Box(modifier = Modifier.fillMaxSize().testTag("drilldown-settings")) {
            SettingsScreen(
                onBack = onBack,
                onClearServer = onClearServer,
                theme = theme,
                onThemePick = onThemePick,
                updateManager = updateManager,
                onBoard = onBoard,
            )
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

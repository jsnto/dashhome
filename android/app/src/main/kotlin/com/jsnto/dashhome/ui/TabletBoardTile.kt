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

/** The content of one board tile (the web's winNode). */
@Composable
internal fun TileContent(
    id: String,
    views: BoardViews,
    chat: ChatState,
    chatScope: CoroutineScope,
    sections: TabletSectionState,
    haEntities: Map<String, HAEntity>,
    haOff: Boolean,
    haLoad: () -> Unit,
    hermesChat: Boolean,
    hermesPush: Boolean,
    dark: Boolean,
) {
    when (id) {
        "calendar" -> CalendarScreen(
            wide = true,
            shared = sections,
            calendarMode = views.calendarMode,
        )
        "chat" -> ChatPane(state = chat, scope = chatScope, configured = hermesChat)
        "home" -> HomeScreen(showTitle = false)
        "push" -> PushPane(configured = hermesPush, shared = sections)
        else -> HaContent(id, views, haEntities, haOff, haLoad, dark)
    }
}

/** The content of a Home Assistant board tile (the web's HATile). */
@Composable
internal fun HaContent(
    id: String,
    views: BoardViews,
    haEntities: Map<String, HAEntity>,
    haOff: Boolean,
    haLoad: () -> Unit,
    dark: Boolean,
) {
    val tile = views.haTiles.firstOrNull { it.id == id } ?: return
    HATileView(
        tile = tile,
        entity = haEntities[tile.entity],
        haOff = haOff,
        onService = haLoad,
        dark = dark,
    )
}

/**
 * One board tile: the web's `.tile`/`.card` - a card placed on the grid
 * by its effective cell (the stored placement or the auto flow, the
 * live preview cell while a drag is in flight), filled with the section's
 * live content under a title bar (the web's card-head title; the maximize
 * affordance there). A drag preview renders the tile at the candidate
 * cell at 0.9 opacity, with the danger outline when the drop would be
 * refused (the web's `.tile.dragging` / `.tile.preview-invalid`). In edit
 * mode the tile carries the web's tile-edit row (the move label, the drag
 * grip, the settings and remove buttons) and the corner resize handle.
 */
@Composable
internal fun BoardTile(
    id: String,
    name: String,
    cell: TileGeometry,
    unitW: androidx.compose.ui.unit.Dp,
    unitH: androidx.compose.ui.unit.Dp,
    editMode: Boolean,
    saving: Boolean = false,
    dragging: Boolean = false,
    refused: Boolean = false,
    onTitleClick: () -> Unit,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
    onGripDown: (Offset) -> Unit = {},
    onGripDrag: (Offset, Offset) -> Unit = { _, _ -> },
    onGripEnd: () -> Unit = {},
    onGripCancel: () -> Unit = {},
    onResizeDown: (Offset) -> Unit = {},
    onResizeDrag: (Offset, Offset) -> Unit = { _, _ -> },
    onResizeEnd: () -> Unit = {},
    onResizeCancel: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gap = BOARD_GAP
    // Each handle's live position in the root space, shared between the
    // layout callback and the pointer blocks (pointerInput keyed on Unit
    // keeps its attach-time closures): the pointer travel is measured in
    // this stable space (the web's client coordinates) - the handle's
    // own local space moves with the tile being dragged.
    val gripPos = remember { object { var pos = Offset.Zero } }
    val resizePos = remember { object { var pos = Offset.Zero } }
    val wDp = maxOf(unitW * cell.w + gap * (cell.w - 1), 1.dp)
    val hDp = maxOf(unitH * cell.h + gap * (cell.h - 1), 1.dp)
    val xPx = with(density) { ((cell.x - 1) * (unitW + gap)).roundToPx() }
    val yPx = with(density) { ((cell.y - 1) * (unitH + gap)).roundToPx() }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 0.dp,
            modifier = Modifier
                .offset { IntOffset(xPx, yPx) }
                .size(width = wDp, height = hDp)
                .then(
                    if (dragging && refused) {
                        // The refused drop's danger outline (the web's
                        // .preview-invalid).
                        Modifier.border(
                            2.dp,
                            Color(0xFFE5534B),
                            shape = MaterialTheme.shapes.medium,
                        )
                    } else Modifier,
                )
                .testTag("boardWin-$id")
                .semantics { contentDescription = "$name tile" }
                .then(if (dragging) Modifier.alpha(0.9f) else Modifier),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !editMode, onClick = onTitleClick)
                        .testTag("boardTitle-$id")
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = name,
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
                if (editMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                            .testTag("boardEditRow-$id"),
                    ) {
                        Text(
                            text = "Move",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        // The move grip (the web's .tile-grip): drag it to
                        // move the tile; the pointer math snaps to the
                        // grid's cells.
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .onGloballyPositioned { c -> gripPos.pos = c.positionInRoot() }
                                .pointerInput(Unit) {
                                    var down: Offset? = null
                                    detectDragGestures(
                                        onDragStart = { o ->
                                            down = o + gripPos.pos
                                            onGripDown(down!!)
                                        },
                                        onDrag = { change, _ ->
                                            down?.let { onGripDrag(it, change.position + gripPos.pos) }
                                        },
                                        onDragEnd = onGripEnd,
                                        onDragCancel = onGripCancel,
                                    )
                                }
                                .testTag("boardGrip-$id")
                                .semantics { contentDescription = "Drag to move this tile" },
                        ) {
                            Text(
                                text = "⠿",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        TextButton(
                            onClick = onEdit,
                            modifier = Modifier
                                .testTag("boardEdit-$id")
                                .semantics { contentDescription = "Edit tile settings" },
                        ) {
                            Text("✎")
                        }
                        TextButton(
                            onClick = onRemove,
                            enabled = !saving,
                            modifier = Modifier
                                .testTag("boardRemove-$id")
                                .semantics { contentDescription = "Remove tile from the board" },
                        ) {
                            Text("✕")
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    content()
                }
                }
                if (editMode) {
                    // The corner resize handle (the web's .tile-resize,
                    // bottom-right): drag it to resize width + height.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 6.dp)
                            .size(18.dp)
                            .onGloballyPositioned { c -> resizePos.pos = c.positionInRoot() }
                            .pointerInput(Unit) {
                                var down: Offset? = null
                                detectDragGestures(
                                    onDragStart = { o ->
                                        down = o + resizePos.pos
                                        onResizeDown(down!!)
                                    },
                                    onDrag = { change, _ ->
                                        down?.let { onResizeDrag(it, change.position + resizePos.pos) }
                                    },
                                    onDragEnd = onResizeEnd,
                                    onDragCancel = onResizeCancel,
                                )
                            }
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(bottomEnd = 8.dp),
                            )
                            .testTag("boardResize-$id")
                            .semantics { contentDescription = "Drag to resize this tile" },
                    )
                }
            }
        }
    }
}

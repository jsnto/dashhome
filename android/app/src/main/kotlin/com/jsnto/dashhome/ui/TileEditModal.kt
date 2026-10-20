package com.jsnto.dashhome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.ui.state.VIEW_IDS
import com.jsnto.dashhome.ui.state.haTileLabel
import com.jsnto.dashhome.ui.state.isHATileId
import com.jsnto.dashhome.ui.state.tileName
import kotlinx.coroutines.launch

private val SIZES = listOf("s", "m", "l", "xl")

/**
 * The per-tile settings modal: the edit options for one tile, grouped in
 * labeled sections - a built-in view tile's size step (the width the
 * board uses when it places the tile automatically), the Calendar
 * tile's view mode (its content), and a Home Assistant tile's entity,
 * kind and label (its HA mapping) - saved through the same board-views
 * path as the settings screen. Closes from the Close/Cancel button, a
 * tap on the scrim (the web's Escape/backdrop close; a tap on the card
 * itself never closes it), or the system back; a successful save closes
 * it too (the change is visible on the board itself).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileEditModal(
    id: String,
    views: BoardViews,
    entities: Map<String, HAEntity>,
    haOff: Boolean,
    onBoard: (Board) -> Unit,
    onClose: () -> Unit,
) {
    val view = id in VIEW_IDS
    val ht = views.haTiles.firstOrNull { it.id == id }
    // The web's modal header: an HA tile wears the entity's friendly
    // label (the tile's label, else the entity's own label, else the id).
    val name = if (ht != null) {
        haTileLabel(ht, entities[ht.entity])
    } else {
        tileName(id, views)
    }

    // Drafts seeded from the effective layout (the modal mounts fresh for
    // every tile it opens on).
    var size by remember { mutableStateOf(views.sizes[id] ?: "m") }
    var mode by remember { mutableStateOf(views.calendarMode) }
    var entity by remember { mutableStateOf(ht?.entity ?: "") }
    var label by remember { mutableStateOf(ht?.label ?: "") }
    var kind by remember { mutableStateOf(ht?.kind ?: "button") }
    var saving by remember { mutableStateOf(false) }
    var saveErr by remember { mutableStateOf<ApiException?>(null) }
    val scope = rememberCoroutineScope()

    val sizeSeed = views.sizes[id] ?: "m"
    val sizeChanged = size != sizeSeed
    val haChanged =
        entity.trim() != (ht?.entity ?: "") ||
        label.trim() != (ht?.label ?: "") ||
        kind != (ht?.kind ?: "button")
    val changed = if (view) {
        sizeChanged || (id == "calendar" && mode != views.calendarMode)
    } else {
        haChanged
    }

    // The board-views patch a save sends: the changed size step (per-tile
    // merge), the calendar view mode when the Calendar tile changed it,
    // and - for an HA tile - the ha_tiles list with this tile's fields
    // replaced. The server re-normalizes and the answer is adopted.
    fun save() {
        if (saving || !changed) return
        val trimmedEntity = entity.trim()
        if (!view && trimmedEntity.isEmpty()) {
            saveErr = ApiException(0, "bad_value", "The tile needs a Home Assistant entity.")
            return
        }
        saving = true
        saveErr = null
        scope.launch {
            try {
                val board = if (view) {
                    DashApi.setViews(
                        sizes = if (sizeChanged) mapOf(id to size) else null,
                        calendarMode =
                            if (id == "calendar" && mode != views.calendarMode) mode else null,
                    )
                } else {
                    DashApi.setViews(
                        haTiles = views.haTiles.map { t ->
                            if (t.id == id) {
                                HABoardTile(
                                    id = id,
                                    entity = trimmedEntity,
                                    label = label.trim().ifEmpty { null },
                                    kind = kind,
                                )
                            } else t
                        },
                    )
                }
                onBoard(board)
            } catch (e: ApiException) {
                saveErr = e
            } finally {
                saving = false
            }
        }
    }

    // The per-tile settings overlay. An in-scene scrim + card (not an
// android Dialog): the board already overlays its drill-downs this way,
// and the pattern keeps the modal in the same composition scene.
Box(
    modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.25f))
        .clickable(onClick = onClose),
) {
    BackHandler(onBack = onClose)
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        // The card absorbs taps itself: only a tap on the scrim, outside
        // the card, closes the modal (the web's pressOnBackdrop guard).
        modifier = Modifier
            .align(Alignment.Center)
            .fillMaxWidth()
            .fillMaxHeight(0.8f)
            .padding(24.dp)
            .testTag("tileEditModal")
            .clickable(interactionSource = null, indication = null) {},
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (isHATileId(id)) {
                        Text(
                            text = "HA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    TextButton(onClick = onClose) {
                        Text(
                            "✕",
                            modifier = Modifier.semantics { contentDescription = "Close tile settings" },
                        )
                    }
                }

                if (view) {
                    ModalSection("Size") {
                        ModalRow(
                            label = "Tile size",
                            desc = "The tile's width when the board places it automatically; an explicit placement (drag/resize on the board) keeps its own span.",
                        )
                        Row {
                            SIZES.forEach { s ->
                                TabChip(
                                    label = sizeStepLabel(s),
                                    selected = size == s,
                                    onClick = { if (!saving) size = s },
                                )
                            }
                        }
                    }
                    if (id == "calendar") {
                        ModalSection("Content") {
                            ModalRow(
                                label = "Calendar view",
                                desc = "How the Calendar tile renders: the month grid, or a schedule summary - a chronological list of upcoming events.",
                            )
                            Row {
                                TabChip(
                                    label = "Calendar",
                                    selected = mode == "month",
                                    onClick = { if (!saving) mode = "month" },
                                )
                                TabChip(
                                    label = "Schedule summary",
                                    selected = mode == "schedule",
                                    onClick = { if (!saving) mode = "schedule" },
                                )
                            }
                        }
                    }
                } else {
                    ModalSection("Home Assistant") {
                        ModalRow(
                            label = "Entity",
                            desc = "The HA entity this tile binds to (type to search the entity list).",
                        )
                        OutlinedTextField(
                            value = entity,
                            onValueChange = { entity = it },
                            placeholder = { Text("light.kitchen") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        EntityPickerChips(
                            entities = entities.values.toList(),
                            onPick = { entity = it.entityId },
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        ModalRow(
                            label = "Tile kind",
                            desc = "A button tile fires the allowlisted action (toggle) from the board; a visual tile shows the entity's state.",
                        )
                        Row {
                            TabChip(
                                label = "Button",
                                selected = kind == "button",
                                onClick = { if (!saving) kind = "button" },
                            )
                            TabChip(
                                label = "Visual",
                                selected = kind == "visual",
                                onClick = { if (!saving) kind = "visual" },
                            )
                        }
                        ModalRow(
                            label = "Label (optional)",
                            desc = "Shown on the tile instead of the entity's own label.",
                        )
                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            placeholder = { Text("Kitchen") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (haOff) {
                            Note(
                                text = "Home Assistant is not configured - the entity list is unavailable until the connection is set.",
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                saveErr?.let {
                    ErrorNote(it, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.padding(end = 8.dp),
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { save() },
                    enabled = !saving && changed,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }
        }
    }
    }
}

private fun sizeStepLabel(size: String): String =
    when (size) {
        "s" -> "Small"
        "l" -> "Large"
        "xl" -> "Full width"
        else -> "Medium"
    }

@Composable
private fun ModalSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Box { content() }
    }
}

@Composable
private fun ModalRow(label: String, desc: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The entity picker (the web's datalist over GET /api/ha/entities): a
 * free-text field plus the entity chips to pick from; tapping a chip
 * fills the field with the entity id.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntityPickerChips(
    entities: List<HAEntity>,
    onPick: (HAEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entities.isEmpty()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            entities.forEach { e ->
                TabChip(
                    label = e.label.ifEmpty { e.entityId },
                    selected = false,
                    onClick = { onPick(e) },
                )
            }
        }
    }
}

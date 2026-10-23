package com.jsnto.dashhome.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.ui.state.VIEW_IDS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The HA entity data the board tiles read: the entity states (polled,
 *  never stored), the honest "not configured" flag, and the on-demand
 *  refresh (the web's useHAEntities hook). */
data class BoardHaState(
    val entities: Map<String, HAEntity>,
    val off: Boolean,
    val load: () -> Unit,
)

/**
 * The board's Home Assistant entity states, polled while any HA tile is
 * visible (the web's useHAEntities: on mount, on a 10 s timer; the 503
 * "not configured" is reported via [BoardHaState.off] rather than an
 * error, and a transient 502 keeps the last good states). A refresh the
 * moment the app regains visibility too (the web's visibilitychange
 * reload): the 10 s poll alone would leave a visual tile's state up to a
 * full tick stale after a background. The listener lives only while an
 * HA tile is visible (the web's `active` gate), so a board without HA
 * tiles fetches nothing.
 */
@Composable
internal fun boardHaEntities(visible: List<String>, scope: CoroutineScope): BoardHaState {
    val haVisible = remember(visible) { visible.filter { it !in VIEW_IDS } }
    var entities by remember { mutableStateOf<Map<String, HAEntity>>(emptyMap()) }
    var off by remember { mutableStateOf(false) }
    val haKey = haVisible.joinToString(",")

    val load: () -> Unit = remember {
        {
            scope.launch {
                try {
                    entities = DashApi.getHAEntities().associateBy { it.entityId }
                    off = false
                } catch (e: ApiException) {
                    if (e.code == "homeassistant_not_configured") off = true
                }
            }
        }
    }
    LaunchedEffect(DashApi.baseUrl, haKey) {
        if (haKey.isEmpty()) return@LaunchedEffect
        load()
        while (true) {
            delay(HA_POLL_MS)
            load()
        }
    }

    // A refresh the moment the app regains visibility (the web's
    // visibilitychange reload): the 10 s poll alone would leave a
    // visual tile's state up to a full tick stale after a background.
    // The listener lives only while an HA tile is visible (the web's
    // `active` gate), so a board without HA tiles fetches nothing.
    val lifecycleOwner = LocalLifecycleOwner.current
    if (haKey.isNotEmpty()) {
        DisposableEffect(lifecycleOwner, haKey) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) load()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    return BoardHaState(entities, off, load)
}

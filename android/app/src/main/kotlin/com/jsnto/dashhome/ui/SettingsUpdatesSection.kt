package com.jsnto.dashhome.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiConfig
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.Board
import com.jsnto.dashhome.api.BoardViews
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.HABoardTile
import com.jsnto.dashhome.api.HAEntity
import com.jsnto.dashhome.api.TileGeometry
import com.jsnto.dashhome.data.Prefs
import com.jsnto.dashhome.ui.state.GRID_MAX
import com.jsnto.dashhome.ui.state.GRID_MIN
import com.jsnto.dashhome.ui.state.NEW_TILE_DEFAULT_SIZE
import com.jsnto.dashhome.ui.state.TILE_SIZE_LABELS
import com.jsnto.dashhome.ui.state.firstFreeCell
import com.jsnto.dashhome.ui.state.isHATileId
import com.jsnto.dashhome.ui.state.nextHATileId
import com.jsnto.dashhome.ui.state.normalizeGrid
import com.jsnto.dashhome.ui.state.normalizeViews
import com.jsnto.dashhome.ui.state.tileName
import kotlinx.coroutines.launch

/** The live-update interval (PUT /api/config/refresh). */
@Composable
internal fun UpdatesSection(
    seconds: String,
    secMsg: String?,
    saving: Boolean,
    onSecondsChange: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    Text(
        text = "How often the Hermes push panel polls the server.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = seconds,
            onValueChange = onSecondsChange,
            label = { Text("Seconds (5–3600)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { onSave(seconds) },
            enabled = !saving,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Text(if (saving) "Saving…" else "Save")
        }
    }
    secMsg?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

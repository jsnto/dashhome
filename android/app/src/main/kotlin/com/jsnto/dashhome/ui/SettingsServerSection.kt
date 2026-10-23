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

/** The Server section (the app's own: the dashhome server URL). */
@Composable
internal fun ServerSection(
    url: String,
    urlMsg: String?,
    cfgErr: ApiException?,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    onSaved: (String, String?) -> Unit,
) {
    var draft by remember { mutableStateOf(url) }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("Server URL") },
        placeholder = { Text("http://10.0.0.4:3000") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            val trimmed = draft.trim().trimEnd('/')
            if (trimmed.isBlank()) {
                onSaved(draft, "Enter the dashhome server URL.")
                return@Button
            }
            scope.launch {
                val msg = if (trimmed == DashApi.baseUrl) {
                    "Saved."
                } else {
                    val prev = DashApi.baseUrl
                    DashApi.baseUrl = trimmed
                    try {
                        DashApi.getConfig()
                        Prefs.setBase(context, trimmed)
                        "Saved — reconnected to $trimmed"
                    } catch (e: ApiException) {
                        DashApi.baseUrl = prev
                        if (e.status == -1) "Cannot reach $trimmed" else e.code
                    }
                }
                onSaved(trimmed, msg)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text("Save")
    }
    urlMsg?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }
    cfgErr?.let {
        ErrorNote(it, modifier = Modifier.padding(top = 6.dp))
    }
}

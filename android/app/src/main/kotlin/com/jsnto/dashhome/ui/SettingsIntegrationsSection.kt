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

/** The read-only integration summary (the web's services status view). */
@Composable
internal fun IntegrationsSection(cfg: ApiConfig?, context: android.content.Context) {
    Text(
        text = "Configured on the server (read-only).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    cfg?.let { c ->
        IntegrationRow(
            label = "Google Calendar",
            stateText = when {
                !c.google.configured -> "Not configured"
                c.google.loginRequired -> "Sign-in required"
                else -> "Configured"
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (c.google.configured && c.google.loginRequired) {
            OutlinedButton(
                onClick = {
                    val u = c.google.loginUrl ?: (DashApi.baseUrl + "/api/auth/google/login")
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    } catch (e: Exception) {
                        // No browser available; the web dashboard's sign-in still works.
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Sign in to Google")
            }
        }
        IntegrationRow(
            label = "Home Assistant",
            stateText = if (c.homeAssistant) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        IntegrationRow(
            label = "Hermes chat",
            stateText = if (c.hermesChat) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        IntegrationRow(
            label = "Hermes push",
            stateText = if (c.hermesPush) "Configured" else "Not configured",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun IntegrationRow(
    label: String,
    stateText: String,
    modifier: Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stateText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stateText == "Not configured") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

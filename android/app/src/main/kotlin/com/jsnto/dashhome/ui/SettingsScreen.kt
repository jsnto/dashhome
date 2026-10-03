package com.jsnto.dashhome.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiConfig
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import kotlinx.coroutines.launch

/**
 * The settings screen: the dashhome server URL (LAN address), the
 * live-update interval (persisted server-side via PUT /api/config/refresh),
 * and the read-only integration summary the web's settings panel shows.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearServer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(DashApi.baseUrl) }
    var urlMsg by remember { mutableStateOf<String?>(null) }

    var seconds by remember { mutableStateOf("") }
    var secMsg by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    var cfg by remember { mutableStateOf<ApiConfig?>(null) }
    var cfgErr by remember { mutableStateOf<ApiException?>(null) }
    LaunchedEffect(DashApi.baseUrl) {
        try {
            cfg = DashApi.getConfig()
            cfgErr = null
            if (seconds.isEmpty()) {
                seconds = (cfg!!.board.refreshSeconds).toString()
            }
        } catch (e: ApiException) {
            cfgErr = e
        }
    }

    Scaffold(
        topBar = { BackAppBar(title = "Settings", onBack = onBack) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                // Centered, width-capped column: on a tablet the form
                // reads as a proper panel instead of a full-width stretch.
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.CenterStart)
                    .fillMaxSize()
                    .widthIn(max = 560.dp)
                    .padding(16.dp),
            ) {
            // --- server -------------------------------------------------
            SectionHeader("Server")
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    urlMsg = null
                },
                label = { Text("Server URL") },
                placeholder = { Text("http://10.0.0.4:3000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val trimmed = url.trim().trimEnd('/')
                    if (trimmed.isBlank()) {
                        urlMsg = "Enter the dashhome server URL."
                        return@Button
                    }
                    scope.launch {
                        urlMsg = if (trimmed == DashApi.baseUrl) "Saved." else {
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
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Save")
            }
            urlMsg?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
            cfgErr?.let {
                ErrorNote(it, modifier = Modifier.padding(top = 6.dp))
            }

            // --- live updates -------------------------------------------
            SectionHeader("Live updates", top = 20.dp)
            Text(
                text = "How often the Hermes push panel polls the server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = seconds,
                    onValueChange = {
                        seconds = it
                        secMsg = null
                    },
                    label = { Text("Seconds (5–3600)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val n = seconds.trim().toIntOrNull()
                        if (n == null) {
                            secMsg = "Enter a whole number of seconds."
                            return@Button
                        }
                        saving = true
                        secMsg = null
                        scope.launch {
                            try {
                                val board = DashApi.setRefreshSeconds(n)
                                seconds = board.refreshSeconds.toString()
                                secMsg = "Saved — the push feed now polls every ${board.refreshSeconds} seconds."
                            } catch (e: ApiException) {
                                secMsg = if (e.detail.isNullOrBlank()) e.code else "${e.code} — ${e.detail}"
                            } finally {
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
            }
            secMsg?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            }

            // --- integrations --------------------------------------------
            SectionHeader("Integrations", top = 20.dp)
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
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
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

            // --- danger zone ---------------------------------------------
            SectionHeader("Danger zone", top = 20.dp)
            OutlinedButton(
                onClick = onClearServer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Forget this server")
            }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, top: Dp = 0.dp) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = if (top == 0.dp) 0.dp else top, bottom = 6.dp),
    )
}

@Composable
private fun IntegrationRow(
    label: String,
    stateText: String,
    modifier: Modifier = Modifier,
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

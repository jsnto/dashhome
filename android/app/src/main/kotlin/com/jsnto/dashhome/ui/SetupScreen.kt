package com.jsnto.dashhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.data.Prefs
import kotlinx.coroutines.launch

/**
 * Shown until a dashhome server URL is saved: the LAN address of the
 * running instance (e.g. http://10.0.0.4:3000). The dashboard has no
 * login, so saving the URL is the whole setup.
 */
@Composable
fun SetupScreen(
    onConnected: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(Prefs.base(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pending) {
        val target = pending ?: return@LaunchedEffect
        checking = true
        val prev = DashApi.baseUrl
        try {
            DashApi.baseUrl = target
            DashApi.getConfig() // throws ApiException when unreachable
            Prefs.setBase(context, target)
            onConnected()
        } catch (e: ApiException) {
            DashApi.baseUrl = prev
            error = if (e.status == -1) "Cannot reach $target" else e.code
        } finally {
            checking = false
            pending = null
        }
    }

    // Explicit surface: without it the screen falls back to the window
    // background and the default (black) text color, which reads as
    // dark-on-dark.
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
        Text(
            text = "dashhome",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Point the app at your dashhome server on the LAN.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                error = null
            },
            label = { Text("Server URL") },
            placeholder = { Text("http://10.0.0.4:3000") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = {
                val trimmed = url.trim().trimEnd('/')
                if (trimmed.isBlank()) {
                    error = "Enter the dashhome server URL."
                } else {
                    error = null
                    pending = trimmed
                }
            },
            enabled = !checking,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
                Text(if (checking) "Checking…" else "Connect")
            }
            }
        }
    }
}

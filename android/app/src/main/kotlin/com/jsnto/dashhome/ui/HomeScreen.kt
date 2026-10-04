package com.jsnto.dashhome.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi

/**
 * The Home section: the real Home Assistant dashboard, same embed as the
 * web's Home card. The URL comes from GET /api/ha/embed-url (assembled
 * server-side, token only when the board enables token_url) and is shown
 * in a WebView - a top-level page, so HA's X-Frame-Options header does
 * not block it the way it can block the web's cross-origin iframe.
 */
@Composable
fun HomeScreen() {
    val context = LocalContext.current

    // Embed URL lifecycle: fetching / loaded / error; [embedKey] remounts
    // the WebView so Reload re-fetches the URL and reloads the frame.
    var embedKey by rememberSaveable { mutableIntStateOf(0) }
    var url by remember(embedKey) { mutableStateOf<String?>(null) }
    var err by remember(embedKey) { mutableStateOf<ApiException?>(null) }
    var loading by remember(embedKey) { mutableStateOf(true) }
    LaunchedEffect(DashApi.baseUrl, embedKey) {
        loading = true
        err = null
        url = null
        try {
            url = DashApi.getHAAEmbedUrl()
        } catch (e: ApiException) {
            err = e
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Home",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (url != null && err == null) {
                TextButton(onClick = { openInBrowser(context, url!!) }) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = "Open in browser",
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text("Browser")
                }
                IconButton(onClick = { embedKey++ }) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reload",
                    )
                }
            }
        }

        when {
            err != null -> ErrorNote(err!!)
            loading -> LoadingRow()
            url != null -> {
                val u = url!!
                AndroidView(
                    factory = {
                        WebView(context).apply {
                            // The HA dashboard is a JavaScript app (same as
                            // the web's iframe) - without JS it renders
                            // nothing; domStorage keeps HA's own session.
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // Stay in-app for http(s) navigation (HA routes);
                            // anything else goes to the default browser.
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                ): Boolean {
                                    val uri = request.url
                                    return if (uri.scheme == "http" || uri.scheme == "https") {
                                        false
                                    } else {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, uri),
                                            )
                                        } catch (e: Exception) {
                                            // Unhandled scheme; the WebView keeps working.
                                        }
                                        true
                                    }
                                }
                            }
                            // First load happens here; the composable key
                            // (embedKey) remounts this view on Reload.
                            loadUrl(u)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
            else -> Note("No Home Assistant configured on the server.")
        }
    }
}

/** Opens the embed URL in the system browser (the web's "new tab" escape). */
private fun openInBrowser(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // No browser available; the in-app WebView keeps working.
    }
}

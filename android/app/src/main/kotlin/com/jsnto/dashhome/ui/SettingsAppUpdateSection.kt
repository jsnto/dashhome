package com.jsnto.dashhome.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.BuildConfig

/**
 * The settings "Update" section: whether the running build is the newest
 * public release of the app's own repository, and (when it is not) the
 * actions to download and install the newest one. The app is sideloaded as
 * an APK (there is no store), so the check reads the public GitHub
 * releases page and the update installs the newest release's APK asset
 * through the platform's own installer.
 *
 * The section is presentational: it renders the [UpdateState] and the
 * download's [progress] the [UpdateManager] publishes, and it calls the
 * manager's actions ([onCheck], [onDownload], [onInstall]). The engine
 * (the fetch, the download, the FileProvider uri, the install intent, and
 * the "install unknown apps" gate) is [UpdateManager]; the version
 * ordering and the response parse are the pure domain in
 * `data/UpdateCheck.kt`.
 */
@Composable
internal fun AppUpdateSection(
    state: UpdateState,
    progress: Float,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The running build (BuildConfig.VERSION_NAME - the version the
        // check compares against).
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "Current version",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "dashhome v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        UpdateStatus(state = state, progress = progress)
        // Check the releases page for a newer build. Live except while a
        // check or a download is in flight and while an APK is already
        // downloaded and awaiting install (re-checking there would lose
        // the ready-to-install state, so the Install button is the live
        // action).
        Button(
            onClick = onCheck,
            enabled = state !is UpdateState.Checking &&
                state !is UpdateState.Downloading &&
                state !is UpdateState.ReadyToInstall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check for latest version")
        }
        // Download the newest release's APK (enabled only while a newer
        // release is known to exist - the check's UpdateAvailable).
        Button(
            onClick = onDownload,
            enabled = state is UpdateState.UpdateAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Update to latest")
        }
        // Install the downloaded APK (enabled only once the download has
        // settled ReadyToInstall): gated on the phone's "install unknown
        // apps" permission, which the engine opens for the user when it
        // is missing.
        Button(
            onClick = onInstall,
            enabled = state is UpdateState.ReadyToInstall,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Install now")
        }
    }
}

/**
 * The section's status copy for the current [UpdateState]: the plain
 * result of a check (up to date / an update is available / a failure's
 * message), the in-flight check, and the download's live progress bar +
 * percentage.
 */
@Composable
private fun UpdateStatus(state: UpdateState, progress: Float) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        UpdateState.Idle -> Text(
            "Check the releases page to see if a newer version is out.",
            style = MaterialTheme.typography.bodyMedium,
        )
        UpdateState.Checking -> Text(
            "Checking for a newer version\u2026",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.UpToDate -> Text(
            "You're up to date (current version v${state.currentVersion}).",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.UpdateAvailable -> Text(
            "Update available: v${state.latestVersion} (you have v${state.currentVersion}).",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Downloading v${state.latestVersion}\u2026 ${percent(progress)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is UpdateState.ReadyToInstall -> Text(
            "Downloaded v${state.latestVersion}. Install it to update.",
            style = MaterialTheme.typography.bodyMedium,
        )
        is UpdateState.Error -> Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.error,
        )
    }
}

/** The download's fraction (0..1) as a percentage label. */
private fun percent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

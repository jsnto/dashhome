package com.jsnto.dashhome.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jsnto.dashhome.api.ApiException
import com.jsnto.dashhome.api.DashApi
import com.jsnto.dashhome.api.Push
import com.jsnto.dashhome.markdown.MarkdownText
import com.jsnto.dashhome.ui.state.ChatState
import com.jsnto.dashhome.ui.state.PushFeed
import com.jsnto.dashhome.ui.state.TabletSectionState
import com.jsnto.dashhome.ui.state.TtsSpeaker
import com.jsnto.dashhome.ui.state.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Hermes section: chat + the markdown push feed (the web's ChatPanel
 * and PushPanel as two tabs on the phone, side by side on wide displays).
 *
 * [shared] is the tablet's shared section state: the board's push window
 * and this view's push pane then agree on the selected history tab. The
 * chat thread is already shared at the root via [chat].
 */
@Composable
fun HermesScreen(
    chat: ChatState,
    chatScope: CoroutineScope,
    wide: Boolean = false,
    shared: TabletSectionState? = null,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = chat, 1 = push
    // Integration flags from /api/config (drive friendly setup notes).
    var hermesChat by remember { mutableStateOf(true) }
    var hermesPush by remember { mutableStateOf(true) }
    LaunchedEffect(DashApi.baseUrl) {
        hermesChat = try { DashApi.getConfig().hermesChat } catch (e: ApiException) { true }
        hermesPush = try { DashApi.getConfig().hermesPush } catch (e: ApiException) { true }
    }

    if (wide) {
        // Tablet: both panes at once, like the web's two side-by-side cards.
        Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 6.dp),
            ) {
                Text(
                    text = "Chat",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ChatPane(state = chat, scope = chatScope, configured = hermesChat)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 6.dp),
            ) {
                Text(
                    text = "Push feed",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                PushPane(configured = hermesPush, shared = shared)
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            TabChip(label = "Chat", selected = tab == 0, onClick = { tab = 0 })
            TabChip(label = "Push feed", selected = tab == 1, onClick = { tab = 1 })
        }
        when (tab) {
            0 -> ChatPane(state = chat, scope = chatScope, configured = hermesChat)
            else -> PushPane(configured = hermesPush, shared = shared)
        }
    }
}

/**
 * The chat pane: the conversation thread plus an input line. The whole
 * thread (oldest first) is sent as the `conversation` payload with each
 * message, same as the web.
 *
 * Voice input mirrors the web's useVoice: the platform's SpeechRecognizer
 * fills the input line (interim results live, the final transcript sent
 * through the same send path), and TextToSpeech reads a reply aloud only
 * when the message that produced it was voice-originated. The mic
 * degrades gracefully: no recognition service, or the microphone
 * permission denied, shows the reason instead of the button working
 * silently.
 */
@Composable
fun ChatPane(state: ChatState, scope: CoroutineScope, configured: Boolean) {
    var input by remember { mutableStateOf("") }
    // True when the message just submitted was voice-originated (the web's
    // wasVoiceRef): its reply is the only one read aloud.
    var wasVoice by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val speaker = remember { TtsSpeaker(context) }

    // True when the message just submitted was voice-originated (the web's
    // wasVoiceRef): its reply is the only one read aloud.
    fun submit() {
        val text = input.trim()
        if (text.isBlank()) return
        input = ""
        val v = wasVoice
        wasVoice = false
        state.send(text, scope, { history -> DashApi.chat(text, history) }, fromVoice = v, onVoiceReply = { reply -> speaker.speak(reply) })
    }

    val voice = remember {
        VoiceInput(context) { text ->
            input = text
            wasVoice = true
            submit()
        }
    }
    DisposableEffect(voice, speaker) {
        onDispose {
            voice.destroy()
            speaker.shutdown()
        }
    }

    // The RECORD_AUDIO runtime permission (Android-only; the web's mic
    // permission prompt is the platform analog).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voice.start()
        } else {
            voice.blocked = "Microphone access was denied - allow the mic in the app to use voice commands"
        }
    }

    fun onMicTap() {
        if (voice.listening) {
            voice.stop()
            return
        }
        if (voice.permissionGranted()) {
            voice.start()
        } else {
            permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // The mic's disabled state and reason (the web's micDisabled and
    // micTitle): unsupported, blocked (denied or no device), or a
    // reply in flight. The button is inert and dimmed while one holds,
    // and the reason shows instead of the button working silently.
    val micBlockedReason = voice.blocked
        ?: if (voice.supported) null else "Voice recognition is unavailable on this device"
    val micDisabled = micBlockedReason != null || state.busy

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        if (!configured) {
            Note("Hermes chat is not configured on the server (HERMES_BASE_URL).")
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (state.messages.isEmpty()) {
                item { Note("Say hi to Hermes — the conversation thread stays on this page.") }
            }
            items(state.messages) { m -> ChatRow(m) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        if (state.busy) {
            Note("thinking…")
        }
        micBlockedReason?.let {
            Note(it, modifier = Modifier.padding(top = 6.dp))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            OutlinedTextField(
                value = if (voice.listening) voice.interim else input,
                onValueChange = { if (!voice.listening) input = it },
                placeholder = {
                    Text(if (voice.listening) "Listening…" else "Message Hermes…")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        submit()
                        defaultKeyboardAction(ImeAction.Send)
                    }
                ),
                enabled = !state.busy && !voice.listening,
                modifier = Modifier.weight(1f),
            )
            // The mic (the web's mic button): tap to start/stop listening;
            // the final transcript is sent through the shared send path.
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (voice.listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                    .alpha(if (micDisabled) 0.5f else 1f)
                    .clickable(
                        enabled = !micDisabled,
                        onClick = { onMicTap() },
                        onClickLabel = if (voice.listening) "Stop listening" else "Voice input",
                    )
                    .testTag("chatMic"),
            ) {
                Icon(
                    imageVector = if (voice.listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (voice.listening) "Stop listening" else "Voice input",
                    modifier = Modifier.align(Alignment.Center),
                    tint = if (voice.listening) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { submit() },
                enabled = state.canSend(if (voice.listening) voice.interim else input),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Send")
            }
        }
    }
}

@Composable
private fun ChatRow(m: ChatState.Msg) {
    val errorColor = MaterialTheme.colorScheme.error
    val color = if (m.error) errorColor else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = if (m.role == ChatState.Role.USER) "You" else "Hermes",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = m.content,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}

/**
 * The push feed: the latest push is always available; previous pushes are
 * browsable in date tabs (newest first). Polls on the board's
 * refresh_seconds interval, same as the web's panel.
 *
 * [shared] is the tablet's shared section state: the board's push window
 * and the drill-down's push pane then select the same history tab.
 */
@Composable
fun PushPane(configured: Boolean, shared: TabletSectionState? = null) {
    var history by remember { mutableStateOf<List<Push>>(emptyList()) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    // The tablet's board and drill-down share the selected tab; a
    // standalone pane owns its own.
    val openTab = shared?.pushTab ?: openId

    fun selectTab(id: String?) {
        if (shared != null) shared.pushTab = id else openId = id
    }

    var open by remember { mutableStateOf<Push?>(null) }
    var err by remember { mutableStateOf<ApiException?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(DashApi.baseUrl) {
        // The board's polling interval hint, read once per (re)connect: the
        // loop delays by it, and making it a key would restart the effect
        // (re-fetching the feed) right after this read sets it.
        val refreshSec = try {
            DashApi.getConfig().board.refreshSeconds.coerceIn(5, 3600)
        } catch (e: ApiException) {
            30
        }
        while (true) {
            try {
                history = PushFeed.newestFirst(DashApi.getPushes(PushFeed.FETCH_LIMIT))
                err = null
            } catch (e: ApiException) {
                err = e
            }
            delay(refreshSec * 1000L)
        }
    }

    val latest = history.firstOrNull()
    val shown = PushFeed.shown(open, openTab, history)
    val isLatest = shown != null && openTab == null && shown.id == latest?.id

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        if (!configured) {
            Note("Push ingestion is not configured on the server (HERMES_PUSH_TOKEN).")
        }
        if (history.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                PushFeed.tabs(history).forEach { t ->
                    TabChip(
                        label = t.label,
                        selected = if (t.id == null) openTab == null else openTab == t.id,
                        onClick = {
                            open = null
                            selectTab(t.id)
                            if (t.id != null) {
                                scope.launch {
                                    open = try {
                                        DashApi.getPush(t.id)
                                    } catch (e: ApiException) {
                                        history.firstOrNull { it.id == t.id }
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
        if (err != null) {
            ErrorNote(err!!)
        }
        if (shown != null) {
            val s = shown
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp),
            ) {
                Text(
                    text = PushFeed.metaLine(s, latest = isLatest),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MarkdownText(markdown = s.markdown)
            }
        } else if (err == null) {
            Note("Waiting for the first Hermes markdown push…")
        }
    }
}

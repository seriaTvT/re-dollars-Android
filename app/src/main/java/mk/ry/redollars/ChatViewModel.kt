package mk.ry.redollars

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.WsUser
import javax.inject.Inject

data class SessionInfo(val uid: Long, val name: String, val formhash: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: MessageRepository,
) : ViewModel() {

    // ---- Observable state from the repository ----
    val messages: StateFlow<List<MessageDto>> =
        repo.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connected: StateFlow<Boolean> = repo.connected
    val onlineCount: StateFlow<Int> = repo.onlineCount
    val typingUsers: StateFlow<List<WsUser>> = repo.typingUsers

    // ---- UI-only state ----
    var session by mutableStateOf<SessionInfo?>(null); private set
    var showLogin by mutableStateOf(false)
    var sendStatus by mutableStateOf<String?>(null); private set
    var loadingOlder by mutableStateOf(false); private set
    var historyExhausted by mutableStateOf(false); private set
    val logs: SnapshotStateList<String> = mutableStateListOf()

    private var started = false
    private var pendingText = ""

    init {
        viewModelScope.launch { repo.logs.collect { log(it) } }
    }

    fun start() {
        if (started) return
        started = true
        repo.connect(uid = 0) // anonymous read connection until the user logs in
    }

    fun onLoggedIn(info: SessionInfo) {
        session = info
        showLogin = false
        log("Session ready: uid=${info.uid} name=${info.name}")
        // Re-identify + join (share presence) so our typing/presence is attributed.
        repo.connect(info.uid, info.name)
    }

    // ---- Composer typing signals: start immediately, stop after 2.5s idle or on send. ----
    private var typingStopJob: Job? = null
    private var typingActive = false

    fun onComposerChanged(text: String) {
        if (session == null) return
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (!typingActive) {
            typingActive = true
            repo.sendTyping(true)
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(2_500)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingStopJob?.cancel()
        typingStopJob = null
        if (typingActive) {
            typingActive = false
            repo.sendTyping(false)
        }
    }

    /** Toggle a reaction as the logged-in user (tap a chip or pick from long-press). */
    fun toggleReaction(messageId: Long, emoji: String) {
        val info = session ?: run {
            sendStatus = "Log in to react"
            return
        }
        viewModelScope.launch { repo.toggleReaction(messageId, info.uid, info.name, emoji) }
    }

    /** Page one more window of history above the oldest displayed message. */
    fun loadOlder() {
        if (loadingOlder || historyExhausted) return
        val oldest = messages.value.firstOrNull()?.id ?: return
        viewModelScope.launch {
            loadingOlder = true
            try {
                if (repo.loadOlder(oldest) < 0) historyExhausted = true
            } finally {
                loadingOlder = false
            }
        }
    }

    /** Driven by the UI lifecycle (foreground/background). */
    fun setForeground(active: Boolean) = repo.setForeground(active)

    fun externalLog(line: String) = log(line)

    fun beginSend(text: String) {
        stopTyping()
        pendingText = text
        sendStatus = "Posting via WebView…"
    }

    fun noteSend(message: String) {
        sendStatus = message
    }

    fun onWebPostResult(json: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull()
            val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false
            val status = obj?.get("status")?.jsonPrimitive?.intOrNull ?: -1
            val head = obj?.get("head")?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", " ")
            log("WebView POST -> ok=$ok status=$status head=${head.take(80)}")
            if (!ok) {
                sendStatus = "Post failed (status=$status)"
                return@launch
            }
            sendStatus = "Posted; confirming…"
            val confirmed = confirmLoop(session?.uid ?: 0, pendingText)
            sendStatus = if (confirmed != null) "Confirmed (id=${confirmed.id})" else "Posted; awaiting WS echo"
        }
    }

    /** Fallback confirm (WS new_messages usually wins first); repo upserts the result. */
    private suspend fun confirmLoop(uid: Long, content: String): MessageDto? {
        repeat(12) { attempt ->
            if (attempt > 0) delay((250L + attempt * 125).coerceAtMost(1000))
            val m = repo.confirm(uid, content)
            if (m != null) return m
        }
        return null
    }

    private fun log(line: String) {
        logs.add(line)
        while (logs.size > 100) logs.removeAt(0)
    }

    // No onCleared: the repository is an app-scoped singleton — its WebSocket is
    // quiesced by setForeground(false), not torn down with the ViewModel.
}

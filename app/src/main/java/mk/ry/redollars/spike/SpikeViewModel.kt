package mk.ry.redollars.spike

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import mk.ry.redollars.spike.data.MessageRepository
import mk.ry.redollars.spike.net.AppJson
import mk.ry.redollars.spike.net.MessageDto

data class SessionInfo(val uid: Long, val name: String, val formhash: String)

class SpikeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MessageRepository(app, viewModelScope)

    // ---- Observable state from the repository ----
    val messages: StateFlow<List<MessageDto>> =
        repo.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connected: StateFlow<Boolean> = repo.connected
    val onlineCount: StateFlow<Int> = repo.onlineCount

    // ---- UI-only state ----
    var session by mutableStateOf<SessionInfo?>(null); private set
    var showLogin by mutableStateOf(false)
    var sendStatus by mutableStateOf<String?>(null); private set
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
        repo.connect(info.uid) // re-identify as the logged-in user (+ gap sync)
    }

    fun externalLog(line: String) = log(line)

    fun beginSend(text: String) {
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

    override fun onCleared() {
        repo.close()
        super.onCleared()
    }
}

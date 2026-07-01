package mk.ry.redollars.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mk.ry.redollars.spike.net.AppJson
import mk.ry.redollars.spike.net.DollarsWs
import mk.ry.redollars.spike.net.MessageDto
import mk.ry.redollars.spike.net.RestApi
import mk.ry.redollars.spike.net.WsEvent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

data class SessionInfo(val uid: Long, val name: String, val formhash: String)

class SpikeViewModel : ViewModel() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS) // we send our own JSON heartbeat
        .build()

    private val rest = RestApi(http)
    private val ws = DollarsWs(http, viewModelScope, ::onWsEvent)

    // ---- UI state ----
    var connected by mutableStateOf(false); private set
    var onlineCount by mutableIntStateOf(0); private set
    var session by mutableStateOf<SessionInfo?>(null); private set
    var showLogin by mutableStateOf(false)
    var sendStatus by mutableStateOf<String?>(null); private set

    val messages: SnapshotStateList<MessageDto> = mutableStateListOf()
    val logs: SnapshotStateList<String> = mutableStateListOf()

    private val seenIds = HashSet<Long>()
    private var started = false
    private var pendingText = ""

    fun start() {
        if (started) return
        started = true
        loadRecent()
        ws.connect(uid = 0) // anonymous read connection until the user logs in
    }

    fun loadRecent() = viewModelScope.launch {
        log("REST GET /messages…")
        val recent = runCatching { rest.fetchRecent(40) }.getOrDefault(emptyList())
        val status = runCatching { rest.status(0) }.getOrNull()
        mergeMessages(recent)
        log("Loaded ${recent.size} messages; latest_id=${status?.latestId ?: "?"}, total=${status?.newCount ?: "?"}")
    }

    fun onLoggedIn(info: SessionInfo) {
        session = info
        showLogin = false
        log("Session ready: uid=${info.uid} name=${info.name}")
        ws.connect(uid = info.uid) // re-identify as the logged-in user
    }

    fun externalLog(line: String) = log(line)

    /** Called right before the WebView fires the in-page fetch. */
    fun beginSend(text: String) {
        pendingText = text
        sendStatus = "Posting via WebView…"
    }

    fun noteSend(message: String) {
        sendStatus = message
    }

    /** Result JSON delivered by the in-page fetch (via the AndroidPost bridge). */
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
            sendStatus = if (confirmed != null) {
                mergeMessages(listOf(confirmed))
                "Confirmed (id=${confirmed.id})"
            } else {
                "Posted; awaiting WS echo (not confirmed via REST)"
            }
        }
    }

    /** Fallback confirm (WS new_messages usually wins first). */
    private suspend fun confirmLoop(uid: Long, content: String): MessageDto? {
        repeat(12) { attempt ->
            if (attempt > 0) delay((250L + attempt * 125).coerceAtMost(1000))
            val res = runCatching { rest.confirm(uid, content) }.getOrNull()
            if (res?.found == true && res.message != null) return res.message
        }
        return null
    }

    private fun onWsEvent(event: WsEvent) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            when (event) {
                is WsEvent.Status -> connected = event.connected
                is WsEvent.OnlineCount -> onlineCount = event.count
                is WsEvent.NewMessages -> mergeMessages(event.messages)
                is WsEvent.Log -> log(event.line)
            }
        }
    }

    private fun mergeMessages(incoming: List<MessageDto>) {
        var added = false
        for (m in incoming) {
            if (seenIds.add(m.id)) {
                messages.add(m)
                added = true
            }
        }
        if (added) {
            messages.sortBy { it.id }
            while (messages.size > 200) messages.removeAt(0)
        }
    }

    private fun log(line: String) {
        logs.add(line)
        while (logs.size > 100) logs.removeAt(0)
    }

    override fun onCleared() {
        ws.close()
        super.onCleared()
    }
}

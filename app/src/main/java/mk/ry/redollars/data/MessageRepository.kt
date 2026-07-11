package mk.ry.redollars.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mk.ry.redollars.data.db.MessageDao
import mk.ry.redollars.data.db.toDto
import mk.ry.redollars.data.db.toEntity
import mk.ry.redollars.di.ApplicationScope
import mk.ry.redollars.net.DollarsWs
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.RestApi
import mk.ry.redollars.net.WsEvent
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for chat messages. Room is authoritative; REST and the
 * WebSocket both write into it, and the UI observes [messages]. Cold start shows the
 * cached rows immediately, then [connect] triggers a gap-recovery sync.
 *
 * App-scoped singleton: the WebSocket and DB writes survive configuration changes;
 * [setForeground] quiesces the socket when the UI is hidden.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val dao: MessageDao,
    http: OkHttpClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val rest = RestApi(http)
    private val ws = DollarsWs(http, scope, ::onWsEvent)

    val messages: Flow<List<MessageDto>> =
        dao.observeRecent(300).map { rows -> rows.map { it.toDto() } }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** (Re)identify the WebSocket as [uid]. Catch-up runs on the Status(true) transition. */
    fun connect(uid: Long) {
        ws.connect(uid)
    }

    /** Foreground/background from the UI lifecycle: pause the socket heartbeat and, on
     *  return, resume/reconnect and catch up on anything missed while away. */
    fun setForeground(active: Boolean) {
        ws.setActive(active)
        if (active) scope.launch { syncNewer() }
    }

    /** Fetch everything newer than the highest cached id (or a recent window when empty). */
    suspend fun syncNewer() {
        val since = dao.maxId() ?: 0L
        val fetched = runCatching {
            if (since <= 0L) rest.fetchRecent(60) else rest.fetchNewer(since, 200)
        }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) dao.upsertAll(fetched.map { it.toEntity() })
        log("Synced ${fetched.size} messages (since_db_id=$since)")
    }

    suspend fun confirm(uid: Long, content: String): MessageDto? {
        val res = runCatching { rest.confirm(uid, content) }.getOrNull()
        val msg = if (res?.found == true) res.message else null
        if (msg != null) dao.upsertAll(listOf(msg.toEntity()))
        return msg
    }

    fun close() = ws.close()

    private fun onWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Status -> {
                _connected.value = event.connected
                // Every (re)connect catches up via since_db_id; WS delivery is best-effort.
                if (event.connected) scope.launch { syncNewer() }
            }
            is WsEvent.OnlineCount -> _onlineCount.value = event.count
            is WsEvent.NewMessages -> scope.launch { dao.upsertAll(event.messages.map { it.toEntity() }) }
            is WsEvent.Log -> log(event.line)
        }
    }

    private fun log(line: String) {
        _logs.tryEmit(line)
    }
}

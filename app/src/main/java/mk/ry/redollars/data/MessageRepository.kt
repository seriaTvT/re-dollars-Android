package mk.ry.redollars.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mk.ry.redollars.data.db.MessageDao
import mk.ry.redollars.data.db.toDto
import mk.ry.redollars.data.db.toEntity
import mk.ry.redollars.di.ApplicationScope
import kotlinx.coroutines.Job
import mk.ry.redollars.net.DollarsWs
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.NotificationItem
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.net.RestApi
import mk.ry.redollars.net.UserProfileDto
import mk.ry.redollars.net.WsEvent
import mk.ry.redollars.net.WsUser
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

    /** How many of the newest cached rows the UI shows; grows as the user pages up. */
    private val displayLimit = MutableStateFlow(INITIAL_WINDOW)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<List<MessageDto>> = displayLimit
        .flatMapLatest { limit -> dao.observeRecent(limit) }
        .map { rows -> rows.map { it.toDto() } }

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _onlineCount = MutableStateFlow(0)
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** Users currently typing (never includes ourselves), newest activity last. */
    private val _typingUsers = MutableStateFlow<List<WsUser>>(emptyList())
    val typingUsers: StateFlow<List<WsUser>> = _typingUsers.asStateFlow()

    /** Unread mention/reply notifications, newest first (server keeps only unread). */
    private val _notifications = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = _notifications.asStateFlow()

    private val typingClearJobs = HashMap<Long, Job>()
    private var ownUid = 0L

    /** (Re)identify the WebSocket as [uid]. Catch-up runs on the Status(true) transition.
     *  Passing [name] enables presence sharing so our typing is attributed to us. */
    fun connect(uid: Long, name: String? = null, avatar: String? = null) {
        ownUid = uid
        ws.connect(uid, name, avatar)
    }

    /** Broadcast our composer typing state. */
    fun sendTyping(typing: Boolean) = ws.sendTyping(typing)

    /** Resolve a uid's cached profile (true nickname + avatar) from the backend. */
    suspend fun fetchUserProfile(uid: Long): UserProfileDto? =
        runCatching { rest.getUser(uid) }.getOrNull()

    suspend fun refreshNotifications() {
        if (ownUid <= 0) return
        val list = runCatching { rest.fetchNotifications(ownUid) }.getOrDefault(emptyList())
        _notifications.value = list
    }

    suspend fun markNotificationRead(id: Long) {
        if (ownUid <= 0) return
        _notifications.value = _notifications.value.filterNot { it.id == id }
        runCatching { rest.markNotificationRead(id, ownUid) }
    }

    suspend fun markAllNotificationsRead() {
        if (ownUid <= 0) return
        _notifications.value = emptyList()
        runCatching { rest.markAllNotificationsRead(ownUid) }
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

    /**
     * Make one more page of history (older than [beforeId], the oldest displayed id)
     * visible. Cache-first: only hits REST when the cache runs out. Returns the number
     * of additional rows now available, or -1 when history is exhausted.
     */
    suspend fun loadOlder(beforeId: Long): Int {
        val cached = dao.countOlderThan(beforeId)
        if (cached >= PAGE_SIZE) {
            displayLimit.value += PAGE_SIZE
            return PAGE_SIZE
        }
        val fetched = runCatching { rest.fetchHistory(beforeId, PAGE_SIZE) }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) dao.upsertAll(fetched.map { it.toEntity() })
        log("History: +${fetched.size} fetched before id=$beforeId (cached older=$cached)")
        val available = cached + fetched.size
        if (available == 0) return -1
        displayLimit.value += available
        return available
    }

    /** Toggle our reaction on a message. The WS echo is authoritative; the local patch
     *  just makes the chip respond instantly (and is deduped when the echo lands). */
    suspend fun toggleReaction(messageId: Long, uid: Long, nickname: String, emoji: String): Boolean {
        val res = runCatching { rest.toggleReaction(messageId, uid, nickname, emoji) }.getOrNull()
        if (res?.status != true) {
            log("Reaction toggle failed (msg=$messageId emoji=$emoji)")
            return false
        }
        patchReactions(messageId) { list ->
            val withoutOwn = list.filterNot { it.userId == uid } // server keeps one per user
            when (res.action) {
                "added", "replaced" ->
                    withoutOwn + (res.data ?: ReactionDto(emoji = emoji, userId = uid, nickname = nickname))
                else -> withoutOwn
            }
        }
        return true
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
                if (event.connected) scope.launch {
                    syncNewer()
                    refreshNotifications()
                }
            }
            is WsEvent.OnlineCount -> _onlineCount.value = event.count
            is WsEvent.NewMessages -> {
                scope.launch { dao.upsertAll(event.messages.map { it.toEntity() }) }
                // A delivered message implies its author stopped typing.
                for (m in event.messages) clearTyping(m.uid)
            }
            is WsEvent.Typing -> onTyping(event)
            is WsEvent.ReactionAdd -> scope.launch {
                patchReactions(event.messageId) { list ->
                    // The server broadcasts remove-then-add on replace, but dedupe anyway.
                    list.filterNot { it.userId == event.reaction.userId && it.emoji == event.reaction.emoji } +
                        event.reaction
                }
            }
            is WsEvent.ReactionRemove -> scope.launch {
                patchReactions(event.messageId) { list ->
                    list.filterNot { it.userId == event.userId && it.emoji == event.emoji }
                }
            }
            is WsEvent.Notification -> {
                _notifications.value =
                    listOf(event.item) + _notifications.value.filterNot { it.id == event.item.id }
            }
            is WsEvent.MessageDeleted -> scope.launch { dao.markDeleted(event.messageId) }
            is WsEvent.MessageEdited -> scope.launch {
                dao.upsertAll(listOf(event.message.toEntity()))
            }
            is WsEvent.Log -> log(event.line)
        }
    }

    private suspend fun patchReactions(
        messageId: Long,
        transform: (List<ReactionDto>) -> List<ReactionDto>,
    ) {
        val row = dao.getById(messageId) ?: return // not cached; next fetch carries them
        val dto = row.toDto()
        dao.upsertAll(listOf(dto.copy(reactions = transform(dto.reactions)).toEntity()))
    }

    private fun onTyping(event: WsEvent.Typing) {
        val uid = event.user.id
        if (uid == ownUid) return
        if (event.typing) {
            _typingUsers.value = _typingUsers.value.filterNot { it.id == uid } + event.user
            typingClearJobs.remove(uid)?.cancel()
            // Mirrors the userscript's TYPING_AUTO_CLEAR: drop stale indicators after 10s.
            typingClearJobs[uid] = scope.launch {
                kotlinx.coroutines.delay(10_000)
                clearTyping(uid)
            }
        } else {
            clearTyping(uid)
        }
    }

    private fun clearTyping(uid: Long) {
        typingClearJobs.remove(uid)?.cancel()
        _typingUsers.value = _typingUsers.value.filterNot { it.id == uid }
    }

    private fun log(line: String) {
        _logs.tryEmit(line)
    }

    private companion object {
        const val INITIAL_WINDOW = 300
        const val PAGE_SIZE = 60
    }
}

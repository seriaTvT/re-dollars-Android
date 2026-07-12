package mk.ry.redollars.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import mk.ry.redollars.data.db.MessageDao
import mk.ry.redollars.data.db.toDto
import mk.ry.redollars.data.db.toEntity
import mk.ry.redollars.di.ApplicationScope
import kotlinx.coroutines.Job
import mk.ry.redollars.net.DollarsWs
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.NotificationItem
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.GalleryResponse
import mk.ry.redollars.net.RestApi
import mk.ry.redollars.net.UploadApi
import mk.ry.redollars.net.UploadResult
import mk.ry.redollars.net.UserProfileDto
import mk.ry.redollars.net.UserSearchDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    private val prefs: SharedPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val rest = RestApi(http)
    private val ws = DollarsWs(http, scope, ::onWsEvent)
    private val uploads = UploadApi(http)

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

    /** Mention autocomplete: users whose nickname/username matches [query]. */
    suspend fun searchUsers(query: String): List<UserSearchDto> =
        runCatching { rest.searchUsers(query) }.getOrDefault(emptyList())

    /** Full-text message search (newest first). */
    suspend fun searchMessages(query: String, offset: Int): List<MessageDto> =
        runCatching { rest.searchMessages(query, offset) }.getOrDefault(emptyList())

    /** One page of the chat media wall. */
    suspend fun fetchGallery(offset: Int): GalleryResponse? =
        runCatching { rest.fetchGallery(offset) }.getOrNull()

    // ---- Sticker favorites (favorites.ts): image URLs, local cache + backend sync ----

    private val favListSerializer = ListSerializer(String.serializer())

    private val _favorites = MutableStateFlow(loadFavoritesCache())
    val favorites: StateFlow<List<String>> = _favorites.asStateFlow()

    private fun loadFavoritesCache(): List<String> =
        prefs.getString(PREF_FAVORITES, null)
            ?.let { runCatching { AppJson.decodeFromString(favListSerializer, it) }.getOrNull() }
            ?: emptyList()

    private fun persistFavorites(list: List<String>) {
        _favorites.value = list
        prefs.edit().putString(PREF_FAVORITES, AppJson.encodeToString(favListSerializer, list)).apply()
    }

    /** Union the backend's list with local additions (favorites.ts sync semantics). */
    suspend fun syncFavorites() {
        if (ownUid <= 0) return
        val server = runCatching { rest.fetchFavorites(ownUid) }.getOrNull() ?: return
        val merged = (server + _favorites.value).distinct()
        if (merged != _favorites.value) persistFavorites(merged)
    }

    /** Local-first; the backend write is fire-and-forget like the web's. */
    suspend fun addFavorite(url: String) {
        if (url.isBlank() || url in _favorites.value) return
        persistFavorites(listOf(url) + _favorites.value)
        if (ownUid > 0) runCatching { rest.addFavorite(ownUid, url) }
    }

    suspend fun removeFavorite(url: String) {
        persistFavorites(_favorites.value - url)
        if (ownUid > 0) runCatching { rest.removeFavorite(ownUid, url) }
    }

    /** Only real JWTs go to the upload server: legacy opaque dollars_auth tokens get
     *  rejected as an invalid bearer, while no header at all is accepted
     *  (getUploadAuthHeaders in the userscript). */
    private val jwtPattern = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")

    /** Upload an image to the upload server. */
    suspend fun uploadImage(bytes: ByteArray, fileName: String, mime: String): UploadResult =
        uploads.uploadImage(bytes, fileName, mime, authToken?.takeIf { jwtPattern.matches(it) })

    /** Upload any non-image file (voice, video, documents) — no auth needed. */
    suspend fun uploadFile(bytes: ByteArray, fileName: String, mime: String): UploadResult =
        uploads.uploadFile(bytes, fileName, mime)

    // ---- Presence dots (presenceHandlers.ts): track authors in the visible window ----

    private val _onlineUsers = MutableStateFlow<Set<Long>>(emptySet())
    val onlineUsers: StateFlow<Set<Long>> = _onlineUsers.asStateFlow()

    private var presenceSubscribed = setOf<Long>()

    /** Diff-subscribe to [want]; the server then answers a query and pushes updates. */
    private fun syncPresence(want: Set<Long>) {
        val added = want - presenceSubscribed
        val removed = presenceSubscribed - want
        ws.sendPresenceUnsubscribe(removed)
        ws.sendPresenceSubscribe(added)
        presenceSubscribed = want
        if (removed.isNotEmpty()) _onlineUsers.value = _onlineUsers.value - removed
    }

    /** A fresh socket has no server-side subscription state; replay ours. */
    private fun resubscribePresence() {
        val want = presenceSubscribed
        presenceSubscribed = emptySet()
        _onlineUsers.value = emptySet()
        syncPresence(want)
    }

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

    // ---- Backend auth token (long-lived Bearer; unlocks edit/delete) ----

    private var authToken: String? = prefs.getString(PREF_AUTH_TOKEN, null)

    fun setAuthToken(token: String?) {
        authToken = token
        prefs.edit().putString(PREF_AUTH_TOKEN, token).apply()
    }

    /** True when the stored token is valid on the backend AND belongs to [expectUid].
     *  An invalid/mismatched token is dropped so we re-run OAuth next login. */
    suspend fun validateAuthToken(expectUid: Long): Boolean {
        val token = authToken ?: return false
        val user = runCatching { rest.authMe(token) }.getOrNull()
        if (user != null && user.id == expectUid) return true
        // Distinguish "backend said no" from network failure: only drop on a real no.
        if (user != null) setAuthToken(null)
        return false
    }

    /** Edit own message; Room is patched immediately, the WS echo re-enriches it. */
    suspend fun editMessage(id: Long, content: String): Boolean {
        val token = authToken ?: return false
        val ok = runCatching { rest.editMessage(id, content, token) }.getOrDefault(false)
        if (ok) {
            dao.getById(id)?.let { row ->
                dao.upsertAll(listOf(row.copy(message = content)))
            }
        } else {
            log("Edit failed (msg=$id)")
        }
        return ok
    }

    /** Delete own message; marked locally at once, the WS broadcast confirms. */
    suspend fun deleteMessage(id: Long): Boolean {
        val token = authToken ?: return false
        val ok = runCatching { rest.deleteMessage(id, token) }.getOrDefault(false)
        if (ok) dao.markDeleted(id) else log("Delete failed (msg=$id)")
        return ok
    }

    /** Foreground/background from the UI lifecycle: pause the socket heartbeat and, on
     *  return, resume/reconnect and catch up on anything missed while away. */
    fun setForeground(active: Boolean) {
        ws.setActive(active)
        if (active) scope.launch { syncNewer() }
    }

    private val syncMutex = Mutex()

    /**
     * Catch up on everything newer than the highest cached id. The backend caps each
     * /messages page at 100 rows and serves since_db_id in *ascending* id order, so
     * after a long absence one call only yields the oldest slice of the backlog.
     * Strategy: fetch the live tail first, then either upsert it directly (it overlaps
     * the cache), backfill the gap page by page, or — when the backlog is deeper than
     * [MAX_CATCHUP_PAGES] pages — swap the cache for the tail. The cached timeline must
     * never keep a hole, because [loadOlder] pages the cache as if it were contiguous.
     */
    suspend fun syncNewer() {
        if (!syncMutex.tryLock()) return // an in-flight sync already covers us
        try {
            val since = dao.maxId() ?: 0L
            val tail = runCatching { rest.fetchRecent(CATCHUP_PAGE) }.getOrDefault(emptyList())
            if (tail.isEmpty()) {
                log("Sync: tail fetch failed (since_db_id=$since)")
                return
            }
            val tailMin = tail.minOf { it.id }
            val tailRows = tail.map { it.toEntity() }

            // Empty cache, or the tail reaches back to what we already have: no gap.
            if (since <= 0L || tailMin <= since + 1) {
                dao.upsertAll(tailRows)
                log("Synced ${tail.count { it.id > since }} messages (since_db_id=$since)")
                return
            }

            // Deeper than we're willing to backfill: reset to the live tail. Older
            // history re-fetches from the server on demand.
            if (tailMin - since > MAX_CATCHUP_PAGES.toLong() * CATCHUP_PAGE) {
                dao.replaceAll(tailRows)
                displayLimit.value = INITIAL_WINDOW
                log("Too far behind (gap≈${tailMin - since}); reset cache to latest ${tail.size}")
                return
            }

            // Shallow gap: bridge it forward, then attach the tail.
            var cursor = since
            var pages = 0
            while (cursor < tailMin - 1 && pages < MAX_CATCHUP_PAGES) {
                val page = runCatching { rest.fetchNewer(cursor, CATCHUP_PAGE) }
                    .getOrDefault(emptyList())
                if (page.isEmpty()) break // network error mid-bridge
                dao.upsertAll(page.map { it.toEntity() })
                cursor = page.maxOf { it.id }
                pages++
            }
            if (cursor >= tailMin - 1) {
                dao.upsertAll(tailRows)
                log("Synced gap of ${cursor - since} + tail (since_db_id=$since)")
            } else {
                // Couldn't close the gap; swapping to the tail beats keeping a hole.
                dao.replaceAll(tailRows)
                displayLimit.value = INITIAL_WINDOW
                log("Bridge failed at id=$cursor; reset cache to latest ${tail.size}")
            }
        } finally {
            syncMutex.unlock()
        }
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
                if (event.connected) {
                    resubscribePresence()
                    scope.launch {
                        syncNewer()
                        refreshNotifications()
                        syncFavorites()
                    }
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
            is WsEvent.Presence -> {
                val current = _onlineUsers.value.toMutableSet()
                for ((id, active) in event.users) if (active) current.add(id) else current.remove(id)
                _onlineUsers.value = current
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

    init {
        // Presence subscriptions follow the authors of the newest cached messages
        // (collectUidsForPresence: last 150; PRESENCE_SYNC_DELAY debounce).
        @OptIn(FlowPreview::class)
        scope.launch {
            messages
                .map { list -> list.takeLast(150).map { it.uid }.toSet() }
                .distinctUntilChanged()
                .debounce(120)
                .collect { want -> syncPresence(want) }
        }
    }

    private companion object {
        const val INITIAL_WINDOW = 300
        const val PAGE_SIZE = 60
        /** Server-side maximum page size for /messages (larger limits are clamped). */
        const val CATCHUP_PAGE = 100
        /** Backfill at most this many pages before jumping to the live tail instead. */
        const val MAX_CATCHUP_PAGES = 5
        const val PREF_AUTH_TOKEN = "dollars_auth_token"
        const val PREF_FAVORITES = "sticker_favorites"
    }
}

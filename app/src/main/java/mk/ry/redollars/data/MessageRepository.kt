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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mk.ry.redollars.data.db.MessageDao
import mk.ry.redollars.data.db.toDto
import mk.ry.redollars.data.db.toEntity
import mk.ry.redollars.di.ApplicationScope
import kotlinx.coroutines.Job
import kotlin.concurrent.Volatile
import mk.ry.redollars.net.DollarsWs
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.NotificationItem
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.AuthMeResult
import mk.ry.redollars.net.Config
import mk.ry.redollars.net.TokenLoginResult
import mk.ry.redollars.net.GalleryResponse
import mk.ry.redollars.net.RestApi
import mk.ry.redollars.net.UploadApi
import mk.ry.redollars.net.UploadResult
import mk.ry.redollars.net.UserProfileDto
import mk.ry.redollars.net.UserSearchDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mk.ry.redollars.net.WsEvent
import mk.ry.redollars.net.WsUser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Raw bytes + MIME type of an image fetched for local saving. */
data class FetchedImage(val bytes: ByteArray, val mimeType: String?)

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
    private val http: OkHttpClient,
    private val prefs: SharedPreferences,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val rest = RestApi(http)
    private val ws = DollarsWs(http, scope, ::onWsEvent)
    private val uploads = UploadApi(http)

    /** How many of the newest cached rows the UI shows; grows as the user pages up. */
    private val displayLimit = MutableStateFlow(INITIAL_WINDOW)

    // ---- Blocked users: two separately-managed lists, persisted so blocked users
    // stay hidden on cold start. Room keeps the rows, display filters them.
    //  * RD (app-local) list: toggled from the profile sheet, unblockable for good.
    //  * Bangumi list: data_ignore_users harvested from the logged-in WebView page
    //    (as user.ts reads it on the web). Owned by the site — the app can only lift
    //    a block *locally* via an override; the site's ignore list is never touched.

    private val blockListSerializer = ListSerializer(Long.serializer())
    private val nameCacheSerializer = MapSerializer(String.serializer(), Long.serializer())

    private val _localBlocked = MutableStateFlow(loadUidSet(PREF_BLOCKED))
    private val _siteBlocked = MutableStateFlow(loadUidSet(PREF_SITE_BLOCKED))
    private val _siteUnblocked = MutableStateFlow(loadUidSet(PREF_SITE_UNBLOCKED))

    /** App-local (RD) blocklist. */
    val localBlockedUsers: StateFlow<Set<Long>> = _localBlocked.asStateFlow()
    /** Bangumi's ignore list as last harvested. */
    val siteBlockedUsers: StateFlow<Set<Long>> = _siteBlocked.asStateFlow()
    /** Site-ignored uids whose block the user lifted app-side only. */
    val siteUnblockedUsers: StateFlow<Set<Long>> = _siteUnblocked.asStateFlow()

    /** What the UI filters by: local ∪ (site − app-side overrides). */
    private val _blockedUsers = MutableStateFlow(effectiveBlocked())
    val blockedUsers: StateFlow<Set<Long>> = _blockedUsers.asStateFlow()

    fun clearAccountState() {
        _favorites.value = emptyList()
        _localBlocked.value = emptySet()
        _siteBlocked.value = emptySet()
        _siteUnblocked.value = emptySet()
        _blockedUsers.value = emptySet()
        _notifications.value = emptyList()
        _typingUsers.value = emptyList()
        displayLimit.value = INITIAL_WINDOW
        ownUid = 0
    }

    private fun effectiveBlocked(): Set<Long> =
        _localBlocked.value + (_siteBlocked.value - _siteUnblocked.value)

    private fun loadUidSet(key: String): Set<Long> =
        prefs.getString(key, null)
            ?.let { runCatching { AppJson.decodeFromString(blockListSerializer, it) }.getOrNull() }
            ?.toSet() ?: emptySet()

    private fun persistUidSet(key: String, set: Set<Long>) {
        prefs.edit()
            .putString(key, AppJson.encodeToString(blockListSerializer, set.toList()))
            .apply()
    }

    /** Toggle the app-local (RD) block for [uid]. */
    fun setBlocked(uid: Long, blocked: Boolean) {
        _localBlocked.value = if (blocked) _localBlocked.value + uid else _localBlocked.value - uid
        persistUidSet(PREF_BLOCKED, _localBlocked.value)
        onBlockedChanged()
    }

    /** Lift (or restore) a Bangumi-side block, app-only: the site's ignore list is
     *  untouched and later harvests keep respecting the override. */
    fun setSiteUnblocked(uid: Long, unblocked: Boolean) {
        _siteUnblocked.value =
            if (unblocked) _siteUnblocked.value + uid else _siteUnblocked.value - uid
        persistUidSet(PREF_SITE_UNBLOCKED, _siteUnblocked.value)
        onBlockedChanged()
    }

    /** Recompute the effective set and evict newly-blocked users from typing/notifications. */
    private fun onBlockedChanged() {
        val all = effectiveBlocked()
        if (all == _blockedUsers.value) return
        _blockedUsers.value = all
        _typingUsers.value.map { it.id }.filter { it in all }.forEach(::clearTyping)
        _notifications.value = _notifications.value.filterNot { it.uid in all }
    }

    /**
     * Apply Bangumi's `data_ignore_users` (mixed uids and usernames). Usernames
     * resolve to uids through the backend, behind a persistent username→uid cache
     * so repeat launches skip the network (initializeBlockedUsers in user.ts).
     */
    private var ignoreListSeq = 0L
    private var lastIgnoreListSeq = 0L
    fun setSiteIgnoreList(raw: List<String>) {
        val seq = ++ignoreListSeq
        scope.launch {
            val uids = mutableSetOf<Long>()
            val cache = loadNameCache().toMutableMap()
            val unresolved = mutableListOf<String>()
            for (entry in raw.map { it.trim() }.filter { it.isNotEmpty() }) {
                val numeric = entry.toLongOrNull()
                when {
                    numeric != null -> uids.add(numeric)
                    entry in cache -> uids.add(cache.getValue(entry))
                    else -> unresolved.add(entry)
                }
            }
            if (unresolved.isNotEmpty()) {
                val resolved = runCatching { rest.lookupUsersByName(unresolved) }.getOrNull()
                if (resolved == null) {
                    log("Ignore list resolve failed — keeping previous list")
                    return@launch
                }
                if (resolved.isNotEmpty()) {
                    uids.addAll(resolved.values)
                    cache.putAll(resolved)
                    prefs.edit()
                        .putString(PREF_NAME_CACHE, AppJson.encodeToString(nameCacheSerializer, cache))
                        .apply()
                }
                if (resolved.size < unresolved.size) {
                    log("Ignore list partial resolve ${resolved.size}/${unresolved.size} — keeping previous for unresolved")
                    val merged = _siteBlocked.value.toMutableSet()
                    merged.addAll(uids)
                    if (merged != _siteBlocked.value) {
                        if (seq < lastIgnoreListSeq) return@launch
                        lastIgnoreListSeq = seq
                        _siteBlocked.value = merged
                        persistUidSet(PREF_SITE_BLOCKED, merged)
                        onBlockedChanged()
                        log("Ignore list: ${merged.size} user(s) blocked via Bangumi (partial)")
                    }
                    return@launch
                }
            }
            if (seq < lastIgnoreListSeq) return@launch
            lastIgnoreListSeq = seq
            if (uids != _siteBlocked.value) {
                _siteBlocked.value = uids
                persistUidSet(PREF_SITE_BLOCKED, uids)
                val overrides = _siteUnblocked.value intersect uids
                if (overrides != _siteUnblocked.value) {
                    _siteUnblocked.value = overrides
                    persistUidSet(PREF_SITE_UNBLOCKED, overrides)
                }
                onBlockedChanged()
                log("Ignore list: ${uids.size} user(s) blocked via Bangumi")
            }
        }
    }

    private fun loadNameCache(): Map<String, Long> =
        prefs.getString(PREF_NAME_CACHE, null)
            ?.let { runCatching { AppJson.decodeFromString(nameCacheSerializer, it) }.getOrNull() }
            ?: emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<List<MessageDto>> = kotlinx.coroutines.flow.combine(
        displayLimit.flatMapLatest { limit -> dao.observeRecent(limit) },
        _blockedUsers,
    ) { rows, blocked ->
        rows.mapNotNull { row ->
            val dto = row.toDto()
            val quoted = dto.replyDetails
            when {
                dto.uid in blocked -> null // hidden entirely
                quoted != null && quoted.uid in blocked ->
                    dto.copy(replyDetails = quoted.copy(content = "[已屏蔽]", firstImage = null))
                else -> dto
            }
        }
        // The per-row JSON decode in toDto() runs in the collector's context (the
        // ViewModel's Main); keep the whole mapping off the UI thread.
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)

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

    // Touched from the WS reader thread and the app-scope coroutines alike.
    private val typingClearJobs = ConcurrentHashMap<Long, Job>()
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

    /** Fetch raw image bytes + MIME type for the lightbox download button. */
    suspend fun fetchImage(url: String): FetchedImage? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        runCatching {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", Config.USER_AGENT)
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                val body = res.body
                if (!res.isSuccessful || body == null) null
                else {
                    val len = res.header("Content-Length")?.toLongOrNull()
                    if (len != null && len > 10 * 1024 * 1024) null
                    else {
                        val bytes = body.bytes()
                        if (bytes.size > 10 * 1024 * 1024) null
                        else FetchedImage(bytes, res.header("Content-Type")?.substringBefore(';')?.trim())
                    }
                }
            }
        }.getOrNull()
    }

    /** Mention autocomplete: users whose nickname/username matches [query]. */
    suspend fun searchUsers(query: String): List<UserSearchDto> =
        runCatching { rest.searchUsers(query) }.getOrDefault(emptyList())

    /** Full-text message search (newest first). Null on network failure (retryable). */
    suspend fun searchMessages(query: String, offset: Int): List<MessageDto>? =
        runCatching { rest.searchMessages(query, offset) }.getOrNull()

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

    /** Upload an image to the upload server. [filePart] streams and carries the mime. */
    suspend fun uploadImage(filePart: okhttp3.RequestBody, fileName: String): UploadResult =
        uploads.uploadImage(
            filePart,
            fileName,
            uploadAuthToken?.takeIf { jwtPattern.matches(it) }
                ?: authToken?.takeIf { jwtPattern.matches(it) },
        )

    /** Upload any non-image file (voice, video, documents) — no auth needed. */
    suspend fun uploadFile(filePart: okhttp3.RequestBody, fileName: String): UploadResult =
        uploads.uploadFile(filePart, fileName)

    // ---- Presence dots (presenceHandlers.ts): track authors in the visible window ----

    private val _onlineUsers = MutableStateFlow<Set<Long>>(emptySet())
    val onlineUsers: StateFlow<Set<Long>> = _onlineUsers.asStateFlow()

    @Volatile
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
        _notifications.value = list.filterNot { it.uid in _blockedUsers.value }
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

    @Volatile
    private var authToken: String? = prefs.getString(PREF_AUTH_TOKEN, null)
    @Volatile
    private var uploadAuthToken: String? = prefs.getString(PREF_UPLOAD_AUTH_TOKEN, null)
    @Volatile
    private var authRevision = 0L
    private val authMutex = Mutex()

    @Synchronized
    fun setAuthToken(token: String?) {
        val wasNull = authToken == null
        authRevision++
        authToken = token
        prefs.edit().putString(PREF_AUTH_TOKEN, token).apply()
        // If token became valid after being null, ensure WS is re-identified
        if (wasNull && token != null && ownUid != 0L) {
            ws.connect(ownUid, null, null)
        }
    }

    /** Persist the rymk-auth JWT separately for upload. The RD backend token
     * returned by /auth/token-login is opaque and must not be sent to up.ry.mk. */
    @Synchronized
    fun setUploadAuthToken(token: String?) {
        uploadAuthToken = token
        prefs.edit().putString(PREF_UPLOAD_AUTH_TOKEN, token).apply()
    }

    /** Clear both backend and upload credentials. */
    @Synchronized
    fun clearAuthTokens() {
        setAuthToken(null)
        setUploadAuthToken(null)
    }

    /** Whether the currently persisted token still looks like the rymk-auth JWT
     * that can be upgraded through /auth/token-login. */
    @Synchronized
    fun hasStoredRymkToken(): Boolean = authToken?.isJwtLike() == true

    @Synchronized
    private fun authSnapshot(): Pair<String?, Long> = authToken to authRevision

    @Synchronized
    private fun isCurrentAuthRevision(revision: Long): Boolean = authRevision == revision

    private fun String.isJwtLike(): Boolean {
        val parts = split('.')
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    /** Outcome of validating the stored backend token against /auth/me. */
    enum class AuthValidation { Valid, Invalid, NetworkError }

    /** Run token-login with bounded exponential backoff. An explicit rejection is
     * definitive; transport/server failures remain retryable. */
    private suspend fun tokenLoginWithRetry(token: String, maxRetries: Int): TokenLoginResult {
        var attempt = 0
        var delayMs = 1000L
        while (true) {
            when (val result = rest.tokenLogin(token)) {
                TokenLoginResult.Invalid -> return result
                is TokenLoginResult.Valid -> return result
                TokenLoginResult.Error -> {
                    if (attempt >= maxRetries) return result
                    attempt++
                    val jitter = kotlin.random.Random.nextLong(0, 1000) +
                        kotlin.random.Random.nextLong(0, (delayMs / 2).coerceAtLeast(1L))
                    kotlinx.coroutines.delay(delayMs + jitter)
                    delayMs = (delayMs * 2 + kotlin.random.Random.nextLong(0, 1000)).coerceAtMost(16_000L)
                }
            }
        }
    }

    /** Exchange a freshly captured rymk-auth JWT for the backend's long-lived
     * local token. On a transport failure the JWT is persisted as a fallback so
     * the caller can still validate it directly and retry the upgrade later. */
    suspend fun exchangeRymkToken(token: String, maxRetries: Int = 3): TokenLoginResult =
        authMutex.withLock {
            // Persist the captured JWT before the network exchange so a process
            // death during token-login still has a recoverable credential.
            setAuthToken(token)
            if (token.isJwtLike()) setUploadAuthToken(token)
            val (_, revision) = authSnapshot()
            when (val result = tokenLoginWithRetry(token, maxRetries)) {
                is TokenLoginResult.Valid -> {
                    if (isCurrentAuthRevision(revision)) setAuthToken(result.token)
                    result
                }
                TokenLoginResult.Invalid -> {
                    if (isCurrentAuthRevision(revision)) clearAuthTokens()
                    result
                }
                TokenLoginResult.Error -> {
                    if (isCurrentAuthRevision(revision)) setAuthToken(token)
                    result
                }
            }
        }

    /** Silently upgrade a persisted rymk-auth JWT on app restart. A local token
     * is already durable and needs no exchange. */
    suspend fun upgradeStoredRymkToken(maxRetries: Int = 3): TokenLoginResult? =
        authMutex.withLock {
            val (token, revision) = authSnapshot()
            val jwt = token?.takeIf { it.isJwtLike() } ?: return@withLock null
            if (isCurrentAuthRevision(revision)) setUploadAuthToken(jwt)
            when (val result = tokenLoginWithRetry(jwt, maxRetries)) {
                is TokenLoginResult.Valid -> {
                    if (isCurrentAuthRevision(revision)) setAuthToken(result.token)
                    result
                }
                TokenLoginResult.Invalid -> {
                    if (isCurrentAuthRevision(revision)) clearAuthTokens()
                    result
                }
                TokenLoginResult.Error -> result
            }
        }

    private suspend fun validateAuthTokenUnlocked(expectUid: Long): AuthValidation {
        val (token, revision) = authSnapshot()
        token ?: return AuthValidation.Invalid
        return when (val r = rest.authMe(token)) {
            is AuthMeResult.Valid -> {
                if (expectUid != 0L && r.user.id != 0L && r.user.id != expectUid) {
                    log("Auth uid mismatch: token uid=${r.user.id} vs expect $expectUid, treating as Valid and will reconcile")
                }
                AuthValidation.Valid
            }
            AuthMeResult.Invalid -> {
                if (isCurrentAuthRevision(revision)) clearAuthTokens()
                AuthValidation.Invalid
            }
            AuthMeResult.Error -> AuthValidation.NetworkError
        }
    }

    /** Validate the stored token: Valid when backend confirms it. A mismatch is
     * logged but remains valid for compatibility with the existing uid namespace
     * handling; only an explicit backend rejection clears the token. */
    suspend fun validateAuthToken(expectUid: Long): AuthValidation = authMutex.withLock {
        validateAuthTokenUnlocked(expectUid)
    }

    /** Validate with exponential backoff retry for transient network errors. Keeps token on final NetworkError. */
    suspend fun validateAuthTokenWithRetry(expectUid: Long, maxRetries: Int = 3): AuthValidation =
        authMutex.withLock {
            var attempt = 0
            var delayMs = 1000L
            var finalResult: AuthValidation
            while (true) {
                val result = validateAuthTokenUnlocked(expectUid)
                if (result != AuthValidation.NetworkError || attempt >= maxRetries) {
                    finalResult = result
                    break
                }
                attempt++
                // Full jitter + decorrelated
                val jitter = kotlin.random.Random.nextLong(0, 1000) +
                    kotlin.random.Random.nextLong(0, (delayMs / 2).coerceAtLeast(1L))
                kotlinx.coroutines.delay(delayMs + jitter)
                delayMs = (delayMs * 2 + kotlin.random.Random.nextLong(0, 1000)).coerceAtMost(16_000L)
            }
            finalResult
        }

    // ---- FCM push ----

    /** Jump target set by MainActivity when a push notification is tapped. */
    val pushJumpRequests = MutableStateFlow<Long?>(null)

    /** Bind our FCM registration token to this account on the backend. No-op until
     *  the OAuth token exists; called again on every auth-ready and token rotation. */
    suspend fun registerPushToken(fcmToken: String) {
        val token = authToken ?: return
        // Retry with backoff for transient failures, decoupled from main auth flow
        var attempt = 0
        var delayMs = 1000L
        while (attempt < 3) {
            val ok = runCatching { rest.registerPush(fcmToken, token) }.getOrDefault(false)
            if (ok) {
                log("Push token registered")
                return
            }
            attempt++
            if (attempt < 3) {
                kotlinx.coroutines.delay(delayMs + kotlin.random.Random.nextLong(0, 500))
                delayMs *= 2
            }
        }
        log("Push token registration failed after retries")
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
    private val reactionMutex = Mutex()

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
    suspend fun jumpToMessage(targetId: Long): Boolean {
        return try {
            val before = rest.fetchHistory(targetId + PAGE_SIZE, PAGE_SIZE).orEmpty()
            val after = runCatching { rest.fetchNewer(targetId - 1, PAGE_SIZE) }.getOrDefault(emptyList())
            val window = (before + after).sortedBy { it.id }
            if (window.isEmpty()) return false
            dao.replaceAll(window.map { it.toEntity() })
            displayLimit.value = INITIAL_WINDOW.coerceAtLeast(window.size + 20)
            true
        } catch (_: Exception) { false }
    }

    suspend fun loadOlder(beforeId: Long): Int {
        val cached = dao.countOlderThan(beforeId)
        if (cached >= PAGE_SIZE) {
            displayLimit.value += PAGE_SIZE
            return PAGE_SIZE
        }
        // Null = fetch failed (network etc.): report 0 so the UI can retry. -1 is
        // reserved for a real end of history and must not fire on a transient error.
        val fetched = rest.fetchHistory(beforeId, PAGE_SIZE)
        if (fetched == null) {
            log("History: fetch failed before id=$beforeId (cached older=$cached)")
            return 0
        }
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
                scope.launch { reactionMutex.withLock {
                    val filtered = mutableListOf<mk.ry.redollars.data.db.MessageEntity>()
                    for (m in event.messages) {
                        val existing = dao.getById(m.id)
                        if (existing?.isDeleted != true) filtered.add(m.toEntity())
                    }
                    if (filtered.isNotEmpty()) dao.upsertAll(filtered)
                } }
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
                if (event.item.uid in _blockedUsers.value) return
                _notifications.value =
                    listOf(event.item) + _notifications.value.filterNot { it.id == event.item.id }
            }
            is WsEvent.Presence -> {
                val current = _onlineUsers.value.toMutableSet()
                for ((id, active) in event.users) if (active) current.add(id) else current.remove(id)
                _onlineUsers.value = current
            }
            is WsEvent.MessageDeleted -> scope.launch { reactionMutex.withLock { dao.markDeleted(event.messageId) } }
            is WsEvent.MessageEdited -> scope.launch {
                reactionMutex.withLock {
                    val existing = dao.getById(event.message.id)
                    if (existing?.isDeleted == true) return@withLock
                    dao.upsertAll(listOf(event.message.toEntity()))
                }
            }
            is WsEvent.Log -> log(event.line)
        }
    }

    private suspend fun patchReactions(
        messageId: Long,
        transform: (List<ReactionDto>) -> List<ReactionDto>,
    ) = reactionMutex.withLock {
        val row = dao.getById(messageId) ?: return@withLock
        val dto = row.toDto()
        dao.upsertAll(listOf(dto.copy(reactions = transform(dto.reactions)).toEntity()))
    }

    private fun onTyping(event: WsEvent.Typing) {
        val uid = event.user.id
        if (uid == ownUid || uid in _blockedUsers.value) return
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
        const val PREF_UPLOAD_AUTH_TOKEN = "dollars_upload_auth_token"
        const val PREF_FAVORITES = "sticker_favorites"
        const val PREF_BLOCKED = "blocked_users"
        /** Resolved uids from Bangumi's data_ignore_users (refreshed on each harvest). */
        const val PREF_SITE_BLOCKED = "site_blocked_users"
        /** App-side overrides: site-ignored uids whose block the user lifted locally. */
        const val PREF_SITE_UNBLOCKED = "site_unblocked_users"
        /** username→uid cache for ignore-list entries (dollars_blocked_cache parity). */
        const val PREF_NAME_CACHE = "blocked_name_cache"
    }
}

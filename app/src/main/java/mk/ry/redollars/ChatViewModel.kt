package mk.ry.redollars

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mk.ry.redollars.data.FetchedImage
import mk.ry.redollars.data.DisplayPrefs
import mk.ry.redollars.data.DisplaySettings
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.NotificationItem
import mk.ry.redollars.net.UploadResult
import mk.ry.redollars.net.UserProfileDto
import mk.ry.redollars.net.UserSearchDto
import mk.ry.redollars.net.WsUser
import mk.ry.redollars.net.TokenLoginResult
import mk.ry.redollars.voice.VoiceRecorder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import java.util.ArrayDeque
import java.io.File
import java.io.IOException
import javax.inject.Inject

data class SessionInfo(val uid: Long, val name: String, val formhash: String)

/** A recorded voice clip: playable locally at once, sendable once [url] is set. */
data class VoiceDraft(
    val file: File,
    val durationSec: Int,
    val url: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: MessageRepository,
    private val displaySettings: DisplaySettings,
    @param:ApplicationContext private val appContext: Context,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val sessionHintPrefs =
        appContext.getSharedPreferences("session_hint", Context.MODE_PRIVATE)
    /** True if login completed on a previous launch; drives silent auto-login on open. */
    val hadPriorSession: Boolean = sessionHintPrefs.getBoolean("had_session", false)

    // ---- Observable state from the repository ----
    val messages: StateFlow<List<MessageDto>> =
        repo.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connected: StateFlow<Boolean> = repo.connected
    val onlineCount: StateFlow<Int> = repo.onlineCount
    val typingUsers: StateFlow<List<WsUser>> = repo.typingUsers
    val notifications: StateFlow<List<NotificationItem>> = repo.notifications
    /** Saved sticker image URLs (favorites tab in the smiley picker). */
    val favorites: StateFlow<List<String>> = repo.favorites
    /** Effective blocklist (RD local ∪ (Bangumi − app-side overrides)); blocked
     *  users vanish from list/typing/notifications. */
    val blockedUsers: StateFlow<Set<Long>> = repo.blockedUsers
    /** App-local (RD) blocklist — the profile-sheet toggle. */
    val localBlockedUsers: StateFlow<Set<Long>> = repo.localBlockedUsers
    /** Bangumi's ignore list as harvested from the site. */
    val siteBlockedUsers: StateFlow<Set<Long>> = repo.siteBlockedUsers
    /** Site-ignored uids whose block was lifted app-side only. */
    val siteUnblockedUsers: StateFlow<Set<Long>> = repo.siteUnblockedUsers
    /** uids currently online among recently-visible authors (presence dots). */
    val onlineUsers: StateFlow<Set<Long>> = repo.onlineUsers
    /** Chat display options (界面设置 sheet); persisted across launches. */
    val displayPrefs: StateFlow<DisplayPrefs> = displaySettings.prefs
    fun updateDisplayPrefs(transform: (DisplayPrefs) -> DisplayPrefs) =
        displaySettings.update(transform)

    // ---- UI-only state ----
    var session by mutableStateOf<SessionInfo?>(null); private set
    var showLogin by mutableStateOf(false)
    /** Account sheet (identity + backend status + logout) for a logged-in user. */
    var showAccount by mutableStateOf(false)
    private val _sendStatus = mutableStateOf<String?>(null)
    var sendStatus: String?
        get() = _sendStatus.value
        // Plain assignments are user-facing; setting one clears the debug flag.
        private set(value) {
            _sendStatus.value = value
            sendStatusIsDebug = false
        }
    /** True when the current [sendStatus] is diagnostic noise (post/edit pipeline,
     *  internal ids). The UI hides these unless the debug panel is enabled. */
    var sendStatusIsDebug by mutableStateOf(false); private set

    /** Set a debug-only status (see [sendStatusIsDebug]). */
    private fun setDebugStatus(message: String?) {
        sendStatus = message // resets the flag…
        sendStatusIsDebug = true // …then marks this one as debug
    }
    var loadingOlder by mutableStateOf(false); private set
    var historyExhausted by mutableStateOf(false); private set
    /** Message id the list should scroll to (set when opening a notification). */
    var pendingJumpId by mutableStateOf<Long?>(null)
    /** Message being replied to; the send path prefixes `[quote=id][/quote]`. */
    var replyTo by mutableStateOf<MessageDto?>(null); private set
    /** Composer contents + cursor, hoisted so edit prefill, smiley insertion and
     *  mention completion can all manipulate it. */
    private val composerDraftKey = "composer_draft_text"
    private val draftPrefs = appContext.getSharedPreferences("draft", android.content.Context.MODE_PRIVATE)
    var composerValue by mutableStateOf(
        savedState.get<String>(composerDraftKey)?.let { TextFieldValue(it, TextRange(it.length)) }
            ?: draftPrefs.getString(composerDraftKey, null)?.let { TextFieldValue(it, TextRange(it.length)) }
            ?: TextFieldValue(""),
    )
        private set

    /** Set the composer value and persist the text so an OS process kill doesn't eat
     *  an in-progress message. */
    private fun updateComposer(value: TextFieldValue) {
        composerValue = value
        savedState[composerDraftKey] = value.text
        draftPrefs.edit().putString(composerDraftKey, value.text).apply()
    }

    /** Clear draft after successful send */
    private fun clearDraft() {
        updateComposer(TextFieldValue(""))
        draftPrefs.edit().remove(composerDraftKey).apply()
    }
    /** Suggestions for the trailing `@query` at the cursor (mention autocomplete). */
    var mentionCandidates by mutableStateOf<List<UserSearchDto>>(emptyList())
        private set
    /** Message being edited (id + any hidden leading quote to restore on save). */
    var editing by mutableStateOf<EditingState?>(null); private set
    /** True once the backend Bearer token is validated for this session's uid. */
    var authReady by mutableStateOf(false); private set

    /** Logged into Bangumi but the backend JWT is not currently valid. */
    val backendAuthExpired: Boolean get() = session != null && !authReady
    /** Non-null asks the host to load the rymk-auth start URL in the login WebView. */
    var oauthRequestUrl by mutableStateOf<String?>(null); private set
    /** Non-null asks the host to reload the shared WebView to this URL — used to return
     *  it to the Bangumi origin after rymk-auth navigated it away, so same-origin
     *  posting works again. */
    var webViewReloadUrl by mutableStateOf<String?>(null); private set
    /** Per-request nonce for the in-flight rymk-auth popup; the returned token's `state`
     *  must match it or we reject the message. */
    private var authNonce: String? = null
    /** uid whose profile sheet is open (tap an avatar). */
    var profileUid by mutableStateOf<Long?>(null)
    val logs: SnapshotStateList<String> = mutableStateListOf()

    data class EditingState(val id: Long, val hiddenQuote: String?)

    private var started = false
    private var pendingSendSeq = 0L
    private data class PendingSend(val id: Long, val text: String)
    private val pendingSends: ArrayDeque<PendingSend> = ArrayDeque()
    private var pendingText: String
        get() = pendingSends.lastOrNull()?.text ?: ""
        set(value) {
            if (value.isEmpty()) pendingSends.clear()
            else if (pendingSends.isEmpty()) pendingSends.addLast(PendingSend(++pendingSendSeq, value))
            else {
                val last = pendingSends.removeLast()
                pendingSends.addLast(last.copy(text = value))
            }
        }

    init {
        viewModelScope.launch { repo.logs.collect { log(it) } }
        viewModelScope.launch {
            repo.pushJumpRequests.collect { id ->
                if (id != null) {
                    pendingJumpId = id
                    repo.pushJumpRequests.value = null
                }
            }
        }
    }

    /** Bind our FCM token to this account once the backend Bearer token is ready.
     *  Silently a no-op when Firebase isn't configured (no google-services.json). */
    private fun registerPush() {
        if (com.google.firebase.FirebaseApp.getApps(appContext).isEmpty()) return
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                viewModelScope.launch {
                    if (ensureBackendAuth()) repo.registerPushToken(token)
                }
            }
    }

    fun start() {
        if (started) return
        started = true
        repo.connect(uid = 0) // anonymous read connection until the user logs in
    }

    /** Force a fresh WebSocket connection (tap the top-bar status when offline). */
    fun reconnect() {
        if (repo.connected.value) return
        val info = session
        if (info != null) repo.connect(info.uid, info.name, null)
        else repo.connect(0)
        noteSend("正在重新连接…")
    }

    fun onLoggedIn(info: SessionInfo) {
        val sameUser = session?.uid == info.uid
        // A WebView recreation can replay the session probe while the same user's
        // login/OAuth work is still active. Do not restart that work or mint a new
        // nonce, but do allow a genuinely new session after stale work is canceled.
        if (sameUser && (authNonce != null || loginJob?.isActive == true || authValidationJob?.isActive == true)) {
            return
        }

        if (!sameUser) {
            authGeneration++
            authValidationJob?.cancel(); authValidationJob = null
            authUpgradeJob?.cancel(); authUpgradeJob = null
            reauthTimeoutJob?.cancel(); reauthTimeoutJob = null
            authNonce = null
            oauthRequestUrl = null
            // A different Bangumi account must never inherit the previous
            // account's backend token. Keep the persisted token on cold start,
            // where there is no previous session to switch away from.
            if (session != null) repo.clearAuthTokens()
        } else if (authNonce != null) {
            // The previous request is stale (for example, its timeout or dismiss
            // path cleared the URL but left the marker). Allow a fresh request.
            authNonce = null
            reauthTimeoutJob?.cancel(); reauthTimeoutJob = null
        }

        session = info
        authReady = false
        // Remember we have a session so future launches auto-login silently on open.
        sessionHintPrefs.edit().putBoolean("had_session", true).apply()
        loginJob?.cancel()
        val generation = authGeneration
        loginJob = viewModelScope.launch {
            // The page-extracted name is the login slug (or a uid fallback), NOT the
            // display nickname — resolve the real one from the backend profile cache
            // before joining, or other clients would show the wrong name for our
            // typing frames and reactions.
            val profile = repo.fetchUserProfile(info.uid)
            if (generation != authGeneration || session?.uid != info.uid) return@launch
            val nickname = profile?.nickname?.takeIf { it.isNotBlank() } ?: info.name
            val avatar = profile?.avatar?.let { it.medium ?: it.large ?: it.small }
            session = info.copy(name = nickname)
            log("Session ready: uid=${info.uid} nickname=$nickname (profile=${profile != null})")
            // Re-identify + join (share presence) so our typing/presence is attributed.
            repo.connect(info.uid, nickname, avatar)

            // Reuse a persisted RD token. A persisted rymk JWT is also accepted as
            // a temporary fallback, then upgraded silently to a long-lived RD token.
            when (repo.validateAuthTokenWithRetry(info.uid)) {
                MessageRepository.AuthValidation.Valid -> {
                    if (generation != authGeneration || session?.uid != info.uid) return@launch
                    backendAuthReady("stored token")
                    if (repo.hasStoredRymkToken()) startStoredTokenUpgrade(generation)
                }
                MessageRepository.AuthValidation.Invalid -> {
                    if (generation == authGeneration && session?.uid == info.uid) requestReauth()
                }
                MessageRepository.AuthValidation.NetworkError -> {
                    if (generation != authGeneration || session?.uid != info.uid) return@launch
                    authReady = false
                    showLogin = false
                    log("Backend auth validation failed (network); keeping stored token")
                    sendStatus = "网络波动，授权稍后自动重试"
                    startPeriodicAuthCheck()
                    if (repo.hasStoredRymkToken()) startStoredTokenUpgrade(generation)
                }
            }
        }
    }

    /** Trigger the rymk-auth OAuth flow to obtain a fresh backend JWT. Used both on
     * first login and to auto-recover when a stored token has expired mid-session. */
    private var reauthTimeoutJob: Job? = null

    private fun requestReauth() {
        if (authNonce != null) return
        beginReauth()
    }

    /** Start a new OAuth attempt. The URL changes on every attempt so Compose's
     * LaunchedEffect always loads a fresh flow instead of racing WebView.reload(). */
    private fun beginReauth() {
        authGeneration++
        authValidationJob?.cancel(); authValidationJob = null
        authUpgradeJob?.cancel(); authUpgradeJob = null
        reauthTimeoutJob?.cancel()
        authNonce = java.util.UUID.randomUUID().toString()
        showLogin = true
        oauthRequestUrl = mk.ry.redollars.net.Config.rymkAuthStartUrl(authNonce!!)
        authReady = false
        sendStatus = null
        log("Requesting rymk-auth authorization…")
        reauthTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (authNonce != null) {
                log("rymk-auth timeout, showing retry")
                sendStatus = "授权超时，请点击刷新重试"
            }
        }
    }

    fun retryAuth() {
        beginReauth()
        log("Retrying rymk-auth…")
    }

    /** Mark the current backend token usable and restore the WebView to Bangumi. */
    private fun backendAuthReady(source: String) {
        authReady = true
        showLogin = false
        webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
        log("Backend auth ready ($source)")
        registerPush()
        startPeriodicAuthCheck()
    }

    /** Retry upgrading a persisted rymk JWT without reopening the login page. */
    private fun startStoredTokenUpgrade(generation: Long) {
        authUpgradeJob?.cancel()
        authUpgradeJob = viewModelScope.launch {
            while (repo.hasStoredRymkToken() && generation == authGeneration) {
                val result = repo.upgradeStoredRymkToken()
                if (generation != authGeneration) return@launch
                when (result) {
                    is TokenLoginResult.Valid -> {
                        log("Stored rymk-auth JWT upgraded to a persistent RD token")
                        break
                    }
                    TokenLoginResult.Invalid -> {
                        if (generation == authGeneration && session != null) {
                            authReady = false
                            requestReauth()
                            sendStatus = "授权已过期，请重新授权"
                        }
                        break
                    }
                    TokenLoginResult.Error -> {
                        log("Stored rymk-auth JWT upgrade deferred (network/server error)")
                        kotlinx.coroutines.delay(5 * 60 * 1000L)
                    }
                    null -> break
                }
            }
        }
    }

    /** Re-validate the stored backend token before a token-gated action (edit/delete).
     *  On expiry it drops the token and opens the login WebView to auto-recover auth. */
    private suspend fun ensureBackendAuth(): Boolean {
        val uid = session?.uid ?: return false
        when (repo.validateAuthTokenWithRetry(uid)) {
            MessageRepository.AuthValidation.Valid -> {
                authReady = true
                if (repo.hasStoredRymkToken()) startStoredTokenUpgrade(authGeneration)
                return true
            }
            MessageRepository.AuthValidation.Invalid -> {
                authReady = false
                requestReauth()
                sendStatus = "授权已过期，请在弹出的登录页完成授权后重试"
            }
            MessageRepository.AuthValidation.NetworkError -> {
                authReady = false
                sendStatus = "无法连接服务器，请稍后重试"
            }
        }
        return false
    }

    /** JWT captured from the rymk-auth popup (the `rymk_auth` postMessage in auth.ts),
     * delivered by the WebView bridge. [state] must equal the nonce we minted for this
     * request, or the message is ignored (stale/foreign popup guard). */
    fun onAuthToken(token: String, state: String?) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (token.isBlank()) {
                log("rymk-auth: ignoring empty token")
                return@launch
            }
            val expectedState = authNonce
            if (expectedState == null || state != expectedState) {
                val got = state?.take(8)?.let { "$it…" } ?: "null"
                val expected = expectedState?.take(8)?.let { "$it…" } ?: "null"
                log("rymk-auth: ignoring token with mismatched state (expected $expected, got $got)")
                return@launch
            }

            reauthTimeoutJob?.cancel(); reauthTimeoutJob = null
            authNonce = null // consume the nonce before any suspension; duplicate callbacks are ignored
            oauthRequestUrl = null
            val uidForValidation = session?.uid
            val generation = authGeneration
            authValidationJob?.cancel()
            authValidationJob = viewModelScope.launch(Dispatchers.Main.immediate) {
                log("Exchanging rymk-auth token with backend")
                val exchange = repo.exchangeRymkToken(token)
                if (generation != authGeneration || session?.uid != uidForValidation) return@launch
                when (exchange) {
                    is TokenLoginResult.Valid -> {
                        log("Backend returned a persistent RD token")
                        when (repo.validateAuthTokenWithRetry(uidForValidation ?: 0L)) {
                            MessageRepository.AuthValidation.Valid -> backendAuthReady("token-login")
                            MessageRepository.AuthValidation.Invalid -> {
                                authReady = false
                                log("Backend auth validation FAILED after token-login")
                                sendStatus = "授权失败，请重试"
                                requestReauth()
                            }
                            MessageRepository.AuthValidation.NetworkError -> {
                                authReady = false
                                showLogin = false
                                webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
                                log("Persistent RD token validation deferred (network error)")
                                sendStatus = "网络波动，授权稍后自动重试"
                                startPeriodicAuthCheck()
                            }
                        }
                    }
                    TokenLoginResult.Invalid -> {
                        authReady = false
                        log("Backend rejected the rymk-auth token")
                        sendStatus = "授权失败，请重试"
                        requestReauth()
                    }
                    TokenLoginResult.Error -> {
                        // exchangeRymkToken persists the JWT in this branch. Try it
                        // directly so a temporary token-login outage does not force
                        // the user to authorize again.
                        log("Backend token-login unavailable; validating JWT fallback")
                        when (repo.validateAuthTokenWithRetry(uidForValidation ?: 0L)) {
                            MessageRepository.AuthValidation.Valid -> {
                                backendAuthReady("rymk JWT fallback")
                                startStoredTokenUpgrade(generation)
                            }
                            MessageRepository.AuthValidation.Invalid -> {
                                authReady = false
                                sendStatus = "授权失败，请重试"
                                requestReauth()
                            }
                            MessageRepository.AuthValidation.NetworkError -> {
                                authReady = false
                                showLogin = false
                                webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
                                log("Backend auth validation deferred (network error); JWT kept")
                                sendStatus = "网络波动，授权稍后自动重试"
                                startPeriodicAuthCheck()
                                startStoredTokenUpgrade(generation)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Bangumi's data_ignore_users as harvested from a logged-in WebView page. */
    fun onIgnoreUsers(list: List<String>) = repo.setSiteIgnoreList(list)

    /** Host consumed [webViewReloadUrl] (reloaded the WebView); clear the request. */
    fun onWebViewReloaded() {
        webViewReloadUrl = null
    }

    /** Sign out: drop the backend token, forget the session hint (so the next launch
     *  won't silently auto-login), clear Bangumi cookies so the WebView session is gone,
     *  reset the shared WebView to a logged-out page, and fall back to the anonymous
     *  read connection. Posting/edit/delete lock again until the user logs back in. */
    private fun startPeriodicAuthCheck() {
        periodicAuthJob?.cancel()
        periodicAuthJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30 * 60 * 1000L) // 30 minutes
                val uid = session?.uid ?: continue
                when (repo.validateAuthTokenWithRetry(uid, maxRetries = 1)) {
                    MessageRepository.AuthValidation.Invalid -> {
                        log("Periodic auth check: token invalid, requesting reauth")
                        requestReauth()
                        break
                    }
                    MessageRepository.AuthValidation.NetworkError -> {
                        log("Periodic auth check: network error, will retry next cycle")
                    }
                    else -> { /* Valid, continue */ }
                }
            }
        }
    }

    fun logout() {
        loginJob?.cancel(); loginJob = null
        authValidationJob?.cancel(); authValidationJob = null
        authUpgradeJob?.cancel(); authUpgradeJob = null
        periodicAuthJob?.cancel(); periodicAuthJob = null
        authGeneration++
        repo.clearAccountState()
        repo.clearAuthTokens()
        sessionHintPrefs.edit().putBoolean("had_session", false).apply()
        session = null
        authReady = false
        authNonce = null
        oauthRequestUrl = null
        showAccount = false
        showLogin = false
        pendingSends.clear()
        replyTo = null
        editing = null
        setComposer("")
        discardVoiceDraft()
        stopTyping()
        val cm = android.webkit.CookieManager.getInstance()
        cm.removeAllCookies { _ ->
            cm.flush()
            webViewReloadUrl = "${mk.ry.redollars.net.Config.BGM_HOST}/login"
        }
        webViewReloadUrl = "${mk.ry.redollars.net.Config.BGM_HOST}/login"
        repo.connect(uid = 0)
        log("Logged out")
    }

    /** User closed the login overlay; abandon any in-flight rymk-auth request. If a
     *  Bangumi session already exists, return the WebView to the Bangumi origin so
     *  posting still works (rymk-auth may have navigated it to bgm.tv/auth.ry.mk). */
    fun dismissLogin() {
        authGeneration++
        reauthTimeoutJob?.cancel(); reauthTimeoutJob = null
        authValidationJob?.cancel(); authValidationJob = null
        showLogin = false
        oauthRequestUrl = null
        authNonce = null
        if (session != null) webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
    }

    // ---- Composer typing signals: start immediately, stop after 2.5s idle or on send. ----
    private var loginJob: Job? = null
    private var authValidationJob: Job? = null
    private var authUpgradeJob: Job? = null
    private var periodicAuthJob: Job? = null
    private var authGeneration = 0L
    private var typingStopJob: Job? = null
    private var typingActive = false

    fun onComposerChanged(value: TextFieldValue) {
        val textChanged = value.text != composerValue.text
        // Route text changes through updateComposer so a half-typed message survives
        // an OS process kill; cursor-only moves skip the persistence write.
        if (textChanged) updateComposer(value) else composerValue = value
        updateMentionSuggestions(value)
        if (session == null || !textChanged) return // cursor moves aren't typing
        if (value.text.isBlank()) {
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

    /** Programmatic composer reset (send/edit flows); cursor to end, suggestions gone. */
    private fun setComposer(text: String) {
        updateComposer(TextFieldValue(text, TextRange(text.length)))
        mentionJob?.cancel()
        mentionCandidates = emptyList()
        mentionStart = -1
    }

    /** Replace the current selection with [snippet], cursor after it. */
    fun insertAtCursor(snippet: String) {
        val v = composerValue
        val text = v.text.replaceRange(v.selection.min, v.selection.max, snippet)
        updateComposer(TextFieldValue(text, TextRange(v.selection.min + snippet.length)))
    }

    fun insertSmiley(code: String) = insertAtCursor(code)

    /** uid → resolved profile cache for long-press mentions (avoids repeat lookups). */
    private val uidMentionCache = HashMap<Long, UserProfileDto>()

    /** Long-press an author's avatar to mention them. Messages carry a uid + nickname
     *  but no `@username` handle, so we resolve the username via the backend profile
     *  endpoint (cached), insert the readable `@username ` like the @-completer, and
     *  pre-fill [mentionCache] so [transformMentions] turns it into a `[user=id]` tag
     *  at send time. Falls back to the raw BBCode if resolution fails. */
    fun mentionUser(uid: Long, nickname: String) {
        viewModelScope.launch {
            val cached = uidMentionCache[uid]
            val username = cached?.username?.takeIf { it.isNotBlank() }
                ?: repo.fetchUserProfile(uid)?.also { uidMentionCache[uid] = it }?.username
            if (!username.isNullOrBlank()) {
                mentionCache[username] = UserSearchDto(
                    id = uid,
                    username = username,
                    nickname = nickname.ifBlank { cached?.nickname ?: nickname },
                )
                // transformMentions only rewrites @tokens at a line start or after
                // whitespace; add a leading space so a mention typed mid-word still
                // becomes a [user=id] tag when sent.
                val cursor = composerValue.selection.min
                val atBoundary = cursor == 0 ||
                    composerValue.text.getOrNull(cursor - 1)?.isWhitespace() == true
                insertAtCursor(if (atBoundary) "@$username " else " @$username ")
            } else {
                insertAtCursor("[user=$uid]${nickname.ifBlank { uid.toString() }}[/user] ")
            }
        }
    }

    /** A saved sticker was picked from the panel (SmileyPanel.tsx insert format). */
    fun insertSticker(url: String) = insertAtCursor("[sticker]$url[/sticker]")

    /** Download the lightbox image to the system gallery (Pictures/ReDollars). */
    fun downloadImage(url: String) {
        viewModelScope.launch {
            val img = repo.fetchImage(url)
            if (img == null || img.bytes.isEmpty()) {
                toast("下载失败：无法获取图片")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) { saveImageToGallery(img) }
            toast(if (saved) "已保存到相册" else "保存失败")
        }
    }

    private fun saveImageToGallery(img: FetchedImage): Boolean = runCatching {
        val mime = img.mimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        val ext = mime.removePrefix("image/").takeIf { it.length in 2..5 } ?: "jpg"
        val name = "redollars_${System.currentTimeMillis()}.$ext"
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ReDollars")
            values.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = resolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(img.bytes) } ?: return@runCatching false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { it.write(img.bytes) } ?: return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun toast(message: String) {
        android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- Image upload + sticker favorites ----

    var uploading by mutableStateOf(false)
        private set

    /** Upload picked images and insert an [img] tag per success. */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty() || uploading) return
        viewModelScope.launch {
            uploading = true
            try {
                uris.forEachIndexed { i, uri ->
                    sendStatus = "Uploading image ${i + 1}/${uris.size}…"
                    val res = readAndUpload(uri)
                    if (res.url != null) {
                        insertAtCursor("[img]${res.url}[/img]")
                    } else {
                        sendStatus = "Upload failed: ${res.error}"
                        return@launch
                    }
                }
                sendStatus = if (uris.size > 1) "${uris.size} images attached" else "Image attached"
            } finally {
                uploading = false
            }
        }
    }

    /** Upload a picked image straight into the sticker favorites (panel upload tile). */
    fun uploadFavorite(uri: Uri) {
        if (uploading) return
        viewModelScope.launch {
            uploading = true
            try {
                sendStatus = "Uploading sticker…"
                val res = readAndUpload(uri)
                if (res.url != null) {
                    repo.addFavorite(res.url)
                    sendStatus = "Sticker saved"
                } else {
                    sendStatus = "Upload failed: ${res.error}"
                }
            } finally {
                uploading = false
            }
        }
    }

    /** Upload any picked document/video/audio; images route through the image
     *  endpoint (auth), everything else through the no-auth file endpoint, and the
     *  inserted tag follows the mime: [img] / [video] / [audio] / [file=name]. */
    fun attachFile(uri: Uri) {
        if (uploading) return
        viewModelScope.launch {
            uploading = true
            try {
                sendStatus = "Uploading file…"
                val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "file"
                val mime = appContext.contentResolver.getType(uri) ?: "application/octet-stream"
                val res: UploadResult
                val tag: String
                if (mime.startsWith("image/")) {
                    res = readAndUpload(uri)
                    tag = res.url?.let { "[img]$it[/img]" }.orEmpty()
                } else {
                    val size = withContext(Dispatchers.IO) { contentLength(uri) }
                    res = if (size != -1L && size > MAX_FILE_BYTES) UploadResult(error = "Too large (max 200MB)")
                    else repo.uploadFile(uriRequestBody(uri, mime, size), name)
                    tag = res.url?.let {
                        when {
                            mime.startsWith("video/") -> "[video]$it[/video]"
                            mime.startsWith("audio/") -> "[audio]$it[/audio]"
                            else -> "[file=$name]$it[/file]"
                        }
                    }.orEmpty()
                }
                if (res.url != null) {
                    insertAtCursor(tag)
                    sendStatus = "File attached"
                } else {
                    sendStatus = "Upload failed: ${res.error}"
                }
            } finally {
                uploading = false
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Save an image URL (e.g. from the lightbox) as a sticker favorite. */
    fun addFavorite(url: String) {
        viewModelScope.launch {
            repo.addFavorite(url)
            sendStatus = "Saved to stickers"
        }
    }

    fun removeFavorite(url: String) {
        viewModelScope.launch { repo.removeFavorite(url) }
    }

    // ---- Voice messages (useVoiceRecorder.ts): record -> upload -> [audio] on send ----

    private val voiceRecorder = VoiceRecorder(appContext)
    private var voiceTicker: Job? = null

    var recordingVoice by mutableStateOf(false)
        private set
    var recordSeconds by mutableStateOf(0)
        private set
    var voiceDraft by mutableStateOf<VoiceDraft?>(null)
        private set

    fun startVoiceRecording() {
        if (recordingVoice) return
        discardVoiceDraft()
        if (!voiceRecorder.start()) {
            sendStatus = "Could not start recording"
            return
        }
        recordingVoice = true
        recordSeconds = 0
        voiceTicker = viewModelScope.launch {
            while (recordingVoice) {
                delay(1_000)
                recordSeconds++
                if (recordSeconds >= MAX_VOICE_SECONDS) {
                    stopVoiceRecording()
                }
            }
        }
    }

    /** Stop recording and upload right away, so the send path stays synchronous:
     *  the draft becomes sendable once its URL arrives. */
    fun stopVoiceRecording() {
        if (!recordingVoice) return
        recordingVoice = false
        voiceTicker?.cancel()
        val file = voiceRecorder.stop()
        if (file == null) {
            sendStatus = "Recording failed"
            return
        }
        val draft = VoiceDraft(file, recordSeconds.coerceAtLeast(1))
        voiceDraft = draft
        viewModelScope.launch {
            sendStatus = "Uploading voice…"
            val res = repo.uploadFile(file.asRequestBody("audio/mp4".toMediaType()), file.name)
            if (voiceDraft?.file == file) { // still the current draft
                voiceDraft = draft.copy(url = res.url, error = res.error)
                sendStatus = if (res.url != null) "Voice ready" else "Voice upload failed: ${res.error}"
            }
        }
    }

    fun cancelVoiceRecording() {
        if (recordingVoice) {
            recordingVoice = false
            voiceTicker?.cancel()
            voiceRecorder.cancel()
        }
        discardVoiceDraft()
    }

    private fun discardVoiceDraft() {
        voiceDraft?.file?.delete()
        voiceDraft = null
    }

    private suspend fun readAndUpload(uri: Uri): UploadResult = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val size = contentLength(uri)
        if (size != -1L && size > MAX_IMAGE_BYTES) return@withContext UploadResult(error = "Too large (max 50MB)")
        if (size == -1L) {
            val probe = runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().size.toLong() } }.getOrNull() ?: -1L
            if (probe != -1L && probe > MAX_IMAGE_BYTES) return@withContext UploadResult(error = "Too large (max 50MB)")
        }
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "jpg"
        }
        repo.uploadImage(uriRequestBody(uri, mime, size), "image-${System.currentTimeMillis()}.$ext")
    }

    /** Declared size of a content Uri, or -1 when the provider doesn't say. */
    private fun contentLength(uri: Uri): Long = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
    }.getOrNull() ?: -1L

    /** Streams the Uri into the request on write, so a 200MB pick never sits on the
     *  heap. Opened per attempt, which also keeps OkHttp retries safe. */
    private fun uriRequestBody(uri: Uri, mime: String, size: Long): RequestBody =
        object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not read file")
                var written = 0L
                val hardCap = MAX_FILE_BYTES.coerceAtLeast(MAX_IMAGE_BYTES).toLong()
                input.source().use { src ->
                    val buf = okio.Buffer()
                    var read: Long
                    while (src.read(buf, 8192).also { read = it } != -1L) {
                        written += read
                        if (written > hardCap) throw IOException("File too large")
                        sink.write(buf, read)
                    }
                }
            }
        }

    // ---- Mention autocomplete (MentionCompleter.tsx parity) ----

    private var mentionJob: Job? = null
    private var mentionStart = -1 // index of the '@' the suggestions would replace
    /** username -> user for every candidate ever shown; lets the send path resolve
     *  plain `@username` tokens to `[user=id]` BBCode without a network round-trip. */
    private val mentionCache = HashMap<String, UserSearchDto>()
    private val mentionQuery = Regex("""(^|\s)@(\S{1,30})$""")

    private fun updateMentionSuggestions(value: TextFieldValue) {
        mentionJob?.cancel()
        val cursor = if (value.selection.collapsed) value.selection.end else -1
        val match = if (cursor >= 0) mentionQuery.find(value.text.take(cursor)) else null
        if (match == null) {
            mentionStart = -1
            mentionCandidates = emptyList()
            return
        }
        val query = match.groupValues[2]
        mentionStart = cursor - query.length - 1
        mentionJob = viewModelScope.launch {
            delay(250) // debounce, MENTION_DEBOUNCE parity
            val users = repo.searchUsers(query)
            for (u in users) if (u.username.isNotBlank()) mentionCache[u.username] = u
            mentionCandidates = users
        }
    }

    /** A suggestion was tapped: swap the `@query` for `@username ` (web parity — the
     *  readable BBCode form is produced at send time by [transformMentions]). */
    fun pickMention(user: UserSearchDto) {
        val start = mentionStart
        val cursor = composerValue.selection.end
        if (start < 0 || start >= cursor || user.username.isBlank()) return
        val replacement = "@${user.username} "
        val text = composerValue.text.replaceRange(start, cursor, replacement)
        mentionCache[user.username] = user
        updateComposer(TextFieldValue(text, TextRange(start + replacement.length)))
        mentionJob?.cancel()
        mentionCandidates = emptyList()
        mentionStart = -1
    }

    private val mentionToken = Regex("""(^|\s|\[/[^\]]+\])@([\p{L}\p{N}_']{1,30})""")
    private val codeBlockToken = Regex("""\[code\][\s\S]*?\[/code\]""", RegexOption.IGNORE_CASE)

    /** `@username` -> `[user=id]nickname[/user]` for users seen in the completer;
     *  [code] blocks are left untouched (mentions.ts parity, minus its network lookup). */
    private fun transformMentions(text: String): String {
        if ('@' !in text || mentionCache.isEmpty()) return text
        val blocks = mutableListOf<String>()
        val masked = codeBlockToken.replace(text) { m ->
            blocks.add(m.value)
            "\u0000CB${blocks.size - 1}\u0000"
        }
        var out = mentionToken.replace(masked) { m ->
            val user = mentionCache[m.groupValues[2]]
            if (user != null) "${m.groupValues[1]}[user=${user.id}]${user.nickname}[/user]"
            else m.value
        }
        blocks.forEachIndexed { i, b -> out = out.replace("\u0000CB$i\u0000", b) }
        return out
    }

    /** Open a notification: mark it read and ask the list to jump to its message. */
    fun openNotification(item: NotificationItem) {
        viewModelScope.launch { repo.markNotificationRead(item.id) }
        pendingJumpId = item.messageId
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch { repo.markAllNotificationsRead() }
    }

    /** Toggle a reaction as the logged-in user (tap a chip or pick from long-press). */
    fun toggleReaction(messageId: Long, emoji: String) {
        val info = session ?: run {
            sendStatus = "Log in to react"
            return
        }
        viewModelScope.launch { repo.toggleReaction(messageId, info.uid, info.name, emoji) }
    }

    /** Resolve a profile for the profile sheet (backend user cache). */
    suspend fun loadProfile(uid: Long) = repo.fetchUserProfile(uid)

    /** RD (app-local) block/unblock from the profile sheet. */
    fun toggleBlock(uid: Long) {
        val nowBlocked = uid !in repo.localBlockedUsers.value
        repo.setBlocked(uid, nowBlocked)
        sendStatus = if (nowBlocked) "已 RD 屏蔽该用户" else "已取消 RD 屏蔽"
    }

    /** Remove a user from the RD (app-local) blocklist (block manager). */
    fun unblockLocal(uid: Long) = repo.setBlocked(uid, false)

    /** Lift/restore a Bangumi-side block app-only; the site's ignore list stays as-is. */
    fun setSiteUnblocked(uid: Long, unblocked: Boolean) = repo.setSiteUnblocked(uid, unblocked)

    /** Full-text search page (search sheet). */
    suspend fun searchMessages(query: String, offset: Int) = repo.searchMessages(query, offset)

    /** Media wall page (gallery sheet). */
    suspend fun fetchGallery(offset: Int) = repo.fetchGallery(offset)

    fun syncNewer() { viewModelScope.launch { repo.syncNewer() } }
    fun jumpToMessage(id: Long) { viewModelScope.launch { if (!repo.jumpToMessage(id)) sendStatus = "无法定位该消息" } }

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

    fun startReply(m: MessageDto) {
        editing = null
        replyTo = m
    }

    fun cancelReply() {
        replyTo = null
    }

    // ---- Edit / delete own messages (needs the backend token) ----

    private val leadingQuote = Regex("""^\[quote=\d+\]\[/quote\]""")

    fun startEdit(m: MessageDto) {
        replyTo = null
        cancelVoiceRecording() // edits never carry a new voice attachment
        val hidden = leadingQuote.find(m.message)?.value
        editing = EditingState(m.id, hidden)
        setComposer(if (hidden != null) m.message.removePrefix(hidden) else m.message)
    }

    fun cancelEdit() {
        editing = null
        setComposer("")
    }

    fun submitEdit(text: String) {
        val edit = editing ?: return
        viewModelScope.launch {
            if (!ensureBackendAuth()) return@launch
            sendStatus = "Saving edit…"
            val body = (edit.hiddenQuote ?: "") + transformMentions(text)
            val ok = repo.editMessage(edit.id, body)
            if (ok) setDebugStatus("Edited (id=${edit.id})") else sendStatus = "Edit failed"
            if (ok) {
                editing = null
                setComposer("")
            }
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            if (!ensureBackendAuth()) return@launch
            if (repo.deleteMessage(id)) setDebugStatus("Deleted (id=$id)") else sendStatus = "Delete failed"
        }
    }

    /**
     * Called right before the WebView fires the in-page fetch. Returns the full
     * outgoing body: replies are `[quote=<id>][/quote]<text>` (sendMessage.ts parity).
     */
    fun beginSend(text: String): String {
        stopTyping()
        val content = transformMentions(text)
        val voice = voiceDraft?.url?.let { "[audio]$it[/audio]" }
        val merged = when {
            voice == null -> content
            content.isBlank() -> voice
            else -> "$content\n$voice"
        }
        val body = replyTo?.let { "[quote=${it.id}][/quote]$merged" } ?: merged
        replyTo = null
        discardVoiceDraft()
        setComposer("")
        pendingSends.addLast(PendingSend(++pendingSendSeq, body))
        setDebugStatus("Posting via WebView…")
        return body
    }
    fun beginSendWithId(text: String): Pair<String, Long> {
        val body = beginSend(text)
        val id = pendingSends.lastOrNull()?.id ?: pendingSendSeq
        return body to id
    }

    fun noteSend(message: String) {
        sendStatus = message
    }

    fun onWebPostResult(json: String) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val obj = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull()
            var ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull ?: false
            val status = obj?.get("status")?.jsonPrimitive?.intOrNull ?: -1
            val head = obj?.get("head")?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", " ")
            val url = obj?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
            val pendingId = obj?.get("pendingId")?.jsonPrimitive?.let { runCatching { it.content.toLong() }.getOrNull() } ?: obj?.get("id")?.jsonPrimitive?.let { runCatching { it.content.toLong() }.getOrNull() }
            log("WebView POST -> ok=$ok status=$status url=${url.take(80)} head=${head.take(80)} pendingId=$pendingId")
            val pending = when {
                pendingId != null -> pendingSends.find { it.id == pendingId }
                else -> pendingSends.firstOrNull()
            }
            if (pendingId != null && pending == null) {
                // The send was already resolved (duplicate deliver): falling back to
                // the queue head would remove a still-pending send by mistake.
                log("WebView POST for unknown pendingId=$pendingId — ignoring")
                return@launch
            }
            val pendingBody = pending?.text ?: pendingText
            if (status == 401 || status == 403 || url.contains("/login", ignoreCase = true)) {
                if (pendingBody.isNotBlank()) setComposer(pendingBody)
                if (pending != null) pendingSends.remove(pending) else pendingSends.clear()
                session = null
                authReady = false
                sendStatus = "登录已过期，请重新登录后再发"
                showLogin = true
                webViewReloadUrl = "${mk.ry.redollars.net.Config.BGM_HOST}/login"
                return@launch
            }
            val lowerHead = head.lowercase()
            val looksLikeHtml = head.trimStart().startsWith("<", ignoreCase = true) || "<html" in lowerHead || "<!doctype" in lowerHead
            if (ok && looksLikeHtml) {
                log("WebView POST looks like HTML despite ok=true — treating as failure")
                ok = false
            }
            if (!ok) {
                if (pendingBody.isNotBlank()) setComposer(pendingBody)
                if (pending != null) pendingSends.remove(pending)
                sendStatus = if (looksLikeHtml) "Post failed (server returned HTML)" else "Post failed (status=$status)"
                return@launch
            }
            setDebugStatus("Posted; confirming…")
            val confirmed = confirmLoop(session?.uid ?: 0, pendingBody)
            if (pending != null) pendingSends.remove(pending)
            setDebugStatus(
                if (confirmed != null) "Confirmed (id=${confirmed.id})" else "Posted; awaiting WS echo",
            )
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

    private companion object {
        const val MAX_IMAGE_BYTES = 50 * 1024 * 1024 // upload server's image cap
        const val MAX_FILE_BYTES = 200 * 1024 * 1024 // upload server's file cap
        const val MAX_VOICE_SECONDS = 600 // safety cap on a single recording
    }

    // No onCleared: the repository is an app-scoped singleton — its WebSocket is
    // quiesced by setForeground(false), not torn down with the ViewModel.
}

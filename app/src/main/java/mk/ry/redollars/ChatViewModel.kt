package mk.ry.redollars

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.NotificationItem
import mk.ry.redollars.net.UploadResult
import mk.ry.redollars.net.UserSearchDto
import mk.ry.redollars.net.WsUser
import mk.ry.redollars.voice.VoiceRecorder
import java.io.File
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
    @param:ApplicationContext private val appContext: Context,
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
    /** App-local blocklist; blocked users vanish from list/typing/notifications. */
    val blockedUsers: StateFlow<Set<Long>> = repo.blockedUsers
    /** uids currently online among recently-visible authors (presence dots). */
    val onlineUsers: StateFlow<Set<Long>> = repo.onlineUsers

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
    var composerValue by mutableStateOf(TextFieldValue(""))
        private set
    /** Suggestions for the trailing `@query` at the cursor (mention autocomplete). */
    var mentionCandidates by mutableStateOf<List<UserSearchDto>>(emptyList())
        private set
    /** Message being edited (id + any hidden leading quote to restore on save). */
    var editing by mutableStateOf<EditingState?>(null); private set
    /** True once the backend Bearer token is validated for this session's uid. */
    var authReady by mutableStateOf(false); private set
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
    private var pendingText = ""

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
                viewModelScope.launch { repo.registerPushToken(token) }
            }
    }

    fun start() {
        if (started) return
        started = true
        repo.connect(uid = 0) // anonymous read connection until the user logs in
    }

    fun onLoggedIn(info: SessionInfo) {
        session = info
        // Remember we have a session so future launches auto-login silently on open.
        sessionHintPrefs.edit().putBoolean("had_session", true).apply()
        viewModelScope.launch {
            // The page-extracted name is the login slug (or a uid fallback), NOT the
            // display nickname — resolve the real one from the backend profile cache
            // before joining, or other clients would show the wrong name for our
            // typing frames and reactions.
            val profile = repo.fetchUserProfile(info.uid)
            val nickname = profile?.nickname?.takeIf { it.isNotBlank() } ?: info.name
            val avatar = profile?.avatar?.let { it.medium ?: it.large ?: it.small }
            session = info.copy(name = nickname)
            log("Session ready: uid=${info.uid} nickname=$nickname (profile=${profile != null})")
            // Re-identify + join (share presence) so our typing/presence is attributed.
            repo.connect(info.uid, nickname, avatar)

            // Backend token: reuse a stored one when it belongs to this uid; otherwise
            // drive the rymk-auth flow in the login WebView, which must be visible so the
            // user can complete the bgm.tv login/authorize it redirects through. The
            // captured JWT is delivered back via onAuthToken.
            if (repo.validateAuthToken(info.uid)) {
                authReady = true
                showLogin = false
                log("Backend auth ready (stored token)")
                registerPush()
            } else {
                val nonce = java.util.UUID.randomUUID().toString()
                authNonce = nonce
                showLogin = true
                oauthRequestUrl = mk.ry.redollars.net.Config.rymkAuthStartUrl(nonce)
                log("Requesting rymk-auth authorization…")
            }
        }
    }

    /** JWT captured from the rymk-auth popup (the `rymk_auth` postMessage in auth.ts),
     *  delivered off the main thread by the WebView bridge — so marshal onto Main before
     *  touching Compose state. [state] must equal the nonce we minted for this request,
     *  or the message is ignored (stale/foreign popup guard). */
    fun onAuthToken(token: String, state: String?) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (authNonce == null || state != authNonce) {
                log("rymk-auth: ignoring token with mismatched state")
                return@launch
            }
            authNonce = null
            oauthRequestUrl = null
            repo.setAuthToken(token)
            authReady = repo.validateAuthToken(session?.uid ?: 0)
            showLogin = false
            // rymk-auth left the shared WebView on bgm.tv/auth.ry.mk; return it to the
            // Bangumi origin so buildPostJs's same-origin fetch works.
            webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
            log("Backend auth ${if (authReady) "ready (rymk JWT)" else "validation FAILED"}")
            if (authReady) registerPush()
        }
    }

    /** Host consumed [webViewReloadUrl] (reloaded the WebView); clear the request. */
    fun onWebViewReloaded() {
        webViewReloadUrl = null
    }

    /** Sign out: drop the backend token, forget the session hint (so the next launch
     *  won't silently auto-login), clear Bangumi cookies so the WebView session is gone,
     *  reset the shared WebView to a logged-out page, and fall back to the anonymous
     *  read connection. Posting/edit/delete lock again until the user logs back in. */
    fun logout() {
        repo.setAuthToken(null)
        sessionHintPrefs.edit().putBoolean("had_session", false).apply()
        session = null
        authReady = false
        authNonce = null
        oauthRequestUrl = null
        showAccount = false
        showLogin = false
        val cm = android.webkit.CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
        // Reset the shared WebView off the logged-in page so a later login starts clean.
        webViewReloadUrl = "${mk.ry.redollars.net.Config.BGM_HOST}/login"
        repo.connect(uid = 0) // back to anonymous read
        log("Logged out")
    }

    /** User closed the login overlay; abandon any in-flight rymk-auth request. If a
     *  Bangumi session already exists, return the WebView to the Bangumi origin so
     *  posting still works (rymk-auth may have navigated it to bgm.tv/auth.ry.mk). */
    fun dismissLogin() {
        showLogin = false
        oauthRequestUrl = null
        authNonce = null
        if (session != null) webViewReloadUrl = mk.ry.redollars.net.Config.DOLLARS_URL
    }

    // ---- Composer typing signals: start immediately, stop after 2.5s idle or on send. ----
    private var typingStopJob: Job? = null
    private var typingActive = false

    fun onComposerChanged(value: TextFieldValue) {
        val textChanged = value.text != composerValue.text
        composerValue = value
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
        composerValue = TextFieldValue(text, TextRange(text.length))
        mentionJob?.cancel()
        mentionCandidates = emptyList()
        mentionStart = -1
    }

    /** Replace the current selection with [snippet], cursor after it. */
    fun insertAtCursor(snippet: String) {
        val v = composerValue
        val text = v.text.replaceRange(v.selection.min, v.selection.max, snippet)
        composerValue = TextFieldValue(text, TextRange(v.selection.min + snippet.length))
    }

    fun insertSmiley(code: String) = insertAtCursor(code)

    /** Long-press an author's avatar to mention them. Messages carry a uid + nickname
     *  but no `@username` handle, so we insert the resolved `[user=id]` BBCode directly —
     *  exactly what [transformMentions] emits for a completed @-mention — rather than the
     *  readable `@name` the completer inserts. */
    fun mentionUser(uid: Long, nickname: String) {
        insertAtCursor("[user=$uid]${nickname.ifBlank { uid.toString() }}[/user] ")
    }

    /** A saved sticker was picked from the panel (SmileyPanel.tsx insert format). */
    fun insertSticker(url: String) = insertAtCursor("[sticker]$url[/sticker]")

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
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                            .getOrNull()
                    }
                    res = when {
                        bytes == null -> UploadResult(error = "Could not read file")
                        bytes.size > MAX_FILE_BYTES -> UploadResult(error = "Too large (max 200MB)")
                        else -> repo.uploadFile(bytes, name, mime)
                    }
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
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            val res = if (bytes == null) UploadResult(error = "Could not read recording")
            else repo.uploadFile(bytes, file.name, "audio/mp4")
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
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext UploadResult(error = "Could not read file")
        if (bytes.size > MAX_IMAGE_BYTES) return@withContext UploadResult(error = "Too large (max 50MB)")
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/avif" -> "avif"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "jpg"
        }
        repo.uploadImage(bytes, "image-${System.currentTimeMillis()}.$ext", mime)
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
        composerValue = TextFieldValue(text, TextRange(start + replacement.length))
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

    /** Block/unblock from the profile sheet. */
    fun toggleBlock(uid: Long) {
        val nowBlocked = uid !in repo.blockedUsers.value
        repo.setBlocked(uid, nowBlocked)
        sendStatus = if (nowBlocked) "已屏蔽该用户" else "已取消屏蔽"
    }

    /** Full-text search page (search sheet). */
    suspend fun searchMessages(query: String, offset: Int) = repo.searchMessages(query, offset)

    /** Media wall page (gallery sheet). */
    suspend fun fetchGallery(offset: Int) = repo.fetchGallery(offset)

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
        // Voice rides along on its own line (sendMessage.ts attachVoice).
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
        pendingText = body
        setDebugStatus("Posting via WebView…")
        return body
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
            setDebugStatus("Posted; confirming…")
            val confirmed = confirmLoop(session?.uid ?: 0, pendingText)
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

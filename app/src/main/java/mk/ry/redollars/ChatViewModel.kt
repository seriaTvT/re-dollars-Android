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
import javax.inject.Inject

data class SessionInfo(val uid: Long, val name: String, val formhash: String)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repo: MessageRepository,
    @param:ApplicationContext private val appContext: Context,
) : ViewModel() {

    // ---- Observable state from the repository ----
    val messages: StateFlow<List<MessageDto>> =
        repo.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val connected: StateFlow<Boolean> = repo.connected
    val onlineCount: StateFlow<Int> = repo.onlineCount
    val typingUsers: StateFlow<List<WsUser>> = repo.typingUsers
    val notifications: StateFlow<List<NotificationItem>> = repo.notifications
    /** Saved sticker image URLs (favorites tab in the smiley picker). */
    val favorites: StateFlow<List<String>> = repo.favorites

    // ---- UI-only state ----
    var session by mutableStateOf<SessionInfo?>(null); private set
    var showLogin by mutableStateOf(false)
    var sendStatus by mutableStateOf<String?>(null); private set
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
    /** Non-null asks the host to load the OAuth authorize URL in the login WebView. */
    var oauthRequestUrl by mutableStateOf<String?>(null); private set
    val logs: SnapshotStateList<String> = mutableStateListOf()

    data class EditingState(val id: Long, val hiddenQuote: String?)

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
            // drive the OAuth authorize flow in the still-visible login WebView (its
            // callback sets a dollars_auth cookie we can harvest).
            if (repo.validateAuthToken(info.uid)) {
                authReady = true
                showLogin = false
                log("Backend auth ready (stored token)")
            } else {
                oauthRequestUrl = mk.ry.redollars.net.Config.oauthAuthorizeUrl()
                log("Requesting backend OAuth authorization…")
            }
        }
    }

    /** Token harvested from the OAuth callback cookie by the WebView. */
    fun onAuthToken(token: String) {
        oauthRequestUrl = null
        viewModelScope.launch {
            repo.setAuthToken(token)
            authReady = repo.validateAuthToken(session?.uid ?: 0)
            showLogin = false
            log("Backend auth ${if (authReady) "ready (new token)" else "validation FAILED"}")
        }
    }

    /** User closed the login overlay; abandon any in-flight OAuth request. */
    fun dismissLogin() {
        showLogin = false
        oauthRequestUrl = null
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

    /** A saved sticker was picked from the panel (SmileyPanel.tsx insert format). */
    fun insertSticker(url: String) = insertAtCursor("[sticker]$url[/sticker]")

    // ---- Image upload + sticker favorites ----

    var uploading by mutableStateOf(false)
        private set

    /** Upload picked images and insert an [img] tag per success. */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty() || uploading) return
        if (!authReady) {
            sendStatus = "Image upload needs backend authorization (re-login)"
            return
        }
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
        if (!authReady) {
            sendStatus = "Sticker upload needs backend authorization (re-login)"
            return
        }
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
            sendStatus = if (ok) "Edited (id=${edit.id})" else "Edit failed"
            if (ok) {
                editing = null
                setComposer("")
            }
        }
    }

    fun deleteMessage(id: Long) {
        viewModelScope.launch {
            sendStatus = if (repo.deleteMessage(id)) "Deleted (id=$id)" else "Delete failed"
        }
    }

    /**
     * Called right before the WebView fires the in-page fetch. Returns the full
     * outgoing body: replies are `[quote=<id>][/quote]<text>` (sendMessage.ts parity).
     */
    fun beginSend(text: String): String {
        stopTyping()
        val content = transformMentions(text)
        val body = replyTo?.let { "[quote=${it.id}][/quote]$content" } ?: content
        replyTo = null
        setComposer("")
        pendingText = body
        sendStatus = "Posting via WebView…"
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

    private companion object {
        const val MAX_IMAGE_BYTES = 50 * 1024 * 1024 // upload server's image cap
    }

    // No onCleared: the repository is an app-scoped singleton — its WebSocket is
    // quiesced by setForeground(false), not torn down with the ViewModel.
}

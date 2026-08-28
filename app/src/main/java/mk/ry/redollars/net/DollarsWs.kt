package mk.ry.redollars.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/** A user attached to a presence/typing frame. */
data class WsUser(val id: Long, val name: String, val avatar: String?)

/** Events surfaced to the repository. */
sealed interface WsEvent {
    data class Status(val connected: Boolean) : WsEvent
    data class OnlineCount(val count: Int) : WsEvent
    data class NewMessages(val messages: List<MessageDto>) : WsEvent
    data class Typing(val user: WsUser, val typing: Boolean) : WsEvent
    data class ReactionAdd(val messageId: Long, val reaction: ReactionDto) : WsEvent
    data class ReactionRemove(val messageId: Long, val userId: Long, val emoji: String) : WsEvent
    data class MessageDeleted(val messageId: Long) : WsEvent
    data class MessageEdited(val message: MessageDto) : WsEvent
    data class Notification(val item: NotificationItem) : WsEvent
    /** Online-state answers/pushes for subscribed uids (presence_result / _update). */
    data class Presence(val users: List<Pair<Long, Boolean>>) : WsEvent
    data class Log(val line: String) : WsEvent
}

/**
 * OkHttp WebSocket client mirroring re-dollars-preact/src/hooks/useWebSocket.ts, plus:
 *  - exponential-backoff reconnect on unexpected close/failure,
 *  - heartbeat paused while backgrounded ([setActive]),
 *  - stale-socket guard so a replaced socket's callbacks are ignored.
 * Gap recovery lives in the repository, driven by the Status(true) transition.
 */
class DollarsWs(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onEvent: (WsEvent) -> Unit,
) {
    private var currentSocket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnectJob: Job? = null
    private var uid: Long = 0
    private var joinName: String? = null
    private var joinAvatar: String? = null
    private var active = true
    private var intentionallyClosed = false
    private var attempt = 0
    private var lastPongAt = 0L

    /** [name]/[avatar] enable presence sharing (`join`): the server then attributes our
     *  typing/presence frames. Without them we stay an identified-but-anonymous reader. */
    fun connect(uid: Long, name: String? = null, avatar: String? = null) {
        this.uid = uid
        this.joinName = name
        this.joinAvatar = avatar
        intentionallyClosed = false
        attempt = 0
        reconnectJob?.cancel(); reconnectJob = null
        open()
    }

    /** Re-identify with current uid without full reconnect, for token refresh */
    fun reidentify() {
        if (currentSocket != null && !intentionallyClosed) {
            currentSocket?.send("{\"type\":\"identify\",\"uid\":\"$uid\"}")
        }
    }

    /** Broadcast our typing state (server attaches the joined user identity). */
    fun sendTyping(typing: Boolean) {
        currentSocket?.send("""{"type":"${if (typing) "typing_start" else "typing_stop"}"}""")
    }

    /** Presence subscriptions (presenceHandlers.ts): the server pushes presence_update
     *  only for subscribed uids; a query answers immediately with presence_result. */
    fun sendPresenceSubscribe(uids: Collection<Long>) {
        if (uids.isEmpty()) return
        currentSocket?.send("""{"type":"presence_subscribe","uids":${uidsJson(uids)}}""")
        currentSocket?.send("""{"type":"presence_query","uids":${uidsJson(uids)}}""")
    }

    fun sendPresenceUnsubscribe(uids: Collection<Long>) {
        if (uids.isEmpty()) return
        currentSocket?.send("""{"type":"presence_unsubscribe","uids":${uidsJson(uids)}}""")
    }

    private fun uidsJson(uids: Collection<Long>) =
        uids.joinToString(",", "[", "]") { "\"$it\"" }

    /** Foreground/background toggle: pause heartbeat when hidden, resume/reconnect when shown. */
    /** Foreground/background toggle: quiesce socket when backgrounded. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        currentSocket?.send("{\"type\":\"presence\",\"open\":$active}")
        if (active) {
            if (currentSocket == null && !intentionallyClosed) open() else startHeartbeat()
        } else {
            stopHeartbeat()
            reconnectJob?.cancel(); reconnectJob = null
            currentSocket?.close(1000, "background")
            currentSocket = null
        }
    }

    fun close() {
        intentionallyClosed = true
        reconnectJob?.cancel(); reconnectJob = null
        stopHeartbeat()
        currentSocket?.close(1000, "bye")
        currentSocket = null
    }

    private fun open() {
        val req = Request.Builder()
            .url(Config.WEBSOCKET_URL)
            .header("User-Agent", Config.USER_AGENT)
            .build()
        // Replacing currentSocket makes any prior socket's callbacks stale (ignored below).
        currentSocket?.close(1000, "reconnect")
        currentSocket = client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (intentionallyClosed || !active) return
        reconnectJob?.cancel()
        attempt++
        // Exponential backoff with full jitter: base 2s, cap 60s, jitter 0-1000ms + decorrelated
        val base = (1000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000L)
        val jitter = Random.nextLong(0, 1000) + Random.nextLong(0, base / 4)
        val backoff = (base + jitter).coerceAtMost(60_000L)
        // Circuit breaker: after 10 attempts, pause 2 minutes
        val isCircuitOpen = attempt > 10
        val finalDelay = if (isCircuitOpen) 120_000L else backoff
        reconnectJob = scope.launch {
            onEvent(WsEvent.Log("WS reconnect in ${finalDelay}ms (attempt $attempt${if (isCircuitOpen) " circuit-open" else ""})"))
            delay(finalDelay)
            if (!intentionallyClosed && active) open()
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(Config.HEARTBEAT_INTERVAL_MS)
                val socket = currentSocket ?: break
                // No pong for two intervals means the link is dead even when no TCP
                // error surfaces (silent NAT drop); reconnect instead of pinging a
                // black hole and relying on the shared client's read timeout.
                if (System.currentTimeMillis() - lastPongAt > Config.HEARTBEAT_STALE_MS) {
                    onEvent(WsEvent.Log("WS stale (no pong) — reconnecting"))
                    currentSocket = null
                    socket.close(1000, "stale")
                    stopHeartbeat()
                    onEvent(WsEvent.Status(false))
                    scheduleReconnect()
                    break
                }
                socket.send("""{"type":"ping"}""")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private fun presencePair(user: JsonObject): Pair<Long, Boolean>? {
        val id = user["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val active = user["active"]?.jsonPrimitive?.contentOrNull == "true"
        return id to active
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== currentSocket) return
            attempt = 0
            lastPongAt = System.currentTimeMillis()
            onEvent(WsEvent.Status(true))
            onEvent(WsEvent.Log("WS open -> identify uid=$uid join=${joinName != null}"))
            webSocket.send("""{"type":"identify","uid":"$uid"}""")
            joinName?.let { name ->
                val user = buildJsonObject {
                    put("id", uid.toString())
                    put("name", name)
                    joinAvatar?.let { put("avatar", it) }
                }
                webSocket.send("""{"type":"join","user":$user}""")
            }
            webSocket.send("""{"type":"presence","open":$active}""")
            if (active) startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== currentSocket) return
            val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return

            obj["ackId"]?.jsonPrimitive?.contentOrNull?.let { ackId ->
                webSocket.send("""{"type":"ack","ackId":"$ackId"}""")
            }

            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "online_count_update" ->
                    obj["count"]?.let { onEvent(WsEvent.OnlineCount(it.jsonPrimitive.int)) }

                "new_messages" -> {
                    val arr = obj["payload"] as? JsonArray ?: return
                    val msgs = arr.mapNotNull { el ->
                        runCatching { AppJson.decodeFromJsonElement(MessageDto.serializer(), el) }.getOrNull()
                    }
                    if (msgs.isNotEmpty()) onEvent(WsEvent.NewMessages(msgs))
                }

                "typing_start", "typing_stop" -> {
                    val userObj = obj["user"] as? JsonObject ?: return
                    val id = userObj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                    val name = userObj["nickname"]?.jsonPrimitive?.contentOrNull
                        ?: userObj["name"]?.jsonPrimitive?.contentOrNull ?: id.toString()
                    val avatar = userObj["avatar"]?.jsonPrimitive?.contentOrNull
                    onEvent(
                        WsEvent.Typing(
                            WsUser(id, name, avatar),
                            typing = obj["type"]?.jsonPrimitive?.contentOrNull == "typing_start",
                        ),
                    )
                }

                "reaction_add" -> {
                    val payload = obj["payload"] as? JsonObject ?: return
                    val messageId = payload["message_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                    val reaction = payload["reaction"]?.let { el ->
                        runCatching { AppJson.decodeFromJsonElement(ReactionDto.serializer(), el) }.getOrNull()
                    } ?: return
                    onEvent(WsEvent.ReactionAdd(messageId, reaction))
                }

                "reaction_remove" -> {
                    val payload = obj["payload"] as? JsonObject ?: return
                    val messageId = payload["message_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                    val userId = payload["user_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                    val emoji = payload["emoji"]?.jsonPrimitive?.contentOrNull ?: return
                    onEvent(WsEvent.ReactionRemove(messageId, userId, emoji))
                }

                "notification" -> {
                    // Server sends this only to our identified uid (sendToUser).
                    val payload = obj["payload"] ?: return
                    val dto = runCatching {
                        AppJson.decodeFromJsonElement(NotificationWsDto.serializer(), payload)
                    }.getOrNull() ?: return
                    onEvent(WsEvent.Notification(dto.toItem()))
                }

                "message_delete" -> {
                    val payload = obj["payload"] as? JsonObject ?: return
                    val id = payload["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                    onEvent(WsEvent.MessageDeleted(id))
                }

                "message_edit" -> {
                    // Payload is the full re-enriched message.
                    val payload = obj["payload"] ?: return
                    val message = runCatching {
                        AppJson.decodeFromJsonElement(MessageDto.serializer(), payload)
                    }.getOrNull() ?: return
                    onEvent(WsEvent.MessageEdited(message))
                }

                "presence_result" -> {
                    val users = (obj["users"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.let(::presencePair) }
                        .orEmpty()
                    if (users.isNotEmpty()) onEvent(WsEvent.Presence(users))
                }

                "presence_update" -> {
                    (obj["user"] as? JsonObject)?.let(::presencePair)
                        ?.let { onEvent(WsEvent.Presence(listOf(it))) }
                }

                "pong" -> { lastPongAt = System.currentTimeMillis() }
                else -> { /* unhandled frame types */ }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== currentSocket) return
            stopHeartbeat()
            currentSocket = null
            onEvent(WsEvent.Status(false))
            onEvent(WsEvent.Log("WS closed: $code $reason"))
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== currentSocket) return
            stopHeartbeat()
            currentSocket = null
            onEvent(WsEvent.Status(false))
            onEvent(WsEvent.Log("WS failure: ${t.message}"))
            scheduleReconnect()
        }
    }
}
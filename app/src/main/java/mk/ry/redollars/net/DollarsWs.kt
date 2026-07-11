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

    /** Broadcast our typing state (server attaches the joined user identity). */
    fun sendTyping(typing: Boolean) {
        currentSocket?.send("""{"type":"${if (typing) "typing_start" else "typing_stop"}"}""")
    }

    /** Foreground/background toggle: pause heartbeat when hidden, resume/reconnect when shown. */
    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        currentSocket?.send("""{"type":"presence","open":$active}""")
        if (active) {
            if (currentSocket == null && !intentionallyClosed) open() else startHeartbeat()
        } else {
            stopHeartbeat()
            reconnectJob?.cancel(); reconnectJob = null
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
        currentSocket = client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (intentionallyClosed || !active) return
        reconnectJob?.cancel()
        attempt++
        val backoff = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L) // 2s,4s,…,30s cap
        reconnectJob = scope.launch {
            onEvent(WsEvent.Log("WS reconnect in ${backoff}ms (attempt $attempt)"))
            delay(backoff + Random.nextLong(0, 400))
            if (!intentionallyClosed && active) open()
        }
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(Config.HEARTBEAT_INTERVAL_MS)
                currentSocket?.send("""{"type":"ping"}""")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== currentSocket) return
            attempt = 0
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

                "pong" -> { /* heartbeat ack */ }
                else -> { /* presence_update/reactions/etc. not yet handled */ }
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

package mk.ry.redollars.spike.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Events surfaced to the UI layer. */
sealed interface WsEvent {
    data class Status(val connected: Boolean) : WsEvent
    data class OnlineCount(val count: Int) : WsEvent
    data class NewMessages(val messages: List<MessageDto>) : WsEvent
    data class Log(val line: String) : WsEvent
}

/**
 * OkHttp WebSocket client mirroring re-dollars-preact/src/hooks/useWebSocket.ts:
 * on open -> identify; heartbeat ping every 25s; ack any frame carrying ackId.
 * Reconnect/gap-recovery are intentionally out of scope for the spike.
 */
class DollarsWs(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onEvent: (WsEvent) -> Unit,
) {
    private var ws: WebSocket? = null
    private var heartbeat: Job? = null
    private var uid: Long = 0

    fun connect(uid: Long) {
        this.uid = uid
        close()
        val req = Request.Builder()
            .url(Config.WEBSOCKET_URL)
            .header("User-Agent", Config.USER_AGENT)
            .build()
        ws = client.newWebSocket(req, listener)
    }

    fun close() {
        heartbeat?.cancel()
        heartbeat = null
        ws?.close(1000, "bye")
        ws = null
    }

    private fun startHeartbeat() {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (isActive) {
                delay(Config.HEARTBEAT_INTERVAL_MS)
                ws?.send("""{"type":"ping"}""")
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onEvent(WsEvent.Status(true))
            onEvent(WsEvent.Log("WS open -> identify uid=$uid"))
            webSocket.send("""{"type":"identify","uid":"$uid"}""")
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return

            // Ack any reliable frame first.
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

                "pong" -> { /* heartbeat ack */ }
                else -> { /* typing/presence/etc. ignored in the spike */ }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeat?.cancel()
            onEvent(WsEvent.Status(false))
            onEvent(WsEvent.Log("WS closed: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            heartbeat?.cancel()
            onEvent(WsEvent.Status(false))
            onEvent(WsEvent.Log("WS failure: ${t.message}"))
        }
    }
}

package mk.ry.redollars.ui.render

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mk.ry.redollars.di.ApplicationScope
import mk.ry.redollars.net.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Provided at the app root so bubbles/preview can drive shared audio playback. */
val LocalAudioPlayer = staticCompositionLocalOf<AudioPlayer?> { null }

/**
 * One shared MediaPlayer for the whole app: starting a clip stops whatever else was
 * playing. Remote `[audio]` URLs are downloaded through OkHttp to the cache first and
 * played from disk — Android's MediaPlayer network stack is unreliable against the
 * Cloudflare-fronted upload host, while local-file playback is rock solid and the
 * OkHttp client already fetches from that host everywhere else. Local paths (the voice
 * preview) play directly.
 */
@Singleton
class AudioPlayer @Inject constructor(
    private val http: OkHttpClient,
    @param:ApplicationContext context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val cacheDir = File(context.cacheDir, "audio").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var loadJob: Job? = null

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    fun toggle(source: String) {
        if (_nowPlaying.value == source) {
            stop()
            return
        }
        stop()
        _nowPlaying.value = source // reflects "loading" until the clip actually starts
        loadJob = scope.launch {
            val local = resolveLocal(source)
            if (local == null || _nowPlaying.value != source) {
                if (_nowPlaying.value == source) stop()
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (_nowPlaying.value == source) startLocal(local)
            }
        }
    }

    /** A local path passes through; a remote URL is cached (once) and its path returned. */
    private suspend fun resolveLocal(source: String): String? {
        if (source.startsWith("/")) return source
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(cacheDir, Integer.toHexString(source.hashCode()) + ".m4a")
                if (!file.exists() || file.length() == 0L) {
                    val req = Request.Builder()
                        .url(source)
                        .header("User-Agent", Config.USER_AGENT)
                        .build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@runCatching null
                        res.body?.byteStream()?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        }
                    }
                }
                if (file.length() > 0L) file.absolutePath else null
            }.getOrNull()
        }
    }

    private fun startLocal(path: String) {
        runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(path)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            p.prepareAsync()
            player = p
        }.onFailure { stop() }
    }

    fun stop() {
        loadJob?.cancel()
        loadJob = null
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
        _nowPlaying.value = null
    }

    /** Current position/duration in ms; zeros until prepared. */
    fun positionMs(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
    fun durationMs(): Int = runCatching { player?.duration ?: 0 }.getOrDefault(0)
}

private fun formatMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** In-bubble audio player for `[audio]url[/audio]` (voice messages). */
@Composable
fun AudioBlockView(url: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val player = LocalAudioPlayer.current
    val nowPlaying by (player?.nowPlaying ?: MutableStateFlow(null)).collectAsState()
    val playing = nowPlaying == url
    var progress by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(playing) {
        if (!playing || player == null) {
            progress = 0f
            positionMs = 0
            return@LaunchedEffect
        }
        while (true) {
            val duration = player.durationMs()
            positionMs = player.positionMs()
            progress = if (duration > 0) positionMs.toFloat() / duration else 0f
            delay(200)
        }
    }

    Row(
        modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { player?.toggle(url) },
            modifier = Modifier.size(34.dp),
        ) {
            if (playing) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(cs.primary, RoundedCornerShape(2.dp)),
                )
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play voice message")
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.padding(horizontal = 8.dp).width(120.dp),
        )
        Text(
            text = if (playing) formatMs(positionMs) else "语音",
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

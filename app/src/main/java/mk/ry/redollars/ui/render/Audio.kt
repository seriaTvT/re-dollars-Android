package mk.ry.redollars.ui.render

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One shared MediaPlayer for the whole app: starting a clip stops whatever else was
 * playing. Sources are http(s) URLs ([audio] messages) or local paths (voice preview).
 */
object AudioPlayer {
    private var player: MediaPlayer? = null

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    fun toggle(source: String) {
        if (_nowPlaying.value == source) {
            stop()
            return
        }
        stop()
        runCatching {
            val p = MediaPlayer()
            p.setDataSource(source)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { stop() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                true
            }
            p.prepareAsync()
            player = p
            _nowPlaying.value = source
        }.onFailure { stop() }
    }

    fun stop() {
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
    val nowPlaying by AudioPlayer.nowPlaying.collectAsState()
    val playing = nowPlaying == url
    var progress by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableIntStateOf(0) }

    LaunchedEffect(playing) {
        if (!playing) {
            progress = 0f
            positionMs = 0
            return@LaunchedEffect
        }
        while (true) {
            val duration = AudioPlayer.durationMs()
            positionMs = AudioPlayer.positionMs()
            progress = if (duration > 0) positionMs.toFloat() / duration else 0f
            delay(200)
        }
    }

    Row(
        modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { AudioPlayer.toggle(url) },
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

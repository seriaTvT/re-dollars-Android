package mk.ry.redollars.ui.render

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import mk.ry.redollars.net.Config

/**
 * Inline `[video]url[/video]` player. The chat list stays a light poster card (a play
 * badge + host label); tapping opens a fullscreen dialog that plays the clip with
 * ExoPlayer. The platform VideoView/MediaPlayer stack silently failed on the
 * Cloudflare-fronted mp4s (play→pause flicker, no duration), so we use media3 with a
 * browser-UA HTTP data source — matching what the web's `<video>` does — and surface any
 * remaining error with an "open externally" fallback.
 */
@Composable
fun VideoBlockView(url: String) {
    var open by remember(url) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val host = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: "视频"
    }

    Row(
        Modifier
            .padding(vertical = 4.dp)
            .widthIn(max = 320.dp)
            .clickable { open = true }
            .background(cs.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).background(cs.primary, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "播放视频", tint = cs.onPrimary)
        }
        Text(
            text = "视频 · $host",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp),
        )
    }

    if (open) VideoDialog(url) { open = false }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoDialog(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Config.BROWSER_UA)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        loading = state == Player.STATE_BUFFERING
                    }

                    override fun onPlayerError(e: PlaybackException) {
                        loading = false
                        error = "播放失败：${e.errorCodeName} (${e.errorCode})"
                    }
                })
                prepare()
            }
    }

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center),
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = true
                    }
                },
            )

            if (loading && error == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            error?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(msg, color = Color.White, textAlign = TextAlign.Center)
                    TextButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .setDataAndType(Uri.parse(url), "video/*")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        onClose()
                    }) { Text("用浏览器打开", color = Color.White) }
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

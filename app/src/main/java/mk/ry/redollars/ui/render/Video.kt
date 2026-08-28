package mk.ry.redollars.ui.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mk.ry.redollars.net.Config
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Inline `[video]url[/video]` player. The chat list shows a poster: the clip's first
 * frame with a play badge and duration (fetched via MediaMetadataRetriever range
 * requests, cached in memory and on disk), or a light host-label card until/unless a
 * frame is available. Tapping opens a fullscreen dialog that plays the clip with
 * ExoPlayer. The platform VideoView/MediaPlayer stack silently failed on the
 * Cloudflare-fronted mp4s (play→pause flicker, no duration), so we use media3 with a
 * browser-UA HTTP data source — matching what the web's `<video>` does — and surface any
 * remaining error with an "open externally" fallback.
 */
@Composable
fun VideoBlockView(url: String) {
    var open by remember(url) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val host = remember(url) {
        runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: "视频"
    }

    var retryKey by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(0) }
    // Poster frames count as media previews: with auto-load off we stop fetching new
    // ones (the host-label card shows instead), though already-cached frames still draw.
    val autoLoad = LocalAutoLoadMedia.current
    val meta by produceState(metaCache.get(url), url, retryKey, autoLoad) {
        if ((value == null || retryKey > 0) && autoLoad) {
            val dir = File(ctx.cacheDir, "vthumb").apply { mkdirs() }
            value = withContext(Dispatchers.IO) { loadVideoMeta(dir, url) }
                ?.also { metaCache.put(url, it) }
        }
    }

    val m = meta
    if (m != null) {
        val ratio = if (m.frame.height > 0) m.frame.width.toFloat() / m.frame.height else 16f / 9f
        Box(
            Modifier
                .padding(vertical = 4.dp)
                .widthIn(max = 320.dp)
                .aspectRatio(ratio)
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true },
        ) {
            Image(
                bitmap = m.frame,
                contentDescription = "视频 · $host",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .size(44.dp)
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (m.durationMs > 0) {
                Text(
                    text = formatDuration(m.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    } else {
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
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = { retryKey++ }) {
                androidx.compose.material3.Text("重试")
            }
        }
    }

    if (open) VideoViewerDialog(url) { open = false }
}

/** Shared first-frame thumbnail for a video clip: shows the extracted first frame
 *  (disk/memory cached via [loadVideoMeta]), falling back to [fallbackUrl] (e.g. the
 *  backend poster) while loading, then a neutral placeholder with a play badge. Used
 *  by the media wall and composer preview bubbles. */
@Composable
fun VideoThumbnail(
    url: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    playBadge: Boolean = true,
    fallbackUrl: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var loading by remember(url) { mutableStateOf(metaCache.get(url) == null) }
    val meta by produceState(metaCache.get(url), url) {
        if (value == null) {
            val dir = File(ctx.cacheDir, "vthumb").apply { mkdirs() }
            value = withContext(Dispatchers.IO) { loadVideoMeta(dir, url) }
                ?.also { metaCache.put(url, it) }
        }
        loading = false
    }
    val frame = meta?.frame
    // Only show the progress state when there is nothing better to display yet.
    val showLoading = loading && (frame == null || frame.width <= 0) && fallbackUrl.isNullOrBlank()

    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            frame != null && frame.width > 0 -> Image(
                bitmap = frame,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            fallbackUrl != null -> AsyncImage(
                model = fallbackUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Box(
                Modifier.fillMaxSize().background(cs.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (showLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
        if (playBadge && !showLoading) {
            Box(
                Modifier.size(28.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private class VideoMeta(val frame: ImageBitmap, val durationMs: Long)

// Byte-budgeted (~24MB): first frames decode at source resolution, so one 1080p
// frame is ~8MB — an entry-counted cache could pin hundreds of MB on the media wall.
private val metaCache = object : LruCache<String, VideoMeta>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: VideoMeta): Int =
        value.frame.asAndroidBitmap().allocationByteCount.coerceAtLeast(1)
}

private val retrieverPool = Executors.newFixedThreadPool(3) { r ->
    Thread(r, "video-meta").apply { isDaemon = true }
}

/** Bounded wait for [block] on the retriever pool. The retriever's network path
 *  stalls indefinitely on some hosts and cannot be interrupted (withTimeoutOrNull
 *  cannot cancel a blocked native call); on timeout we stop waiting and the daemon
 *  thread releases the retriever whenever the call eventually returns. */
private fun <T> withRetrieverTimeout(timeoutMs: Long, block: () -> T): T? = try {
    retrieverPool.submit(Callable(block)).get(timeoutMs, TimeUnit.MILLISECONDS)
} catch (_: Exception) {
    null // timeout / rejection / interruption — treat as "no metadata available"
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** First frame + duration for a remote clip, disk-cached as JPEG (+ a `.dur` sidecar)
 *  so app restarts don't refetch. Null when the host/container defeats the retriever —
 *  the caller keeps the plain card. */
private fun loadVideoMeta(dir: File, url: String): VideoMeta? = runCatching {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching null
    val key = Integer.toHexString(url.hashCode())
    val jpg = File(dir, "$key.jpg")
    val dur = File(dir, "$key.dur")
    if (jpg.exists()) {
        BitmapFactory.decodeFile(jpg.absolutePath)?.let { cached ->
            val durationMs = runCatching { dur.readText().toLong() }.getOrDefault(0L)
            return@runCatching VideoMeta(cached.asImageBitmap(), durationMs)
        }
    }
    withRetrieverTimeout(12_000) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, mapOf("User-Agent" to Config.BROWSER_UA))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = retriever.frameAtTime ?: return@withRetrieverTimeout null
            runCatching {
                jpg.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                dur.writeText(durationMs.toString())
            }
            VideoMeta(frame.asImageBitmap(), durationMs)
        } finally {
            runCatching { retriever.release() }
        }
    }
}.getOrNull()

/** Fullscreen ExoPlayer dialog for a remote video clip; shared by inline messages,
 *  the media wall, and composer previews. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoViewerDialog(url: String, onClose: () -> Unit) {
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
                            if (!url.startsWith("http://") && !url.startsWith("https://")) return@runCatching
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

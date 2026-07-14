package mk.ry.redollars.ui.render

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import java.nio.ByteBuffer
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
    private var prepared = false
    private var loadJob: Job? = null

    /** Probed fallback durations (ms) by source, for containers MediaPlayer can't
     *  measure — Chrome's MediaRecorder emits WebM with no duration header. */
    private val probedDurations = HashMap<String, Int>()

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    /** True from toggle() until the clip is prepared and audible. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun toggle(source: String) {
        if (_nowPlaying.value == source) {
            stop()
            return
        }
        stop()
        _nowPlaying.value = source // reflects "loading" until the clip actually starts
        _loading.value = true
        loadJob = scope.launch {
            val local = resolveLocal(source)
            if (local == null || _nowPlaying.value != source) {
                if (_nowPlaying.value == source) stop()
                return@launch
            }
            val probed =
                if (probedDurations.containsKey(source)) null
                else withContext(Dispatchers.IO) { probeDurationMs(local) }
            withContext(Dispatchers.Main) {
                if (probed != null) probedDurations[source] = probed
                if (_nowPlaying.value == source) startLocal(source, local)
            }
        }
    }

    /** A local path passes through; a remote URL is cached (once) and its path returned. */
    private suspend fun resolveLocal(source: String): String? {
        if (source.startsWith("/")) return source
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = cacheFile(source)
                if (!file.exists() || file.length() == 0L) {
                    val req = Request.Builder()
                        .url(source)
                        .header("User-Agent", Config.USER_AGENT)
                        .build()
                    http.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@runCatching null
                        // Download to a private temp file and rename into place once
                        // complete: a cancelled/failed attempt must never leave a
                        // half-written cache entry for the next click to choke on.
                        val tmp = File.createTempFile("dl-", ".part", cacheDir)
                        try {
                            res.body?.byteStream()?.use { input ->
                                tmp.outputStream().use { input.copyTo(it) }
                            }
                            if (tmp.length() == 0L || !tmp.renameTo(file)) return@runCatching null
                        } finally {
                            tmp.delete()
                        }
                    }
                }
                if (file.length() > 0L) {
                    repairSeekability(file)
                    file.absolutePath
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    private fun cacheFile(source: String) =
        File(cacheDir, Integer.toHexString(source.hashCode()) + ".m4a")

    /** Container-reported duration in ms; 0 when the header carries none. */
    private fun containerDurationMs(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Chrome's MediaRecorder emits WebM with no duration header and no cue index;
     *  native MediaPlayer reports such files as duration 0 and clamps every seek to
     *  "past end of file". Losslessly remux those downloads (MediaExtractor ->
     *  MediaMuxer, no re-encode) so the cached copy gains a duration and seek index.
     *  Files whose container already reports a duration are left untouched. */
    private fun repairSeekability(file: File) {
        if (containerDurationMs(file.absolutePath) > 0) return
        val mime = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                    .firstOrNull { it.startsWith("audio/") }
            } finally {
                extractor.release()
            }
        }.getOrNull() ?: return
        val containers = buildList {
            if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS && Build.VERSION.SDK_INT >= 29) {
                add(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_OPUS ||
                mime == MediaFormat.MIMETYPE_AUDIO_VORBIS
            ) {
                add(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
            }
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                add(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }
        }
        for (container in containers) {
            val out = File.createTempFile("fix-", ".tmp", cacheDir)
            val ok = runCatching { remux(file, out, container) }.getOrDefault(false) &&
                containerDurationMs(out.absolutePath) > 0
            if (ok && out.renameTo(file)) return
            out.delete()
        }
    }

    /** Copy the first audio track of [src] into [container] at [out], sample by sample. */
    private fun remux(src: File, out: File, container: Int): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(src.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return false
            val m = MediaMuxer(out.absolutePath, container)
            muxer = m
            val dst = m.addTrack(extractor.getTrackFormat(track))
            m.start()
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(512 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags =
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                info.set(0, size, extractor.sampleTime, flags)
                m.writeSampleData(dst, buffer, info)
                if (!extractor.advance()) break
            }
            m.stop()
            return true
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Duration of a local clip via container metadata, else by walking the samples
     *  (needed for duration-less WebM). Runs on the load coroutine, off the main thread. */
    private fun probeDurationMs(path: String): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val ms = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (ms > 0) return ms.toInt()
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return 0
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val us = format.getLong(MediaFormat.KEY_DURATION)
                if (us > 0) return (us / 1000).toInt()
            }
            extractor.selectTrack(track)
            var lastUs = 0L
            while (extractor.sampleTime >= 0) {
                lastUs = extractor.sampleTime
                if (!extractor.advance()) break
            }
            return (lastUs / 1000).toInt()
        } catch (_: Exception) {
            return 0
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun startLocal(source: String, path: String) {
        prepared = false
        runCatching {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(path)
            p.setOnPreparedListener {
                if (it === player) {
                    prepared = true
                    _loading.value = false
                    it.start()
                }
            }
            p.setOnCompletionListener { if (it === player) stop() }
            p.setOnErrorListener { mp, _, _ ->
                if (mp === player) {
                    // A cached download that won't decode is corrupt: evict it so the
                    // next tap re-downloads instead of failing forever.
                    if (!source.startsWith("/")) cacheFile(source).delete()
                    stop()
                }
                true
            }
            p.prepareAsync()
            player = p
        }.onFailure { stop() }
    }

    fun stop() {
        loadJob?.cancel()
        loadJob = null
        player?.let { p ->
            runCatching { if (prepared) p.stop() }
            runCatching { p.release() }
        }
        player = null
        prepared = false
        _loading.value = false
        _nowPlaying.value = null
    }

    /** Current position/duration in ms; zeros until prepared. Never queries the
     *  MediaPlayer before it is prepared — that raises error(-38) and kills playback. */
    fun positionMs(): Int =
        if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0

    fun durationMs(): Int {
        val reported =
            if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0) else 0
        if (reported > 0) return reported
        return _nowPlaying.value?.let { probedDurations[it] } ?: 0
    }

    /** Seek within the currently playing clip; no-op until prepared. */
    fun seekTo(fraction: Float) {
        if (!prepared) return
        runCatching {
            val p = player ?: return
            val duration = durationMs()
            if (duration > 0) p.seekTo((duration * fraction.coerceIn(0f, 1f)).toInt())
        }
    }
}

private fun formatMs(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** In-bubble audio player for `[audio]url[/audio]` (voice messages): play/stop,
 *  a draggable seek bar, and an elapsed/total timer while playing. */
@Composable
fun AudioBlockView(url: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val player = LocalAudioPlayer.current
    val nowPlaying by (player?.nowPlaying ?: MutableStateFlow(null)).collectAsState()
    val loading by (player?.loading ?: MutableStateFlow(false)).collectAsState()
    val playing = nowPlaying == url
    var progress by remember { mutableFloatStateOf(0f) }
    var positionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing) {
        if (!playing || player == null) {
            progress = 0f
            positionMs = 0
            return@LaunchedEffect
        }
        while (true) {
            durationMs = player.durationMs()
            positionMs = player.positionMs()
            if (!dragging) {
                progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            }
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
            when {
                playing && loading -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                playing -> Box(
                    Modifier
                        .size(10.dp)
                        .background(cs.primary, RoundedCornerShape(2.dp)),
                )
                else ->
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play voice message")
            }
        }
        Slider(
            value = if (dragging) dragValue else progress,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player?.seekTo(dragValue)
                progress = dragValue
                dragging = false
            },
            enabled = playing && !loading,
            modifier = Modifier.padding(horizontal = 4.dp).width(132.dp),
        )
        Text(
            text = when {
                playing && loading -> "加载中…"
                playing && dragging && durationMs > 0 ->
                    "${formatMs((dragValue * durationMs).toInt())} / ${formatMs(durationMs)}"
                playing -> "${formatMs(positionMs)} / ${formatMs(durationMs)}"
                else -> "语音"
            },
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
        )
    }
}

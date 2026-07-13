package mk.ry.redollars.bmo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import mk.ry.redollars.di.ApplicationScope
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.Config
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Provided at the app root so BBCode inline content can render BMO codes. */
val LocalBmoRenderer = staticCompositionLocalOf<BmoRenderer?> { null }

/**
 * Native port of bmo.js rendering: decode a `(bmoC…)` code, fetch the referenced
 * part PNGs from the Bangumi site (disk-cached; browser UA for Cloudflare), and
 * composite them at 63×63 — per layer: center-translate + rotate + flip-scale,
 * stretch to the full canvas without filtering (pixel art), per-pixel HSL shift,
 * then stack in (layer, category, order) order.
 */
@Singleton
class BmoRenderer @Inject constructor(
    private val http: OkHttpClient,
    @param:ApplicationContext context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val cacheDir = File(context.cacheDir, "bmo").apply { mkdirs() }

    private val renders = LruCache<String, ImageBitmap>(256)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val parts = ConcurrentHashMap<String, Bitmap>()

    @Volatile private var table: List<BmoCodec.Meta>? = null
    private val tableMutex = Mutex()

    /** Render a compact BMO code; null when the code (or an asset) is unresolvable. */
    suspend fun render(code: String): ImageBitmap? {
        renders.get(code)?.let { return it }
        val task = inFlight.computeIfAbsent(code) {
            scope.async(Dispatchers.IO) {
                try {
                    doRender(code)?.also { renders.put(code, it) }
                } finally {
                    inFlight.remove(code)
                }
            }
        }
        return runCatching { task.await() }.getOrNull()
    }

    private suspend fun doRender(code: String): ImageBitmap? {
        val meta = loadTable() ?: return null
        val items = BmoCodec.decode(code, meta) ?: return null
        if (items.isEmpty()) return null

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply { isFilterBitmap = false }
        val full = RectF(-SIZE / 2f, -SIZE / 2f, SIZE / 2f, SIZE / 2f)

        for (item in items) {
            val part = loadPart(item.meta.src) ?: return null
            val temp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            val tc = Canvas(temp)
            tc.save()
            tc.translate(SIZE / 2f + item.dx, SIZE / 2f + item.dy)
            if (item.rotation != 0f) tc.rotate(item.rotation)
            tc.scale(
                item.scaleX * (if (item.flipH) -1f else 1f),
                item.scaleY * (if (item.flipV) -1f else 1f),
            )
            tc.drawBitmap(part, null, full, paint)
            tc.restore()
            if (item.hue != 0 || item.light != 0 || item.sat != 0) {
                applyHsl(temp, item.hue, item.light, item.sat)
            }
            canvas.drawBitmap(temp, 0f, 0f, null)
        }
        return out.asImageBitmap()
    }

    // ---- Manifest table (memory -> disk -> network) ----

    private suspend fun loadTable(): List<BmoCodec.Meta>? {
        table?.let { return it }
        return tableMutex.withLock {
            table?.let { return it }
            val file = File(cacheDir, "manifest.json")
            val text = fetch(Config.BMO_MANIFEST_URL)
                ?.also { runCatching { file.writeText(it) } }
                ?: runCatching { file.takeIf { f -> f.exists() }?.readText() }.getOrNull()
                ?: return null
            val manifest =
                runCatching { AppJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: return null
            BmoCodec.buildTable(manifest).also { table = it }
        }
    }

    // ---- Part bitmaps (memory -> disk -> network) ----

    private fun loadPart(src: String): Bitmap? {
        parts[src]?.let { return it }
        val file = File(cacheDir, src.substringAfterLast('/'))
        val bytes = runCatching { file.takeIf { it.exists() }?.readBytes() }.getOrNull()
            ?: fetchBytes(Config.BMO_BASE_URL + src)
                ?.also { runCatching { file.writeBytes(it) } }
            ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        parts[src] = bmp
        return bmp
    }

    private fun fetch(url: String): String? = fetchBytes(url)?.decodeToString()

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Config.BROWSER_UA)
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) null else res.body?.bytes()
        }
    }.getOrNull()

    // ---- Per-pixel HSL shift (bmo.js applyColorAdjust; h 0..360, s/l 0..100) ----

    private fun applyHsl(bmp: Bitmap, hue: Int, light: Int, sat: Int) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val hsl = rgbToHsl((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            val hh = (hsl[0] + hue + 360f) % 360f
            val ss = (hsl[1] + sat).coerceIn(0f, 100f)
            val ll = (hsl[2] + light).coerceIn(0f, 100f)
            val rgb = hslToRgb(hh, ss, ll)
            px[i] = (a shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun rgbToHsl(r255: Int, g255: Int, b255: Int): FloatArray {
        val r = r255 / 255f
        val g = g255 / 255f
        val b = b255 / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        var h = 0f
        var s = 0f
        val l = (max + min) / 2f
        if (max != min) {
            val d = max - min
            s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
            h = when (max) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
        }
        return floatArrayOf(h * 360f, s * 100f, l * 100f)
    }

    private fun hue2rgb(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun hslToRgb(h360: Float, s100: Float, l100: Float): IntArray {
        val h = h360 / 360f
        val s = s100 / 100f
        val l = l100 / 100f
        val r: Float
        val g: Float
        val b: Float
        if (s == 0f) {
            r = l
            g = l
            b = l
        } else {
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            r = hue2rgb(p, q, h + 1f / 3f)
            g = hue2rgb(p, q, h)
            b = hue2rgb(p, q, h - 1f / 3f)
        }
        return intArrayOf(
            Math.round(r * 255f).coerceIn(0, 255),
            Math.round(g * 255f).coerceIn(0, 255),
            Math.round(b * 255f).coerceIn(0, 255),
        )
    }

    private companion object {
        const val SIZE = 63 // bmo.js DEFAULT_WIDTH/HEIGHT
    }
}

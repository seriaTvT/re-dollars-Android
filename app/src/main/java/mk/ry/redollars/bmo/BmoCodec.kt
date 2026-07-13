package mk.ry.redollars.bmo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import mk.ry.redollars.net.AppJson

/**
 * Pure-Kotlin port of the site's bmo.js compact codec. A BMO emoji code like
 * `(bmoC<base64url>)` is a varint stream of layered parts: per item one varuint
 * whose high bits are the compact id (index into the manifest-derived [Meta] table)
 * and low 7 bits are presence flags for the modifier varints that follow.
 */
object BmoCodec {

    /** One manifest part, indexed by compact id. */
    data class Meta(val src: String, val layer: Int, val order: Int, val category: String)

    /** One decoded, render-ready layer. */
    data class Item(
        val meta: Meta,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        val rotation: Float = 0f,
        val hue: Int = 0,
        val light: Int = 0,
        val sat: Int = 0,
        val dx: Float = 0f,
        val dy: Float = 0f,
        val scaleX: Float = 1f,
        val scaleY: Float = 1f,
    )

    private const val FLAG_TF = 1
    private const val FLAG_H = 2
    private const val FLAG_L = 4
    private const val FLAG_S = 8
    private const val FLAG_X = 16
    private const val FLAG_Y = 32
    private const val FLAG_EXTRA = 64

    /**
     * Build the compact-id table from the manifest (bmo.js setAssets): iterate
     * categories in JSON order, bucket items by version, then assign sequential
     * ids version-ascending, first occurrence of each codeId wins.
     */
    fun buildTable(manifest: JsonObject): List<Meta> {
        data class Entry(val meta: Meta, val primaryKey: String, val version: Int)

        val entries = mutableListOf<Entry>()
        for ((key, catEl) in manifest) {
            val cat = catEl as? JsonObject ?: continue
            val items = cat["items"] as? JsonArray ?: continue
            val layerBase = (cat["layer"] as? JsonPrimitive)?.intOrNull ?: 0
            val categoryCode = (cat["id"] as? JsonPrimitive)?.contentOrNull ?: key
            items.forEachIndexed { j, itemEl ->
                val item = itemEl as? JsonObject ?: return@forEachIndexed
                val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                val src = (item["src"] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
                val custom = (item["custom"] as? JsonPrimitive)?.booleanOrNull ?: false
                val codeId =
                    if (itemId.isNotEmpty() && itemId.all { it.isDigit() } && !custom) categoryCode + itemId
                    else itemId
                val versionRaw = (item["version"] as? JsonPrimitive)?.doubleOrNull ?: 1.0
                entries += Entry(
                    meta = Meta(
                        src = "/js/lib/bmo/" + src.removePrefix("./"),
                        layer = (item["layer"] as? JsonPrimitive)?.intOrNull ?: layerBase,
                        order = (item["order"] as? JsonPrimitive)?.intOrNull ?: j,
                        category = key,
                    ),
                    primaryKey = codeId,
                    version = if (versionRaw.isFinite() && versionRaw > 0) versionRaw.toInt() else 1,
                )
            }
        }

        val seen = HashSet<String>()
        val table = ArrayList<Meta>(entries.size)
        entries.groupBy { it.version }.toSortedMap().values.forEach { bucket ->
            for (e in bucket) if (seen.add(e.primaryKey)) table.add(e.meta)
        }
        return table
    }

    /**
     * Decode a compact code into render-sorted layers (layer asc, category name,
     * order asc — bmo.js sortItems). Null = not a decodable compact code; the
     * caller should fall back to plain text. Legacy `(bmo_name)` codes are not
     * supported here.
     */
    fun decode(code: String, table: List<Meta>): List<Item>? {
        var t = code.trim()
        if (t.startsWith("(") && t.endsWith(")")) t = t.substring(1, t.length - 1)
        if (!t.startsWith("bmoC")) return null
        var payload = t.substring(4)
        if (payload.firstOrNull() == '_' || payload.firstOrNull() == ':' || payload.firstOrNull() == '-') {
            payload = payload.substring(1)
        }
        if (payload.isEmpty()) return emptyList()

        val bytes = decodeBase64Url(payload) ?: return null
        val reader = VarReader(bytes)
        val items = mutableListOf<Item>()

        while (reader.hasMore()) {
            val combined = reader.readVarUint() ?: return null
            val compactId = (combined ushr 7).toInt()
            val flags = (combined and 127L).toInt()
            val meta = table.getOrNull(compactId) ?: return null

            var flipH = false
            var flipV = false
            var rotation = 0f
            var hue = 0
            var light = 0
            var sat = 0
            var dx = 0f
            var dy = 0f
            var scaleX = 1f
            var scaleY = 1f

            if (flags and FLAG_TF != 0) {
                val mask = (reader.readVarUint() ?: return null).toInt() and 63
                flipH = mask and 1 != 0
                flipV = mask and 2 != 0
                rotation = (((mask shr 2) and 3) * 90).toFloat()
            }
            if (flags and FLAG_H != 0) hue = reader.readVarInt() ?: return null
            if (flags and FLAG_L != 0) light = reader.readVarInt() ?: return null
            if (flags and FLAG_S != 0) sat = reader.readVarInt() ?: return null
            if (flags and FLAG_X != 0) dx = (reader.readVarInt() ?: return null).toFloat()
            if (flags and FLAG_Y != 0) dy = (reader.readVarInt() ?: return null).toFloat()
            if (flags and FLAG_EXTRA != 0) {
                val len = (reader.readVarUint() ?: return null).toInt()
                val extra = reader.readBytes(len) ?: return null
                runCatching {
                    val obj = AppJson.parseToJsonElement(extra.decodeToString()) as? JsonObject
                    (obj?.get("rotate") as? JsonPrimitive)?.floatOrNull?.let { rotation = it }
                    (obj?.get("scale") as? JsonPrimitive)?.floatOrNull?.let { scaleX = it; scaleY = it }
                    (obj?.get("scaleX") as? JsonPrimitive)?.floatOrNull?.let { scaleX = it }
                    (obj?.get("scaleY") as? JsonPrimitive)?.floatOrNull?.let { scaleY = it }
                    (obj?.get("x") as? JsonPrimitive)?.floatOrNull?.let { dx = it }
                    (obj?.get("y") as? JsonPrimitive)?.floatOrNull?.let { dy = it }
                }
            }

            items += Item(meta, flipH, flipV, rotation, hue, light, sat, dx, dy, scaleX, scaleY)
        }

        return items.sortedWith(
            compareBy({ it.meta.layer }, { it.meta.category }, { it.meta.order }),
        )
    }

    // ---- LEB128 varints + zigzag (bmo.js createVarReader) ----

    private class VarReader(private val bytes: ByteArray) {
        private var offset = 0

        fun hasMore() = offset < bytes.size

        fun readVarUint(): Long? {
            var result = 0L
            var shift = 0
            while (offset < bytes.size) {
                val b = bytes[offset++].toInt() and 0xFF
                result = result or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return result
                shift += 7
                if (shift > 35) return null
            }
            return null
        }

        fun readVarInt(): Int? {
            val encoded = readVarUint() ?: return null
            return ((encoded ushr 1) xor -(encoded and 1L)).toInt()
        }

        fun readBytes(length: Int): ByteArray? {
            if (length < 0 || offset + length > bytes.size) return null
            val out = bytes.copyOfRange(offset, offset + length)
            offset += length
            return out
        }
    }

    private fun decodeBase64Url(str: String): ByteArray? {
        var base64 = str.replace('-', '+').replace('_', '/')
        while (base64.length % 4 != 0) base64 += "="
        return runCatching { java.util.Base64.getDecoder().decode(base64) }.getOrNull()
    }
}

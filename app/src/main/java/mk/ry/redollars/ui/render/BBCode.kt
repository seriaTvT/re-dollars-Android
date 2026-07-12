package mk.ry.redollars.ui.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import coil3.compose.AsyncImage
import mk.ry.redollars.bmo.LocalBmoRenderer
import mk.ry.redollars.net.ReplyDetails

// ---------------------------------------------------------------------------
// Block-level parsing
// ---------------------------------------------------------------------------

private sealed interface Block
private data class TextBlock(val text: String) : Block
private data class ImageBlock(val urls: List<String>) : Block
private data class QuoteBlock(val inner: String) : Block
private data class CodeBlock(val content: String) : Block
private data class LinkBlock(val label: String, val url: String) : Block
private data class AudioBlock(val url: String) : Block

private val BLOCK_REGEX = Regex(
    "\\[img\\]([\\s\\S]+?)\\[/img\\]" +                        // 1
        "|\\[quote(?:=(\\d+))?\\]([\\s\\S]*?)\\[/quote\\]" +   // 2 id, 3 body
        "|\\[code\\]([\\s\\S]*?)\\[/code\\]" +                 // 4
        "|\\[video\\]([\\s\\S]+?)\\[/video\\]" +               // 5
        "|\\[audio\\]([\\s\\S]+?)\\[/audio\\]" +               // 6
        "|\\[file=([^\\]]*)\\]([\\s\\S]+?)\\[/file\\]",        // 7 name, 8 url
    RegexOption.IGNORE_CASE,
)

private fun parseBlocks(message: String): List<Block> {
    val out = ArrayList<Block>()
    var cursor = 0

    fun addImage(url: String, gluedToPrev: Boolean) {
        val prev = out.lastOrNull()
        if (gluedToPrev && prev is ImageBlock) {
            out[out.size - 1] = ImageBlock(prev.urls + url.trim())
        } else {
            out.add(ImageBlock(listOf(url.trim())))
        }
    }

    for (m in BLOCK_REGEX.findAll(message)) {
        val between = message.substring(cursor, m.range.first)
        val betweenBlank = between.isBlank()
        if (!betweenBlank) out.add(TextBlock(between))
        cursor = m.range.last + 1

        val g = m.groups
        when {
            g[1] != null -> addImage(g[1]!!.value, betweenBlank)
            // Reply quotes are empty inline (content lives in reply_details, rendered
            // as a ReplyHeader), so only keep quotes that actually carry text.
            g[3] != null -> if (g[3]!!.value.isNotBlank()) out.add(QuoteBlock(g[3]!!.value))
            g[4] != null -> out.add(CodeBlock(g[4]!!.value))
            g[5] != null -> out.add(LinkBlock("[视频]", g[5]!!.value.trim()))
            g[6] != null -> out.add(AudioBlock(g[6]!!.value.trim()))
            g[8] != null -> out.add(LinkBlock(g[7]?.value?.trim().orEmpty().ifEmpty { "[附件]" }, g[8]!!.value.trim()))
        }
    }
    if (cursor < message.length) {
        val tail = message.substring(cursor)
        if (tail.isNotBlank()) out.add(TextBlock(tail))
    }
    if (out.isEmpty()) out.add(TextBlock(message))
    return out
}

private fun simplifyQuoteInner(s: String): String = s
    .replace(Regex("\\[img\\][\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "[图片]")
    .replace(Regex("\\[video\\][\\s\\S]*?\\[/video\\]", RegexOption.IGNORE_CASE), "[视频]")
    .replace(Regex("\\[audio\\][\\s\\S]*?\\[/audio\\]", RegexOption.IGNORE_CASE), "[音频]")
    .replace(Regex("\\[file=[^\\]]*\\][\\s\\S]*?\\[/file\\]", RegexOption.IGNORE_CASE), "[附件]")
    .trim()

// ---------------------------------------------------------------------------
// Inline parsing (styles, links, mentions, smilies)
// ---------------------------------------------------------------------------

private enum class St { BOLD, ITALIC, UNDERLINE, STRIKE, MASK, LINK }

private val INLINE_REGEX = Regex(
    "\\[b\\]([\\s\\S]*?)\\[/b\\]" +                              // 1
        "|\\[i\\]([\\s\\S]*?)\\[/i\\]" +                        // 2
        "|\\[u\\]([\\s\\S]*?)\\[/u\\]" +                        // 3
        "|\\[s\\]([\\s\\S]*?)\\[/s\\]" +                        // 4
        "|\\[mask\\]([\\s\\S]*?)\\[/mask\\]" +                  // 5
        "|\\[url=([^\\]]+?)\\]([\\s\\S]*?)\\[/url\\]" +         // 6 href, 7 label
        "|\\[user=([^\\]]+?)\\]([\\s\\S]*?)\\[/user\\]" +       // 8 uid, 9 name
        "|(\\[(?:emoji|sticker)\\][\\s\\S]+?\\[/(?:emoji|sticker)\\]" +
        "|\\((?:musume_|blake_)\\d+\\)|\\(bgm\\d+\\)|\\(bmo(?:C|_)[a-zA-Z0-9_-]+\\))" + // 10 token
        "|(https?://[^\\s<>\"'\\[\\]]+)",                        // 11 bare url
    RegexOption.IGNORE_CASE,
)

private class InlineResult(val annotated: AnnotatedString, val inline: Map<String, InlineTextContent>)

private fun spanFor(styles: Set<St>, linkColor: Color, maskBg: Color): SpanStyle? {
    if (styles.isEmpty()) return null
    val deco = buildList {
        if (St.UNDERLINE in styles) add(TextDecoration.Underline)
        if (St.STRIKE in styles) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (St.BOLD in styles) FontWeight.Bold else null,
        fontStyle = if (St.ITALIC in styles) FontStyle.Italic else null,
        textDecoration = if (deco.isEmpty()) null else TextDecoration.combine(deco),
        color = if (St.LINK in styles) linkColor else Color.Unspecified,
        background = if (St.MASK in styles) maskBg else Color.Unspecified,
    )
}

private fun smileyInline(src: String, large: Boolean): InlineTextContent {
    val size = if (large) 2.6.em else 1.5.em
    return InlineTextContent(Placeholder(size, size, PlaceholderVerticalAlign.TextCenter)) {
        AsyncImage(model = src, contentDescription = null, modifier = Modifier.fillMaxWidth())
    }
}

/** `[sticker]url[/sticker]` / `[emoji]url[/emoji]`: an arbitrary image rendered big
 *  (web caps .custom-emoji at 150px); tapping opens the lightbox. */
private val STICKER_TAG =
    Regex("""\[(?:emoji|sticker)\](.+?)\[/(?:emoji|sticker)\]""", RegexOption.IGNORE_CASE)

private fun stickerInline(src: String): InlineTextContent =
    InlineTextContent(Placeholder(6.em, 6.em, PlaceholderVerticalAlign.TextCenter)) {
        val openViewer = LocalImageViewer.current
        AsyncImage(
            model = src,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().clickable { openViewer(src) },
        )
    }

/** A compact `(bmoC…)` code composited natively by [LocalBmoRenderer]; the pixel-art
 *  bitmap is 63×63, drawn unfiltered at smiley size (web shows BMO at 21px). */
private fun bmoInline(code: String): InlineTextContent =
    InlineTextContent(Placeholder(1.5.em, 1.5.em, PlaceholderVerticalAlign.TextCenter)) {
        val renderer = LocalBmoRenderer.current
        val bitmap by produceState<ImageBitmap?>(null, code, renderer) {
            value = renderer?.render(code)
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = code,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

private fun buildInline(text: String, linkColor: Color, maskBg: Color): InlineResult {
    val inline = LinkedHashMap<String, InlineTextContent>()
    val counter = intArrayOf(0)

    fun appendStyled(b: AnnotatedString.Builder, chunk: String, styles: Set<St>) {
        if (chunk.isEmpty()) return
        val span = spanFor(styles, linkColor, maskBg)
        if (span == null) b.append(chunk) else b.withStyle(span) { append(chunk) }
    }

    fun walk(b: AnnotatedString.Builder, src: String, styles: Set<St>) {
        var i = 0
        for (m in INLINE_REGEX.findAll(src)) {
            if (m.range.first > i) appendStyled(b, src.substring(i, m.range.first), styles)
            val g = m.groups
            when {
                g[1] != null -> walk(b, g[1]!!.value, styles + St.BOLD)
                g[2] != null -> walk(b, g[2]!!.value, styles + St.ITALIC)
                g[3] != null -> walk(b, g[3]!!.value, styles + St.UNDERLINE)
                g[4] != null -> walk(b, g[4]!!.value, styles + St.STRIKE)
                g[5] != null -> walk(b, g[5]!!.value, styles + St.MASK)
                g[7] != null -> b.withLink(LinkAnnotation.Url(g[6]!!.value)) {
                    walk(this, g[7]!!.value, styles + St.LINK)
                }
                g[9] != null -> b.withLink(LinkAnnotation.Url("https://bgm.tv/user/${g[8]!!.value}")) {
                    appendStyled(this, "@" + g[9]!!.value, styles + St.LINK)
                }
                g[10] != null -> {
                    val raw = g[10]!!.value
                    val stickerSrc = STICKER_TAG.find(raw)?.groupValues?.get(1)?.trim()
                    val smileySrc = Smilies.urlFor(raw)
                    when {
                        stickerSrc != null && stickerSrc.startsWith("http") -> {
                            val key = "sm${counter[0]++}"
                            inline[key] = stickerInline(stickerSrc)
                            b.appendInlineContent(key, raw)
                        }
                        raw.startsWith("(bmoC") -> {
                            val key = "sm${counter[0]++}"
                            inline[key] = bmoInline(raw)
                            b.appendInlineContent(key, raw)
                        }
                        smileySrc != null -> {
                            val key = "sm${counter[0]++}"
                            val large = raw.startsWith("(musume_") || raw.startsWith("(blake_")
                            inline[key] = smileyInline(smileySrc, large)
                            b.appendInlineContent(key, raw)
                        }
                        // Legacy (bmo_name) codes stay as text.
                        else -> appendStyled(b, raw, styles)
                    }
                }
                g[11] != null -> b.withLink(LinkAnnotation.Url(g[11]!!.value)) {
                    appendStyled(this, g[11]!!.value, styles + St.LINK)
                }
            }
            i = m.range.last + 1
        }
        if (i < src.length) appendStyled(b, src.substring(i), styles)
    }

    val anno = buildAnnotatedString { walk(this, text, emptySet()) }
    return InlineResult(anno, inline)
}

// ---------------------------------------------------------------------------
// Composables
// ---------------------------------------------------------------------------

@Composable
fun BBCodeMessage(message: String, modifier: Modifier = Modifier) {
    val blocks = remember(message) { parseBlocks(message) }
    // Only pure-text messages collapse (shouldCollapseMessage: no media/quote/code).
    val loneText = blocks.singleOrNull() as? TextBlock
    if (loneText != null) {
        Column(modifier) { CollapsibleInlineText(loneText.text) }
        return
    }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is TextBlock -> InlineText(block.text)
                is ImageBlock -> ImageStack(block.urls)
                is QuoteBlock -> QuoteView(block.inner)
                is CodeBlock -> CodeView(block.content)
                is LinkBlock -> LinkLine(block.label, block.url)
                is AudioBlock -> AudioBlockView(block.url)
            }
        }
    }
}

@Composable
private fun InlineText(
    text: String,
    maxLines: Int = Int.MAX_VALUE,
    onOverflow: (Boolean) -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val result = remember(text, cs.primary, cs.surfaceVariant) {
        buildInline(text, linkColor = cs.primary, maskBg = cs.surfaceVariant)
    }
    Text(
        text = result.annotated,
        inlineContent = result.inline,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = maxLines,
        onTextLayout = { onOverflow(it.hasVisualOverflow) },
    )
}

/** Text-only messages collapse past ~15 lines (COLLAPSE_MAX_HEIGHT ≈ 300px on the
 *  web) with a 展开/收起 toggle; media/quote/code messages never collapse. */
private const val COLLAPSE_LINES = 15

@Composable
private fun CollapsibleInlineText(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflowed by remember(text) { mutableStateOf(false) }
    Column {
        InlineText(
            text = text,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSE_LINES,
            onOverflow = { if (!expanded) overflowed = it },
        )
        if (overflowed || expanded) {
            Text(
                text = if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(top = 4.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun ImageStack(urls: List<String>) {
    val openViewer = LocalImageViewer.current
    Column(Modifier.padding(vertical = 4.dp)) {
        urls.forEach { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .sizeIn(maxWidth = 240.dp, maxHeight = 280.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { openViewer(url) },
            )
        }
    }
}

@Composable
private fun QuoteView(inner: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceVariant),
    ) {
        Row(Modifier.width(3.dp).fillMaxHeight().background(cs.primary)) {}
        Column(Modifier.padding(8.dp)) {
            InlineText(simplifyQuoteInner(inner))
        }
    }
}

@Composable
private fun CodeView(content: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceVariant)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = content,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun LinkLine(label: String, url: String) {
    val cs = MaterialTheme.colorScheme
    val anno = remember(label, url, cs.primary) {
        buildAnnotatedString {
            withLink(LinkAnnotation.Url(url)) {
                withStyle(SpanStyle(color = cs.primary)) { append(label) }
            }
        }
    }
    Text(anno, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
}

/** One-line preview of quoted content: media -> placeholders, other bbcode stripped. */
private fun previewText(content: String): String = content
    .replace(Regex("\\[img\\][\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "[图片]")
    .replace(Regex("\\[video\\][\\s\\S]*?\\[/video\\]", RegexOption.IGNORE_CASE), "[视频]")
    .replace(Regex("\\[audio\\][\\s\\S]*?\\[/audio\\]", RegexOption.IGNORE_CASE), "[音频]")
    .replace(Regex("\\[file=[^\\]]*\\][\\s\\S]*?\\[/file\\]", RegexOption.IGNORE_CASE), "[附件]")
    .replace(Regex("\\[/?[a-zA-Z][^\\]]*\\]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(80)

/** Compact reply preview rendered from reply_details (mirrors the userscript). */
@Composable
fun ReplyHeader(reply: ReplyDetails, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceVariant)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(3.dp).fillMaxHeight().background(cs.primary)) {}
        val thumb = reply.firstImage
        if (!thumb.isNullOrBlank()) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 6.dp).size(28.dp).clip(RoundedCornerShape(4.dp)),
            )
        } else {
            AsyncImage(
                model = avatarUrl(reply.avatar, 's'),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 6.dp).size(20.dp).clip(CircleShape),
            )
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                text = reply.nickname,
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = previewText(reply.content),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

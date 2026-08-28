package mk.ry.redollars.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import mk.ry.redollars.data.DisplayPrefs
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.ui.render.BBCodeMessage
import mk.ry.redollars.ui.render.LocalAutoLoadMedia
import mk.ry.redollars.ui.render.LocalBubbleLongPress
import mk.ry.redollars.ui.render.LocalBubuScale
import mk.ry.redollars.ui.render.ReplyHeader
import mk.ry.redollars.ui.render.Smilies
import mk.ry.redollars.ui.render.avatarUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private val AVATAR = 34.dp

/**
 * A chat bubble. [isOwn] flips it to the right with a primary tint. [firstInGroup] /
 * [lastInGroup] mark the ends of a run of consecutive messages from the same author:
 * the avatar + name show only at the top and the timestamp only at the bottom, and the
 * bubble corners on the author's side are squared off to read as one group.
 */
/** Quick-reaction choices for the long-press menu (userscript CONTEXT_MENU_REACTIONS). */
private val QUICK_REACTIONS = listOf(67, 63, 38, 124, 46, 106).map { "(bgm$it)" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    m: MessageDto,
    isOwn: Boolean,
    firstInGroup: Boolean,
    lastInGroup: Boolean,
    ownUid: Long? = null,
    canModify: Boolean = false,
    online: Boolean = false,
    prefs: DisplayPrefs = DisplayPrefs(),
    onShowProfile: (Long) -> Unit = {},
    onMention: () -> Unit = {},
    onReact: (String) -> Unit = {},
    onReply: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onJumpTo: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.80f).dp
    val nameColor = remember(m.color) {
        runCatching { Color(android.graphics.Color.parseColor(m.color)) }.getOrNull()
    }

    // Own messages mirror to the left edge when align-own-right is off. Every
    // side-aware choice below (arrangement, corners, avatar/name placement,
    // reply-swipe direction) keys off this visual side instead of isOwn.
    val endAligned = isOwn && prefs.alignOwnRight
    /** Avatar rendered beside this bubble (outside edge of its side). */
    val showAvatar = if (isOwn) prefs.showOwnAvatar else prefs.showOtherAvatars

    // Swipe-to-reply (useSwipeToReply.ts): drag the bubble away from its screen edge with
    // elastic resistance; past the threshold, releasing starts a reply. Left-side bubbles
    // drag right; right-side bubbles (own by default) drag left.
    val swipeOffset = remember(m.id) { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val swipeTriggerPx = with(LocalDensity.current) { 40.dp.toPx() }
    val swipeDir = if (endAligned) -1f else 1f
    val swipeModifier = if (!m.isDeleted) {
        Modifier.pointerInput(m.id, endAligned) {
            val maxPull = 60.dp.toPx()
            val ease = 90.dp.toPx()
            val touchSlop = viewConfiguration.touchSlop
            // The bubble only claims a gesture when horizontal intent is unambiguous:
            // 1.5× plain touch slop of horizontal travel AND 2:1 over any vertical
            // drift. detectHorizontalDragGestures claimed on bare slop, so a list
            // scroll with slight sideways jitter crossed horizontal slop first and
            // stole (and killed) the vertical page swipe. The dominance ratio is what
            // protects scrolls — a genuine reply drag blows past it, scroll jitter
            // never does — so the distance bar stays low enough to feel responsive.
            val claimSlop = touchSlop * 1.5f
            val dominance = 2f
            var raw = 0f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                raw = 0f
                var dx = 0f
                var dy = 0f
                var claimed = false
                var cancelled = false
                var last = down.position
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        ?: break
                    if (!change.pressed) break
                    val delta = change.position - last
                    last = change.position
                    if (!claimed) {
                        if (change.isConsumed) break // scroll/another gesture took over
                        dx += delta.x
                        dy += delta.y
                        if (abs(dy) > touchSlop) break // vertical intent: leave it to the list
                        if (abs(dx) > claimSlop && abs(dx) > abs(dy) * dominance) claimed = true
                    } else {
                        if (change.isConsumed) {
                            cancelled = true
                            break
                        }
                        raw += delta.x
                        val pulled = (raw * swipeDir).coerceAtLeast(0f)
                        val eased = maxPull * (1f - exp(-pulled / ease))
                        if (eased != swipeOffset.value) {
                            change.consume()
                            swipeScope.launch { swipeOffset.snapTo(eased) }
                        }
                    }
                }
                if (claimed) {
                    val hit = !cancelled && swipeOffset.value >= swipeTriggerPx
                    swipeScope.launch {
                        swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                        if (hit) onReply()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    Box(modifier.fillMaxWidth()) {
        val swipeProgress = (swipeOffset.value / swipeTriggerPx).coerceIn(0f, 1f)
        if (swipeProgress > 0f) {
            Surface(
                shape = CircleShape,
                color = cs.primaryContainer,
                modifier = Modifier
                    .align(if (endAligned) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 10.dp)
                    .size(28.dp)
                    .graphicsLayer {
                        alpha = swipeProgress
                        scaleX = 0.5f + 0.5f * swipeProgress
                        scaleY = 0.5f + 0.5f * swipeProgress
                    },
            ) {
                Icon(
                    if (endAligned) Icons.AutoMirrored.Filled.ArrowForward
                    else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Reply",
                    tint = cs.onPrimaryContainer,
                    modifier = Modifier.padding(5.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = if (firstInGroup) 8.dp else 2.dp)
                .offset { IntOffset((swipeOffset.value * swipeDir).roundToInt(), 0) }
                .then(swipeModifier),
            horizontalArrangement = if (endAligned) Arrangement.End else Arrangement.Start,
        ) {
            // Avatar column hugs the outer edge of the row's side: leading for
            // start-aligned rows, trailing for own right-aligned rows. When hidden by
            // prefs the whole gutter drops so that side keeps a uniform bubble edge.
            if (showAvatar && !endAligned) {
                MetaGutter(m, firstInGroup, online = !isOwn && online, onShowProfile, onMention)
                Spacer(Modifier.width(8.dp))
            }

            Column(
                horizontalAlignment = if (endAligned) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = maxBubble),
            ) {
                if (firstInGroup && (!isOwn || prefs.showOwnName)) {
                    Text(
                        text = m.nickname.ifBlank { "uid ${m.uid}" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = nameColor ?: cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 2.dp),
                    )
                }

                var showQuickReact by remember { mutableStateOf(false) }
                var showDeleteConfirm by remember { mutableStateOf(false) }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete message?") },
                        text = { Text("This deletes the message for everyone.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showDeleteConfirm = false
                                    onDelete()
                                },
                            ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                        },
                    )
                }

                Box {
                    Surface(
                        color = if (isOwn) cs.primaryContainer else cs.surfaceVariant,
                        contentColor = if (isOwn) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        shape = bubbleShape(endAligned, firstInGroup, lastInGroup),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { if (!m.isDeleted) showQuickReact = true },
                        ),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            if (m.isDeleted) {
                                Text(
                                    text = "(deleted)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = FontStyle.Italic,
                                )
                            } else {
                                m.replyDetails?.let { reply ->
                                    ReplyHeader(
                                        reply,
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp)
                                            .then(
                                                m.replyToId?.let { target ->
                                                    Modifier.clickable { onJumpTo(target) }
                                                } ?: Modifier,
                                            ),
                                    )
                                }
                                // Media swallows gestures, so long-press on an image/sticker
                                // never reaches the bubble; forward it to the same menu.
                                // Media previews are gated by the display settings too.
                                CompositionLocalProvider(
                                    LocalBubbleLongPress provides { showQuickReact = true },
                                    LocalAutoLoadMedia provides prefs.autoLoadMediaPreviews,
                                    LocalBubuScale provides prefs.bubuScale,
                                ) {
                                    BBCodeMessage(m.message)
                                }
                            }
                        }
                    }

                    var showFullPicker by remember { mutableStateOf(false) }
                    DropdownMenu(
                        expanded = showQuickReact,
                        onDismissRequest = {
                            showQuickReact = false
                            showFullPicker = false
                        },
                    ) {
                        if (!showFullPicker) {
                            if (prefs.showQuickReactions) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    QUICK_REACTIONS.forEach { code ->
                                        AsyncImage(
                                            model = Smilies.urlFor(code),
                                            contentDescription = code,
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp)
                                                .size(28.dp)
                                                .clickable {
                                                    showQuickReact = false
                                                    onReact(code)
                                                },
                                        )
                                    }
                                    IconButton(onClick = { showFullPicker = true }) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "More reactions",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                            DropdownMenuItem(
                                text = { Text("Reply") },
                                onClick = {
                                    showQuickReact = false
                                    onReply()
                                },
                            )
                            val clipboard = LocalClipboardManager.current
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = {
                                    showQuickReact = false
                                    clipboard.setText(AnnotatedString(m.message))
                                },
                            )
                            if (isOwn && canModify) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = {
                                        showQuickReact = false
                                        onEdit()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showQuickReact = false
                                        showDeleteConfirm = true
                                    },
                                )
                            }
                        } else {
                            ReactionPicker(
                                onPick = { code ->
                                    showQuickReact = false
                                    showFullPicker = false
                                    onReact(code)
                                },
                            )
                        }
                    }
                }

                ReactionChips(m.reactions, ownUid, onToggle = onReact)

                if (lastInGroup) {
                    Text(
                        text = timeFmt.format(Instant.ofEpochSecond(m.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }

            if (showAvatar && endAligned) {
                Spacer(Modifier.width(8.dp))
                MetaGutter(m, firstInGroup, online = false, onShowProfile, onMention)
            }
        }
    }
}

private fun bubbleShape(alignEnd: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
    val big = 16.dp
    val small = 5.dp
    return if (alignEnd) {
        RoundedCornerShape(
            topStart = big,
            topEnd = if (first) big else small,
            bottomEnd = if (last) big else small,
            bottomStart = big,
        )
    } else {
        RoundedCornerShape(
            topStart = if (first) big else small,
            topEnd = big,
            bottomEnd = big,
            bottomStart = if (last) big else small,
        )
    }
}

/** The author's avatar for a first-of-group row, or an empty spacer so later rows stay
 *  aligned with their group. Used on either side of the bubble (see [endAligned] in
 *  [MessageRow]); profile tap and long-press mention come along with it. */
@Composable
private fun MetaGutter(
    m: MessageDto,
    firstInGroup: Boolean,
    online: Boolean,
    onShowProfile: (Long) -> Unit,
    onMention: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (firstInGroup) {
        Box {
            AsyncImage(
                // 'l' (not 's'): a 48px small avatar upscaled into the 34dp circle looks
                // blurry on hi-DPI screens; the large source stays crisp and Coil
                // downscales it once.
                model = avatarUrl(m.avatar, 'l'),
                contentDescription = "用户头像 ${m.nickname}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(AVATAR)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { onShowProfile(m.uid) },
                        // Long-press to @mention the author in the composer.
                        onLongClick = onMention,
                    ),
            )
            if (online) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(cs.surface)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF34C759)),
                )
            }
        }
    } else {
        Spacer(Modifier.width(AVATAR))
    }
}

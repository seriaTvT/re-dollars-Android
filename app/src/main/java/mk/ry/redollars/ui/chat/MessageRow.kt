package mk.ry.redollars.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.ui.render.BBCodeMessage
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

    // Swipe-to-reply (useSwipeToReply.ts): drag the row right with elastic resistance;
    // past the threshold, releasing starts a reply. The indicator scales/fades in behind.
    val swipeOffset = remember(m.id) { Animatable(0f) }
    val swipeScope = rememberCoroutineScope()
    val swipeTriggerPx = with(LocalDensity.current) { 40.dp.toPx() }
    val swipeModifier = if (!m.isDeleted) {
        Modifier.pointerInput(m.id) {
            val maxPull = 60.dp.toPx()
            val ease = 150.dp.toPx()
            var raw = 0f
            detectHorizontalDragGestures(
                onDragStart = { raw = 0f },
                onDragCancel = { swipeScope.launch { swipeOffset.animateTo(0f, spring()) } },
                onDragEnd = {
                    val hit = swipeOffset.value >= swipeTriggerPx
                    swipeScope.launch {
                        swipeOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                    }
                    if (hit) onReply()
                },
            ) { change, delta ->
                raw = (raw + delta).coerceAtLeast(0f)
                val eased = maxPull * (1f - exp(-raw / ease))
                if (eased != swipeOffset.value) {
                    change.consume()
                    swipeScope.launch { swipeOffset.snapTo(eased) }
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
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .size(28.dp)
                    .graphicsLayer {
                        alpha = swipeProgress
                        scaleX = 0.5f + 0.5f * swipeProgress
                        scaleY = 0.5f + 0.5f * swipeProgress
                    },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
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
                .offset { IntOffset(swipeOffset.value.roundToInt(), 0) }
                .then(swipeModifier),
            horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        ) {
            if (!isOwn) {
                if (firstInGroup) {
                    AsyncImage(
                        model = avatarUrl(m.avatar, 's'),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(AVATAR).clip(CircleShape),
                    )
                } else {
                    Spacer(Modifier.width(AVATAR))
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(
                horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
                modifier = Modifier.widthIn(max = maxBubble),
            ) {
                if (!isOwn && firstInGroup) {
                    Text(
                        text = m.nickname.ifBlank { "uid ${m.uid}" },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = nameColor ?: cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
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
                        shape = bubbleShape(isOwn, firstInGroup, lastInGroup),
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
                                BBCodeMessage(m.message)
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
        }
    }
}

private fun bubbleShape(isOwn: Boolean, first: Boolean, last: Boolean): RoundedCornerShape {
    val big = 16.dp
    val small = 5.dp
    return if (isOwn) {
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

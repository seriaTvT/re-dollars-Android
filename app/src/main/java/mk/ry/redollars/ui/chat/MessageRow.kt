package mk.ry.redollars.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
    onReact: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.80f).dp
    val nameColor = remember(m.color) {
        runCatching { Color(android.graphics.Color.parseColor(m.color)) }.getOrNull()
    }

    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = if (firstInGroup) 8.dp else 2.dp),
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
                            m.replyDetails?.let {
                                ReplyHeader(it, Modifier.fillMaxWidth().padding(bottom = 4.dp))
                            }
                            BBCodeMessage(m.message)
                        }
                    }
                }

                DropdownMenu(
                    expanded = showQuickReact,
                    onDismissRequest = { showQuickReact = false },
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
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

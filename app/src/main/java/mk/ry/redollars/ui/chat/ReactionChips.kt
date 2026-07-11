package mk.ry.redollars.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.ui.render.Smilies
import mk.ry.redollars.ui.render.avatarUrl

/** Mirrors the userscript's MAX_AVATARS_SHOWN. */
private const val MAX_AVATARS = 5

private data class ReactionGroup(val emoji: String, val users: List<ReactionDto>, val mine: Boolean)

/**
 * Grouped reaction chips below a bubble, ported from MessageReactions.tsx: each chip
 * shows the emoji plus a stacked row of reactor avatars (up to [MAX_AVATARS], "+N" for
 * the rest / avatar-less reactors). Tap toggles; long-press lists who reacted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(
    reactions: List<ReactionDto>,
    ownUid: Long?,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (reactions.isEmpty()) return
    val groups = remember(reactions, ownUid) {
        reactions.groupBy { it.emoji }.map { (emoji, list) ->
            ReactionGroup(emoji, list, ownUid != null && list.any { it.userId == ownUid })
        }
    }
    FlowRow(modifier.padding(top = 2.dp)) {
        groups.forEach { group ->
            ReactionChip(group) { onToggle(group.emoji) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReactionChip(group: ReactionGroup, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var showReactors by remember { mutableStateOf(false) }

    val withAvatar = remember(group) { group.users.filter { !it.avatar.isNullOrBlank() } }
    val avatarsToShow = withAvatar.take(MAX_AVATARS)
    // Overflow = avatars beyond the cap + reactors without any avatar (userscript parity).
    val extraCount = (withAvatar.size - MAX_AVATARS).coerceAtLeast(0) +
        (group.users.size - withAvatar.size)

    Box {
        Surface(
            color = if (group.mine) cs.primaryContainer else cs.surfaceContainerHigh,
            contentColor = if (group.mine) cs.onPrimaryContainer else cs.onSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
            border = if (group.mine) {
                androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.5f))
            } else null,
            modifier = Modifier
                .padding(end = 4.dp, bottom = 3.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showReactors = true },
                ),
        ) {
            Row(
                Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val smileySrc = remember(group.emoji) { Smilies.urlFor(group.emoji) }
                if (smileySrc != null) {
                    AsyncImage(model = smileySrc, contentDescription = group.emoji, modifier = Modifier.size(17.dp))
                } else {
                    Text(group.emoji, fontSize = 13.sp)
                }

                if (avatarsToShow.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                        avatarsToShow.forEachIndexed { i, user ->
                            AsyncImage(
                                model = avatarUrl(user.avatar!!, 's'),
                                contentDescription = user.nickname,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .zIndex((MAX_AVATARS - i).toFloat())
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, cs.surface, CircleShape),
                            )
                        }
                    }
                }

                when {
                    avatarsToShow.isEmpty() -> {
                        Spacer(Modifier.width(4.dp))
                        Text("${group.users.size}", style = MaterialTheme.typography.labelSmall)
                    }
                    extraCount > 0 -> {
                        Spacer(Modifier.width(4.dp))
                        Text("+$extraCount", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Long-press: who reacted (tooltip equivalent).
        DropdownMenu(expanded = showReactors, onDismissRequest = { showReactors = false }) {
            group.users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.nickname, style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = {
                        AsyncImage(
                            model = avatarUrl(user.avatar ?: "", 's'),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(22.dp).clip(CircleShape),
                        )
                    },
                    onClick = { showReactors = false },
                )
            }
        }
    }
}

package mk.ry.redollars.ui.chat

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import mk.ry.redollars.net.ReactionDto
import mk.ry.redollars.ui.render.Smilies

private data class ReactionGroup(val emoji: String, val count: Int, val mine: Boolean)

/**
 * Grouped reaction chips below a bubble. Read-only for now; long-press-to-react
 * arrives with backend auth. `emoji` may be a smiley code — rendered as its image.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionChips(reactions: List<ReactionDto>, ownUid: Long?, modifier: Modifier = Modifier) {
    if (reactions.isEmpty()) return
    val groups = remember(reactions, ownUid) {
        reactions.groupBy { it.emoji }.map { (emoji, list) ->
            ReactionGroup(emoji, list.size, ownUid != null && list.any { it.userId == ownUid })
        }
    }
    FlowRow(modifier.padding(top = 2.dp)) {
        groups.forEach { group ->
            ReactionChip(group)
        }
    }
}

@Composable
private fun ReactionChip(group: ReactionGroup) {
    val cs = MaterialTheme.colorScheme
    Surface(
        color = if (group.mine) cs.primaryContainer else cs.surfaceContainerHigh,
        contentColor = if (group.mine) cs.onPrimaryContainer else cs.onSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(end = 4.dp, bottom = 2.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val smileySrc = remember(group.emoji) { Smilies.urlFor(group.emoji) }
            if (smileySrc != null) {
                AsyncImage(model = smileySrc, contentDescription = group.emoji, modifier = Modifier.size(16.dp))
            } else {
                Text(group.emoji, fontSize = 13.sp)
            }
            if (group.count > 1) {
                Spacer(Modifier.width(4.dp))
                Text("${group.count}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

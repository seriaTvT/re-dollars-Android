package mk.ry.redollars.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.net.WsUser
import mk.ry.redollars.ui.render.avatarUrl

/** "X is typing…" line shown between the message list and the composer. */
@Composable
fun TypingIndicator(users: List<WsUser>, modifier: Modifier = Modifier) {
    if (users.isEmpty()) return
    val label = when (users.size) {
        1 -> "${users[0].name} is typing"
        2 -> "${users[0].name} and ${users[1].name} are typing"
        else -> "${users.size} people are typing"
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        users.take(3).forEach { user ->
            AsyncImage(
                model = avatarUrl(user.avatar ?: "", 's'),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(end = 3.dp).size(16.dp).clip(CircleShape),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(3.dp))
        PulsingDots()
    }
}

@Composable
private fun PulsingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "typing-phase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val active = phase.toInt() % 3 == i
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .size(4.dp)
                    .alpha(if (active) 1f else 0.35f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}

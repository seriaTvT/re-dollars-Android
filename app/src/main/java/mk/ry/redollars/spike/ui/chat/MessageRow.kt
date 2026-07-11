package mk.ry.redollars.spike.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.spike.net.MessageDto
import mk.ry.redollars.spike.ui.render.BBCodeMessage
import mk.ry.redollars.spike.ui.render.ReplyHeader
import mk.ry.redollars.spike.ui.render.avatarUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun MessageRow(m: MessageDto, modifier: Modifier = Modifier) {
    val nameColor = remember(m.color) {
        runCatching { Color(android.graphics.Color.parseColor(m.color)) }
            .getOrDefault(Color.Unspecified)
    }
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        AsyncImage(
            model = avatarUrl(m.avatar, 's'),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(38.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = m.nickname.ifBlank { "uid ${m.uid}" },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeFmt.format(Instant.ofEpochSecond(m.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            if (m.isDeleted) {
                Text(
                    text = "(deleted)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                m.replyDetails?.let { ReplyHeader(it, Modifier.fillMaxWidth().padding(bottom = 3.dp)) }
                BBCodeMessage(m.message)
            }
        }
    }
}

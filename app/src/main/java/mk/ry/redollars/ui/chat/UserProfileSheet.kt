package mk.ry.redollars.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.net.Config
import mk.ry.redollars.net.UserProfileDto
import java.time.Instant

private fun isoToMillis(iso: String?): Long? =
    iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/** Tap-an-avatar profile: identity, sign, chat stats (UserProfilePanel.tsx, trimmed). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSheet(
    uid: Long,
    online: Boolean,
    blocked: Boolean,
    canBlock: Boolean,
    onToggleBlock: () -> Unit,
    loadProfile: suspend (Long) -> UserProfileDto?,
    onDismiss: () -> Unit,
) {
    val profile by produceState<UserProfileDto?>(null, uid) { value = loadProfile(uid) }
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss) {
        val p = profile
        if (p == null) {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
            return@ModalBottomSheet
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AsyncImage(
                    model = p.avatar?.large ?: p.avatar?.medium ?: p.avatar?.small,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(80.dp).clip(CircleShape),
                )
                if (online) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(cs.surface)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759)),
                    )
                }
            }
            Text(
                text = p.nickname.ifBlank { "uid $uid" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = "@${p.username.ifBlank { uid.toString() }}",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            p.sign?.takeIf { it.isNotBlank() }?.let { sign ->
                Text(
                    text = sign,
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            p.stats?.let { stats ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatCell("消息", stats.messageCount.toString())
                    StatCell("日均", "%.1f".format(stats.averagePerDay))
                    StatCell(
                        "最近活跃",
                        isoToMillis(stats.lastMessageTime)?.let {
                            DateUtils.getRelativeTimeSpanString(it).toString()
                        } ?: "—",
                    )
                }
            }

            Row(
                Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val path = p.url?.takeIf { it.isNotBlank() }
                            ?: "/user/${p.username.ifBlank { uid.toString() }}"
                        uriHandler.openUri(Config.BGM_HOST + path)
                    },
                ) { Text("在 Bangumi 查看") }
                if (canBlock) {
                    // RD = the app-local blocklist; Bangumi-side blocks live in 屏蔽管理.
                    OutlinedButton(onClick = onToggleBlock) {
                        Text(
                            text = if (blocked) "取消 RD 屏蔽" else "RD 屏蔽",
                            color = if (blocked) cs.primary else cs.error,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

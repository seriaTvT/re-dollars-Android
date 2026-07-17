package mk.ry.redollars.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.net.UserProfileDto

/**
 * 屏蔽管理: the two blocklists, managed separately.
 *  * RD (app-local) blocks can be removed for good.
 *  * Bangumi blocks come from the site's ignore list; the app can only lift them
 *    locally (an override that survives re-harvests) — the site setting is never
 *    changed from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockManagerSheet(
    localBlocked: Set<Long>,
    siteBlocked: Set<Long>,
    siteUnblocked: Set<Long>,
    onUnblockLocal: (Long) -> Unit,
    onSetSiteUnblocked: (Long, Boolean) -> Unit,
    loadProfile: suspend (Long) -> UserProfileDto?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            item {
                Text(
                    text = "屏蔽管理",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            item {
                SectionHeader(
                    title = "RD 屏蔽 (${localBlocked.size})",
                    caption = "本应用内的屏蔽,可随时解除",
                )
            }
            if (localBlocked.isEmpty()) {
                item { EmptyHint() }
            }
            items(localBlocked.sorted(), key = { "local-$it" }) { uid ->
                BlockedUserRow(uid = uid, loadProfile = loadProfile) {
                    OutlinedButton(onClick = { onUnblockLocal(uid) }) { Text("解除") }
                }
            }

            item {
                SectionHeader(
                    title = "Bangumi 屏蔽 (${siteBlocked.size})",
                    caption = "来自 Bangumi 的绝交名单;在这里解除仅在本应用生效,不影响 Bangumi 上的设置",
                )
            }
            if (siteBlocked.isEmpty()) {
                item { EmptyHint() }
            }
            items(siteBlocked.sorted(), key = { "site-$it" }) { uid ->
                val lifted = uid in siteUnblocked
                BlockedUserRow(uid = uid, loadProfile = loadProfile, dimmed = lifted) {
                    if (lifted) {
                        TextButton(onClick = { onSetSiteUnblocked(uid, false) }) {
                            Text("恢复屏蔽", color = cs.error)
                        }
                    } else {
                        OutlinedButton(onClick = { onSetSiteUnblocked(uid, true) }) {
                            Text("临时解除")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, caption: String) {
    Column(Modifier.padding(top = 20.dp, bottom = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "暂无屏蔽用户",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun BlockedUserRow(
    uid: Long,
    loadProfile: suspend (Long) -> UserProfileDto?,
    dimmed: Boolean = false,
    action: @Composable () -> Unit,
) {
    val profile by produceState<UserProfileDto?>(null, uid) { value = loadProfile(uid) }
    val cs = MaterialTheme.colorScheme

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = profile?.avatar?.let { it.medium ?: it.large ?: it.small },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = profile?.nickname?.ifBlank { null } ?: "uid $uid",
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) cs.onSurfaceVariant else cs.onSurface,
            )
            profile?.username?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "@$it",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        action()
    }
}

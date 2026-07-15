package mk.ry.redollars.ui.chat

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.SessionInfo
import mk.ry.redollars.net.Config
import mk.ry.redollars.net.UserProfileDto

/** Logged-in account sheet: identity, backend auth status, and sign-out. Opened from
 *  the top-bar account icon (the login WebView opens instead when no session exists). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    session: SessionInfo,
    authReady: Boolean,
    loadProfile: suspend (Long) -> UserProfileDto?,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
) {
    val profile by produceState<UserProfileDto?>(null, session.uid) { value = loadProfile(session.uid) }
    val uriHandler = LocalUriHandler.current
    val cs = MaterialTheme.colorScheme
    var confirmLogout by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = profile?.avatar?.large ?: profile?.avatar?.medium ?: profile?.avatar?.small,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(80.dp).clip(CircleShape),
            )
            Text(
                text = session.name.ifBlank { "uid ${session.uid}" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
            profile?.username?.takeIf { it.isNotBlank() }?.let { username ->
                Text(
                    text = "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }

            // Backend session status: green once the rymk-auth JWT is validated for this
            // uid (edit/delete/upload unlocked), amber while it isn't.
            Row(
                Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (authReady) Color(0xFF34C759) else Color(0xFFFF9F0A)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (authReady) "后端已验证 (rymk-auth)" else "后端未验证",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }

            Row(
                Modifier.padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val path = profile?.url?.takeIf { it.isNotBlank() }
                            ?: "/user/${profile?.username?.ifBlank { null } ?: session.uid}"
                        uriHandler.openUri(Config.BGM_HOST + path)
                    },
                ) { Text("在 Bangumi 查看") }
                OutlinedButton(onClick = { confirmLogout = true }) {
                    Text("退出登录", color = cs.error)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将清除本地登录状态与 Bangumi 会话，需要重新登录才能发送消息。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        onLogout()
                    },
                ) { Text("退出", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

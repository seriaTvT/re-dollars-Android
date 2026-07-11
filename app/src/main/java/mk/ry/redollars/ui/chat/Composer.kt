package mk.ry.redollars.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.UserSearchDto
import mk.ry.redollars.ui.render.avatarUrl

/** ~50-char BBCode-stripped preview, mirroring the web reply strip. */
private fun replyPreview(content: String): String = content
    .replace(Regex("\\[img\\][\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "[图片]")
    .replace(Regex("\\[quote(?:=\\d+)?\\][\\s\\S]*?\\[/quote\\]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\[/?[a-zA-Z][^\\]]*\\]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(50)
    .ifEmpty { "…" }

@Composable
fun ChatComposer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean,
    status: String?,
    replyTo: MessageDto?,
    onCancelReply: () -> Unit,
    editing: Boolean,
    onCancelEdit: () -> Unit,
    mentionCandidates: List<UserSearchDto>,
    onPickMention: (UserSearchDto) -> Unit,
    onInsertSmiley: (String) -> Unit,
    onSend: (String) -> Unit,
    onLogin: () -> Unit,
) {
    var showSmilies by rememberSaveable { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 6.dp),
                )
            }
            if (enabled && mentionCandidates.isNotEmpty()) {
                MentionSuggestions(mentionCandidates, onPickMention)
            }
            if (editing) {
                EditStrip(onCancelEdit)
            } else if (replyTo != null) {
                ReplyStrip(replyTo, onCancelReply)
            }
            if (!enabled) {
                FilledTonalButton(
                    onClick = onLogin,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    Icon(Icons.Filled.AccountCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Log in to chat")
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showSmilies = !showSmilies }) {
                        Icon(
                            Icons.Filled.Face,
                            contentDescription = "Smilies",
                            tint = if (showSmilies) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(
                                when {
                                    editing -> "Edit message…"
                                    replyTo != null -> "Reply…"
                                    else -> "Message…"
                                },
                            )
                        },
                        maxLines = 5,
                        shape = RoundedCornerShape(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            val trimmed = value.text.trim()
                            if (trimmed.isNotEmpty()) {
                                showSmilies = false
                                onSend(trimmed)
                            }
                        },
                        enabled = value.text.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
                AnimatedVisibility(visible = showSmilies) {
                    SmileyPicker(
                        onPick = { code ->
                            onInsertSmiley(code)
                            showSmilies = false // SmileyPanel.tsx closes on select
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        columns = GridCells.Adaptive(minSize = 44.dp),
                        gridHeight = 240.dp,
                    )
                }
            }
        }
    }
}

/** Users matching the `@query` at the cursor; tapping one completes the mention. */
@Composable
private fun MentionSuggestions(
    candidates: List<UserSearchDto>,
    onPick: (UserSearchDto) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHigh),
    ) {
        candidates.take(6).forEach { user ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(user) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AsyncImage(
                    model = avatarUrl(user.avatarUrl, 'm'),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
                Text(
                    text = user.nickname.ifBlank { user.username },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun EditStrip(onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHigh)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(3.dp).fillMaxHeight().background(cs.tertiary)) {}
        Text(
            text = "Editing message",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.tertiary,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp),
        )
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel edit",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ReplyStrip(replyTo: MessageDto, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHigh)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(3.dp).fillMaxHeight().background(cs.primary)) {}
        AsyncImage(
            model = avatarUrl(replyTo.avatar, 's'),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "Replying to ${replyTo.nickname}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = remember(replyTo.message) { replyPreview(replyTo.message) },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel reply",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

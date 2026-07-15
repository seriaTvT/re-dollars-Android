package mk.ry.redollars.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import mk.ry.redollars.VoiceDraft
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.UserSearchDto
import kotlinx.coroutines.flow.MutableStateFlow
import mk.ry.redollars.ui.render.LocalAudioPlayer
import mk.ry.redollars.ui.render.avatarUrl

/** The web app's emoji-panel glyph (styles/index.css --dollars-icon-emoji): a tray
 *  with a chevron dropping in, Tabler-stroke style. Stroke color is a placeholder —
 *  Icon's tint recolors it. */
private val EmojiPanelIcon: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(
        name = "EmojiPanel",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(3f, 9f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
            horizontalLineToRelative(14f)
            arcToRelative(2f, 2f, 0f, false, true, 2f, 2f)
            verticalLineToRelative(9f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, 2f)
            horizontalLineToRelative(-14f)
            arcToRelative(2f, 2f, 0f, false, true, -2f, -2f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(16f, 3f)
            lineToRelative(-4f, 4f)
            lineToRelative(-4f, -4f)
        }
    }.build()
}

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
    favorites: List<String>,
    onPickSticker: (String) -> Unit,
    onUploadFavorite: (Uri) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onAttachImages: (List<Uri>) -> Unit,
    onAttachFile: (Uri) -> Unit,
    recordingVoice: Boolean,
    recordSeconds: Int,
    voiceDraft: VoiceDraft?,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onCancelVoice: () -> Unit,
    onSend: (String) -> Unit,
    onLogin: () -> Unit,
) {
    var showSmilies by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onStartVoice() }
    val startVoiceChecked = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onStartVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 9),
    ) { uris -> if (uris.isNotEmpty()) onAttachImages(uris) }
    val stickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onUploadFavorite) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onAttachFile) }

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
                if (voiceDraft != null) {
                    VoiceStrip(voiceDraft, onCancelVoice)
                }
                if (recordingVoice) {
                    RecordingBar(recordSeconds, onCancelVoice, onStopVoice)
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { showSmilies = !showSmilies }) {
                            Icon(
                                EmojiPanelIcon,
                                contentDescription = "Smilies",
                                tint = if (showSmilies) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box {
                            var showAttach by remember { mutableStateOf(false) }
                            IconButton(onClick = { showAttach = true }) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "Attach",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showAttach,
                                onDismissRequest = { showAttach = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("图片") },
                                    onClick = {
                                        showAttach = false
                                        attachLauncher.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                                            ),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("视频 / 文件") },
                                    onClick = {
                                        showAttach = false
                                        fileLauncher.launch("*/*")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("语音消息") },
                                    onClick = {
                                        showAttach = false
                                        startVoiceChecked()
                                    },
                                )
                            }
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
                                if (trimmed.isNotEmpty() || voiceDraft?.url != null) {
                                    showSmilies = false
                                    onSend(trimmed)
                                }
                            },
                            enabled = value.text.isNotBlank() || voiceDraft?.url != null,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
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
                        favorites = favorites,
                        onPickSticker = { url ->
                            onPickSticker(url)
                            showSmilies = false
                        },
                        onUploadFavorite = {
                            stickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRemoveFavorite = onRemoveFavorite,
                    )
                }
            }
        }
    }
}

private fun formatVoiceSeconds(seconds: Int): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

/** Live recording state: pulsating-red timer, cancel, and a stop button. */
@Composable
private fun RecordingBar(seconds: Int, onCancel: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(cs.error),
        )
        Text(
            text = "Recording… ${formatVoiceSeconds(seconds)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Cancel recording",
                tint = cs.onSurfaceVariant,
            )
        }
        FilledIconButton(onClick = onStop) {
            Spacer(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.onPrimary),
            )
        }
    }
}

/** Recorded clip awaiting send: play/pause preview, upload status, cancel. */
@Composable
private fun VoiceStrip(draft: VoiceDraft, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val player = LocalAudioPlayer.current
    val nowPlaying by (player?.nowPlaying ?: MutableStateFlow(null)).collectAsState()
    val source = draft.file.absolutePath
    val playing = nowPlaying == source
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHigh)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(3.dp).fillMaxHeight().background(cs.secondary)) {}
        FilledTonalIconButton(
            onClick = { player?.toggle(source) },
            modifier = Modifier.padding(start = 8.dp).size(30.dp),
        ) {
            if (playing) {
                Spacer(
                    Modifier
                        .size(9.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(cs.primary),
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play recording",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(
                text = "Voice message · ${formatVoiceSeconds(draft.durationSec)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.secondary,
            )
            Text(
                text = when {
                    draft.url != null -> "Ready to send"
                    draft.error != null -> "Upload failed: ${draft.error}"
                    else -> "Uploading…"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (draft.error != null) cs.error else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Discard recording",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
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
            model = avatarUrl(replyTo.avatar, 'l'),
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

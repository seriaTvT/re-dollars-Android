package mk.ry.redollars.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.ui.render.avatarUrl

private const val PAGE = 20

/** BBCode-stripped multi-line preview for result rows. */
private fun resultPreview(content: String): String = content
    .replace(Regex("\\[img\\][\\s\\S]*?\\[/img\\]", RegexOption.IGNORE_CASE), "[图片]")
    .replace(Regex("\\[quote(?:=\\d+)?\\][\\s\\S]*?\\[/quote\\]", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\[/?[a-zA-Z][^\\]]*\\]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifEmpty { "…" }

/** Full-text message search over the backend /search endpoint (SearchPanel.tsx). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    search: suspend (String, Int) -> List<MessageDto>,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageDto>>(emptyList()) }
    var offset by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch(reset: Boolean) {
        val q = query.trim()
        if (searching || q.isEmpty()) return
        scope.launch {
            searching = true
            try {
                val from = if (reset) 0 else offset
                val page = search(q, from)
                results = if (reset) page else results + page
                offset = from + page.size
                exhausted = page.size < PAGE
                searched = true
            } finally {
                searching = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxHeight(0.88f).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索聊天记录…") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch(true) }),
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { runSearch(true) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                    }
                },
            )
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(results, key = { it.id }) { m -> SearchResultRow(m) }
                if (results.isNotEmpty() && !exhausted) {
                    item(key = "more") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TextButton(onClick = { runSearch(false) }, enabled = !searching) {
                                Text(if (searching) "加载中…" else "加载更多")
                            }
                        }
                    }
                }
                if (searched && results.isEmpty() && !searching) {
                    item(key = "empty") {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "无结果",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(m: MessageDto) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
            model = avatarUrl(m.avatar, 'l'),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(32.dp).clip(CircleShape),
        )
        Column(Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = m.nickname.ifBlank { "uid ${m.uid}" },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = DateUtils.getRelativeTimeSpanString(m.timestamp * 1000).toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                text = remember(m.message) { resultPreview(m.message) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

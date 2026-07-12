package mk.ry.redollars.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import mk.ry.redollars.net.GalleryItemDto
import mk.ry.redollars.net.GalleryResponse
import mk.ry.redollars.ui.render.LocalImageViewer

/** Media wall over the backend /gallery endpoint (GalleryPanel.tsx): thumbnail grid,
 *  tap opens the full image in the lightbox, paging via the trailing tile. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySheet(
    fetch: suspend (Int) -> GalleryResponse?,
    onDismiss: () -> Unit,
) {
    var items by remember { mutableStateOf<List<GalleryItemDto>>(emptyList()) }
    var hasMore by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    val openViewer = LocalImageViewer.current
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || !hasMore) return
        scope.launch {
            loading = true
            try {
                val page = fetch(items.size)
                if (page != null) {
                    items = items + page.items
                    hasMore = page.hasMore
                } else {
                    hasMore = false
                }
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadMore() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier.fillMaxHeight(0.88f).padding(horizontal = 12.dp),
        ) {
            // No keys: the same url/message can legitimately appear twice.
            items(items) { item ->
                AsyncImage(
                    model = item.thumbnailUrl ?: item.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(3.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { openViewer(item.url) },
                )
            }
            if (hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = { loadMore() }) { Text("加载更多") }
                        }
                    }
                }
            }
        }
    }
}

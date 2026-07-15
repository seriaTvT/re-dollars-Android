package mk.ry.redollars.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.ui.render.Smilies

/**
 * Tabbed smiley picker for reactions, ported from ReactionPickerFloating.tsx:
 * [SmileyPicker] sized for the long-press dropdown (no favorites tab — reactions
 * are smiley codes only).
 */
@Composable
fun ReactionPicker(onPick: (String) -> Unit, modifier: Modifier = Modifier) =
    SmileyPicker(onPick, modifier.width(276.dp))

/**
 * Tabbed smiley picker over the TV/BGM/VS/500 groups (each tab represented by its
 * first smiley); tapping any smiley emits its code. Also backs the composer's
 * smiley panel (SmileyPanel.tsx), which sizes it full-width with adaptive columns
 * and passes [favorites] to add the saved-stickers tab (star): tap sends a sticker,
 * long-press removes it, and the leading tile uploads a new one.
 */
@Composable
fun SmileyPicker(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Fixed(7),
    gridHeight: Dp = 216.dp,
    groups: List<Smilies.Group> = Smilies.pickerGroups,
    favorites: List<String>? = null,
    onPickSticker: (String) -> Unit = {},
    onUploadFavorite: () -> Unit = {},
    onRemoveFavorite: (String) -> Unit = {},
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val favTabIndex = groups.size
    val maxTab = if (favorites != null) favTabIndex else favTabIndex - 1
    if (tabIndex > maxTab) tabIndex = 0
    val cs = MaterialTheme.colorScheme

    Column(modifier.padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            groups.forEachIndexed { i, g ->
                val selected = i == tabIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
                        .clickable { tabIndex = i }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = remember(g.iconCode) { Smilies.urlFor(g.iconCode) },
                            contentDescription = g.name,
                            modifier = Modifier.size(20.dp),
                        )
                        if (selected) {
                            Text(
                                text = g.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onPrimaryContainer,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }
            }
            if (favorites != null) {
                val selected = tabIndex == favTabIndex
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
                        .clickable { tabIndex = favTabIndex }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Saved stickers",
                        tint = if (selected) cs.onPrimaryContainer else cs.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        if (favorites != null && tabIndex == favTabIndex) {
            FavoritesGrid(favorites, gridHeight, onPickSticker, onUploadFavorite, onRemoveFavorite)
        } else {
            val group = groups[tabIndex.coerceIn(0, favTabIndex - 1)]
            if (group.isLarge) {
                LargeSmileyGrid(group, gridHeight, onPick)
            } else {
                LazyVerticalGrid(
                    columns = columns,
                    modifier = Modifier.fillMaxWidth().height(gridHeight),
                ) {
                    items(group.codes, key = { it }) { code ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onPick(code) },
                        ) {
                            AsyncImage(
                                model = remember(code) { Smilies.urlFor(code) },
                                contentDescription = code,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Large animated stickers (Musume/Blake) shown in bigger tiles under section headers
 *  (SmileyPanel.tsx large-smiley-mode); tapping inserts the sticker's code. */
@Composable
private fun LargeSmileyGrid(group: Smilies.Group, gridHeight: Dp, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 56.dp),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
    ) {
        group.sections.forEach { section ->
            item(key = "hdr-${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
            }
            items(section.codes, key = { it }) { code ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(3.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(code) },
                ) {
                    AsyncImage(
                        model = remember(code) { Smilies.urlFor(code) },
                        contentDescription = code,
                        modifier = Modifier.size(46.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesGrid(
    urls: List<String>,
    gridHeight: Dp,
    onPick: (String) -> Unit,
    onUpload: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    confirmRemove?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove sticker?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        onRemove(url)
                    },
                ) { Text("Remove", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Cancel") }
            },
        )
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 68.dp),
        modifier = Modifier.fillMaxWidth().height(gridHeight),
    ) {
        item(key = "upload-tile") {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.surfaceContainerHigh)
                    .clickable(onClick = onUpload),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Upload sticker",
                    tint = cs.onSurfaceVariant,
                )
            }
        }
        items(urls, key = { it }) { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { onPick(url) },
                        onLongClick = { confirmRemove = url },
                    ),
            )
        }
    }
}

package mk.ry.redollars.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mk.ry.redollars.ui.render.Smilies

/**
 * Tabbed smiley picker for reactions, ported from ReactionPickerFloating.tsx:
 * [SmileyPicker] sized for the long-press dropdown.
 */
@Composable
fun ReactionPicker(onPick: (String) -> Unit, modifier: Modifier = Modifier) =
    SmileyPicker(onPick, modifier.width(276.dp))

/**
 * Tabbed smiley picker over the TV/BGM/VS/500 groups (each tab represented by its
 * first smiley); tapping any smiley emits its code. Also backs the composer's
 * smiley panel (SmileyPanel.tsx), which sizes it full-width with adaptive columns.
 */
@Composable
fun SmileyPicker(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Fixed(7),
    gridHeight: Dp = 216.dp,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val group = Smilies.pickerGroups[tabIndex]
    val cs = MaterialTheme.colorScheme

    Column(modifier.padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Smilies.pickerGroups.forEachIndexed { i, g ->
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
        }

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

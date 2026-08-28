package mk.ry.redollars.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mk.ry.redollars.data.BubuScale
import mk.ry.redollars.data.DisplayPrefs

/** Interface display settings (opened from the top-bar overflow menu). Each toggle maps
 *  to one [DisplayPrefs] field and takes effect on the message list immediately. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsSheet(
    prefs: DisplayPrefs,
    onChange: ((DisplayPrefs) -> DisplayPrefs) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                text = "界面设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SwitchRow(
                title = "显示自己的头像",
                caption = "在自己的消息外侧显示头像",
                checked = prefs.showOwnAvatar,
                onChecked = { v -> onChange { it.copy(showOwnAvatar = v) } },
            )
            SwitchRow(
                title = "显示自己的用户名",
                caption = "在自己的消息上方显示昵称",
                checked = prefs.showOwnName,
                onChecked = { v -> onChange { it.copy(showOwnName = v) } },
            )
            SwitchRow(
                title = "自己的消息靠右显示",
                caption = "关闭后自己的消息移到左侧；滑动回复的方向会随消息所在侧自动调整",
                checked = prefs.alignOwnRight,
                onChecked = { v -> onChange { it.copy(alignOwnRight = v) } },
            )
            SwitchRow(
                title = "显示他人头像",
                caption = "关闭后不加载他人头像，节省流量并让气泡更紧凑",
                checked = prefs.showOtherAvatars,
                onChecked = { v -> onChange { it.copy(showOtherAvatars = v) } },
            )
            SwitchRow(
                title = "自动加载图片与视频预览",
                caption = "关闭后消息中的图片和视频帧只在点击时加载，大幅节省流量",
                checked = prefs.autoLoadMediaPreviews,
                onChecked = { v -> onChange { it.copy(autoLoadMediaPreviews = v) } },
            )
            SwitchRow(
                title = "显示贴贴",
                caption = "长按菜单顶部的快捷表情行；菜单里的回复、复制不受影响",
                checked = prefs.showQuickReactions,
                onChecked = { v -> onChange { it.copy(showQuickReactions = v) } },
            )
            BubuScaleRow(
                selected = prefs.bubuScale,
                onSelect = { v -> onChange { it.copy(bubuScale = v) } },
            )
        }
    }
}

/** "让布布变大吧": musume/blake dynamic-emoji size in chat — 小 is the classic
 *  inline size, 中/大 double/quadruple the side length. */
@Composable
private fun BubuScaleRow(selected: BubuScale, onSelect: (BubuScale) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("让布布变大吧", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Musume / Blake 动态表情在聊天中的显示大小",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            listOf(
                "小" to BubuScale.SMALL,
                "中" to BubuScale.MEDIUM,
                "大" to BubuScale.LARGE,
            ).forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    caption: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

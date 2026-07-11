package mk.ry.redollars.spike.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mk.ry.redollars.spike.SpikeViewModel
import mk.ry.redollars.spike.net.MessageDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: SpikeViewModel,
    onSend: (String) -> Unit,
    onOpenLogin: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val onlineCount by vm.onlineCount.collectAsState()
    val messages by vm.messages.collectAsState()
    var showDebug by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ChatTopBar(
                connected = connected,
                onlineCount = onlineCount,
                debugOn = showDebug,
                onToggleDebug = { showDebug = !showDebug },
                onAccount = onOpenLogin,
            )
        },
        bottomBar = {
            ChatComposer(
                enabled = vm.session != null,
                status = vm.sendStatus,
                onSend = onSend,
                onLogin = onOpenLogin,
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            AnimatedVisibility(visible = showDebug) {
                DebugPanel(vm.logs, connected, onlineCount, vm.session)
            }
            MessageList(messages, vm.session?.uid, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    connected: Boolean,
    onlineCount: Int,
    debugOn: Boolean,
    onToggleDebug: () -> Unit,
    onAccount: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Dollars",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                StatusLine(connected, onlineCount)
            }
        },
        actions = {
            IconButton(onClick = onToggleDebug) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "Debug",
                    tint = if (debugOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onAccount) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
            }
        },
    )
}

@Composable
private fun StatusLine(connected: Boolean, onlineCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF34C759) else Color(0xFFFF9F0A)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = if (connected) "$onlineCount online" else "reconnecting…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageList(messages: List<MessageDto>, ownUid: Long?, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
            val prev = messages.getOrNull(i - 1)
            val next = messages.getOrNull(i + 1)
            MessageRow(
                m = m,
                isOwn = ownUid != null && m.uid == ownUid,
                firstInGroup = !groupable(prev, m),
                lastInGroup = !groupable(m, next),
            )
        }
    }
}

/** Consecutive messages from the same author within 300s render as one bubble group. */
private fun groupable(a: MessageDto?, b: MessageDto?): Boolean {
    if (a == null || b == null) return false
    return a.uid == b.uid && (b.timestamp - a.timestamp) in 0..300
}

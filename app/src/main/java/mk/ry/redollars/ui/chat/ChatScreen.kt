package mk.ry.redollars.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import mk.ry.redollars.ChatViewModel
import mk.ry.redollars.net.MessageDto
import mk.ry.redollars.net.WsUser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onSend: (String) -> Unit,
    onOpenLogin: () -> Unit,
) {
    val connected by vm.connected.collectAsState()
    val onlineCount by vm.onlineCount.collectAsState()
    val messages by vm.messages.collectAsState()
    val typingUsers by vm.typingUsers.collectAsState()
    val notifications by vm.notifications.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val onlineUsers by vm.onlineUsers.collectAsState()
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var showNotifications by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showGallery by rememberSaveable { mutableStateOf(false) }
    var showBlockManager by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ChatTopBar(
                connected = connected,
                onlineCount = onlineCount,
                notificationCount = notifications.size,
                debugOn = showDebug,
                onToggleDebug = { showDebug = !showDebug },
                onSearch = { showSearch = true },
                onGallery = { showGallery = true },
                onBlockManager = { showBlockManager = true },
                onNotifications = { showNotifications = true },
                // Logged in: open the account sheet (status + logout). Logged out: the
                // login WebView, as before.
                onAccount = { if (vm.session != null) vm.showAccount = true else onOpenLogin() },
            )
        },
        bottomBar = {
            ChatComposer(
                value = vm.composerValue,
                onValueChange = vm::onComposerChanged,
                enabled = vm.session != null,
                // Diagnostic statuses (post/edit pipeline, internal ids) only surface
                // when the debug panel is enabled.
                status = if (vm.sendStatusIsDebug && !showDebug) null else vm.sendStatus,
                replyTo = vm.replyTo,
                onCancelReply = vm::cancelReply,
                editing = vm.editing != null,
                onCancelEdit = vm::cancelEdit,
                mentionCandidates = vm.mentionCandidates,
                onPickMention = vm::pickMention,
                onInsertSmiley = vm::insertSmiley,
                favorites = favorites,
                onPickSticker = vm::insertSticker,
                onUploadFavorite = vm::uploadFavorite,
                onRemoveFavorite = vm::removeFavorite,
                onAttachImages = vm::attachImages,
                onAttachFile = vm::attachFile,
                recordingVoice = vm.recordingVoice,
                recordSeconds = vm.recordSeconds,
                voiceDraft = vm.voiceDraft,
                onStartVoice = vm::startVoiceRecording,
                onStopVoice = vm::stopVoiceRecording,
                onCancelVoice = vm::cancelVoiceRecording,
                onSend = onSend,
                onLogin = onOpenLogin,
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            AnimatedVisibility(visible = showDebug) {
                DebugPanel(vm.logs, connected, onlineCount, vm.session)
            }
            MessageList(
                messages = messages,
                ownUid = vm.session?.uid,
                canModify = vm.authReady,
                onlineUsers = onlineUsers,
                onShowProfile = { vm.profileUid = it },
                onMention = { vm.mentionUser(it.uid, it.nickname) },
                loadingOlder = vm.loadingOlder,
                onLoadOlder = vm::loadOlder,
                onReact = vm::toggleReaction,
                onReply = vm::startReply,
                onEdit = vm::startEdit,
                onDelete = vm::deleteMessage,
                onJumpTo = { vm.pendingJumpId = it },
                jumpToId = vm.pendingJumpId,
                onJumpHandled = { vm.pendingJumpId = null },
                typingUsers = typingUsers,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showSearch) {
        SearchSheet(search = vm::searchMessages, onDismiss = { showSearch = false })
    }
    if (showGallery) {
        GallerySheet(fetch = vm::fetchGallery, onDismiss = { showGallery = false })
    }
    if (showBlockManager) {
        val localBlocked by vm.localBlockedUsers.collectAsState()
        val siteBlocked by vm.siteBlockedUsers.collectAsState()
        val siteUnblocked by vm.siteUnblockedUsers.collectAsState()
        BlockManagerSheet(
            localBlocked = localBlocked,
            siteBlocked = siteBlocked,
            siteUnblocked = siteUnblocked,
            onUnblockLocal = vm::unblockLocal,
            onSetSiteUnblocked = vm::setSiteUnblocked,
            loadProfile = vm::loadProfile,
            onDismiss = { showBlockManager = false },
        )
    }

    if (vm.showAccount) {
        vm.session?.let { s ->
            AccountSheet(
                session = s,
                authReady = vm.authReady,
                loadProfile = vm::loadProfile,
                onLogout = vm::logout,
                onDismiss = { vm.showAccount = false },
            )
        }
    }

    vm.profileUid?.let { uid ->
        // The profile button toggles the RD (app-local) list only; Bangumi-side
        // blocks are managed in the 屏蔽管理 sheet.
        val localBlocked by vm.localBlockedUsers.collectAsState()
        UserProfileSheet(
            uid = uid,
            online = uid in onlineUsers,
            blocked = uid in localBlocked,
            canBlock = uid != vm.session?.uid,
            onToggleBlock = { vm.toggleBlock(uid) },
            loadProfile = vm::loadProfile,
            onDismiss = { vm.profileUid = null },
        )
    }

    if (showNotifications) {
        NotificationsSheet(
            notifications = notifications,
            onOpen = { item ->
                showNotifications = false
                vm.openNotification(item)
            },
            onMarkAllRead = vm::markAllNotificationsRead,
            onDismiss = { showNotifications = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    connected: Boolean,
    onlineCount: Int,
    notificationCount: Int,
    debugOn: Boolean,
    onToggleDebug: () -> Unit,
    onSearch: () -> Unit,
    onGallery: () -> Unit,
    onBlockManager: () -> Unit,
    onNotifications: () -> Unit,
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
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
            IconButton(onClick = onNotifications) {
                BadgedBox(
                    badge = {
                        if (notificationCount > 0) {
                            Badge { Text(if (notificationCount > 99) "99+" else "$notificationCount") }
                        }
                    },
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                }
            }
            IconButton(onClick = onAccount) {
                Icon(Icons.Filled.AccountCircle, contentDescription = "Account")
            }
            Box {
                var showMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("图片墙") },
                        onClick = {
                            showMenu = false
                            onGallery()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("屏蔽管理") },
                        onClick = {
                            showMenu = false
                            onBlockManager()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (debugOn) "隐藏调试信息" else "调试信息") },
                        onClick = {
                            showMenu = false
                            onToggleDebug()
                        },
                    )
                }
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
private fun MessageList(
    messages: List<MessageDto>,
    ownUid: Long?,
    canModify: Boolean,
    onlineUsers: Set<Long>,
    onShowProfile: (Long) -> Unit,
    onMention: (MessageDto) -> Unit,
    loadingOlder: Boolean,
    onLoadOlder: () -> Unit,
    onReact: (Long, String) -> Unit,
    onReply: (MessageDto) -> Unit,
    onEdit: (MessageDto) -> Unit,
    onDelete: (Long) -> Unit,
    onJumpTo: (Long) -> Unit,
    jumpToId: Long?,
    onJumpHandled: () -> Unit,
    typingUsers: List<WsUser>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // "At bottom" = the last item is the last visible one (or the list is empty).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }

    // Whether new messages should keep pinning the list to the bottom. We can't read
    // `atBottom` when a message arrives: by then the LazyColumn has already re-laid-out
    // with the new item below the fold, so `atBottom` reads false and the scroll never
    // fires. Instead we latch intent here — only a user-driven scroll away from the
    // bottom releases the pin; an append (which never sets isScrollInProgress) keeps it.
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom to listState.isScrollInProgress }
            .collect { (bottom, scrolling) ->
                when {
                    bottom -> stickToBottom = true
                    scrolling -> stickToBottom = false
                }
            }
    }

    var newCount by remember { mutableStateOf(0) }
    var prevLastId by remember { mutableStateOf(0L) }
    var initialized by remember { mutableStateOf(false) }

    // Track the newest id, not the list size: history prepends grow the list from the
    // top and must neither auto-scroll nor count toward the "new messages" badge.
    LaunchedEffect(messages) {
        val lastId = messages.lastOrNull()?.id ?: return@LaunchedEffect
        when {
            !initialized -> {
                listState.scrollToItem(messages.size - 1) // land at the bottom on first load
                initialized = true
            }
            lastId > prevLastId -> {
                if (stickToBottom) listState.animateScrollToItem(messages.size - 1)
                else newCount += messages.count { it.id > prevLastId }
            }
        }
        prevLastId = lastId
    }

    // Reaching the bottom (manually or via the pill) clears the unread badge.
    LaunchedEffect(atBottom) { if (atBottom) newCount = 0 }

    // Jump to a notification's message when it's in the loaded window (the spinner
    // header shifts list indices by one while visible).
    LaunchedEffect(jumpToId, messages) {
        val target = jumpToId ?: return@LaunchedEffect
        val index = messages.indexOfFirst { it.id == target }
        val oldestLoaded = messages.firstOrNull()?.id ?: Long.MAX_VALUE
        when {
            index >= 0 -> {
                listState.animateScrollToItem(index + if (loadingOlder) 1 else 0)
                onJumpHandled()
            }
            // Older than the loaded window: page back and re-run when messages change.
            messages.isNotEmpty() && target < oldestLoaded -> onLoadOlder()
            // Should be in range but missing (e.g. deleted): give up quietly.
            messages.isNotEmpty() -> onJumpHandled()
        }
    }

    // Nearing the top pages in older history (VM guards re-entrancy/exhaustion).
    LaunchedEffect(listState) {
        snapshotFlow { initialized && listState.firstVisibleItemIndex <= 3 }
            .filter { it }
            .collect { onLoadOlder() }
    }

    // Keep the newest message visible while the viewport shrinks (IME opening, reply
    // strip appearing…): each shrink re-pins the bottom. Gate on the stickToBottom
    // latch, not atBottom — a large shrink step can push the last item fully off
    // screen for a frame, which breaks atBottom and used to stall the re-pin midway
    // through the IME animation, leaving the newest messages behind the keyboard.
    LaunchedEffect(listState) {
        var lastHeight = 0
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { height ->
            if (height in 1 until lastHeight && stickToBottom) {
                val total = listState.layoutInfo.totalItemsCount
                if (total > 0) listState.scrollToItem(total - 1)
            }
            lastHeight = height
        }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            if (loadingOlder) {
                item(key = "history-spinner") {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }
            }
            itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                val prev = messages.getOrNull(i - 1)
                val next = messages.getOrNull(i + 1)
                if (prev == null || !sameDay(prev.timestamp, m.timestamp)) {
                    DayDivider(m.timestamp)
                }
                MessageRow(
                    m = m,
                    isOwn = ownUid != null && m.uid == ownUid,
                    firstInGroup = !groupable(prev, m),
                    lastInGroup = !groupable(m, next),
                    ownUid = ownUid,
                    canModify = canModify,
                    online = m.uid in onlineUsers,
                    onShowProfile = onShowProfile,
                    onMention = { onMention(m) },
                    onReact = { emoji -> onReact(m.id, emoji) },
                    onReply = { onReply(m) },
                    onEdit = { onEdit(m) },
                    onDelete = { onDelete(m.id) },
                    onJumpTo = onJumpTo,
                )
            }
        }

        // Typing indicator floats over the list (FloatingUI.tsx parity): it must never
        // resize the LazyColumn viewport, or its appearance shoves the pinned bottom
        // message up and back down on every keystroke burst.
        AnimatedVisibility(
            visible = typingUsers.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 6.dp),
        ) {
            TypingIndicator(typingUsers)
        }

        // Telegram-style jump-to-latest button (web #dollars-scroll-bottom-btn): shown
        // whenever the user is away from the live edge, with the unread count badged.
        AnimatedVisibility(
            visible = !atBottom && initialized,
            enter = fadeIn(tween(150)) + scaleIn(initialScale = 0.8f, animationSpec = tween(150)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f, animationSpec = tween(120)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
        ) {
            ScrollToBottomButton(newCount) {
                scope.launch { listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0)) }
                newCount = 0
            }
        }
    }
}

@Composable
private fun DayDivider(timestampSec: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = dayLabel(timestampSec),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ScrollToBottomButton(unread: Int, onClick: () -> Unit) {
    BadgedBox(
        badge = {
            if (unread > 0) {
                Badge { Text(if (unread > 99) "99+" else "$unread") }
            }
        },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            shadowElevation = 4.dp,
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "跳转到最新消息",
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

/** Consecutive messages from the same author within 300s render as one bubble group. */
private fun groupable(a: MessageDto?, b: MessageDto?): Boolean {
    if (a == null || b == null) return false
    return a.uid == b.uid && (b.timestamp - a.timestamp) in 0..300
}

private fun sameDay(a: Long, b: Long): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochSecond(a).atZone(zone).toLocalDate() ==
        Instant.ofEpochSecond(b).atZone(zone).toLocalDate()
}

private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Mirrors format.ts formatDate(_, 'label'): 今天 / 昨天 / 周X / yyyy-MM-dd. */
private fun dayLabel(timestampSec: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(timestampSec).atZone(zone).toLocalDate()
    val diff = ChronoUnit.DAYS.between(date, LocalDate.now(zone))
    return when {
        diff == 0L -> "今天"
        diff == 1L -> "昨天"
        diff in 2..6 -> WEEKDAYS[date.dayOfWeek.value - 1]
        else -> date.format(DAY_FMT)
    }
}

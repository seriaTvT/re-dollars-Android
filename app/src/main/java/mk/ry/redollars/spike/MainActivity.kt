package mk.ry.redollars.spike

import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import mk.ry.redollars.spike.net.MessageDto
import mk.ry.redollars.spike.ui.render.BBCodeMessage
import mk.ry.redollars.spike.ui.render.ReplyHeader
import mk.ry.redollars.spike.ui.render.avatarUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpikeApp() }
    }
}

@Composable
private fun SpikeApp() {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: SpikeViewModel = viewModel()
            LaunchedEffect(Unit) { vm.start() }
            var webView by remember { mutableStateOf<WebView?>(null) }

            Box(Modifier.fillMaxSize()) {
                if (!vm.showLogin) {
                    MainScreen(vm) { text ->
                        val info = vm.session
                        val wv = webView
                        when {
                            info == null -> vm.noteSend("Log in first")
                            wv == null -> vm.noteSend("WebView not ready")
                            else -> {
                                vm.beginSend(text)
                                wv.evaluateJavascript(buildPostJs(text), null)
                            }
                        }
                    }
                }

                // Persistent Bangumi WebView: full-screen while logging in, kept alive
                // (1dp) afterwards so posts run same-origin from the real session.
                mk.ry.redollars.spike.ui.BangumiWebView(
                    visible = vm.showLogin,
                    onCreated = { webView = it },
                    onResult = vm::onLoggedIn,
                    onLog = vm::externalLog,
                    onPostResult = vm::onWebPostResult,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
    }
}

/**
 * Same-origin `/dollars?ajax=1` POST run inside the WebView. Matches a real captured
 * request: body is just `message=…` (no formhash — the endpoint authenticates via the
 * chii_auth / chii_sec_id cookies), with X-Requested-With; the browser adds cookies,
 * Origin, Referer and sec-fetch-* automatically.
 */
private fun buildPostJs(text: String): String {
    val msg = org.json.JSONObject.quote(text)
    return """
        (function(){
          try {
            fetch('/dollars?ajax=1', {
              method: 'POST',
              credentials: 'same-origin',
              headers: {
                'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                'X-Requested-With': 'XMLHttpRequest'
              },
              body: 'message=' + encodeURIComponent($msg)
            })
            .then(function(r){ return r.text().then(function(t){
              AndroidPost.deliver(JSON.stringify({ ok: r.ok, status: r.status, len: t.length, head: t.slice(0, 160) }));
            }); })
            .catch(function(e){ AndroidPost.deliver(JSON.stringify({ ok: false, status: -1, err: String(e) })); });
          } catch (e) { AndroidPost.deliver(JSON.stringify({ ok: false, status: -2, err: String(e) })); }
        })();
    """.trimIndent()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: SpikeViewModel, onSend: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (vm.connected) Color(0xFF34C759) else Color(0xFFFF3B30)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Re:Dollars Spike")
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "● ${vm.onlineCount} online",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SessionBar(vm)
            HorizontalDivider()
            MessageFeed(vm.messages, Modifier.weight(1f))
            LogPanel(vm.logs)
            HorizontalDivider()
            Composer(
                enabled = vm.session != null,
                status = vm.sendStatus,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun SessionBar(vm: SpikeViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s = vm.session
        Text(
            text = if (s == null) "Not logged in" else "Logged in as ${s.name} (uid ${s.uid})",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { vm.showLogin = true }) {
            Text(if (vm.session == null) "Login to Bangumi" else "Re-login")
        }
    }
}

private val timeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun MessageFeed(messages: List<MessageDto>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        items(messages, key = { it.id }) { m -> MessageRow(m) }
    }
}

@Composable
private fun MessageRow(m: MessageDto) {
    val nameColor = remember(m.color) {
        runCatching { Color(android.graphics.Color.parseColor(m.color)) }
            .getOrDefault(Color.Unspecified)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        AsyncImage(
            model = avatarUrl(m.avatar, 's'),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = m.nickname.ifBlank { "uid ${m.uid}" },
                    fontWeight = FontWeight.SemiBold,
                    color = nameColor,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeFmt.format(Instant.ofEpochSecond(m.timestamp)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (m.isDeleted) {
                Text(
                    text = "(deleted)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                m.replyDetails?.let { ReplyHeader(it, Modifier.fillMaxWidth()) }
                BBCodeMessage(m.message)
            }
        }
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    if (logs.isEmpty()) return
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(96.dp),
    ) {
        items(logs) { line ->
            Text(line, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Composer(enabled: Boolean, status: String?, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (status != null) {
            Text(
                status,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(if (enabled) "Send a test message…" else "Log in to post") },
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(text); text = "" },
                enabled = enabled && text.isNotBlank(),
            ) { Text("Send") }
        }
    }
}

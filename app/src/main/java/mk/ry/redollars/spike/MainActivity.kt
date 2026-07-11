package mk.ry.redollars.spike

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import mk.ry.redollars.spike.ui.chat.ChatScreen
import mk.ry.redollars.spike.ui.chat.Lightbox
import mk.ry.redollars.spike.ui.render.LocalImageViewer
import mk.ry.redollars.spike.ui.theme.RedollarsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpikeApp() }
    }
}

@Composable
private fun SpikeApp() {
    RedollarsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: SpikeViewModel = viewModel()
            LaunchedEffect(Unit) { vm.start() }
            LifecycleStartEffect(Unit) {
                vm.setForeground(true)
                onStopOrDispose { vm.setForeground(false) }
            }
            var webView by remember { mutableStateOf<WebView?>(null) }
            var lightboxUrl by remember { mutableStateOf<String?>(null) }

            CompositionLocalProvider(LocalImageViewer provides { url -> lightboxUrl = url }) {
                Box(Modifier.fillMaxSize()) {
                    if (!vm.showLogin) {
                        ChatScreen(
                            vm = vm,
                            onSend = { text ->
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
                            },
                            onOpenLogin = { vm.showLogin = true },
                        )
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

                    if (vm.showLogin) {
                        FilledTonalIconButton(
                            onClick = { vm.showLogin = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(8.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Close login")
                        }
                    }
                }
            }

            lightboxUrl?.let { url ->
                Lightbox(url = url, onDismiss = { lightboxUrl = null })
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

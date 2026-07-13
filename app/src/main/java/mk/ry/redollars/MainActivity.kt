package mk.ry.redollars

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.hilt.navigation.compose.hiltViewModel
import javax.inject.Inject
import mk.ry.redollars.bmo.BmoRenderer
import mk.ry.redollars.bmo.LocalBmoRenderer
import mk.ry.redollars.data.MessageRepository
import mk.ry.redollars.push.RedollarsMessagingService
import mk.ry.redollars.ui.chat.ChatScreen
import mk.ry.redollars.ui.chat.Lightbox
import mk.ry.redollars.ui.render.AudioPlayer
import mk.ry.redollars.ui.render.LocalAudioPlayer
import mk.ry.redollars.ui.render.LocalImageViewer
import mk.ry.redollars.ui.theme.RedollarsTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var bmoRenderer: BmoRenderer
    @Inject lateinit var audioPlayer: AudioPlayer
    @Inject lateinit var repo: MessageRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        consumeJumpIntent(intent)
        setContent { RedollarsApp(bmoRenderer, audioPlayer) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeJumpIntent(intent)
    }

    private fun consumeJumpIntent(intent: Intent?) {
        val id = intent?.getLongExtra(RedollarsMessagingService.EXTRA_JUMP_MESSAGE_ID, 0L) ?: 0L
        if (id > 0) {
            repo.pushJumpRequests.value = id
            intent?.removeExtra(RedollarsMessagingService.EXTRA_JUMP_MESSAGE_ID)
        }
    }
}

@Composable
private fun RedollarsApp(bmoRenderer: BmoRenderer, audioPlayer: AudioPlayer) {
    RedollarsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: ChatViewModel = hiltViewModel()
            LaunchedEffect(Unit) { vm.start() }
            LifecycleStartEffect(Unit) {
                vm.setForeground(true)
                onStopOrDispose { vm.setForeground(false) }
            }
            var webView by remember { mutableStateOf<WebView?>(null) }
            var lightboxUrl by remember { mutableStateOf<String?>(null) }

            // The VM requests the OAuth authorize flow after login when no valid
            // backend token is stored; drive it in the (still visible) login WebView.
            LaunchedEffect(vm.oauthRequestUrl) {
                vm.oauthRequestUrl?.let { webView?.loadUrl(it) }
            }

            CompositionLocalProvider(
                LocalImageViewer provides { url -> lightboxUrl = url },
                LocalBmoRenderer provides bmoRenderer,
                LocalAudioPlayer provides audioPlayer,
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (!vm.showLogin) {
                        ChatScreen(
                            vm = vm,
                            onSend = { text ->
                                val info = vm.session
                                val wv = webView
                                when {
                                    vm.editing != null -> vm.submitEdit(text)
                                    info == null -> vm.noteSend("Log in first")
                                    wv == null -> vm.noteSend("WebView not ready")
                                    else -> {
                                        val body = vm.beginSend(text)
                                        wv.evaluateJavascript(buildPostJs(body), null)
                                    }
                                }
                            },
                            onOpenLogin = { vm.showLogin = true },
                        )
                    }

                    // Persistent Bangumi WebView: full-screen while logging in, kept alive
                    // (1dp) afterwards so posts run same-origin from the real session.
                    mk.ry.redollars.ui.BangumiWebView(
                        visible = vm.showLogin,
                        onCreated = { webView = it },
                        onResult = vm::onLoggedIn,
                        onLog = vm::externalLog,
                        onPostResult = vm::onWebPostResult,
                        onAuthToken = vm::onAuthToken,
                        modifier = Modifier.align(Alignment.TopStart),
                    )

                    if (vm.showLogin) {
                        FilledTonalIconButton(
                            onClick = vm::dismissLogin,
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
                Lightbox(
                    url = url,
                    onDismiss = { lightboxUrl = null },
                    onSaveSticker = { vm.addFavorite(url) },
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

package mk.ry.redollars

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import dagger.hilt.android.AndroidEntryPoint
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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

// Upper bound on how long the splash may cover the cold-start load. Generous enough to
// let a returning user's hidden auto-login WebView spin up under the splash on slow starts.
private const val SPLASH_MAX_HOLD_MS = 2500L
// After the hidden auto-login WebView is inserted, how long its one-time surface-recreation
// flash takes to pass — kept under the splash so it's never visible over the chat.
private const val WEBVIEW_FLASH_SETTLE_MS = 500L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var bmoRenderer: BmoRenderer
    @Inject lateinit var audioPlayer: AudioPlayer
    @Inject lateinit var repo: MessageRepository

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Flipped true once the chat's first content frame has rendered; gates splash dismissal.
    private var contentDrawn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate(): shows the branded splash during cold start,
        // then swaps to postSplashScreenTheme (Theme.Redollars) at the first frame.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Hold the splash until the chat has actually drawn its first frame (set from
        // RedollarsApp below), so we go straight from the branded splash to content with
        // no blank window-background frames in between. Hard-capped so a stall can never
        // trap the user on the splash — after the cap we reveal whatever has rendered.
        val splashStart = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            !contentDrawn && SystemClock.uptimeMillis() - splashStart < SPLASH_MAX_HOLD_MS
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        consumeJumpIntent(intent)
        setContent { RedollarsApp(bmoRenderer, audioPlayer, onContentDrawn = { contentDrawn = true }) }
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
private fun RedollarsApp(
    bmoRenderer: BmoRenderer,
    audioPlayer: AudioPlayer,
    onContentDrawn: () -> Unit = {},
) {
    RedollarsTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val vm: ChatViewModel = hiltViewModel()
            LaunchedEffect(Unit) { vm.start() }

            var webView by remember { mutableStateOf<WebView?>(null) }
            var lightboxUrl by remember { mutableStateOf<String?>(null) }

            // A returning user (logged in on a previous launch) is auto-logged-in silently:
            // the login WebView is mounted hidden right away and re-derives the session in
            // the background from saved cookies — no login page, no tap. A first-time user
            // has no prior session, so nothing heavy is created until they open login/post.
            val autoLogin = remember { vm.hadPriorSession }

            // Inserting the Bangumi WebView forces a one-time window surface recreation that
            // briefly blanks the window white. So it is created only when actually needed —
            // silent auto-login here, or login / first-post on demand — and, for auto-login,
            // *under the splash* (see the hold below) so that flash is hidden. Once created
            // it stays mounted for the session so the flash never recurs.
            var mountWebView by remember { mutableStateOf(autoLogin) }

            // Hold the branded splash over the whole cold-start jank, then lift it straight
            // onto real content: the system-drawn splash stays smooth while our main thread
            // is busy, so releasing only once content is ready avoids exposing a blank
            // window. For auto-login, also wait for the hidden WebView's surface flash to
            // pass under the splash. Hard-capped in onCreate so a stall can't trap the user.
            LaunchedEffect(Unit) {
                vm.messages.first { it.isNotEmpty() }
                if (autoLogin) {
                    snapshotFlow { webView != null }.first { it }
                    delay(WEBVIEW_FLASH_SETTLE_MS)
                }
                withFrameNanos { }
                onContentDrawn()
            }

            LifecycleStartEffect(Unit) {
                vm.setForeground(true)
                onStopOrDispose { vm.setForeground(false) }
            }

            // The VM requests the rymk-auth flow after login when no valid backend token
            // is stored; drive it in the (now visible) login WebView.
            LaunchedEffect(vm.oauthRequestUrl) {
                vm.oauthRequestUrl?.let { webView?.loadUrl(it) }
            }

            // After rymk-auth, the VM asks to return the WebView to the Bangumi origin so
            // same-origin posting works again.
            LaunchedEffect(vm.webViewReloadUrl) {
                vm.webViewReloadUrl?.let { webView?.loadUrl(it); vm.onWebViewReloaded() }
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
                                    wv == null -> {
                                        mountWebView = true   // not preloaded yet; kick it off
                                        vm.noteSend("One sec — getting ready, try again")
                                    }
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
                    // Mounted lazily (after first frame, or immediately if login opens) so
                    // its Chromium init stays off the cold-start critical path.
                    if (mountWebView || vm.showLogin) {
                        mk.ry.redollars.ui.BangumiWebView(
                            visible = vm.showLogin,
                            onCreated = { webView = it; mountWebView = true },
                            onResult = vm::onLoggedIn,
                            onLog = vm::externalLog,
                            onPostResult = vm::onWebPostResult,
                            onAuthToken = vm::onAuthToken,
                            onIgnoreList = vm::onIgnoreUsers,
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }

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

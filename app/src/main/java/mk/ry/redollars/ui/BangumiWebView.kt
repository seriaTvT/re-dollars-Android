package mk.ry.redollars.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mk.ry.redollars.SessionInfo
import mk.ry.redollars.net.AppJson
import mk.ry.redollars.net.Config

private const val EXTRACT_JS = """
(function(){
  try {
    var fh = document.querySelector('input[name=formhash]');
    return JSON.stringify({
      uid: (typeof CHOBITS_UID !== 'undefined' && CHOBITS_UID) ? String(CHOBITS_UID) : null,
      formhash: fh ? fh.value : null,
      name: (typeof CHOBITS_USERNAME !== 'undefined') ? CHOBITS_USERNAME : null
    });
  } catch (e) { return null; }
})();
"""

/** Bangumi renders its ignore list into logged-in pages as the `data_ignore_users`
 *  global (mixed usernames and uids) — the same source user.ts reads on the web. */
private const val IGNORE_JS = """
(function(){
  try {
    if (typeof data_ignore_users === 'undefined' || !data_ignore_users) return null;
    return JSON.stringify(Array.prototype.map.call(data_ignore_users, String));
  } catch (e) { return null; }
})();
"""

/**
 * Installed on auth.ry.mk pages. rymk-auth's popup callback returns the JWT by calling
 * `window.opener.postMessage(...)`, and it guards that with `window.opener && ...`. A
 * top-level WebView load has no opener, so unless we plant one *before* the page's own
 * script runs, that guard short-circuits and the token is silently dropped. So this must
 * land at document-start; the getter (not a bare assignment) also survives the
 * `window.opener = null` a security-minded page may run first. window.name is set to the
 * popup name the web flow uses, and the shim carries no-op close/focus in case the page
 * calls them. It reports back via AndroidAuth.log so the debug log shows it ran.
 */
private const val OPENER_SHIM_JS = """
(function(){
  try { if (window.name !== 'rymk-auth') window.name = 'rymk-auth'; } catch (e) {}
  if (window.__rdAuthHook) return;
  window.__rdAuthHook = true;
  function jlog(m){ try { if (window.AndroidAuth && AndroidAuth.log) AndroidAuth.log(String(m)); } catch (e) {} }
  function forward(data){
    try {
      var s = (typeof data === 'string') ? data : JSON.stringify(data);
      if (window.AndroidAuth && AndroidAuth.deliver) AndroidAuth.deliver(s);
    } catch (e) { jlog('forward error: ' + e); }
  }
  var shim = {
    postMessage: function(data, targetOrigin){ forward(data); },
    close: function(){}, focus: function(){}, blur: function(){}
  };
  try {
    Object.defineProperty(window, 'opener', {
      configurable: true,
      get: function(){ return shim; },
      set: function(){}
    });
  } catch (e) {
    try { window.opener = shim; } catch (e2) { jlog('opener install failed: ' + e2); }
  }
  jlog('opener shim installed @ ' + location.href);
})();
"""

private const val AUTH_ORIGIN = "https://auth.ry.mk"

/** Hosts this WebView may navigate to: the Bangumi mirrors (login, posting, the OAuth
 *  chain) and the rymk-auth server. Everything else leaves for the real browser, so a
 *  foreign page never runs with the AndroidPost/AndroidAuth bridges or the logged-in
 *  Bangumi cookie jar attached. */
private val ALLOWED_NAV_HOSTS = setOf("bgm.tv", "bangumi.tv", "chii.in", "auth.ry.mk")

private fun isAllowedNavigation(uri: Uri): Boolean {
    if (uri.scheme != "http" && uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return host in ALLOWED_NAV_HOSTS || ALLOWED_NAV_HOSTS.any { host.endsWith(".$it") }
}

private data class Parsed(val uid: Long?, val name: String?, val formhash: String?)

private fun parseSession(raw: String): Parsed? {
    val el = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() ?: return null
    val inner = (el as? JsonPrimitive)?.contentOrNull ?: return null
    if (inner == "null" || inner.isBlank()) return null
    val obj = runCatching { AppJson.parseToJsonElement(inner).jsonObject }.getOrNull() ?: return null
    val uid = obj["uid"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    val formhash = obj["formhash"]?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }
    val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }
    return Parsed(uid, name, formhash)
}

/** Unwraps the double-encoded IGNORE_JS result into the raw ignore-list entries.
 *  null (not empty) when the global is absent, so a logged-out page is a no-op. */
private fun parseIgnoreList(raw: String): List<String>? {
    val el = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() ?: return null
    val inner = (el as? JsonPrimitive)?.contentOrNull ?: return null
    if (inner == "null" || inner.isBlank()) return null
    return runCatching {
        AppJson.parseToJsonElement(inner).jsonArray.map { it.jsonPrimitive.content }
    }.getOrNull()
}

private data class AuthMessage(val ok: Boolean, val token: String?, val state: String?)

/** Parses a rymk-auth postMessage payload: `{type:'rymk_auth', ok, token, state}`. */
private fun parseAuthMessage(raw: String): AuthMessage? {
    val obj = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "rymk_auth") return null
    return AuthMessage(
        ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false,
        token = obj["token"]?.jsonPrimitive?.contentOrNull,
        state = obj["state"]?.jsonPrimitive?.contentOrNull,
    )
}

/** Bridge so the in-page fetch() can report its async result back to Kotlin. */
private class PostBridge(private val cb: (String) -> Unit) {
    @JavascriptInterface
    fun deliver(json: String) = cb(json)
}

/** Bridge for the rymk-auth flow. [onMessage] receives the postMessage payload from the
 *  opener shim; [onDiag] surfaces the shim's own trace into the debug log. Both fire on a
 *  JS/binder thread, so the ViewModel marshals onto Main before touching state. */
private class AuthBridge(
    private val onMessage: (String) -> Unit,
    private val onDiag: (String) -> Unit,
) {
    @JavascriptInterface
    fun deliver(json: String) = onMessage(json)

    @JavascriptInterface
    fun log(msg: String) = onDiag(msg)
}

/**
 * Single persistent Bangumi WebView. Full-screen while [visible] (login / rymk-auth);
 * otherwise kept alive off-screen so messages can be POSTed *same-origin* from the real
 * logged-in session (see MainActivity.buildPostJs). This mirrors the userscript, whose
 * fetch to `/dollars?ajax=1` succeeds precisely because it runs on the Bangumi origin.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BangumiWebView(
    visible: Boolean,
    onCreated: (WebView) -> Unit,
    onResult: (SessionInfo) -> Unit,
    onLog: (String) -> Unit,
    onPostResult: (String) -> Unit,
    onAuthToken: (String, String?) -> Unit = { _, _ -> },
    onIgnoreList: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = if (visible) modifier.fillMaxSize() else modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                onCreated(this)
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(PostBridge(onPostResult), "AndroidPost")
                addJavascriptInterface(
                    AuthBridge(
                        onMessage = { raw ->
                            onLog("rymk-auth payload: ${raw.take(120)}")
                            when (val msg = parseAuthMessage(raw)) {
                                null -> {}
                                else -> if (msg.ok && !msg.token.isNullOrBlank()) {
                                    onLog("rymk-auth: JWT received")
                                    onAuthToken(msg.token, msg.state)
                                } else {
                                    onLog("rymk-auth: login reported failure (ok=${msg.ok})")
                                }
                            }
                        },
                        onDiag = { m -> onLog("rymk-auth js: $m") },
                    ),
                    "AndroidAuth",
                )

                // Preferred: inject the opener shim at document-start, scoped to
                // auth.ry.mk, so it is in place before the callback page's own script
                // reads window.opener. Without this the postMessage silently no-ops.
                // onPageStarted/onPageFinished re-inject as a backstop (guarded).
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(this, OPENER_SHIM_JS, setOf(AUTH_ORIGIN))
                    }
                        .onSuccess { onLog("rymk-auth: document-start shim registered") }
                        .onFailure { onLog("rymk-auth: document-start registration failed (${it.message}); using onPage fallback") }
                } else {
                    onLog("rymk-auth: DOCUMENT_START_SCRIPT unsupported; using onPage fallback")
                }

                webViewClient = object : WebViewClient() {
                    private var done = false

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val uri = request.url
                        if (isAllowedNavigation(uri)) return false
                        // Foreign http(s) links open in the browser; other schemes
                        // (intent:, tel:, custom app links) are dropped outright.
                        if (uri.scheme == "http" || uri.scheme == "https") {
                            runCatching {
                                view.context.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        // A fresh login always (re)starts at the Bangumi login page — logout
                        // navigates this persistent WebView there. Reset the one-shot session
                        // probe so a re-login is detected again; without this, `done` stays
                        // latched from the first login and onPageFinished skips the re-login
                        // (the WebView is no longer recreated between logins).
                        if (url != null && url.startsWith("${Config.BGM_HOST}/login")) done = false
                        // Trace the OAuth redirect chain so a stall (bgm.tv second login,
                        // Cloudflare interstitial, unexpected page) is visible in the log.
                        if (url != null && (url.startsWith(AUTH_ORIGIN) || url.contains("/oauth/"))) {
                            onLog("rymk-auth nav: ${url.take(100)}")
                        }
                        // Backstop for WebView builds without DOCUMENT_START_SCRIPT: plant
                        // the opener shim as early as we can on auth.ry.mk pages. The
                        // __rdAuthHook guard makes a double install a no-op.
                        if (url != null && url.startsWith(AUTH_ORIGIN)) {
                            view.evaluateJavascript(OPENER_SHIM_JS, null)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // Last-chance shim install for auth.ry.mk pages that postMessage
                        // late (on load rather than during parse). Runs before the done
                        // gate so it isn't skipped once the session is already captured.
                        if (url != null && url.startsWith(AUTH_ORIGIN)) {
                            view.evaluateJavascript(OPENER_SHIM_JS, null)
                        }
                        // Harvest the site ignore list from any logged-in Bangumi page
                        // (also after `done`: the post-auth reload to /dollars lands here).
                        if (url != null && url.startsWith(Config.BGM_HOST) && !url.contains("/oauth/")) {
                            view.evaluateJavascript(IGNORE_JS) { raw ->
                                parseIgnoreList(raw)?.let(onIgnoreList)
                            }
                        }
                        if (done) return
                        // Skip the OAuth redirect chain: auth.ry.mk and bgm.tv/oauth/*
                        // are logged-in Bangumi pages that still expose CHOBITS_UID, so
                        // probing them here would mis-fire onLoggedIn and restart the
                        // token exchange. The JWT arrives via the opener shim, not this
                        // probe, so nothing is lost by ignoring these pages.
                        if (url != null && (url.startsWith(AUTH_ORIGIN) || url.contains("/oauth/"))) return
                        view.evaluateJavascript(EXTRACT_JS) { raw ->
                            val parsed = parseSession(raw) ?: return@evaluateJavascript
                            // uid present => logged in. formhash is optional (posting
                            // doesn't need it), so we complete as soon as we know the uid.
                            val uid = parsed.uid ?: return@evaluateJavascript
                            done = true
                            CookieManager.getInstance().flush()
                            val fhNote = parsed.formhash?.let { ", formhash=${it.take(8)}…" } ?: ""
                            onLog("Logged in: uid=$uid$fhNote")
                            onResult(SessionInfo(uid, parsed.name ?: uid.toString(), parsed.formhash ?: ""))
                        }
                    }
                }
                loadUrl("${Config.BGM_HOST}/login")
            }
        },
    )
}

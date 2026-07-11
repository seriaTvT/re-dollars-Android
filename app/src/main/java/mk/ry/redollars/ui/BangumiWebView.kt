package mk.ry.redollars.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

/** Bridge so the in-page fetch() can report its async result back to Kotlin. */
private class PostBridge(private val cb: (String) -> Unit) {
    @JavascriptInterface
    fun deliver(json: String) = cb(json)
}

/**
 * Single persistent Bangumi WebView. Full-screen while [visible] (login); otherwise
 * kept alive off-screen so messages can be POSTed *same-origin* from the real logged-in
 * session (see MainActivity.buildPostJs). This mirrors the userscript, whose fetch to
 * `/dollars?ajax=1` succeeds precisely because it runs on the Bangumi origin.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun BangumiWebView(
    visible: Boolean,
    onCreated: (WebView) -> Unit,
    onResult: (SessionInfo) -> Unit,
    onLog: (String) -> Unit,
    onPostResult: (String) -> Unit,
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

                webViewClient = object : WebViewClient() {
                    private var done = false

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (done) return
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

package miku.moe.app

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object SokujaWebViewSession {
    private const val BASE_URL = "https://x6.sokuja.uk/"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun storedCookieHeader(): String? {
        return try {
            CookieManager.getInstance().getCookie(BASE_URL)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun syncToOkHttp() {
        val header = storedCookieHeader() ?: return
        try {
            SokujaSource.setWebViewCookies(header)
        } catch (_: Exception) {
        }
    }

    suspend fun ensureSession(context: Context) {
        withContext(Dispatchers.Main) {
            val existing = storedCookieHeader()
            if (existing != null) {
                syncToOkHttp()
                return@withContext
            }
            try {
                withTimeoutOrNull(25_000L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        var webView: WebView? = null
                        var parent: ViewGroup? = null
                        var done = false
                        fun finish() {
                            if (done) return
                            done = true
                            try {
                                parent?.removeView(webView)
                            } catch (_: Exception) {
                            }
                            try {
                                webView?.stopLoading()
                                webView?.destroy()
                            } catch (_: Exception) {
                            }
                            webView = null
                            syncToOkHttp()
                            if (cont.isActive) cont.resume(Unit)
                        }
                        try {
                            val manager = CookieManager.getInstance()
                            manager.setAcceptCookie(true)
                            val view = WebView(context)
                            webView = view
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.userAgentString = BROWSER_UA
                            try {
                                val decor = (context as? Activity)?.window?.decorView as? ViewGroup
                                if (decor != null) {
                                    parent = decor
                                    view.layoutParams = ViewGroup.LayoutParams(1, 1)
                                    decor.addView(view)
                                }
                            } catch (_: Exception) {
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    finish()
                                }
                            }
                            cont.invokeOnCancellation { finish() }
                            view.loadUrl(BASE_URL)
                        } catch (_: Exception) {
                            finish()
                        }
                    }
                }
            } catch (_: Exception) {
            }
            syncToOkHttp()
        }
    }

    private val STARTER_JS = """
        (function(){
          window.__mikuVideo = '';
          try {
            var html = document.documentElement.innerHTML;
            var q = html.indexOf('qualityOptions');
            if (q < 0) { window.__mikuVideo = 'ERR:no-quality'; return; }
            var head = html.substring(0, q);
            var re = /\\?"episodeId\\?":\s*(\d+)/g;
            var m, last = null;
            while ((m = re.exec(head)) !== null) { last = m[1]; }
            if (!last) { window.__mikuVideo = 'ERR:no-id'; return; }
            fetch('/api/video-mirrors/?e=' + last, {headers: {'Accept': 'application/json'}})
              .then(function(r){ return r.text(); })
              .then(function(t){ window.__mikuVideo = 'OK:' + btoa(unescape(encodeURIComponent(t))); })
              .catch(function(e){ window.__mikuVideo = 'ERR:' + e; });
          } catch (e) { window.__mikuVideo = 'ERR:' + e; }
        })()
    """.trimIndent()

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null" || raw.isBlank()) return null
        return try {
            if (raw.startsWith("\"")) org.json.JSONObject("{\"v\":$raw}").optString("v") else raw
        } catch (_: Exception) {
            raw.trim('"')
        }
    }

    suspend fun fetchVideoResult(context: Context, episodeSlug: String): SokujaSource.VideoResult? {
        return withContext(Dispatchers.Main) {
            try {
                withTimeoutOrNull(35_000L) {
                    suspendCancellableCoroutine<SokujaSource.VideoResult?> { cont ->
                        var webView: WebView? = null
                        var parent: ViewGroup? = null
                        var done = false
                        fun finish(result: SokujaSource.VideoResult?) {
                            if (done) return
                            done = true
                            try {
                                parent?.removeView(webView)
                            } catch (_: Exception) {
                            }
                            try {
                                webView?.stopLoading()
                                webView?.destroy()
                            } catch (_: Exception) {
                            }
                            webView = null
                            if (cont.isActive) cont.resume(result)
                        }
                        try {
                            val manager = CookieManager.getInstance()
                            manager.setAcceptCookie(true)
                            val view = WebView(context)
                            webView = view
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.userAgentString = BROWSER_UA
                            try {
                                val decor = (context as? Activity)?.window?.decorView as? ViewGroup
                                if (decor != null) {
                                    parent = decor
                                    view.layoutParams = ViewGroup.LayoutParams(1, 1)
                                    decor.addView(view)
                                }
                            } catch (_: Exception) {
                            }
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            var pollStart = 0L
                            val poll = object : Runnable {
                                override fun run() {
                                    if (done) return
                                    val v = webView ?: run { finish(null); return }
                                    try {
                                        v.evaluateJavascript("window.__mikuVideo || ''") { raw ->
                                            if (done) return@evaluateJavascript
                                            val decoded = decodeJsString(raw)
                                            if (decoded != null && decoded.startsWith("OK:")) {
                                                val result = try {
                                                    val bytes = android.util.Base64.decode(decoded.substring(3), android.util.Base64.DEFAULT)
                                                    SokujaSource.parseVideoJson(String(bytes, Charsets.UTF_8))
                                                } catch (_: Exception) {
                                                    null
                                                }
                                                finish(result)
                                            } else if (decoded != null && decoded.startsWith("ERR:")) {
                                                finish(null)
                                            } else if (android.os.SystemClock.uptimeMillis() - pollStart > 25_000L) {
                                                finish(null)
                                            } else {
                                                handler.postDelayed(this, 500L)
                                            }
                                        }
                                    } catch (_: Exception) {
                                        finish(null)
                                    }
                                }
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    pollStart = android.os.SystemClock.uptimeMillis()
                                    try {
                                        view.evaluateJavascript(STARTER_JS, null)
                                    } catch (_: Exception) {
                                        finish(null)
                                        return
                                    }
                                    handler.postDelayed(poll, 500L)
                                }
                            }
                            cont.invokeOnCancellation { finish(null) }
                            view.loadUrl(BASE_URL + episodeSlug.trim() + "/")
                        } catch (_: Exception) {
                            finish(null)
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

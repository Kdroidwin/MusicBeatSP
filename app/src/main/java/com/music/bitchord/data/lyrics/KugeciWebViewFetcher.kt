package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Kugeci can serve a Cloudflare JavaScript challenge instead of its search or
 * lyrics page to OkHttp. A real WebView can run that first-party challenge and
 * keep its short-lived clearance cookie for the subsequent Kugeci pages.
 */
internal object KugeciWebViewFetcher {
    private const val TIMEOUT_MS = 18_000L
    private const val INSPECTION_INTERVAL_MS = 500L

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var retryAfterElapsedMs = 0L
    private val requestMutex = Mutex()

    val isCoolingDown: Boolean
        get() = SystemClock.elapsedRealtime() < retryAfterElapsedMs

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun fetchHtml(url: String): String? {
        if (AppSettings.offlineMode.value) return null
        if (!Uri.parse(url).host.equals("www.kugeci.com", ignoreCase = true)) return null
        if (isCoolingDown) return null
        val context = appContext ?: return null
        val html = withTimeoutOrNull(TIMEOUT_MS) {
            requestMutex.withLock {
                withContext(Dispatchers.Main.immediate) {
                    suspendCancellableCoroutine { continuation ->
                        val handler = Handler(Looper.getMainLooper())
                        val cancelled = AtomicBoolean(false)
                        var webView: WebView? = null
                        var finished = false
                        var inspecting = false
                        val startedAt = SystemClock.elapsedRealtime()
                        var inspect: (() -> Unit)? = null
                        val inspectRunnable = Runnable { inspect?.invoke() }
                        var finish: (String?) -> Unit = {}
                        val timeoutRunnable = Runnable { finish(null) }

                        finish = { html ->
                            if (!finished) {
                                finished = true
                                handler.removeCallbacks(inspectRunnable)
                                handler.removeCallbacks(timeoutRunnable)
                                webView?.let { view ->
                                    runCatching { view.stopLoading() }
                                    runCatching { view.webViewClient = WebViewClient() }
                                    runCatching { view.destroy() }
                                }
                                webView = null
                                if (continuation.isActive) continuation.resume(html)
                            }
                        }

                        inspect = {
                            val view = webView
                            if (!finished && !inspecting && view != null) {
                                inspecting = true
                                view.evaluateJavascript("document.documentElement?.outerHTML || ''") { encoded ->
                                    inspecting = false
                                    if (!finished) {
                                        val html = decodeJavascriptString(encoded)
                                        when {
                                            !html.isNullOrBlank() && !isCloudflareChallenge(html) -> finish(html)
                                            SystemClock.elapsedRealtime() - startedAt >= TIMEOUT_MS - INSPECTION_INTERVAL_MS -> finish(null)
                                            else -> handler.postDelayed(inspectRunnable, INSPECTION_INTERVAL_MS)
                                        }
                                    }
                                }
                            }
                        }

                        continuation.invokeOnCancellation {
                            cancelled.set(true)
                            handler.post { finish(null) }
                        }

                        try {
                            CookieManager.getInstance().setAcceptCookie(true)
                            webView = WebView(context).apply {
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                                        if (!finished && !cancelled.get()) {
                                            handler.removeCallbacks(inspectRunnable)
                                            handler.postDelayed(inspectRunnable, 300L)
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) finish(null)
                                    }
                                }
                            }
                            handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
                            webView?.loadUrl(url)
                        } catch (_: Throwable) {
                            finish(null)
                        }
                    }
                }
            }
        }
        retryAfterElapsedMs = if (html.isNullOrBlank()) {
            SystemClock.elapsedRealtime() + 60_000L
        } else 0L
        return html
    }

    private fun decodeJavascriptString(value: String?): String? {
        val encoded = value ?: return null
        return runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull()
    }

    internal fun isCloudflareChallenge(html: String): Boolean {
        val title = Jsoup.parse(html).title().trim()
        if (title.equals("Just a moment...", ignoreCase = true) ||
            title.equals("Attention Required! | Cloudflare", ignoreCase = true)
        ) return true
        val lower = html.lowercase()
        return "cf-chl-" in lower || "cf_chl_opt" in lower || "challenge-platform" in lower ||
            "enable javascript and cookies to continue" in lower
    }
}

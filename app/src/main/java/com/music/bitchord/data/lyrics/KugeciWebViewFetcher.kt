package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
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

    /** Shares the clearance cookie with OkHttp so later pages do not hit the challenge again. */
    fun cookieFor(url: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)
    }.getOrNull()

    /** Uses the same user agent that received the site's clearance cookie. */
    fun userAgent(): String? = appContext?.let { context ->
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
    }

    suspend fun fetchHtml(url: String): String? {
        if (AppSettings.offlineMode.value) return null
        if (!isAllowedKugeciPage(Uri.parse(url))) return null
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
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                settings.safeBrowsingEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: android.webkit.WebResourceRequest,
                                    ): Boolean {
                                        // The page needs HTTPS subresources for its challenge,
                                        // but it must never be able to navigate the WebView to
                                        // cleartext, a local address, or an unrelated origin.
                                        return if (request.isForMainFrame) {
                                            !isAllowedKugeciPage(request.url)
                                        } else {
                                            !request.url.scheme.equals("https", ignoreCase = true)
                                        }
                                    }

                                    @Suppress("DEPRECATION")
                                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                                        !isAllowedKugeciPage(Uri.parse(url))

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        if (!isAllowedKugeciPage(Uri.parse(url.orEmpty()))) {
                                            finish(null)
                                            view.stopLoading()
                                        }
                                    }

                                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                                        if (!finished && !cancelled.get()) {
                                            // Cloudflare's clearance cookie is
                                            // persisted before the following
                                            // search/detail request is issued.
                                            runCatching { CookieManager.getInstance().flush() }
                                            handler.removeCallbacks(inspectRunnable)
                                            handler.postDelayed(inspectRunnable, 300L)
                                        }
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

    private fun isAllowedKugeciPage(uri: Uri): Boolean =
        uri.scheme.equals("https", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            (uri.host?.equals("www.kugeci.com", ignoreCase = true) == true ||
                uri.host?.equals("kugeci.com", ignoreCase = true) == true)

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

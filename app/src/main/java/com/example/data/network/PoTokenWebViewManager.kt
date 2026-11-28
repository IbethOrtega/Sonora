package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class PoTokenSessionData(
    val visitorData: String? = null,
    val playerRequestPoToken: String? = null,
    val streamingDataPoToken: String? = null,
    val obtainedAtMs: Long = System.currentTimeMillis()
) {
    val bestPoToken: String?
        get() = streamingDataPoToken?.takeIf { it.isNotBlank() }
            ?: playerRequestPoToken?.takeIf { it.isNotBlank() }
}

/**
 * [TokenProvider] backed by a persistent offscreen Chromium WebView BotGuard session.
 *
 * Rules:
 * - Maintains at most ONE active BotGuard WebView page per `(visitorData, sessionCookie)`.
 * - Recreates the page automatically if it expired, died, or the session changed.
 * - Retries once with a freshly recreated page if token minting fails.
 * - Enforces an 8-second hard timeout returning `null` so extraction can proceed without PoToken.
 */
class PoTokenWebViewManager(
    context: Context
) : TokenProvider {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    override val capabilities: TokenProviderCapabilities = TokenProviderCapabilities(
        providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
        usesWebView = true
    )

    private class BotGuardPageSession(
        val webView: WebView,
        val visitorData: String,
        val cookieSignature: Int,
        val createdAtMs: Long = System.currentTimeMillis()
    ) {
        val isAlive = AtomicBoolean(true)
        val mintWaiters = ConcurrentHashMap<String, CompletableDeferred<String?>>()

        @Volatile
        var expiresAtMs: Long = createdAtMs + DEFAULT_SESSION_TTL_MS

        @Volatile
        var cachedPlayerRequestToken: String? = null

        @Volatile
        var latestGvsToken: String? = null

        @Volatile
        var minterReady: Boolean = false

        val readySignal = CompletableDeferred<Boolean>()

        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            nowMs >= expiresAtMs
    }

    @Volatile
    private var activeSession: BotGuardPageSession? = null

    @Volatile
    private var rendererCrashedUntilMs: Long = 0L

    internal var clockMs: () -> Long = { System.currentTimeMillis() }

    internal fun isRendererInCooldown(nowMs: Long = clockMs()): Boolean =
        nowMs < maxOf(rendererCrashedUntilMs, processRendererCrashedUntilMs)

    internal fun recordRendererProcessCrash(nowMs: Long = clockMs()) {
        val until = nowMs + RENDERER_CRASH_COOLDOWN_MS
        rendererCrashedUntilMs = until
        processRendererCrashedUntilMs = maxOf(processRendererCrashedUntilMs, until)
    }

    fun clearCache() {
        val stale = activeSession
        activeSession = null
        if (stale != null) {
            stale.isAlive.set(false)
            destroySessionAsync(stale)
        }
    }

    override suspend fun getPoToken(
        videoId: String,
        visitorData: String,
        cookie: String?
    ): PoTokenResult? {
        if (visitorData.isBlank() || isRendererInCooldown() || isUnsupportedHeadlessWebView()) {
            return null
        }
        return withTimeoutOrNull(PO_TOKEN_TIMEOUT_MS) {
            mutex.withLock {
                if (isRendererInCooldown() || isUnsupportedHeadlessWebView()) {
                    return@withLock null
                }
                val cookieSig = cookie?.trim().orEmpty().hashCode()
                val firstAttempt = obtainTokensFromPageLocked(
                    videoId = videoId,
                    visitorData = visitorData,
                    cookie = cookie,
                    cookieSig = cookieSig,
                    forceRecreate = false
                )
                if (firstAttempt != null) {
                    return@withLock firstAttempt
                }

                if (isRendererInCooldown()) {
                    Log.w(TAG, "ITX BotGuard skipping retry during WebView renderer cooldown")
                    return@withLock null
                }

                // One retry with a brand-new BotGuard page
                Log.w(TAG, "ITX BotGuard token generation failed; recreating WebView page once for retry")
                obtainTokensFromPageLocked(
                    videoId = videoId,
                    visitorData = visitorData,
                    cookie = cookie,
                    cookieSig = cookieSig,
                    forceRecreate = true
                )
            }
        }
    }

    override suspend fun close() {
        mutex.withLock {
            val existing = activeSession
            activeSession = null
            if (existing != null) {
                destroySessionOnMain(existing)
            }
        }
    }

    private suspend fun obtainTokensFromPageLocked(
        videoId: String,
        visitorData: String,
        cookie: String?,
        cookieSig: Int,
        forceRecreate: Boolean
    ): PoTokenResult? {
        val now = System.currentTimeMillis()
        val current = activeSession
        val needsNewPage = forceRecreate ||
            current == null ||
            !current.isAlive.get() ||
            current.isExpired(now) ||
            current.visitorData != visitorData ||
            current.cookieSignature != cookieSig

        val session = if (needsNewPage) {
            if (current != null) {
                activeSession = null
                destroySessionOnMain(current)
            }
            createBotGuardPageOnMain(
                videoId = videoId,
                visitorData = visitorData,
                cookie = cookie,
                cookieSig = cookieSig
            ) ?: return null
        } else {
            current!!
        }

        // Wait briefly for the page's initial BotGuard runtime / capture if just created
        if (!session.readySignal.isCompleted) {
            withTimeoutOrNull(INITIAL_PAGE_WAIT_MS) {
                session.readySignal.await()
            }
        }

        if (!session.isAlive.get() || session.isExpired()) {
            return null
        }

        // 1) Obtain session playerRequestToken bound to visitorData
        val playerToken = session.cachedPlayerRequestToken?.takeIf { it.isNotBlank() }
            ?: mintIdentifierOnSession(session, visitorData)?.also {
                session.cachedPlayerRequestToken = it
            }

        // 2) Obtain streamingDataToken bound to videoId (or minted stream token)
        val streamingToken = if (videoId.isNotBlank()) {
            mintIdentifierOnSession(session, videoId)
                ?: session.latestGvsToken?.takeIf { it.isNotBlank() }
        } else {
            session.latestGvsToken?.takeIf { it.isNotBlank() }
        }

        val resolvedPlayerToken = playerToken?.takeIf { it.isNotBlank() }
            ?: streamingToken?.takeIf { it.isNotBlank() }
            ?: return null
        val resolvedStreamingToken = streamingToken?.takeIf { it.isNotBlank() }
            ?: resolvedPlayerToken

        return PoTokenResult(
            playerRequestToken = resolvedPlayerToken,
            streamingDataToken = resolvedStreamingToken,
            visitorData = visitorData
        )
    }

    private suspend fun mintIdentifierOnSession(
        session: BotGuardPageSession,
        identifier: String
    ): String? {
        if (!session.isAlive.get() || session.isExpired() || identifier.isBlank()) {
            return null
        }
        val reqId = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<String?>()
        session.mintWaiters[reqId] = waiter

        return try {
            val invoked = withContext(Dispatchers.Main) {
                if (!session.isAlive.get()) return@withContext false
                val safeReq = JSONObject.quote(reqId)
                val safeId = JSONObject.quote(identifier)
                session.webView.evaluateJavascript(
                    "window.__sonoraMintPoToken && window.__sonoraMintPoToken($safeReq, $safeId);",
                    null
                )
                true
            }
            if (!invoked) return null
            withTimeoutOrNull(MINT_CALL_TIMEOUT_MS) {
                waiter.await()
            }?.takeIf { it.isNotBlank() }
        } finally {
            session.mintWaiters.remove(reqId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun createBotGuardPageOnMain(
        videoId: String,
        visitorData: String,
        cookie: String?,
        cookieSig: Int
    ): BotGuardPageSession? = withContext(Dispatchers.Main) {
        if (isRendererInCooldown() || isUnsupportedHeadlessWebView()) return@withContext null
        try {
            syncCookies(cookie)

            val view = WebView(appContext)
            // Keep offscreen BotGuard WebView in software mode so emulators without GPU
            // rendernodes don't block hardware compositor frames.
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
                userAgentString = StreamResolver.BROWSER_USER_AGENT
            }

            val session = BotGuardPageSession(
                webView = view,
                visitorData = visitorData,
                cookieSignature = cookieSig
            )

            val bridge = object {
                @JavascriptInterface
                fun onMinterReady(lifetimeSeconds: Long) {
                    session.minterReady = true
                    if (lifetimeSeconds > 60L) {
                        val safeTtlMs = (lifetimeSeconds - 30L).coerceAtMost(MAX_SESSION_TTL_SEC) * 1000L
                        session.expiresAtMs = System.currentTimeMillis() + safeTtlMs
                    }
                    if (!session.readySignal.isCompleted) {
                        session.readySignal.complete(true)
                    }
                }

                @JavascriptInterface
                fun onPoTokenCaptured(playerPoToken: String?, capturedVisitor: String?) {
                    val token = playerPoToken?.trim()?.takeIf { it.isNotBlank() } ?: return
                    if (capturedVisitor.isNullOrBlank() || capturedVisitor == visitorData) {
                        session.cachedPlayerRequestToken = token
                    }
                    if (!session.readySignal.isCompleted) {
                        session.readySignal.complete(true)
                    }
                }

                @JavascriptInterface
                fun onGvsPoTokenCaptured(pot: String?) {
                    val clean = pot?.trim()?.takeIf { it.isNotBlank() } ?: return
                    session.latestGvsToken = clean
                    if (!session.readySignal.isCompleted && !session.cachedPlayerRequestToken.isNullOrBlank()) {
                        session.readySignal.complete(true)
                    }
                }

                @JavascriptInterface
                fun onMintResult(reqId: String?, token: String?) {
                    val waiter = reqId?.let { session.mintWaiters[it] } ?: return
                    waiter.complete(token?.trim()?.takeIf { it.isNotBlank() })
                }

                @JavascriptInterface
                fun onFatalError(reason: String?) {
                    Log.w(TAG, "ITX BotGuard page error: ${reason.orEmpty()}")
                    if (!session.readySignal.isCompleted) {
                        session.readySignal.complete(false)
                    }
                }
            }

            view.addJavascriptInterface(bridge, BRIDGE_NAME)
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    v?.evaluateJavascript(buildHookJs(visitorData), null)
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    v?.evaluateJavascript(buildHookJs(visitorData), null)
                }

                override fun onRenderProcessGone(
                    v: WebView?,
                    detail: RenderProcessGoneDetail?
                ): Boolean {
                    Log.w(TAG, "ITX BotGuard WebView render process died; entering cooldown")
                    recordRendererProcessCrash()
                    session.isAlive.set(false)
                    session.mintWaiters.values.forEach { it.complete(null) }
                    if (!session.readySignal.isCompleted) {
                        session.readySignal.complete(false)
                    }
                    if (activeSession === session) {
                        activeSession = null
                    }
                    runCatching {
                        v?.webViewClient = WebViewClient()
                        v?.stopLoading()
                        v?.destroy()
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    v: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString().orEmpty()
                    if (reqUrl.isBlank()) return null
                    val lower = reqUrl.lowercase()

                    if (lower.contains("googlevideo.com/videoplayback")) {
                        runCatching {
                            val pot = Uri.parse(reqUrl).getQueryParameter("pot")
                            if (!pot.isNullOrBlank()) {
                                bridge.onGvsPoTokenCaptured(pot)
                            }
                        }
                        return empty204Response()
                    }

                    if (lower.contains("googlevideo.com/ptracking") ||
                        lower.contains("i.ytimg.com") ||
                        lower.contains("yt3.ggpht.com") ||
                        lower.contains("doubleclick.net") ||
                        lower.contains("googlesyndication.com") ||
                        lower.contains("googleadservices.com") ||
                        lower.contains("play.google.com/log")
                    ) {
                        return empty204Response()
                    }
                    return null
                }
            }

            val seedVideo = videoId.ifBlank { FALLBACK_SEED_VIDEO_ID }
            val targetUrl = if (!cookie.isNullOrBlank()) {
                "https://music.youtube.com/watch?v=$seedVideo"
            } else {
                "https://www.youtube.com/embed/$seedVideo?controls=0"
            }
            activeSession = session
            view.loadUrl(targetUrl)
            session
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to create BotGuard WebView page: ${t.message}")
            recordRendererProcessCrash()
            null
        }
    }

    private fun syncCookies(cookie: String?) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (cookie.isNullOrBlank()) {
                cm.removeSessionCookies(null)
            } else {
                cookie.split(";")
                    .map { it.trim() }
                    .filter { it.contains("=") }
                    .forEach { kv ->
                        cm.setCookie("https://.youtube.com", "$kv; Path=/; Secure")
                        cm.setCookie("https://music.youtube.com", "$kv; Path=/; Secure")
                    }
            }
            cm.flush()
        }
    }

    private fun destroySessionAsync(session: BotGuardPageSession) {
        session.isAlive.set(false)
        session.mintWaiters.values.forEach { it.complete(null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appContext.mainExecutor.execute {
                destroyWebViewSafe(session.webView)
            }
        }
    }

    private suspend fun destroySessionOnMain(session: BotGuardPageSession) {
        session.isAlive.set(false)
        session.mintWaiters.values.forEach { it.complete(null) }
        if (!session.readySignal.isCompleted) {
            session.readySignal.complete(false)
        }
        withContext(NonCancellable + Dispatchers.Main) {
            destroyWebViewSafe(session.webView)
        }
    }

    private fun destroyWebViewSafe(webView: WebView) {
        runCatching {
            webView.stopLoading()
            webView.webViewClient = WebViewClient()
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.destroy()
        }
    }

    private fun empty204Response(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    companion object {
        private const val TAG = "Sonora"
        private const val BRIDGE_NAME = "SonoraBotGuardBridge"
        private const val PO_TOKEN_TIMEOUT_MS = 8_000L
        private const val INITIAL_PAGE_WAIT_MS = 3_800L
        private const val MINT_CALL_TIMEOUT_MS = 2_400L
        private const val DEFAULT_SESSION_TTL_MS = 2 * 60 * 60_000L
        private const val MAX_SESSION_TTL_SEC = 6 * 3600L
        internal const val RENDERER_CRASH_COOLDOWN_MS = 10 * 60_000L
        private const val FALLBACK_SEED_VIDEO_ID = "jNQXAC9IVRw"

        @Volatile
        private var processRendererCrashedUntilMs: Long = 0L

        @Volatile
        private var loggedUnsupportedHeadlessWebView = false

        fun markProcessRendererCrash(nowMs: Long = System.currentTimeMillis()) {
            processRendererCrashedUntilMs = maxOf(processRendererCrashedUntilMs, nowMs + RENDERER_CRASH_COOLDOWN_MS)
        }

        fun isProcessRendererInCooldown(nowMs: Long = System.currentTimeMillis()): Boolean {
            return nowMs < processRendererCrashedUntilMs
        }

        fun isAndroidEmulator(): Boolean {
            val fp = Build.FINGERPRINT.orEmpty()
            val model = Build.MODEL.orEmpty()
            val mfg = Build.MANUFACTURER.orEmpty()
            val brand = Build.BRAND.orEmpty()
            val device = Build.DEVICE.orEmpty()
            val product = Build.PRODUCT.orEmpty()
            val hardware = Build.HARDWARE.orEmpty()
            return fp.startsWith("generic") ||
                fp.startsWith("unknown") ||
                model.contains("google_sdk", ignoreCase = true) ||
                model.contains("Emulator", ignoreCase = true) ||
                model.contains("Android SDK built for", ignoreCase = true) ||
                mfg.contains("Genymotion", ignoreCase = true) ||
                (brand.startsWith("generic") && device.startsWith("generic")) ||
                product == "google_sdk" ||
                product.contains("sdk_gphone", ignoreCase = true) ||
                product.contains("emulator", ignoreCase = true) ||
                hardware.contains("goldfish", ignoreCase = true) ||
                hardware.contains("ranchu", ignoreCase = true)
        }

        /**
         * Headless / container Android emulators without `/dev/dri/renderD*` log
         * `Failed to open rendernode` and abort Chromium's renderer (`code -1`).
         * Physical phones do not expose `/dev/dri` under SELinux, so this guard
         * triggers strictly on recognized emulator builds lacking a render node.
         */
        fun isUnsupportedHeadlessWebView(): Boolean {
            if (!isAndroidEmulator()) return false
            val hasRenderNode = runCatching {
                val driDir = java.io.File("/dev/dri")
                java.io.File("/dev/dri/renderD128").exists() ||
                    driDir.listFiles()?.any { it.name.startsWith("renderD") } == true
            }.getOrDefault(false)
            if (!hasRenderNode && !loggedUnsupportedHeadlessWebView) {
                loggedUnsupportedHeadlessWebView = true
                Log.i(TAG, "Headless emulator without GPU rendernode detected; skipping offscreen WebView")
            }
            return !hasRenderNode
        }

        private fun buildHookJs(visitorData: String): String {
            val safeVisitor = JSONObject.quote(visitorData)
            return """
                (function() {
                  try {
                    if (window.__sonoraBgHooked) return;
                    window.__sonoraBgHooked = true;
                    window.__sonoraExpectedVisitor = $safeVisitor;
                    window.__sonoraBgMinter = null;

                    function emitResult(reqId, token) {
                      try {
                        if (window.$BRIDGE_NAME) {
                          window.$BRIDGE_NAME.onMintResult(String(reqId), token ? String(token) : '');
                        }
                      } catch (_) {}
                    }

                    function base64UrlFromBytes(u8) {
                      var bin = '';
                      for (var i = 0; i < u8.length; i++) {
                        bin += String.fromCharCode(u8[i]);
                      }
                      return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_');
                    }

                    window.__sonoraMintPoToken = function(reqId, identifier) {
                      try {
                        var minter = window.__sonoraBgMinter;
                        if (typeof minter === 'function') {
                          var encoded = new TextEncoder().encode(String(identifier || ''));
                          var maybePromise = minter(encoded);
                          if (maybePromise && typeof maybePromise.then === 'function') {
                            maybePromise.then(function(res) {
                              var out = (typeof res === 'string') ? res : (res instanceof Uint8Array ? base64UrlFromBytes(res) : '');
                              emitResult(reqId, out);
                            }).catch(function() {
                              emitResult(reqId, '');
                            });
                            return;
                          } else {
                            var outSync = (typeof maybePromise === 'string') ? maybePromise : (maybePromise instanceof Uint8Array ? base64UrlFromBytes(maybePromise) : '');
                            emitResult(reqId, outSync);
                            return;
                          }
                        }
                        if (String(identifier) === window.__sonoraExpectedVisitor && window.__sonoraLastPlayerPoToken) {
                          emitResult(reqId, window.__sonoraLastPlayerPoToken);
                          return;
                        }
                        emitResult(reqId, '');
                      } catch (_) {
                        emitResult(reqId, '');
                      }
                    };

                    function inspectPlayerRequest(bodyStr) {
                      try {
                        if (!bodyStr || typeof bodyStr !== 'string') return;
                        var j = JSON.parse(bodyStr);
                        var pot = j && j.serviceIntegrityDimensions && j.serviceIntegrityDimensions.poToken;
                        var vd = j && j.context && j.context.client && j.context.client.visitorData;
                        if (pot && window.$BRIDGE_NAME) {
                          window.__sonoraLastPlayerPoToken = String(pot);
                          window.$BRIDGE_NAME.onPoTokenCaptured(String(pot), vd ? String(vd) : '');
                        }
                      } catch (_) {}
                    }

                    function inspectWaaResponse(text) {
                      try {
                        if (!text || typeof text !== 'string') return;
                        var parsed = JSON.parse(text);
                        if (Array.isArray(parsed) && typeof parsed[0] === 'string' && typeof parsed[1] === 'number') {
                          if (window.$BRIDGE_NAME) {
                            window.$BRIDGE_NAME.onMinterReady(Number(parsed[1]));
                          }
                        }
                      } catch (_) {}
                    }

                    var origFetch = window.fetch;
                    if (typeof origFetch === 'function') {
                      window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        if (url.indexOf('/youtubei/v1/player') !== -1 && init && typeof init.body === 'string') {
                          inspectPlayerRequest(init.body);
                        }
                        var p = origFetch.apply(this, arguments);
                        if (url.indexOf('GenerateIT') !== -1) {
                          p.then(function(resp) {
                            return resp.clone().text().then(inspectWaaResponse);
                          }).catch(function() {});
                        }
                        return p;
                      };
                    }

                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                      this.__sonoraUrl = String(url || '');
                      return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function(body) {
                      var u = this.__sonoraUrl || '';
                      if (u.indexOf('/youtubei/v1/player') !== -1 && typeof body === 'string') {
                        inspectPlayerRequest(body);
                      }
                      if (u.indexOf('GenerateIT') !== -1) {
                        this.addEventListener('load', function() {
                          inspectWaaResponse(this.responseText);
                        });
                      }
                      return origSend.apply(this, arguments);
                    };

                    function muteMedia() {
                      try {
                        var els = document.querySelectorAll('video, audio');
                        for (var i = 0; i < els.length; i++) {
                          els[i].muted = true;
                          els[i].volume = 0;
                        }
                      } catch (_) {}
                    }
                    setInterval(muteMedia, 350);
                  } catch (err) {
                    try {
                      if (window.$BRIDGE_NAME) {
                        window.$BRIDGE_NAME.onFatalError(String(err));
                      }
                    } catch (_) {}
                  }
                })();
            """.trimIndent()
        }
    }
}

package com.example.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayInputStream

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

data class LiveWebPlayerResult(
    val playerJson: JSONObject? = null,
    val poTokenData: PoTokenSessionData? = null
)

/**
 * Offscreen Chromium WebView BotGuard & live player extractor.
 *
 * Executes YouTube's real web player & BotGuard scripts in a silent headless WebView
 * (blocking all googlevideo media bytes with HTTP 204 so zero audio downloads or plays)
 * to harvest:
 *  1) Session / content Proof-of-Origin tokens (`poToken` & `&pot=`)
 *  2) Matching `visitorData`
 *  3) Authenticated `streamingData` player JSON when a track is blocked on cookieless clients.
 */
class PoTokenWebViewManager(
    context: Context
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var cachedSessionToken: PoTokenSessionData? = null

    @Volatile
    private var cachedCookieSignature: Int = 0

    @Volatile
    private var lastWarmAttemptAtMs: Long = 0L

    fun getCachedSessionToken(cookies: String?): PoTokenSessionData? {
        val currentHash = cookies?.trim().orEmpty().hashCode()
        if (cachedCookieSignature != currentHash) return null
        val cached = cachedSessionToken ?: return null
        if (System.currentTimeMillis() - cached.obtainedAtMs > TOKEN_TTL_MS) {
            return null
        }
        return cached
    }

    fun clearCache() {
        cachedSessionToken = null
        cachedCookieSignature = 0
        lastWarmAttemptAtMs = 0L
    }

    suspend fun warmSessionPoToken(
        cookies: String?,
        timeoutMs: Long = 5_500L
    ): PoTokenSessionData? {
        getCachedSessionToken(cookies)?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastWarmAttemptAtMs < WARM_COOLDOWN_MS) return null
        lastWarmAttemptAtMs = now
        val result = extractFromLiveWebPlayer(
            videoId = WARMUP_PUBLIC_VIDEO_ID,
            cookies = cookies,
            capturePlayerJson = false,
            timeoutMs = timeoutMs
        )
        return result?.poTokenData ?: getCachedSessionToken(cookies)
    }

    suspend fun extractForVideo(
        videoId: String,
        cookies: String?,
        timeoutMs: Long = 5_000L
    ): LiveWebPlayerResult? {
        return extractFromLiveWebPlayer(
            videoId = videoId,
            cookies = cookies,
            capturePlayerJson = true,
            timeoutMs = timeoutMs
        )
    }

    private suspend fun extractFromLiveWebPlayer(
        videoId: String,
        cookies: String?,
        capturePlayerJson: Boolean,
        timeoutMs: Long
    ): LiveWebPlayerResult? {
        val acquired = withTimeoutOrNull(timeoutMs) {
            mutex.withLock {
                if (!capturePlayerJson) {
                    getCachedSessionToken(cookies)?.let { cached ->
                        return@withLock LiveWebPlayerResult(playerJson = null, poTokenData = cached)
                    }
                }
                runWebViewCapture(
                    videoId = videoId,
                    cookies = cookies,
                    capturePlayerJson = capturePlayerJson,
                    timeoutMs = timeoutMs
                )
            }
        }
        return acquired
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebViewCapture(
        videoId: String,
        cookies: String?,
        capturePlayerJson: Boolean,
        timeoutMs: Long
    ): LiveWebPlayerResult? = withContext(Dispatchers.Main) {
        val resultDeferred = CompletableDeferred<LiveWebPlayerResult?>()
        var webView: WebView? = null

        class CaptureState {
            @Volatile var capturedPlayerPoToken: String? = null
            @Volatile var capturedGvsPoToken: String? = null
            @Volatile var capturedVisitorData: String? = null
            @Volatile var capturedPlayerJson: JSONObject? = null
            @Volatile var finalizing = false
        }
        val state = CaptureState()

        fun snapshotToken(): PoTokenSessionData? {
            val pt = state.capturedPlayerPoToken?.takeIf { it.isNotBlank() }
            val gvs = state.capturedGvsPoToken?.takeIf { it.isNotBlank() }
            val vd = state.capturedVisitorData?.takeIf { it.isNotBlank() }
            if (pt == null && gvs == null) return null
            val bundle = PoTokenSessionData(
                visitorData = vd,
                playerRequestPoToken = pt,
                streamingDataPoToken = gvs,
                obtainedAtMs = System.currentTimeMillis()
            )
            cachedSessionToken = bundle
            cachedCookieSignature = cookies?.trim().orEmpty().hashCode()
            return bundle
        }

        fun tryComplete(force: Boolean = false) {
            if (resultDeferred.isCompleted) return
            val tokenBundle = snapshotToken()
            val pJson = state.capturedPlayerJson
            val ready = if (capturePlayerJson) {
                (pJson != null && tokenBundle != null) || (force && (pJson != null || tokenBundle != null))
            } else {
                (tokenBundle?.streamingDataPoToken != null && tokenBundle.playerRequestPoToken != null) ||
                    (force && tokenBundle != null)
            }
            if (ready) {
                resultDeferred.complete(
                    LiveWebPlayerResult(
                        playerJson = pJson,
                        poTokenData = tokenBundle
                    )
                )
            }
        }

        fun scheduleSoftGraceCompletion() {
            if (state.finalizing || resultDeferred.isCompleted) return
            state.finalizing = true
            launch(Dispatchers.Main) {
                delay(700L)
                tryComplete(force = true)
            }
        }

        try {
            runCatching {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                if (!cookies.isNullOrBlank()) {
                    cookies.split(";")
                        .map { it.trim() }
                        .filter { it.contains("=") }
                        .forEach { kv ->
                            cm.setCookie("https://.youtube.com", "$kv; Path=/; Secure")
                            cm.setCookie("https://music.youtube.com", "$kv; Path=/; Secure")
                        }
                }
                cm.flush()
            }

            val view = WebView(appContext)
            webView = view
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = StreamResolver.BROWSER_USER_AGENT
            }

            val bridge = object {
                @JavascriptInterface
                fun onPoTokenCaptured(playerPoToken: String?, visitorData: String?) {
                    if (!playerPoToken.isNullOrBlank()) {
                        state.capturedPlayerPoToken = playerPoToken.trim()
                    }
                    if (!visitorData.isNullOrBlank()) {
                        state.capturedVisitorData = visitorData.trim()
                    }
                    snapshotToken()
                    tryComplete(force = false)
                    if (state.capturedPlayerPoToken != null && (!capturePlayerJson || state.capturedPlayerJson != null)) {
                        scheduleSoftGraceCompletion()
                    }
                }

                @JavascriptInterface
                fun onPlayerJsonCaptured(rawJson: String?) {
                    if (rawJson.isNullOrBlank()) return
                    runCatching {
                        val json = JSONObject(rawJson)
                        val status = json.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
                        val hasStreaming = json.optJSONObject("streamingData") != null
                        if ((status == "OK" || status == "LIVE_STREAM_OFFLINE") && hasStreaming) {
                            state.capturedPlayerJson = json
                            val respVisitor = json.optJSONObject("responseContext")?.optString("visitorData")
                            if (!respVisitor.isNullOrBlank() && state.capturedVisitorData.isNullOrBlank()) {
                                state.capturedVisitorData = respVisitor
                            }
                            tryComplete(force = false)
                            if (capturePlayerJson) {
                                scheduleSoftGraceCompletion()
                            }
                        }
                    }
                }

                @JavascriptInterface
                fun onVisitorDataCaptured(visitorData: String?) {
                    if (!visitorData.isNullOrBlank() && state.capturedVisitorData.isNullOrBlank()) {
                        state.capturedVisitorData = visitorData.trim()
                    }
                }
            }

            view.addJavascriptInterface(bridge, "SonoraPoBridge")
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(v, url, favicon)
                    v?.evaluateJavascript(JS_INTERCEPTOR, null)
                }

                override fun onPageFinished(v: WebView?, url: String?) {
                    super.onPageFinished(v, url)
                    v?.evaluateJavascript(JS_INTERCEPTOR, null)
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
                                state.capturedGvsPoToken = pot
                                snapshotToken()
                                launch(Dispatchers.Main) {
                                    tryComplete(force = false)
                                    scheduleSoftGraceCompletion()
                                }
                            }
                        }
                        // Never download audio/video bytes or let Chromium emit background sound
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

            // music.youtube.com matches the user's YT Music cookies & triggers BotGuard + player + pot
            view.loadUrl("https://music.youtube.com/watch?v=$videoId")

            withTimeoutOrNull(timeoutMs - 250L) {
                resultDeferred.await()
            } ?: run {
                tryComplete(force = true)
                val tokenBundle = snapshotToken()
                val pJson = state.capturedPlayerJson
                if (pJson != null || tokenBundle != null) {
                    LiveWebPlayerResult(playerJson = pJson, poTokenData = tokenBundle)
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            Log.w("PoTokenWebViewManager", "Offscreen WebView PoToken capture unavailable: ${t.message}")
            null
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching {
                    webView?.apply {
                        stopLoading()
                        removeJavascriptInterface("SonoraPoBridge")
                        loadUrl("about:blank")
                        clearHistory()
                        destroy()
                    }
                }
            }
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
        private const val TOKEN_TTL_MS = 2 * 60 * 60_000L
        private const val WARM_COOLDOWN_MS = 60_000L
        private const val WARMUP_PUBLIC_VIDEO_ID = "jNQXAC9IVRw"

        private val JS_INTERCEPTOR = """
            (function() {
              try {
                if (window.__sonoraPoHooked) return;
                window.__sonoraPoHooked = true;

                function emitVisitor(v) {
                  try {
                    if (v && typeof v === 'string' && window.SonoraPoBridge) {
                      window.SonoraPoBridge.onVisitorDataCaptured(v);
                    }
                  } catch(_) {}
                }

                function inspectRequestBody(bodyStr) {
                  try {
                    if (!bodyStr || typeof bodyStr !== 'string') return;
                    var j = JSON.parse(bodyStr);
                    var pot = j && j.serviceIntegrityDimensions && j.serviceIntegrityDimensions.poToken;
                    var vd = j && j.context && j.context.client && j.context.client.visitorData;
                    emitVisitor(vd);
                    if (pot && window.SonoraPoBridge) {
                      window.SonoraPoBridge.onPoTokenCaptured(String(pot), vd ? String(vd) : '');
                    }
                  } catch(_) {}
                }

                function muteMediaTags() {
                  try {
                    var els = document.querySelectorAll('video, audio');
                    for (var i = 0; i < els.length; i++) {
                      els[i].muted = true;
                      els[i].volume = 0;
                    }
                  } catch(_) {}
                }

                if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                  emitVisitor(window.ytcfg.get('VISITOR_DATA'));
                }
                if (window.ytInitialPlayerResponse && window.SonoraPoBridge) {
                  try {
                    window.SonoraPoBridge.onPlayerJsonCaptured(JSON.stringify(window.ytInitialPlayerResponse));
                  } catch(_) {}
                }

                var origFetch = window.fetch;
                if (typeof origFetch === 'function') {
                  window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    if (url.indexOf('/youtubei/v1/player') !== -1 && init && typeof init.body === 'string') {
                      inspectRequestBody(init.body);
                    }
                    var p = origFetch.apply(this, arguments);
                    if (url.indexOf('/youtubei/v1/player') !== -1) {
                      p.then(function(resp) {
                        try {
                          return resp.clone().text().then(function(txt) {
                            if (txt && window.SonoraPoBridge) {
                              window.SonoraPoBridge.onPlayerJsonCaptured(txt);
                            }
                          });
                        } catch(_) {}
                      }).catch(function(){});
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
                    inspectRequestBody(body);
                    this.addEventListener('load', function() {
                      try {
                        if (this.responseText && window.SonoraPoBridge) {
                          window.SonoraPoBridge.onPlayerJsonCaptured(this.responseText);
                        }
                      } catch(_) {}
                    });
                  }
                  return origSend.apply(this, arguments);
                };

                setInterval(muteMediaTags, 350);
              } catch(_) {}
            })();
        """.trimIndent()
    }
}

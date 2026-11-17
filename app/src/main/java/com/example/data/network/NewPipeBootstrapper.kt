package com.example.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object NewPipeBootstrapper {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val initialized = AtomicBoolean(false)

    @Volatile
    var sessionCookies: String? = null

    fun ensureInitialized(baseClient: OkHttpClient) {
        if (!initialized.compareAndSet(false, true)) return

        val npClient = baseClient.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(11, TimeUnit.SECONDS)
            .build()

        val downloader = object : Downloader() {
            @Throws(IOException::class, ReCaptchaException::class)
            override fun execute(request: NpRequest): NpResponse {
                val reqBuilder = Request.Builder().url(request.url())
                val method = request.httpMethod().uppercase()
                val bodyBytes = request.dataToSend()

                if (method == "POST" || method == "PUT" || method == "PATCH") {
                    val contentType = request.headers()["Content-Type"]?.firstOrNull()
                        ?: "application/json; charset=utf-8"
                    val body = (bodyBytes ?: ByteArray(0)).toRequestBody(contentType.toMediaType())
                    reqBuilder.method(method, body)
                } else if (method == "HEAD") {
                    reqBuilder.head()
                } else {
                    reqBuilder.get()
                }

                var hasUserAgent = false
                request.headers().forEach { (key, values) ->
                    if (key.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    values.forEach { value ->
                        reqBuilder.addHeader(key, value)
                    }
                }
                if (!hasUserAgent) {
                    reqBuilder.header("User-Agent", BROWSER_USER_AGENT)
                }

                val cookies = sessionCookies
                if (!cookies.isNullOrBlank() &&
                    (request.url().contains("youtube.com") || request.url().contains("youtu.be"))
                ) {
                    reqBuilder.header("Cookie", cookies)
                    val origin = if (request.url().contains("music.youtube.com")) {
                        "https://music.youtube.com"
                    } else {
                        "https://www.youtube.com"
                    }
                    buildMultiSapisidHeader(cookies, origin)?.let { auth ->
                        reqBuilder.header("Authorization", auth)
                        reqBuilder.header("X-Origin", origin)
                        reqBuilder.header("X-Goog-AuthUser", "0")
                    }
                }

                npClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 429) {
                        throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                    }
                    val responseBody = resp.body?.string().orEmpty()
                    return NpResponse(
                        resp.code,
                        resp.message,
                        resp.headers.toMultimap(),
                        responseBody,
                        resp.request.url.toString()
                    )
                }
            }
        }
        NewPipe.init(downloader, Localization("es", "MX"))
    }

    internal fun buildMultiSapisidHeader(cookies: String, origin: String): String? {
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val sapisid = extractCookie(cookies, "SAPISID")
        val p1 = extractCookie(cookies, "__Secure-1PAPISID")
        val p3 = extractCookie(cookies, "__Secure-3PAPISID")
        val parts = mutableListOf<String>()
        val mainKey = p3 ?: sapisid
        if (mainKey != null) {
            parts.add(computeSchemeHash("SAPISIDHASH", mainKey, origin, timestamp))
        }
        if (p1 != null) {
            parts.add(computeSchemeHash("SAPISID1PHASH", p1, origin, timestamp))
        }
        if (p3 != null) {
            parts.add(computeSchemeHash("SAPISID3PHASH", p3, origin, timestamp))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    private fun extractCookie(cookies: String, key: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
    }

    private fun computeSchemeHash(scheme: String, key: String, origin: String, timestamp: String): String {
        val input = "$timestamp $key $origin"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "$scheme ${timestamp}_$hex"
    }
}

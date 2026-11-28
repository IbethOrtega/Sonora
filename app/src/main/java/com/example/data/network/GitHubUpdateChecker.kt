package com.example.data.network

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import com.example.BuildConfig
import com.example.model.GitHubReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

class GitHubRateLimitException(message: String) : IllegalStateException(message)

sealed interface ApkInstallVerificationResult {
    data class Valid(val versionCode: Long) : ApkInstallVerificationResult
    data object SignatureMismatch : ApkInstallVerificationResult
    data class VersionNotNewer(val downloadedCode: Long, val installedCode: Long) : ApkInstallVerificationResult
    data class InvalidPackage(val reason: String) : ApkInstallVerificationResult
}

class SonoraPackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        GitHubUpdateChecker.cleanupCachedUpdateApksBlocking(
            context = context.applicationContext,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong()
        )
        runCatching {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
            }
        }
    }
}

class GitHubUpdateChecker(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeInstallReceiver: BroadcastReceiver? = null

    @Volatile
    private var activeInstallAppContext: Context? = null

    suspend fun fetchLatestRelease(
        owner: String = "IbethOrtega",
        repo: String = "Sonora"
    ): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Sonora-Android-Updater")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.code == 404) {
                    throw IllegalStateException("Aún no hay lanzamientos publicados en GitHub.")
                }
                if (resp.code == 403 || resp.code == 429) {
                    throw GitHubRateLimitException("Límite de consultas de GitHub alcanzado. Intenta más tarde.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("No se pudo verificar actualizaciones (${resp.code}).")
                }
                val raw = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val name = root["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { tag }
                val htmlUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty()
                val body = root["body"]?.jsonPrimitive?.content.orEmpty()
                val apkAsset = root["assets"]?.jsonArray
                    ?.firstOrNull { elem ->
                        val assetName = elem.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                        assetName.endsWith(".apk", ignoreCase = true)
                    }
                    ?.jsonObject
                val assetName = apkAsset?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
                val apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.contentOrNull
                val apkSizeBytes = apkAsset?.get("size")?.jsonPrimitive?.longOrNull ?: 0L
                val digestFromApi = extractSha256Hex(apkAsset?.get("digest")?.jsonPrimitive?.contentOrNull)
                val apkSha256 = digestFromApi ?: extractSha256FromNotes(body, assetName)

                if (tag.isBlank() && htmlUrl.isBlank()) {
                    throw IllegalStateException("Respuesta de GitHub sin versión válida.")
                }

                GitHubReleaseInfo(
                    tagName = tag,
                    releaseName = name,
                    htmlUrl = htmlUrl.ifBlank { "https://github.com/$owner/$repo/releases" },
                    apkDownloadUrl = apkUrl,
                    notes = body,
                    apkSha256 = apkSha256,
                    apkSizeBytes = apkSizeBytes
                )
            }
        }
    }

    suspend fun downloadApkToCacheWithResume(
        context: Context,
        release: GitHubReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long, progress: Float?) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val downloadUrl = release.apkDownloadUrl?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Este lanzamiento no incluye un archivo APK."))

        val updatesDir = getUpdatesCacheDir(context).apply { mkdirs() }
        val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
        val apkFile = File(updatesDir, "sonora_$safeTag.apk")
        val partFile = File(updatesDir, "sonora_$safeTag.apk.part")

        if (apkFile.exists() && apkFile.length() > 0L) {
            val expectedSha = release.apkSha256
            val shaOk = expectedSha == null || verifyFileSha256(apkFile, expectedSha)
            val sizeOk = release.apkSizeBytes <= 0L || apkFile.length() == release.apkSizeBytes
            if (shaOk && sizeOk && readArchivePackageInfo(context.packageManager, apkFile) != null) {
                val len = apkFile.length()
                onProgress(len, len, 1f)
                return@withContext Result.success(apkFile)
            }
            apkFile.delete()
        }

        val maxResumeAttempts = 3
        var attempt = 0
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val existingBytes = if (partFile.exists()) partFile.length() else 0L
                try {
                    val reqBuilder = Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", "Sonora-Android-Updater")
                    if (existingBytes > 0L) {
                        reqBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    val call = okHttpClient.newCall(reqBuilder.build())
                    call.execute().use { resp ->
                        if (resp.code == 416) {
                            if (release.apkSizeBytes > 0L && existingBytes == release.apkSizeBytes) {
                                promotePartFile(partFile, apkFile)
                                onProgress(existingBytes, existingBytes, 1f)
                                return@withContext validateDownloadedShaOrDelete(apkFile, partFile, release.apkSha256)
                            }
                            partFile.delete()
                            throw IOException("Rango inválido, reiniciando descarga.")
                        }

                        if (!resp.isSuccessful) {
                            throw IOException("Error al descargar actualización (${resp.code}).")
                        }

                        val isPartial = resp.code == 206
                        val append = isPartial && existingBytes > 0L
                        val startOffset = if (append) existingBytes else 0L
                        val body = resp.body ?: throw IOException("Respuesta vacía al descargar el APK.")
                        val contentLength = body.contentLength()
                        val totalBytes = when {
                            isPartial -> parseTotalFromContentRange(resp.header("Content-Range"))
                                ?: if (contentLength > 0L) startOffset + contentLength else release.apkSizeBytes
                            contentLength > 0L -> contentLength
                            else -> release.apkSizeBytes
                        }

                        if (!append && partFile.exists()) {
                            partFile.delete()
                        }

                        var downloaded = startOffset
                        onProgress(
                            downloaded,
                            totalBytes,
                            computeProgressFraction(downloaded, totalBytes)
                        )

                        val buffer = ByteArray(16 * 1024)
                        var lastEmittedMs = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partFile, append).use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val nowMs = System.currentTimeMillis()
                                    if (nowMs - lastEmittedMs >= 80L || (totalBytes > 0L && downloaded >= totalBytes)) {
                                        lastEmittedMs = nowMs
                                        onProgress(
                                            downloaded,
                                            totalBytes,
                                            computeProgressFraction(downloaded, totalBytes)
                                        )
                                    }
                                }
                                output.flush()
                            }
                        }
                    }

                    promotePartFile(partFile, apkFile)
                    val finalLen = apkFile.length()
                    onProgress(finalLen, maxOf(finalLen, release.apkSizeBytes), 1f)
                    return@withContext validateDownloadedShaOrDelete(apkFile, partFile, release.apkSha256)
                } catch (ce: CancellationException) {
                    apkFile.delete()
                    partFile.delete()
                    throw ce
                } catch (io: IOException) {
                    val progressed = (partFile.exists() && partFile.length() > existingBytes)
                    attempt++
                    if (attempt >= maxResumeAttempts && !progressed) {
                        apkFile.delete()
                        partFile.delete()
                        return@withContext Result.failure(io)
                    }
                    if (attempt > maxResumeAttempts + 1) {
                        apkFile.delete()
                        partFile.delete()
                        return@withContext Result.failure(io)
                    }
                }
            }
        } catch (ce: CancellationException) {
            apkFile.delete()
            partFile.delete()
            throw ce
        } catch (t: Throwable) {
            apkFile.delete()
            partFile.delete()
            return@withContext Result.failure(t)
        }
    }

    fun installApkWithPackageInstaller(
        context: Context,
        apkFile: File,
        onPendingUserAction: () -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        unregisterActiveInstallReceiver()

        val installAction = "${appContext.packageName}.ACTION_SONORA_APP_UPDATE_INSTALL"
        val packageInstaller = appContext.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        var createdSessionId = -1

        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(appContext.packageName)
                setSize(apkFile.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            createdSessionId = packageInstaller.createSession(params)
            val expectedSessionId = createdSessionId

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(recvContext: Context, intent: Intent?) {
                    if (intent == null || intent.action != installAction) return
                    val eventSessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (eventSessionId != -1 && eventSessionId != expectedSessionId) return

                    when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            mainHandler.post { onPendingUserAction() }
                            val confirmIntent = IntentCompat.getParcelableExtra(
                                intent,
                                Intent.EXTRA_INTENT,
                                Intent::class.java
                            )
                            if (confirmIntent != null) {
                                confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { appContext.startActivity(confirmIntent) }
                                    .onFailure { err ->
                                        unregisterActiveInstallReceiver()
                                        mainHandler.post {
                                            onError(err.localizedMessage ?: "No se pudo abrir el instalador del sistema.")
                                        }
                                    }
                            } else {
                                unregisterActiveInstallReceiver()
                                mainHandler.post {
                                    onError("No se recibió la pantalla de confirmación de Android.")
                                }
                            }
                        }

                        PackageInstaller.STATUS_SUCCESS -> {
                            unregisterActiveInstallReceiver()
                            mainHandler.post { onSuccess() }
                            runCatching {
                                val launch = appContext.packageManager
                                    .getLaunchIntentForPackage(appContext.packageName)
                                    ?.apply {
                                        addFlags(
                                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                                        )
                                    }
                                if (launch != null) {
                                    appContext.startActivity(launch)
                                }
                            }
                        }

                        PackageInstaller.STATUS_FAILURE_ABORTED -> {
                            unregisterActiveInstallReceiver()
                            mainHandler.post { onError("Instalación cancelada.") }
                        }

                        else -> {
                            unregisterActiveInstallReceiver()
                            val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                                ?.takeIf { it.isNotBlank() }
                            mainHandler.post {
                                onError(detail ?: "No se pudo completar la instalación ($status).")
                            }
                        }
                    }
                }
            }

            activeInstallAppContext = appContext
            activeInstallReceiver = receiver
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(installAction),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            session = packageInstaller.openSession(createdSessionId)
            val totalSize = apkFile.length()
            session.openWrite("sonora_update.apk", 0L, totalSize).use { out ->
                FileInputStream(apkFile).use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            val callbackIntent = Intent(installAction).setPackage(appContext.packageName)
            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                createdSessionId,
                callbackIntent,
                pendingFlags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (t: Throwable) {
            runCatching { session?.abandon() }
            if (createdSessionId != -1) {
                runCatching { packageInstaller.abandonSession(createdSessionId) }
            }
            unregisterActiveInstallReceiver()
            mainHandler.post {
                onError(t.localizedMessage ?: "Error al iniciar la instalación del APK.")
            }
        }
    }

    fun unregisterActiveInstallReceiver() {
        val ctx = activeInstallAppContext
        val recv = activeInstallReceiver
        activeInstallAppContext = null
        activeInstallReceiver = null
        if (ctx != null && recv != null) {
            runCatching { ctx.unregisterReceiver(recv) }
        }
    }

    private fun validateDownloadedShaOrDelete(
        apkFile: File,
        partFile: File,
        expectedSha256: String?
    ): Result<File> {
        if (!expectedSha256.isNullOrBlank() && !verifyFileSha256(apkFile, expectedSha256)) {
            apkFile.delete()
            partFile.delete()
            return Result.failure(
                IllegalStateException("El hash SHA-256 del APK no coincide. Se borró el archivo por seguridad.")
            )
        }
        return Result.success(apkFile)
    }

    private fun promotePartFile(partFile: File, apkFile: File) {
        if (apkFile.exists()) apkFile.delete()
        if (!partFile.renameTo(apkFile)) {
            partFile.copyTo(apkFile, overwrite = true)
            partFile.delete()
        }
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val slash = header.substringAfter('/', "").trim()
        return slash.toLongOrNull()?.takeIf { it > 0L }
    }

    private fun computeProgressFraction(downloaded: Long, total: Long): Float? {
        return if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null
    }

    companion object {
        private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")
        private val SHA256_NOTES_LABEL_REGEX = Regex(
            "(?im)(?:sha-?256|checksum)\\s*[:=]\\s*`?([0-9a-fA-F]{64})`?"
        )

        fun getUpdatesCacheDir(context: Context): File = File(context.cacheDir, "updates")

        fun deleteAllCachedUpdateFiles(context: Context) {
            val dir = getUpdatesCacheDir(context)
            dir.listFiles()?.forEach { file ->
                runCatching { file.delete() }
            }
        }

        fun cleanupCachedUpdateApksBlocking(
            context: Context,
            currentVersionName: String = BuildConfig.VERSION_NAME,
            currentVersionCode: Long = BuildConfig.VERSION_CODE.toLong()
        ) {
            val dir = getUpdatesCacheDir(context)
            if (!dir.exists()) return
            val pm = context.packageManager
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".part", ignoreCase = true)) {
                    file.delete()
                } else if (file.name.endsWith(".apk", ignoreCase = true)) {
                    val info = readArchivePackageInfo(pm, file)
                    val shouldDelete = if (info == null) {
                        true
                    } else {
                        val archiveCode = PackageInfoCompat.getLongVersionCode(info)
                        val archiveVersion = info.versionName.orEmpty()
                        archiveCode <= currentVersionCode ||
                            (archiveVersion.isNotBlank() && !isRemoteVersionNewer(archiveVersion, currentVersionName))
                    }
                    if (shouldDelete) {
                        file.delete()
                    }
                }
            }
        }

        fun isRemoteVersionNewer(remoteTag: String, currentVersionName: String): Boolean {
            val remoteParts = extractNumericParts(remoteTag)
            val localParts = extractNumericParts(currentVersionName)
            if (remoteParts.isEmpty()) return false
            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        }

        private fun extractNumericParts(version: String): List<Int> {
            return Regex("\\d+").findAll(version).mapNotNull { it.value.toIntOrNull() }.toList()
        }

        fun extractSha256Hex(rawDigest: String?): String? {
            if (rawDigest.isNullOrBlank()) return null
            val normalized = rawDigest.trim()
                .removePrefix("sha256:")
                .removePrefix("SHA256:")
                .trim()
            return if (SHA256_HEX_REGEX.matches(normalized)) normalized.lowercase() else null
        }

        fun extractSha256FromNotes(notes: String, assetName: String): String? {
            if (notes.isBlank()) return null
            if (assetName.isNotBlank()) {
                val fileMatch = Regex(
                    "(?im)`?([0-9a-fA-F]{64})`?\\s+\\*?${Regex.escape(assetName)}"
                ).find(notes)
                if (fileMatch != null) {
                    return fileMatch.groupValues[1].lowercase()
                }
            }
            val labeledMatch = SHA256_NOTES_LABEL_REGEX.find(notes)
            return labeledMatch?.groupValues?.get(1)?.lowercase()
        }

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun verifySha256Hex(actualHex: String, expectedHex: String?): Boolean {
            val normalizedExpected = extractSha256Hex(expectedHex) ?: return true
            val normalizedActual = extractSha256Hex(actualHex) ?: return false
            return normalizedActual == normalizedExpected
        }

        fun verifyFileSha256(file: File, expectedHex: String?): Boolean {
            val expected = extractSha256Hex(expectedHex) ?: return true
            if (!file.exists() || !file.isFile) return false
            val actual = runCatching { computeFileSha256(file) }.getOrNull() ?: return false
            return verifySha256Hex(actual, expected)
        }

        fun doSigningCertificatesMatch(
            installedDigests: Set<String>,
            downloadedDigests: Set<String>
        ): Boolean {
            if (installedDigests.isEmpty() || downloadedDigests.isEmpty()) return false
            val normalizedInstalled = installedDigests.map { it.trim().lowercase() }.toSet()
            val normalizedDownloaded = downloadedDigests.map { it.trim().lowercase() }.toSet()
            return normalizedInstalled == normalizedDownloaded
        }

        fun isApkVersionCodeNewer(downloadedVersionCode: Long, installedVersionCode: Long): Boolean {
            return downloadedVersionCode > installedVersionCode
        }

        fun verifyApkForInstall(context: Context, apkFile: File): ApkInstallVerificationResult {
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                return ApkInstallVerificationResult.InvalidPackage("El archivo APK no existe o está vacío.")
            }
            val pm = context.packageManager
            val installedInfo = readInstalledPackageInfo(pm, context.packageName)
                ?: return ApkInstallVerificationResult.InvalidPackage("No se pudo leer la firma de la app instalada.")
            val downloadedInfo = readArchivePackageInfo(pm, apkFile)
                ?: return ApkInstallVerificationResult.InvalidPackage("El archivo APK descargado está dañado o no es válido.")

            val installedDigests = extractSigningCertificateSha256s(installedInfo)
            val downloadedDigests = extractSigningCertificateSha256s(downloadedInfo)
            if (!doSigningCertificatesMatch(installedDigests, downloadedDigests)) {
                return ApkInstallVerificationResult.SignatureMismatch
            }

            val installedCode = PackageInfoCompat.getLongVersionCode(installedInfo)
            val downloadedCode = PackageInfoCompat.getLongVersionCode(downloadedInfo)
            if (!isApkVersionCodeNewer(downloadedCode, installedCode)) {
                return ApkInstallVerificationResult.VersionNotNewer(
                    downloadedCode = downloadedCode,
                    installedCode = installedCode
                )
            }

            return ApkInstallVerificationResult.Valid(downloadedCode)
        }

        fun canRequestPackageInstalls(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        }

        fun hasActiveNetwork(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        fun isWifiConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }

        @Suppress("DEPRECATION")
        private fun readInstalledPackageInfo(pm: PackageManager, packageName: String): PackageInfo? {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                }
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        private fun readArchivePackageInfo(pm: PackageManager, apkFile: File): PackageInfo? {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                }
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        private fun extractSigningCertificateSha256s(info: PackageInfo): Set<String> {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
                    ?: signingInfo?.signingCertificateHistory
            } else {
                info.signatures
            }
            if (signatures.isNullOrEmpty()) return emptySet()
            return signatures.map { sig ->
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        }
    }
}

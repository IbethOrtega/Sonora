package com.example.data.network

import com.example.model.GitHubReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class GitHubUpdateChecker(
    private val okHttpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

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
                if (!resp.isSuccessful) {
                    throw IllegalStateException("No se pudo verificar actualizaciones (${resp.code}).")
                }
                val raw = resp.body?.string().orEmpty()
                val root = json.parseToJsonElement(raw).jsonObject
                val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val name = root["name"]?.jsonPrimitive?.content.orEmpty().ifBlank { tag }
                val htmlUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty()
                val body = root["body"]?.jsonPrimitive?.content.orEmpty()
                val apkUrl = root["assets"]?.jsonArray
                    ?.firstOrNull { elem ->
                        val assetName = elem.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                        assetName.endsWith(".apk", ignoreCase = true)
                    }
                    ?.jsonObject
                    ?.get("browser_download_url")
                    ?.jsonPrimitive
                    ?.content

                if (tag.isBlank() && htmlUrl.isBlank()) {
                    throw IllegalStateException("Respuesta de GitHub sin versión válida.")
                }

                GitHubReleaseInfo(
                    tagName = tag,
                    releaseName = name,
                    htmlUrl = htmlUrl.ifBlank { "https://github.com/$owner/$repo/releases" },
                    apkDownloadUrl = apkUrl,
                    notes = body
                )
            }
        }
    }

    companion object {
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
    }
}

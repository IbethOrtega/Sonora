package com.example

import com.example.data.local.SonoraPreferences
import com.example.data.network.GitHubUpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class InAppUpdateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `version comparison handles semantic tags multi-digit parts and versionCode`() {
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v2.1.0", "2.0.5"))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("v2.0.10", "2.0.2"))
        assertTrue(GitHubUpdateChecker.isRemoteVersionNewer("2.0.1", "2.0"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v2.0.0", "2.0"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("v1.9.9", "2.0.0"))
        assertFalse(GitHubUpdateChecker.isRemoteVersionNewer("", "2.0.0"))

        assertTrue(GitHubUpdateChecker.isApkVersionCodeNewer(downloadedVersionCode = 105L, installedVersionCode = 104L))
        assertFalse(GitHubUpdateChecker.isApkVersionCodeNewer(downloadedVersionCode = 104L, installedVersionCode = 104L))
        assertFalse(GitHubUpdateChecker.isApkVersionCodeNewer(downloadedVersionCode = 99L, installedVersionCode = 104L))
    }

    @Test
    fun `six hour cooldown enforces minimum interval and respects preference toggle`() {
        val sixHoursMs = SonoraPreferences.AUTO_UPDATE_CHECK_INTERVAL_MS
        assertEquals(6L * 60L * 60L * 1000L, sixHoursMs)

        val base = 1_760_000_000_000L

        assertFalse(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base + sixHoursMs * 2,
                lastCheckMs = 0L,
                autoCheckEnabled = false
            )
        )
        assertTrue(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base,
                lastCheckMs = 0L,
                autoCheckEnabled = true
            )
        )
        assertFalse(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base + sixHoursMs - 1_000L,
                lastCheckMs = base,
                autoCheckEnabled = true
            )
        )
        assertTrue(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base + sixHoursMs,
                lastCheckMs = base,
                autoCheckEnabled = true
            )
        )
        assertTrue(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base + sixHoursMs + 60_000L,
                lastCheckMs = base,
                autoCheckEnabled = true
            )
        )
        assertFalse(
            SonoraPreferences.shouldAutoCheckUpdates(
                nowMs = base - 5_000L,
                lastCheckMs = base,
                autoCheckEnabled = true
            )
        )
    }

    @Test
    fun `sha256 verification parses api digest notes and validates downloaded file bytes`() {
        val sampleBytes = "sonora-apk-binary-payload-v2.1".toByteArray()
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(sampleBytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(expectedHash, GitHubUpdateChecker.extractSha256Hex("sha256:${expectedHash.uppercase()}"))
        assertNull(GitHubUpdateChecker.extractSha256Hex("sha256:not-a-valid-hex"))

        val notesWithAsset = """
            Cambios de la versión
            $expectedHash *Sonora-release.apk
        """.trimIndent()
        assertEquals(
            expectedHash,
            GitHubUpdateChecker.extractSha256FromNotes(notesWithAsset, "Sonora-release.apk")
        )
        assertEquals(
            expectedHash,
            GitHubUpdateChecker.extractSha256FromNotes("SHA-256: `$expectedHash`", "Other.apk")
        )

        val apkFile = tempFolder.newFile("test_update.apk")
        apkFile.writeBytes(sampleBytes)

        assertEquals(expectedHash, GitHubUpdateChecker.computeFileSha256(apkFile))
        assertTrue(GitHubUpdateChecker.verifyFileSha256(apkFile, "sha256:$expectedHash"))
        assertTrue(GitHubUpdateChecker.verifyFileSha256(apkFile, null))

        val wrongHash = "0".repeat(64)
        assertFalse(GitHubUpdateChecker.verifyFileSha256(apkFile, wrongHash))
    }

    @Test
    fun `signing certificate comparison rejects empty or mismatched certs and accepts identical certs`() {
        val certA = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        val certB = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        assertTrue(
            GitHubUpdateChecker.doSigningCertificatesMatch(
                installedDigests = setOf(certA),
                downloadedDigests = setOf(certA.uppercase())
            )
        )
        assertFalse(
            GitHubUpdateChecker.doSigningCertificatesMatch(
                installedDigests = setOf(certA),
                downloadedDigests = setOf(certB)
            )
        )
        assertFalse(
            GitHubUpdateChecker.doSigningCertificatesMatch(
                installedDigests = emptySet(),
                downloadedDigests = setOf(certA)
            )
        )
        assertFalse(
            GitHubUpdateChecker.doSigningCertificatesMatch(
                installedDigests = setOf(certA),
                downloadedDigests = emptySet()
            )
        )
    }
}

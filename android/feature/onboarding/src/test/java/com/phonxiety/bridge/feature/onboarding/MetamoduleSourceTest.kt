package com.phonxiety.bridge.feature.onboarding

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The pinned metamodule archive is only installed when its SHA-256 matches. */
class MetamoduleSourceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val archive = "meta-overlayfs 1.3.1 test archive".toByteArray()
    private val archiveSha = sha256Hex(archive)

    @Test
    fun acceptsAnArchiveWithThePinnedHash() = runTest {
        val source = HttpMetamoduleSource(
            cacheDir = temporary.root,
            fetcher = HttpFetcher { archive },
            url = TEST_URL,
            expectedSha256 = archiveSha
        )

        val result = source.zip()

        assertTrue(result is MetamoduleResult.Ready)
        val file = (result as MetamoduleResult.Ready).file
        assertEquals(archiveSha, sha256Hex(file.readBytes()))
    }

    @Test
    fun mismatchFailsWithBothHashes() = runTest {
        val expected = "00".repeat(32)
        val source = HttpMetamoduleSource(
            cacheDir = temporary.root,
            fetcher = HttpFetcher { archive },
            url = TEST_URL,
            expectedSha256 = expected
        )

        val result = source.zip()

        assertTrue(result is MetamoduleResult.Failed)
        val reason = (result as MetamoduleResult.Failed).reason
        assertTrue(reason.contains(expected))
        assertTrue(reason.contains(archiveSha))
        assertTrue(temporary.root.listFiles().orEmpty().none { it.extension == "zip" })
    }

    @Test
    fun aVerifiedCacheIsReusedWithoutDownloadingAgain() = runTest {
        var fetches = 0
        val source = HttpMetamoduleSource(
            cacheDir = temporary.root,
            fetcher = HttpFetcher {
                fetches++
                archive
            },
            url = TEST_URL,
            expectedSha256 = archiveSha
        )

        source.zip()
        val second = source.zip()

        assertTrue(second is MetamoduleResult.Ready)
        assertEquals(1, fetches)
    }

    @Test
    fun aCorruptCacheIsReplaced() = runTest {
        val cached = File(temporary.root, "meta-overlayfs-13100-1.3.1.zip")
        cached.writeBytes("corrupt".toByteArray())
        val source = HttpMetamoduleSource(
            cacheDir = temporary.root,
            fetcher = HttpFetcher { archive },
            url = TEST_URL,
            expectedSha256 = archiveSha
        )

        val result = source.zip()

        assertTrue(result is MetamoduleResult.Ready)
        assertEquals(archiveSha, sha256Hex(cached.readBytes()))
    }

    private companion object {
        const val TEST_URL = "https://example.invalid/meta-overlayfs.zip"
    }
}

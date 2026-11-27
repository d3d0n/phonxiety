package com.phonxiety.bridge.data.updates

import com.phonxiety.bridge.data.ApiResult
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReleaseDownloaderTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a daemon source fetches the artifact through the route and hashes it`() = runTest {
        val payload = "served-artifact".toByteArray()
        val artifacts = FakeArtifactSource(payload, ApiResult.Success(200, JsonPrimitive("ok")))
        val target = File(temporary.newFolder("updates"), "phonxiety-bridge.apk")
        val progress = mutableListOf<Float?>()

        val downloaded = ReleaseDownloaderImpl(artifacts, Dispatchers.IO).download(ReleaseSource.Daemon("apk"), target) { progress += it }

        assertEquals("apk", artifacts.name)
        assertEquals(payload.toList(), target.readBytes().toList())
        assertEquals(sha256(payload), downloaded.sha256)
        assertEquals(payload.size.toLong(), downloaded.size)
        assertEquals(listOf(null), progress)
    }

    @Test
    fun `a refused artifact throws and leaves no partial file`() = runTest {
        val artifacts = FakeArtifactSource(
            ByteArray(0),
            ApiResult.Failure(404, "artifact_unavailable", "the daemon has no such artifact")
        )
        val target = File(temporary.newFolder("updates"), "module.zip")

        val error = runCatching {
            ReleaseDownloaderImpl(artifacts, Dispatchers.IO).download(ReleaseSource.Daemon("module"), target) {}
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("artifact_unavailable"))
        assertFalse(target.exists())
    }

    private class FakeArtifactSource(
        private val payload: ByteArray,
        private val result: ApiResult
    ) : UpdateArtifactSource {
        var name: String? = null

        override suspend fun fetch(name: String, sink: OutputStream): ApiResult {
            this.name = name
            if (result is ApiResult.Success) sink.write(payload)
            return result
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) out.append("%02x".format(byte.toInt() and 0xFF))
        return out.toString()
    }
}

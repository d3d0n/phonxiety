package com.phonxiety.bridge

import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.net.AttachmentChannel
import com.phonxiety.bridge.net.AttachmentFrame
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentReceiverTest {
    private val payload = ByteArray(150_000) { (it % 251).toByte() }

    @Test
    fun chunksEndAndSha256CompleteTheDownload() = runTest {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        val channel = FakeChannel(
            listOf(
                AttachmentFrame.Data(payload.copyOfRange(0, 64 * 1024)),
                AttachmentFrame.Data(payload.copyOfRange(64 * 1024, payload.size)),
                AttachmentFrame.End(payload.size.toLong(), digest)
            )
        )
        val sink = ByteArrayOutputStream()

        val outcome = AttachmentReceiver(channel, sink).receive()

        assertTrue(outcome is AttachmentOutcome.Complete)
        assertEquals(payload.size.toLong(), (outcome as AttachmentOutcome.Complete).total)
        assertEquals(digest, outcome.sha256)
        assertArrayEquals(payload, sink.toByteArray())
        assertTrue(channel.cancelled)
    }

    @Test
    fun aDifferentChecksumFailsAndKeepsNoClaimOfSuccess() = runTest {
        val channel = FakeChannel(
            listOf(
                AttachmentFrame.Data(payload),
                AttachmentFrame.End(payload.size.toLong(), "00".repeat(32))
            )
        )
        val outcome = AttachmentReceiver(channel, ByteArrayOutputStream()).receive()
        assertTrue(outcome is AttachmentOutcome.Failed)
        assertEquals("checksum_mismatch", (outcome as AttachmentOutcome.Failed).code)
    }

    @Test
    fun aShortStreamFails() = runTest {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).toHex()
        val channel = FakeChannel(
            listOf(
                AttachmentFrame.Data(payload.copyOfRange(0, 10)),
                AttachmentFrame.End(payload.size.toLong(), digest)
            )
        )
        val outcome = AttachmentReceiver(channel, ByteArrayOutputStream()).receive()
        assertEquals("size_mismatch", (outcome as AttachmentOutcome.Failed).code)
    }

    @Test
    fun anErrorFrameIsReportedAsItsCode() = runTest {
        val channel = FakeChannel(
            listOf(AttachmentFrame.Error("audio_unavailable", "this call has no recording"))
        )
        val outcome = AttachmentReceiver(channel, ByteArrayOutputStream()).receive()
        assertEquals("audio_unavailable", (outcome as AttachmentOutcome.Failed).code)
        assertTrue(channel.cancelled)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private class FakeChannel(private val frames: List<AttachmentFrame>) : AttachmentChannel {
        private var index = 0
        var cancelled = false

        override suspend fun next(): AttachmentFrame {
            val frame = frames.getOrElse(index) { AttachmentFrame.Error("closed", "no more frames") }
            index++
            return frame
        }

        override fun cancel() {
            cancelled = true
        }
    }
}

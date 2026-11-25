package com.phonxiety.bridge

import com.phonxiety.bridge.model.AttachmentOutcome
import com.phonxiety.bridge.net.AttachmentChannel
import com.phonxiety.bridge.net.AttachmentFrame
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Reads one attachment stream into [sink]: binary frames are data, the text
 * `end` frame carries the size and SHA-256 the receiver verifies.  An `error`
 * frame, a short stream or a checksum mismatch fails without pretending the
 * bytes are complete.
 */
internal class AttachmentReceiver(
    private val channel: AttachmentChannel,
    private val sink: OutputStream
) {
    suspend fun receive(): AttachmentOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            while (true) {
                when (val frame = channel.next()) {
                    is AttachmentFrame.Data -> {
                        sink.write(frame.bytes)
                        digest.update(frame.bytes)
                        total += frame.bytes.size
                    }

                    is AttachmentFrame.End -> {
                        sink.flush()
                        val actual = hex(digest.digest())
                        if (frame.total != total) {
                            return AttachmentOutcome.Failed(
                                "size_mismatch",
                                "the attachment ended after $total of ${frame.total} bytes"
                            )
                        }
                        if (!actual.equals(frame.sha256, ignoreCase = true)) {
                            return AttachmentOutcome.Failed("checksum_mismatch", "the attachment checksum does not match")
                        }
                        return AttachmentOutcome.Complete(total, actual)
                    }

                    is AttachmentFrame.Error -> return AttachmentOutcome.Failed(frame.code, frame.message)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AttachmentOutcome.Failed("attachment_failed", e.javaClass.simpleName)
        } finally {
            channel.cancel()
        }
    }

    private fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out[index++] = digits[value ushr 4]
            out[index++] = digits[value and 0x0F]
        }
        return String(out)
    }
}

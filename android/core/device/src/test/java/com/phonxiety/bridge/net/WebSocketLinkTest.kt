package com.phonxiety.bridge.net

import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.model.LinkDownReason
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address link's down report is OkHttp's, on OkHttp's thread: a refused
 * send only returns false.  A synchronous report from inside the send would run
 * the engine's down handler while the supervisor monitor is held (the send
 * methods hold it), which is exactly the lock cycle that deadlocks against the
 * dial path.
 */
class WebSocketLinkTest {
    /** OkHttp's contract as the sender sees it: `send` returns whether it took the frame. */
    private class RefusingSocket : WebSocket {
        override fun request(): Request = error("no request in this test")

        override fun queueSize(): Long = 0L

        override fun send(text: String): Boolean = false

        override fun send(bytes: ByteString): Boolean = false

        override fun close(code: Int, reason: String?): Boolean = true

        override fun cancel() = Unit
    }

    @Test
    fun aRefusedSendNeverReportsDownOnTheCallersThread() {
        val socket = RefusingSocket()
        var adapter: WebSocketListener? = null
        val link = WebSocketLink(
            client = pinnedOkHttpClient(null),
            config = BridgeConfig.Address("wss://127.0.0.1:1/device", "token", null),
            tap = FrameTapSink.None,
            connect = { _, listener ->
                adapter = listener
                socket
            }
        )

        val opened = AtomicBoolean(false)
        val downThreads = Collections.synchronizedList(mutableListOf<Thread>())
        val listener = object : DeviceLink.Listener {
            override fun onLinkOpened() {
                opened.set(true)
            }

            override fun onLinkText(text: String) = Unit

            override fun onLinkBinary(bytes: ByteString) = Unit

            override fun onLinkDown(reason: LinkDownReason, detail: String?) {
                downThreads += Thread.currentThread()
            }
        }

        link.start(listener)
        // OkHttp drives onOpen on its own thread; the test drives it here.
        adapter!!.onOpen(socket, openResponse())
        assertTrue("the link opened", opened.get())

        val caller = Thread.currentThread()
        // A synchronous report would run the down handler right here, inside
        // the send, while the supervisor lock is still held.
        assertFalse("the socket refused the frame", link.sendText("""{"type":"hello"}"""))
        assertFalse("the socket refused the frame", link.offerBinary(byteArrayOf(1, 2, 3), 3))

        assertTrue(
            "a refusal is never reported down on the sender's thread: $downThreads",
            downThreads.none { it === caller }
        )
        assertTrue("no terminal report at all from the send path", downThreads.isEmpty())
    }

    private fun openResponse(): Response = Response.Builder()
        .request(Request.Builder().url("wss://127.0.0.1:1/device").build())
        .protocol(Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()
}

package com.phonxiety.bridge.net

import okhttp3.WebSocket
import okio.ByteString

/**
 * The address link's send path.
 *
 * OkHttp's own send queue is the only queue this transport has, and the audio
 * freeze forbids a second one in front of it: on this socket the freeze
 * overrides an own queue, because an extra queue would add a coroutine hop
 * between capture and the socket and would count every byte twice in the
 * backlog.  So [sendText] and [sendBinary] hand the frame to [WebSocket.send]
 * synchronously, under one short lock, in call order, and the backlog is the
 * socket's [WebSocket.queueSize].
 *
 * A refused send (OkHttp's queue full, or the socket already gone) returns
 * false to the caller, so [LinkSupervisor.sendFrame] and live capture count the
 * frame as unsent instead of assuming delivery.  [onRefused] runs outside the
 * lock, so a terminal report never holds it.
 */
internal class SocketSender(
    private val socket: () -> WebSocket?,
    private val tap: FrameTapSink = FrameTapSink.None,
    private val onRefused: () -> Unit = {}
) {
    private val lock = Any()

    /** OkHttp's queue is the backlog: nothing is added to it. */
    val backlogBytes: Long get() = socket()?.queueSize() ?: 0L

    fun sendText(message: String): Boolean = send(Framing.KIND_TEXT, utf8Length(message)) { it.send(message) }

    fun sendBinary(payload: ByteString): Boolean = send(Framing.KIND_BINARY, payload.size) { it.send(payload) }

    private fun send(kind: Int, bytes: Int, handOff: (WebSocket) -> Boolean): Boolean {
        val target = socket() ?: return false
        val accepted = synchronized(lock) {
            val sent = handOff(target)
            if (sent && tap.enabled) tap.frame(FrameDirection.OUT, FrameStream.SOCKET, kind, bytes)
            sent
        }
        if (!accepted) onRefused()
        return accepted
    }
}

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
 * frame as unsent instead of assuming delivery.
 *
 * A refusal is never a terminal event here.  Every false from [WebSocket.send]
 * is one of two OkHttp conditions, and both leave the listener in charge of the
 * report: `failed`/`enqueuedClose`, where `onFailure`/`onClosing`/`onClosed`
 * has fired or is scheduled, or the 16 MiB queue bound, where OkHttp closes the
 * socket itself and the same close callbacks follow.  So the link goes down
 * through OkHttp's own listener on OkHttp's thread, never through the producer
 * that happened to hit the refusal.  That is what keeps this send path out of
 * the engine-lock versus supervisor-monitor cycle: [LinkSupervisor]'s send
 * methods hold the supervisor monitor while they call here, and a synchronous
 * down report would walk into the engine lock from inside it.
 *
 * The frame tap records the accepted frame *after* the lock is released: the
 * hand-off stays under the lock, the metadata write does not, so a slow dump
 * can never hold up another producer.  Tap order therefore need not match wire
 * order exactly, and the sequence number is taken by the tap itself.
 */
internal class SocketSender(
    private val socket: () -> WebSocket?,
    private val tap: FrameTapSink = FrameTapSink.None
) {
    private val lock = Any()

    /** OkHttp's queue is the backlog: nothing is added to it. */
    val backlogBytes: Long get() = socket()?.queueSize() ?: 0L

    fun sendText(message: String): Boolean = send(Framing.KIND_TEXT, utf8Length(message)) { it.send(message) }

    fun sendBinary(payload: ByteString): Boolean = send(Framing.KIND_BINARY, payload.size) { it.send(payload) }

    private fun send(kind: Int, bytes: Int, handOff: (WebSocket) -> Boolean): Boolean {
        val target = socket() ?: return false
        val accepted = synchronized(lock) { handOff(target) }
        if (accepted && tap.enabled) tap.frame(FrameDirection.OUT, FrameStream.SOCKET, kind, bytes)
        return accepted
    }
}

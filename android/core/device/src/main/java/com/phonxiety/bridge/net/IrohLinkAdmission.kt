package com.phonxiety.bridge.net

import com.phonxiety.bridge.IdleLinkAction

/**
 * The admission token a network recycle took, carried across the link's one
 * terminal transition.
 *
 * A network recycle admits itself before it acts, and the transition it
 * triggers may not be its own: the endpoint shutdown it starts can make a
 * reader or the closed watcher win `downOnce` first.  If the recycle released
 * the token when its own `fail()` returned, the door would open while the
 * winning failure has not yet reached the supervisor and the engine — a dial
 * reserving a call in that window is aborted by the very link-down still being
 * published.  So the token is handed to the transition instead: whichever
 * failure claims it releases the token after
 * [DeviceLink.Listener.onLinkDown] has returned, i.e. once the supervisor has
 * dropped the link and the engine has seen it down.
 *
 * A link that is already finished has no transition left to carry the token;
 * [attach] then refuses and its caller releases it at once.  [finish] is also
 * what a cancelled link calls: the supervisor cancels a link only after it
 * dropped it, so no publication is left to release the token.
 */
internal class IrohLinkAdmission {
    private val lock = Any()
    private var finished = false
    private var held: IdleLinkAction? = null

    /** Hands [action] to the terminal transition; false means the caller must release it itself. */
    fun attach(action: IdleLinkAction): Boolean = synchronized(lock) {
        if (finished) return false
        check(held == null) { "one idle-link token at a time" }
        held = action
        true
    }

    /**
     * Takes [action] back from a link that stays live because the action was
     * refused, and releases it; a finished link already released it.
     */
    fun detach(action: IdleLinkAction) {
        val mine = synchronized(lock) {
            if (held !== action) return
            held = null
            true
        }
        if (mine) action.release()
    }

    /**
     * The link's end is final: release the token, if one is attached, outside
     * the registry lock so a dial waiting on the admission can reserve.
     */
    fun finish() {
        val action = synchronized(lock) {
            if (finished) return
            finished = true
            held.also { held = null }
        }
        action?.release()
    }
}

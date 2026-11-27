package com.phonxiety.bridge.model

/**
 * What a failed `/app/v1` exchange means for the person reading it, decided once from the
 * daemon's status and code instead of repeatedly at each screen.
 *
 * - [TRANSPORT]: the request did not reach a daemon that could answer it (the link, the gateway,
 *   or the wait for an answer failed). Asking again can work, so the card offers a retry.
 * - [DAEMON]: a daemon answered and something inside it failed. Asking again repeats the same
 *   failure; the Doctor knows what to check, so the card offers to open it.
 * - [REFUSED]: the daemon understood the request and declined it (a 4xx other than the transport
 *   ones). The sentence explains what to change; retrying the same request would refuse again.
 */
enum class FailureKind {
    TRANSPORT,
    DAEMON,
    REFUSED;

    companion object {
        /** Codes that mean "no daemon answer", whatever status carries them. */
        private val TRANSPORT_CODES = setOf("transport_error", "link_down", "timeout", "pairing_changed")

        /** Codes a daemon itself produced a 5xx-shaped answer for. */
        private val DAEMON_CODES = setOf("decode_failed", "internal_error")

        /** Statuses the engine and the retry budget use when no daemon answer arrived. */
        private val TRANSPORT_STATUS = setOf(408, 502, 503, 504)

        /**
         * Classifies one failure. The code wins where it names a cause outright; the status
         * otherwise. Anything unrecognised is a daemon failure: the request reached a daemon and
         * the answer is not something a retry or the caller can fix.
         */
        fun of(status: Int, code: String?): FailureKind {
            if (code in TRANSPORT_CODES) return TRANSPORT
            if (code in DAEMON_CODES) return DAEMON
            return when {
                status in TRANSPORT_STATUS -> TRANSPORT
                status in 400..499 -> REFUSED
                else -> DAEMON
            }
        }
    }
}

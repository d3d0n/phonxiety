package com.phonxiety.bridge

/**
 * Remembers the capabilities the last queued `hello` announced.
 *
 * `callControl` and `frameworkAudio` reach the daemon only through `hello`, so
 * a permission granted or revoked while the link is up stays invisible to it
 * until the next handshake.  This watch compares permission samples against the
 * last hello — never against the UI [Capabilities], which [BridgeEngine] also
 * writes with the daemon's voice-control support — and only reports a change
 * that two consecutive samples agree on.
 */
internal class CapabilityWatch {
    private var announced: PhoneCapabilities? = null

    /** The last sample that differed from [announced], waiting for the next one. */
    private var candidate: PhoneCapabilities? = null

    /** The transport accepted a hello carrying [capabilities]. */
    @Synchronized
    fun helloSent(capabilities: PhoneCapabilities) {
        announced = capabilities
        candidate = null
    }

    /** The link went down: the next hello announces whatever it finds then. */
    @Synchronized
    fun helloGone() {
        announced = null
        candidate = null
    }

    /**
     * Records one permission sample and says whether the change is stable:
     * true only when this sample and the one before it agree and both differ
     * from the last hello.  A sample matching the last hello clears the
     * candidate, so permissions alternating around the hello never admit a
     * refresh.  False before the first hello.
     */
    @Synchronized
    fun stableChange(current: PhoneCapabilities): Boolean {
        if (announced == null || current == announced) {
            candidate = null
            return false
        }
        val previous = candidate
        candidate = current
        return previous == current
    }
}

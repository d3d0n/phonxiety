package com.phonxiety.bridge

import com.phonxiety.bridge.model.LinkDownReason

/**
 * Turns a stable capability change into one link refresh.
 *
 * Two consecutive samples must agree and differ from the last hello before a
 * refresh is admitted, so a permission flapping on and off never reconnects.
 * The idle door is taken before the decision and held through the synchronous
 * link-down publication, so a call reserved a moment earlier is never cut by a
 * refresh decided on an older sample; the re-sample under the door re-checks
 * the change has not reverted meanwhile.  A refused door defers: the candidate
 * stays, and the next stable sample decides again.
 */
internal class CapabilityRefresh(
    private val watch: CapabilityWatch,
    private val admission: CallAdmission,
    private val callOwned: () -> Boolean,
    private val sample: () -> PhoneCapabilities,
    private val reconnect: (reason: LinkDownReason) -> Boolean
) {
    /**
     * One sample; true when the link was dropped so the next attempt sends a
     * hello carrying what the phone can do now.
     */
    fun run(): Boolean {
        if (!watch.stableChange(sample())) return false
        val action = admission.tryBeginIdleLinkAction(callOwned) ?: return false
        try {
            if (!watch.stableChange(sample())) return false
            return reconnect(CAPABILITIES_CHANGED)
        } finally {
            action.close()
        }
    }

    companion object {
        /** Link-down reason: only a fresh hello can tell the daemon otherwise. */
        val CAPABILITIES_CHANGED = LinkDownReason.CAPABILITIES_CHANGED
    }
}

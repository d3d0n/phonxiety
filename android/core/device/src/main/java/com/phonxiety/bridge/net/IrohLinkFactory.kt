package com.phonxiety.bridge.net

import android.content.Context
import com.phonxiety.bridge.BridgeConfig
import com.phonxiety.bridge.IdleLinkAction
import com.phonxiety.bridge.model.LinkKind

internal class IrohLinkFactory(
    private val context: Context,
    private val config: BridgeConfig.Iroh,
    private val ownsCall: () -> Boolean,
    private val beginIdleLinkAction: () -> IdleLinkAction?,
    private val tap: () -> FrameTapSink = { FrameTapSink.None },
    clock: () -> Long = { System.nanoTime() / 1_000_000L }
) : DeviceLinkFactory {
    /**
     * Escalation history for the paired endpoint: one instance outlives every
     * link this factory creates and every endpoint recycle, so the recycle
     * window and the cooldown are measured across reconnects.
     */
    private val policy = IrohEscalationPolicy(clock = clock)

    /**
     * Recovery history for an endpoint that never opens: one instance outlives
     * every link this factory creates, so the failure streak, the pending
     * fresh-bind request and the recycle cooldown span reconnects.
     */
    private val budget = IrohFailureBudget(clock)

    override fun create(): DeviceLink = IrohLink(context, config, ownsCall, beginIdleLinkAction, policy, budget, tap())

    /** Only iroh outcomes reach this factory; each one feeds the endpoint's one account. */
    override fun attemptEnded(outcome: AttemptOutcome, kind: LinkKind?) = budget.note(outcome)

    override fun shutdown() {
        IrohSupport.shutdownAsync()
    }
}

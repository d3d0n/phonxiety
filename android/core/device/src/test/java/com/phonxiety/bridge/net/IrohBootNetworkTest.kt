package com.phonxiety.bridge.net

import com.phonxiety.bridge.CallAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The boot-network half of the endpoint's lifetime: only the generation bound
 * while the default network lacked INTERNET is recycled once INTERNET appears,
 * and a live link keeps its veto so a call or a recent escalation is never cut
 * by boot timing.
 */
class IrohBootNetworkTest {
    private val noDeadlines = object : LinkScheduler {
        override fun after(delayMs: Long, task: () -> Unit) = LinkTask { }
    }

    /** Records the generations the boot path recycled. */
    private class Recycler {
        val generations = mutableListOf<Long>()

        fun recycle(generation: Long): Boolean {
            generations += generation
            return true
        }
    }

    @Test
    fun anEndpointBoundWithoutInternetIsRecycledOnceInternetAppears() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)
        boot.bound(4, hadInternet = false)

        boot.internetAvailable()
        // A second capability event never recycles again.
        boot.internetAvailable()

        assertEquals(listOf(4L), recycler.generations)
    }

    @Test
    fun anEndpointBoundWithInternetIsNotRecycled() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)
        boot.bound(5, hadInternet = true)

        boot.internetAvailable()

        assertEquals(emptyList<Long>(), recycler.generations)
    }

    @Test
    fun aCallOwnedLinkIsNeverRecycledByTheBootNetworkPath() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)
        val admission = CallAdmission()
        // A real monitor: its policy holds the same cooldown the link uses.
        val monitor = IrohPathMonitor(
            IrohEscalationPolicy({ 0L }),
            noDeadlines,
            { true },
            { true }
        )
        monitor.start(IrohPathSource { IrohPathWatch { } })
        val veto = IrohBootVeto(
            beginIdleLinkAction = { admission.tryBeginIdleLinkAction { true } },
            admitRecycle = { monitor.admitNetworkRecycle() }
        )

        var linkRecycled = 0
        boot.bound(7, hadInternet = false)
        boot.attach(7) {
            val admitted = veto.admit()
            if (admitted != null) {
                admitted.release()
                linkRecycled++
            }
            admitted != null
        }

        boot.internetAvailable()

        assertEquals(emptyList<Long>(), recycler.generations)
        assertEquals(0, linkRecycled)
        // The veto released its token: the door admits an idle action again.
        val again = admission.tryBeginIdleLinkAction { false }
        assertNotNull("the boot veto must not leave the admission shut", again)
        again?.release()
        monitor.cancel()
    }

    @Test
    fun aLinkRegisteredDuringPublicationBlocksTheBootRecycle() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)
        var asked = 0

        boot.bound(11, hadInternet = false)
        // connect() registers its veto before publishing the link as open: the
        // dial may already own a call when the capability event arrives.
        val release = boot.attach(11) {
            asked++
            false
        }

        boot.internetAvailable()

        assertEquals(1, asked)
        assertEquals(emptyList<Long>(), recycler.generations)

        // Refused, not dropped: the generation stays armed and the next event
        // asks the link again instead of bypassing it.
        boot.internetAvailable()
        assertEquals(2, asked)
        assertEquals(emptyList<Long>(), recycler.generations)

        release()
    }

    @Test
    fun aNoOwnerRecycleIsNotOvertakenByADialThatLandsWithIt() {
        val recycler = Recycler()
        var asked = 0
        lateinit var boot: IrohBootNetwork
        var lateRelease: (() -> Unit)? = null

        boot = IrohBootNetwork { generation ->
            // The dial attaches at the instant the no-owner decision is taken.
            lateRelease = boot.attach(generation) {
                asked++
                true
            }
            recycler.generations += generation
            true
        }

        boot.bound(12, hadInternet = false)
        boot.internetAvailable()
        assertEquals(listOf(12L), recycler.generations)

        // The registration is refused: no link is asked about a generation that
        // is already being replaced, and nothing else is recycled.
        boot.internetAvailable()
        assertEquals(0, asked)
        assertEquals(listOf(12L), recycler.generations)
        lateRelease?.invoke()
    }

    @Test
    fun anInternetEventDuringTheBindIsRecycledOnceTheEndpointIsPublished() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)

        boot.beginBind()
        // INTERNET appears while the native bind runs, before any generation exists.
        boot.internetAvailable()
        assertEquals(emptyList<Long>(), recycler.generations)

        boot.bound(13, hadInternet = false)
        assertEquals(listOf(13L), recycler.generations)

        // The held transition is spent: the same network's later events do nothing.
        boot.internetAvailable()
        assertEquals(listOf(13L), recycler.generations)
    }

    @Test
    fun aBindThatFoundInternetRecyclesNothingFromTheHeldEvent() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)

        boot.beginBind()
        boot.internetAvailable()
        boot.bound(14, hadInternet = true)

        boot.internetAvailable()

        assertEquals(emptyList<Long>(), recycler.generations)
    }

    @Test
    fun aHeldEventIsDroppedWhenTheBindFails() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)

        boot.beginBind()
        boot.internetAvailable()
        boot.bindFailed()

        // A later, unrelated bind must not inherit the failed bind's transition.
        boot.bound(15, hadInternet = false)
        assertEquals(emptyList<Long>(), recycler.generations)
    }

    @Test
    fun aLiveLinkThatAdmitsTheRecycleIsNotDoubleRecycled() {
        val recycler = Recycler()
        val boot = IrohBootNetwork(recycler::recycle)
        val admission = CallAdmission()
        val monitor = IrohPathMonitor(
            IrohEscalationPolicy({ 0L }),
            noDeadlines,
            { false },
            { true }
        )
        monitor.start(IrohPathSource { IrohPathWatch { } })
        val veto = IrohBootVeto(
            beginIdleLinkAction = { admission.tryBeginIdleLinkAction { false } },
            admitRecycle = { monitor.admitNetworkRecycle() }
        )

        // The link recycles its own generation and reconnects.
        val link = mutableListOf<Long>()
        boot.bound(9, hadInternet = false)
        val release = boot.attach(9) {
            val admitted = veto.admit() ?: return@attach false
            link += 9
            admitted.release()
            true
        }

        boot.internetAvailable()
        boot.internetAvailable()

        assertEquals(listOf(9L), link)
        assertEquals(emptyList<Long>(), recycler.generations)

        // The link ended: its veto is released with it.
        release()
        monitor.cancel()
    }
}

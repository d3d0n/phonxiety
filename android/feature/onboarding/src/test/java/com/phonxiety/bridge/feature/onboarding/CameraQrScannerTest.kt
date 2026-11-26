package com.phonxiety.bridge.feature.onboarding

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val directExecutor = Executor { it.run() }

/**
 * The in-app scanner's session guards. The dialog can be dismissed before the camera provider
 * resolves, so a late initialization listener must bind nothing onto the released executor and
 * scanner; the dismissal unbinds only the use cases this session bound, releases the resources once,
 * and drops a code that arrives afterwards.
 */
class CameraQrScannerTest {
    private val errors = mutableListOf<Throwable>()

    private fun session(
        future: PendingProvider,
        onBind: () -> String = { "this session's use cases" },
        onUnbind: (String) -> Unit = { },
        onRelease: () -> Unit = { }
    ) = CameraSession(
        future = future,
        mainExecutor = directExecutor,
        onError = { errors += it },
        bind = { _, _ -> onBind() },
        unbind = { _, handles -> onUnbind(handles) },
        release = onRelease
    )

    @Test
    fun aProviderResolvedAfterDismissBindsNothing() {
        val future = PendingProvider()
        val binds = mutableListOf<String>()
        var releases = 0
        val session = session(
            future,
            onBind = {
                binds += "bound"
                "nothing"
            },
            onRelease = { releases++ }
        )

        session.start()
        session.dispose()
        future.resolve("camera")

        assertTrue(binds.isEmpty())
        assertEquals(1, releases)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun aProviderThatFailsIsReportedWithoutBinding() {
        val future = PendingProvider()
        val binds = mutableListOf<String>()
        val session = session(
            future,
            onBind = {
                binds += "bound"
                "nothing"
            }
        )

        session.start()
        future.fail(IllegalStateException("camera in use"))

        assertTrue(binds.isEmpty())
        assertEquals(listOf("camera in use"), errors.map { it.message })
    }

    @Test
    fun dismissalUnbindsOnlyThisSessionsUseCasesAndReleasesOnce() {
        val future = PendingProvider()
        val unbound = mutableListOf<String>()
        var releases = 0
        val session = session(future, onUnbind = { unbound += it }, onRelease = { releases++ })

        session.start()
        future.resolve("camera")
        assertTrue(unbound.isEmpty())

        session.dispose()
        assertEquals(listOf("this session's use cases"), unbound)
        assertEquals(1, releases)

        session.dispose()
        assertEquals(1, releases)
        assertEquals(listOf("this session's use cases"), unbound)
    }

    @Test
    fun aCodeThatArrivesAfterTheDismissalIsDropped() {
        val session = session(PendingProvider())
        val codes = mutableListOf<String>()

        session.dispose()
        assertFalse(session.wantsCode())
        session.deliver("late") { codes += it }

        assertTrue("a code from a closed dialog must be dropped", codes.isEmpty())
    }

    @Test
    fun onlyTheFirstCodeOfAnOpenSessionIsDelivered() {
        val session = session(PendingProvider())
        val codes = mutableListOf<String>()

        session.deliver("first") { codes += it }
        session.deliver("second") { codes += it }

        assertEquals(listOf("first"), codes)
    }

    @Test
    fun aClosedDialogStopsDecoding() {
        val session = session(PendingProvider())
        assertTrue(session.wantsCode())

        session.dispose()

        assertFalse(session.wantsCode())
    }

    private class PendingProvider : ListenableFuture<String> {
        private val waiting = mutableListOf<() -> Unit>()
        private var value: String? = null
        private var failure: Throwable? = null

        override fun addListener(listener: Runnable, executor: Executor) {
            waiting += { executor.execute(listener) }
        }

        override fun isDone(): Boolean = value != null || failure != null

        override fun get(): String = failure?.let { throw it } ?: checkNotNull(value) { "the provider has not resolved" }

        override fun get(timeout: Long, unit: TimeUnit): String = get()

        override fun isCancelled(): Boolean = false

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false

        fun resolve(provider: String) {
            value = provider
            runWaiting()
        }

        fun fail(cause: Throwable) {
            failure = cause
            runWaiting()
        }

        private fun runWaiting() {
            val current = waiting.toList()
            waiting.clear()
            current.forEach { it() }
        }
    }
}

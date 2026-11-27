package com.phonxiety.bridge

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * One long-lived scope for the bridge: named in a thread dump, supervised so a
 * failing launch never cancels its siblings, and with an exception handler that
 * reports the throwable's class — never its message, which may carry payloads.
 *
 * [dispatcher] keeps the lifetime and threading the caller already chose; only
 * the job, name and failure reporting are added.
 */
internal fun bridgeScope(
    name: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    report: (area: String, error: Throwable) -> Unit = ::reportUncaught
): CoroutineScope {
    val handler = CoroutineExceptionHandler { _, error -> report(name, error) }
    return CoroutineScope(SupervisorJob() + dispatcher + CoroutineName(name) + handler)
}

private fun reportUncaught(area: String, error: Throwable) {
    BridgeLog.e("Bridge:$area", "uncaught coroutine failure: ${error.javaClass.name}")
}

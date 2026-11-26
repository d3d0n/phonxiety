package com.phonxiety.bridge.data

import android.content.Context
import com.phonxiety.bridge.BridgeConfigException
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime
import com.phonxiety.bridge.BridgeService
import com.phonxiety.bridge.PairingLink
import com.phonxiety.bridge.PairingResult
import com.phonxiety.bridge.model.BridgeState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class RuntimeBridgeStateRepository @Inject constructor() : BridgeStateRepository {
    override val state: StateFlow<BridgeState> = BridgeRuntime.state
}

/**
 * Pairing lives in device-protected storage ([BridgeConfigLoader]); a stored
 * config starts the service, which then owns the link and the state flow.
 *
 * The file is not written only by [pair]: `mise run phone:pair` drops it in through a root shell and
 * the service rewrites it on every start, so a flag captured in the constructor kept the wizard on
 * "Unpaired" until the process was killed. [paired] is therefore an ordinary cached flow — reading
 * it never touches storage — and the cache is re-read from storage on the IO dispatcher at the
 * points where the file may have moved: on every [refresh] a caller asks for (the wizard's probe on
 * start and after returning to the app) and when the bridge service publishes a new link state, the
 * moment it has reloaded the config. Nothing polls.
 */
@Singleton
class DevicePairingRepository internal constructor(
    private val context: Context,
    /** The service start that follows the write; it throws when refused, so the write is undone. */
    private val startService: (Context) -> Unit = { BridgeService.startOrThrow(it) },
    private val read: () -> Boolean
) : PairingRepository {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        read = { BridgeConfigLoader.loadOrNull(context) != null }
    )

    private val state = MutableStateFlow(read())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One lock around every storage read-and-publish and every storage-and-cache mutation, so an
    // older refresh can never publish a value read before a newer refresh or pairing wrote its own.
    private val mutex = Mutex()

    override val paired: StateFlow<Boolean> = state.asStateFlow()

    init {
        scope.launch {
            // A service start reloads config.json and then attaches its engine, which is the only
            // signal the app process gets about a config written by the host task. The first
            // emission is the link state at subscription time: a config written between the
            // constructor's read and this subscription shows up here and only here, so it refreshes
            // the cache like every later change.
            BridgeRuntime.state
                .map { it.link }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /** Re-reads the stored config on the IO dispatcher; the cached value moves only when it changed. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock { state.value = read() }
    }

    override suspend fun pair(link: String): PairResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val parsed = PairingLink.parse(link)) {
                is PairingResult.Invalid -> PairResult.Invalid(parsed.error.code)

                is PairingResult.Valid -> {
                    // The write is atomic, so a failing write leaves the old config alone. Everything
                    // after it can still fail (starting the service throws SecurityException when the
                    // app may not, for one), and a rejected attempt must not leave a readable pairing
                    // behind: the next refresh would publish it and the wizard would promote a pairing
                    // the person was told was rejected. So the previous config is kept and put back
                    // byte for byte, or removed when there was none.
                    val previous = BridgeConfigLoader.snapshot(context)
                    try {
                        val stored = BridgeConfigLoader.write(context, parsed.config)
                        try {
                            BridgeRuntime.rememberPairing(stored.kind)
                            startService(context)
                        } catch (error: Exception) {
                            rollBackPairing(previous)
                            throw error
                        }
                        state.value = true
                        PairResult.Paired
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: BridgeConfigException) {
                        PairResult.Invalid(error.code)
                    } catch (error: Exception) {
                        PairResult.Invalid("pairing_store_failed")
                    }
                }
            }
        }
    }

    /**
     * Undoes a write that did not end in a started service: the stored config goes back to
     * [previous] (or disappears when there was none), and so does the pairing kind the runtime
     * mirrors from it. A rejected attempt must leave neither a readable config nor an app that
     * believes it is paired.
     */
    private fun rollBackPairing(previous: ByteArray?) {
        if (!BridgeConfigLoader.restore(context, previous)) return
        val stored = BridgeConfigLoader.loadOrNull(context)
        if (stored == null) BridgeRuntime.forgetPairing() else BridgeRuntime.rememberPairing(stored.kind)
    }

    override suspend fun unpair() = withContext(Dispatchers.IO) {
        mutex.withLock {
            BridgeConfigLoader.delete(context)
            BridgeRuntime.forgetPairing()
            BridgeService.stop(context)
            state.value = false
        }
    }
}

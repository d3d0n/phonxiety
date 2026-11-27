package com.phonxiety.bridge.engine

import android.content.Context
import android.net.wifi.WifiManager
import com.phonxiety.bridge.BridgeLog
import com.phonxiety.bridge.PhoneSettings
import com.phonxiety.bridge.PhoneSettingsStore
import com.phonxiety.bridge.model.DeviceOutbound
import com.phonxiety.bridge.model.LinkDownReason
import com.phonxiety.bridge.net.ConnectionMode
import com.phonxiety.bridge.net.IrohSupport
import com.phonxiety.bridge.net.LinkSupervisor
import com.phonxiety.bridge.net.RelayModeSetting
import com.phonxiety.bridge.net.RelayOptions
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies the phone's stored preferences to the link: codec and bitrate for
 * the next hello, the relay configuration, the Wi-Fi low-latency lock and the
 * idle connection-mode switch.
 *
 * The first hello must carry what the phone stored, so [loadBeforeHello] is a
 * bounded blocking read on the link thread instead of a race with a coroutine.
 */
internal class SettingsSync(
    private val context: Context,
    private val links: () -> LinkSupervisor,
    private val scheduler: ScheduledExecutorService,
    private val audioSettings: AudioSettings,
    private val hello: HelloState,
    private val wire: (DeviceOutbound) -> Boolean,
    private val ownsCall: () -> Boolean,
    private val isRunning: () -> Boolean
) {
    companion object {
        /** One preferences read on the link thread, so the first hello is not sent blind. */
        private const val SETTINGS_READ_TIMEOUT_MS = 500L
    }

    /** Phone-owned preferences, kept current from DataStore for hello, set_prefs and the lock. */
    @Volatile var phoneSettings = PhoneSettings()
        private set

    @Volatile var loaded = false
        private set

    private val preferences = PhoneSettingsStore.of(context)

    // WIFI_MODE_FULL_LOW_LATENCY is what keeps audio latency stable on a busy Wi-Fi network;
    // it is held only while the link is up and the setting is on.
    private val wifiLock: WifiManager.WifiLock? = context.getSystemService(WifiManager::class.java)
        ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phonxiety:low-latency")
        ?.apply { setReferenceCounted(false) }

    /**
     * One phone preference change: remember it for the next hello, apply the local effects, and
     * tell a live daemon so the next call uses it without a reconnect.
     */
    fun apply(settings: PhoneSettings, push: Boolean = true) {
        val codecChanged = settings.preferredCodec != audioSettings.preferredCodec
        val bitrateChanged = settings.maxBitrate != audioSettings.maxBitrate
        val modeChanged = settings.connectionMode != phoneSettings.connectionMode
        phoneSettings = settings
        audioSettings.preferredCodec = settings.preferredCodec
        audioSettings.maxBitrate = settings.maxBitrate
        BridgeLog.minimumLevel = BridgeLog.levelOf(settings.logLevel) ?: BridgeLog.INFO
        applyRelays(settings)
        applyWifiLock()
        // A new mode applies to the next attempt: the current link was chosen under the old one.
        if (modeChanged && !ownsCall()) {
            runCatching { scheduler.execute { runCatching { links().reconnect(LinkDownReason.CONNECTION_MODE_CHANGED) } } }
        }
        if (push && hello.delivered && links().isUp() && (codecChanged || bitrateChanged)) {
            wire(
                DeviceOutbound.SetPrefs(
                    preferredCodec = settings.preferredCodec,
                    maxBitrate = settings.maxBitrate
                )
            )
        }
    }

    /**
     * Points the iroh endpoint at the relays the phone chose.  A bound endpoint keeps its
     * relays, so a change recycles it — never during a call, where the link must stay put;
     * the choice then applies to the next bind.
     */
    private fun applyRelays(settings: PhoneSettings) {
        val mode = RelayModeSetting.of(settings.relayMode)
        val urls = RelayOptions.urls(mode, settings.relayUrls, settings.daemonRelayUrls)
        val changed = IrohSupport.configureRelays(mode, urls)
        if (changed && !ownsCall()) IrohSupport.recycleEndpoint()
    }

    /**
     * The first `hello` must carry what the phone stored, not the defaults: a call can start
     * seconds after the link comes up.  The link's own thread waits for one small disk read
     * instead of racing a coroutine.
     */
    fun loadBeforeHello() {
        if (loaded) return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(SETTINGS_READ_TIMEOUT_MS) { preferences.read() }
            }?.let { stored ->
                loaded = true
                apply(stored, push = false)
            }
        }
    }

    /** Holds the Wi-Fi low-latency lock exactly while the link is up and the setting is on. */
    fun applyWifiLock() {
        val wanted = phoneSettings.wifiLowLatency && links().isUp()
        runCatching {
            if (wanted) {
                if (wifiLock?.isHeld != true) wifiLock?.acquire()
            } else if (wifiLock?.isHeld == true) {
                wifiLock.release()
            }
        }
    }

    /** Releases the lock unconditionally, for engine shutdown. */
    fun releaseWifiLock() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    /**
     * One idle re-probe: with automatic selection and no call owned, a better measured
     * path takes the link over.  The selector's hysteresis decides, so a single lucky
     * probe never moves it.
     */
    fun considerSwitch() {
        if (!isRunning() || !links().isUp() || ownsCall()) return
        if (ConnectionMode.of(phoneSettings.connectionMode) != ConnectionMode.AUTO) return
        if (links().considerSwitch()) links().reconnect(LinkDownReason.CANDIDATE_BETTER)
    }

    /** The coroutine collector that keeps [phoneSettings] current while the engine runs. */
    fun collectInto(scope: CoroutineScope) {
        scope.launch {
            runCatching {
                preferences.settings.collect {
                    loaded = true
                    apply(it)
                }
            }
        }
    }
}

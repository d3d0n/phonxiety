package com.phonxiety.bridge

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.phonxiety.bridge.device.R as DeviceR
import com.phonxiety.bridge.net.IrohSupport
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One-screen companion UI.  It pairs the phone with a daemon (QR scan or a
 * `phonxiety://pair` link), shows the live link and permission state, and
 * unpairs.  The bearer token is never rendered, and iroh calls stay off the
 * main thread.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private companion object {
        private const val PERMISSION_REQUEST = 0x5041
        private const val REFRESH_INTERVAL_MS = 1_000L
        private const val CONTENT_MAX_WIDTH_DP = 560
        private const val SCREEN_MARGIN_DP = 16
    }

    private enum class Hero { CONNECTED, CALL, CONNECTING, NOT_PAIRED, ATTENTION }

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "phonxiety-ui").apply { isDaemon = true }
    }
    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private lateinit var scroll: View
    private lateinit var column: View
    private lateinit var hero: View
    private lateinit var heroDot: View
    private lateinit var heroTitle: TextView
    private lateinit var heroSupport: TextView
    private lateinit var heroMeta: TextView
    private lateinit var computerSection: View
    private lateinit var pairedWithValue: TextView
    private lateinit var connectionValue: TextView
    private lateinit var callPermissionsValue: TextView
    private lateinit var systemAccessValue: TextView
    private lateinit var simValue: TextView
    private lateinit var grantButton: Button
    private lateinit var primaryAction: Button
    private lateinit var pairAnotherAction: Button
    private lateinit var unpairAction: Button
    private lateinit var footer: TextView

    private var dotAnimator: android.animation.ValueAnimator? = null
    private var appliedHero: Hero? = null
    private var shortIdTicket: String? = null
    private var shortId: String? = null
    private var shortIdPending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(DeviceR.layout.activity_main)

        val root = findViewById<View>(DeviceR.id.root)
        scroll = findViewById(DeviceR.id.scroll)
        column = findViewById(DeviceR.id.column)
        hero = findViewById(DeviceR.id.hero)
        heroDot = findViewById(DeviceR.id.hero_dot)
        heroTitle = findViewById(DeviceR.id.hero_title)
        heroSupport = findViewById(DeviceR.id.hero_support)
        heroMeta = findViewById(DeviceR.id.hero_meta)
        computerSection = findViewById(DeviceR.id.computer_section)
        pairedWithValue = findViewById(DeviceR.id.paired_with_value)
        connectionValue = findViewById(DeviceR.id.connection_value)
        callPermissionsValue = findViewById(DeviceR.id.call_permissions_value)
        systemAccessValue = findViewById(DeviceR.id.system_access_value)
        simValue = findViewById(DeviceR.id.sim_value)
        grantButton = findViewById(DeviceR.id.grant_button)
        primaryAction = findViewById(DeviceR.id.primary_action)
        pairAnotherAction = findViewById(DeviceR.id.pair_another_action)
        unpairAction = findViewById(DeviceR.id.unpair_action)
        footer = findViewById(DeviceR.id.footer)

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyColumnWidth() }

        primaryAction.setOnClickListener { startScanner() }
        pairAnotherAction.setOnClickListener { startScanner() }
        unpairAction.setOnClickListener { confirmUnpair() }
        grantButton.setOnClickListener { requestMissingPermissions() }

        footer.text = getString(DeviceR.string.footer_version, appVersion())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applyColumnWidth()
        syncService()
        refreshStatus()
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        setPulsing(false)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        setPulsing(false)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) refreshStatus()
    }

    /** Keeps the link service running whenever a valid pairing is stored. */
    private fun syncService() {
        val config = BridgeConfigLoader.loadOrNull(this)
        if (config == null) {
            if (BridgeRuntime.engine == null) BridgeRuntime.forgetPairing()
            return
        }
        BridgeRuntime.rememberPairing(config.kind)
        if (BridgeRuntime.engine == null) BridgeService.start(this)
    }

    // ------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------

    private fun refreshStatus() {
        val config = BridgeConfigLoader.loadOrNull(this)
        if (config == null) {
            if (BridgeRuntime.engine == null) BridgeRuntime.forgetPairing()
        } else {
            BridgeRuntime.rememberPairing(config.kind)
        }
        val paired = config != null
        val callState = BridgeRuntime.currentCallState()
        val linkState = BridgeRuntime.linkState
        val route = BridgeRuntime.currentRoute()
        val runtimeMissing = Permissions.runtimeMissing(this)
        val privilegedMissing = Permissions.privilegedMissing(this)

        if (config is BridgeConfig.Iroh) resolveShortId(config.ticket)

        val meta = linkMeta(config, route)
        val metaIsId = config is BridgeConfig.Iroh && route == null
        when {
            !paired -> applyHero(
                Hero.NOT_PAIRED,
                getString(DeviceR.string.hero_not_paired_title),
                getString(DeviceR.string.hero_not_paired_support),
                null
            )

            privilegedMissing.isNotEmpty() || runtimeMissing.isNotEmpty() -> applyHero(
                Hero.ATTENTION,
                getString(DeviceR.string.hero_attention_title),
                attentionSupport(runtimeMissing, privilegedMissing),
                null
            )

            callState != null -> applyHero(
                Hero.CALL,
                getString(DeviceR.string.hero_call_title),
                getString(callSupport(callState)),
                meta,
                metaIsId
            )

            linkState == BridgeRuntime.LinkState.CONNECTED -> applyHero(
                Hero.CONNECTED,
                getString(DeviceR.string.hero_connected_title),
                getString(DeviceR.string.hero_connected_support),
                meta,
                metaIsId
            )

            else -> applyHero(
                Hero.CONNECTING,
                getString(DeviceR.string.hero_connecting_title),
                getString(DeviceR.string.hero_connecting_support),
                meta,
                metaIsId
            )
        }

        computerSection.visibility = if (paired) View.VISIBLE else View.GONE
        if (config != null) {
            val pairedWith = when (config) {
                is BridgeConfig.Address -> config.host
                is BridgeConfig.Iroh -> shortId ?: getString(DeviceR.string.value_none)
            }
            setTextIfChanged(pairedWithValue, pairedWith)
            pairedWithValue.typeface = if (config is BridgeConfig.Iroh) {
                Typeface.MONOSPACE
            } else {
                Typeface.DEFAULT
            }
            setTextIfChanged(connectionValue, connectionLabel(config, route))
        }

        if (runtimeMissing.isEmpty()) {
            grantButton.visibility = View.GONE
            callPermissionsValue.visibility = View.VISIBLE
            setTextIfChanged(callPermissionsValue, getString(DeviceR.string.value_granted))
        } else {
            callPermissionsValue.visibility = View.GONE
            grantButton.visibility = View.VISIBLE
        }

        val active = privilegedMissing.isEmpty()
        setTextIfChanged(
            systemAccessValue,
            getString(if (active) DeviceR.string.value_active else DeviceR.string.value_module_inactive)
        )
        val systemAccessColor = getColor(
            if (active) DeviceR.color.on_surface_variant else DeviceR.color.error
        )
        if (systemAccessValue.currentTextColor != systemAccessColor) {
            systemAccessValue.setTextColor(systemAccessColor)
        }

        setTextIfChanged(simValue, simOperatorName() ?: getString(DeviceR.string.value_none))

        primaryAction.visibility = if (paired) View.GONE else View.VISIBLE
        pairAnotherAction.visibility = if (paired) View.VISIBLE else View.GONE
        unpairAction.visibility = if (paired) View.VISIBLE else View.GONE
        val unpairEnabled = paired && callState == null
        if (unpairAction.isEnabled != unpairEnabled) {
            unpairAction.isEnabled = unpairEnabled
            unpairAction.alpha = if (unpairEnabled) 1f else 0.38f
        }
    }

    private fun attentionSupport(
        runtimeMissing: List<String>,
        privilegedMissing: List<String>
    ): String = if (privilegedMissing.isNotEmpty()) {
        getString(DeviceR.string.hero_attention_module)
    } else {
        getString(
            DeviceR.string.hero_attention_permission,
            getString(Permissions.labelRes(runtimeMissing.first()))
        )
    }

    private fun callSupport(state: String): Int = when (state) {
        "dialing" -> DeviceR.string.hero_call_dialing
        "ending" -> DeviceR.string.hero_call_ending
        else -> DeviceR.string.hero_call_active
    }

    /** Route or identity shown under the hero title. */
    private fun linkMeta(config: BridgeConfig?, route: BridgeRuntime.Route?): String? {
        val paired = config ?: return null
        if (route != null) return routeLabel(route)
        return when (paired) {
            is BridgeConfig.Address -> paired.host
            is BridgeConfig.Iroh -> shortId ?: getString(DeviceR.string.value_none)
        }
    }

    private fun routeLabel(route: BridgeRuntime.Route): String? {
        val rtt = route.rttMs ?: return null
        return getString(
            if (route.relay) DeviceR.string.route_relay else DeviceR.string.route_direct,
            rtt
        )
    }

    private fun connectionLabel(config: BridgeConfig, route: BridgeRuntime.Route?): String = when (config) {
        is BridgeConfig.Address -> getString(
            if (config.certSha256 != null) {
                DeviceR.string.connection_address_pinned
            } else {
                DeviceR.string.connection_address_system
            }
        )

        is BridgeConfig.Iroh -> getString(
            when {
                route == null -> DeviceR.string.value_none
                route.relay -> DeviceR.string.connection_iroh_relay
                else -> DeviceR.string.connection_iroh_direct
            }
        )
    }

    private fun applyHero(
        kind: Hero,
        title: String,
        support: String,
        meta: String?,
        metaIsId: Boolean = false
    ) {
        setTextIfChanged(heroTitle, title)
        setTextIfChanged(heroSupport, support)
        heroMeta.visibility = if (meta == null) View.GONE else View.VISIBLE
        if (meta != null) setTextIfChanged(heroMeta, meta)
        val metaTypeface = if (metaIsId) Typeface.MONOSPACE else Typeface.DEFAULT
        if (heroMeta.typeface !== metaTypeface) heroMeta.typeface = metaTypeface

        if (appliedHero != kind) {
            val background: Int
            val titleColor: Int
            val supportColor: Int
            val metaColor: Int
            when (kind) {
                Hero.CONNECTED -> {
                    background = DeviceR.drawable.bg_hero_primary
                    titleColor = DeviceR.color.on_primary_container
                    supportColor = DeviceR.color.on_primary_container
                    metaColor = DeviceR.color.on_primary_container
                    heroMeta.alpha = 0.75f
                    setDot(DeviceR.color.primary, hollow = false)
                }

                Hero.CALL -> {
                    background = DeviceR.drawable.bg_hero_tertiary
                    titleColor = DeviceR.color.on_tertiary_container
                    supportColor = DeviceR.color.on_tertiary_container
                    metaColor = DeviceR.color.on_tertiary_container
                    heroMeta.alpha = 0.75f
                    setDot(DeviceR.color.on_tertiary_container, hollow = false)
                }

                Hero.CONNECTING -> {
                    background = DeviceR.drawable.bg_hero_neutral
                    titleColor = DeviceR.color.on_surface
                    supportColor = DeviceR.color.on_surface
                    metaColor = DeviceR.color.on_surface_variant
                    heroMeta.alpha = 1f
                    setDot(DeviceR.color.outline, hollow = false)
                }

                Hero.NOT_PAIRED -> {
                    background = DeviceR.drawable.bg_hero_neutral
                    titleColor = DeviceR.color.on_surface
                    supportColor = DeviceR.color.on_surface
                    metaColor = DeviceR.color.on_surface_variant
                    heroMeta.alpha = 1f
                    setDot(DeviceR.color.on_surface_variant, hollow = true)
                }

                Hero.ATTENTION -> {
                    background = DeviceR.drawable.bg_hero_error
                    titleColor = DeviceR.color.on_error_container
                    supportColor = DeviceR.color.on_error_container
                    metaColor = DeviceR.color.on_error_container
                    heroMeta.alpha = 0.75f
                    setDot(DeviceR.color.error, hollow = false)
                }
            }
            hero.setBackgroundResource(background)
            heroTitle.setTextColor(getColor(titleColor))
            heroSupport.setTextColor(getColor(supportColor))
            heroMeta.setTextColor(getColor(metaColor))
            appliedHero = kind
        }
        setPulsing(kind == Hero.CONNECTING)
    }

    private fun setDot(colorRes: Int, hollow: Boolean) {
        heroDot.setBackgroundResource(if (hollow) DeviceR.drawable.bg_dot_hollow else DeviceR.drawable.bg_dot)
        heroDot.backgroundTintList = if (hollow) null else ColorStateList.valueOf(getColor(colorRes))
    }

    private fun setPulsing(activate: Boolean) {
        if (activate) {
            if (dotAnimator != null) return
            if (!animationsEnabled()) return
            val animator = android.animation.ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 900L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                addUpdateListener { heroDot.alpha = it.animatedValue as Float }
                start()
            }
            dotAnimator = animator
        } else {
            dotAnimator?.cancel()
            dotAnimator = null
            heroDot.alpha = 1f
        }
    }

    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    } catch (_: Exception) {
        true
    }

    private fun setTextIfChanged(view: TextView, value: CharSequence) {
        if (view.text.toString() != value.toString()) view.text = value
    }

    /** Caps the content column at 560dp and centres it on wider screens. */
    private fun applyColumnWidth() {
        val width = scroll.width
        if (width <= 0) return
        val density = resources.displayMetrics.density
        val maxContent = (CONTENT_MAX_WIDTH_DP * density).toInt()
        val minimumMargin = (SCREEN_MARGIN_DP * density).toInt()
        val margin = ((width - maxContent) / 2).coerceAtLeast(minimumMargin)
        if (column.paddingLeft != margin || column.paddingRight != margin) {
            column.setPadding(margin, column.paddingTop, margin, column.paddingBottom)
        }
    }

    private fun resolveShortId(ticket: String) {
        if (shortIdTicket == ticket) return
        if (shortIdPending == ticket) return
        shortIdPending = ticket
        worker.execute {
            val resolved = IrohSupport.shortEndpointId(ticket)
            handler.post {
                shortIdPending = null
                shortIdTicket = ticket
                shortId = resolved
                if (!isFinishing && !isDestroyed) refreshStatus()
            }
        }
    }

    private fun simOperatorName(): String? = try {
        val base = getSystemService(TelephonyManager::class.java)
        if (base == null) {
            null
        } else {
            val voiceSubscription = SubscriptionManager.getDefaultVoiceSubscriptionId()
            val manager = if (voiceSubscription >= 0) {
                base.createForSubscriptionId(voiceSubscription)
            } else {
                base
            }
            manager.simOperatorName?.trim()?.takeIf { it.isNotEmpty() }
                ?: manager.networkOperatorName?.trim()?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) {
        null
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        ""
    }

    // ------------------------------------------------------------------
    // Pairing
    // ------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val link = intent.data?.toString() ?: return
        // Consume the link so a configuration change cannot prompt twice.
        intent.data = null
        parsePairing(link)
    }

    /** Parsing an iroh ticket touches the bindings, so it runs off the main thread. */
    private fun parsePairing(link: String) {
        worker.execute {
            val result = PairingLink.parse(link)
            val label = (result as? PairingResult.Valid)?.config?.let { config ->
                when (config) {
                    is BridgeConfig.Address -> config.host
                    is BridgeConfig.Iroh -> IrohSupport.shortEndpointId(config.ticket)
                }
            }
            handler.post {
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is PairingResult.Valid -> {
                        if (label == null && result.config is BridgeConfig.Iroh) {
                            toast(DeviceR.string.pair_error_iroh_invalid)
                        } else {
                            offerPairing(result.config, label ?: "")
                        }
                    }

                    is PairingResult.Invalid -> toast(errorLabelRes(result.error))
                }
            }
        }
    }

    private fun errorLabelRes(error: BridgeConfigError): Int = when (error) {
        BridgeConfigError.PAIR_LINK_INVALID -> DeviceR.string.pair_error_link

        BridgeConfigError.PAIR_URL_MISSING -> DeviceR.string.pair_error_url_missing

        BridgeConfigError.PAIR_IROH_MISSING -> DeviceR.string.pair_error_iroh_missing

        BridgeConfigError.PAIR_TOKEN_MISSING -> DeviceR.string.pair_error_token_missing

        BridgeConfigError.URL_INVALID -> DeviceR.string.pair_error_url_invalid

        BridgeConfigError.TOKEN_INVALID -> DeviceR.string.pair_error_token_invalid

        BridgeConfigError.CERT_INVALID -> DeviceR.string.pair_error_cert_invalid

        BridgeConfigError.PAIR_IROH_INVALID,
        BridgeConfigError.IROH_INVALID -> DeviceR.string.pair_error_iroh_invalid

        else -> DeviceR.string.pair_error_generic
    }

    private fun offerPairing(candidate: BridgeConfig, label: String) {
        if (BridgeRuntime.currentCallState() != null) {
            toast(DeviceR.string.pair_blocked_call)
            return
        }
        val message = when (candidate) {
            is BridgeConfig.Iroh -> getString(DeviceR.string.pair_dialog_iroh_message, label)

            is BridgeConfig.Address -> {
                val pinLine = candidate.certSha256
                    ?.let { getString(DeviceR.string.pair_dialog_pin, it.take(16)) }
                    ?: getString(DeviceR.string.pair_dialog_no_pin)
                getString(DeviceR.string.pair_dialog_message, candidate.host, pinLine)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(DeviceR.string.pair_dialog_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(DeviceR.string.pair_dialog_confirm) { _, _ -> applyPairing(candidate) }
            .show()
    }

    private fun applyPairing(candidate: BridgeConfig) {
        if (BridgeRuntime.currentCallState() != null) {
            toast(DeviceR.string.pair_blocked_call)
            return
        }
        val stored = try {
            BridgeConfigLoader.write(this, candidate)
        } catch (_: BridgeConfigException) {
            toast(DeviceR.string.pair_save_failed)
            return
        }
        BridgeRuntime.rememberPairing(stored.kind)
        shortIdTicket = null
        shortId = null
        BridgeService.stop(this)
        BridgeService.start(this)
        toast(DeviceR.string.pair_saved)
        refreshStatus()
    }

    private fun startScanner() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this, options)
                .startScan()
                .addOnSuccessListener { barcode ->
                    if (isFinishing || isDestroyed) return@addOnSuccessListener
                    val raw = barcode.rawValue
                    if (raw.isNullOrEmpty()) toast(DeviceR.string.scan_empty) else parsePairing(raw)
                }
                .addOnFailureListener {
                    if (isFinishing || isDestroyed) return@addOnFailureListener
                    toast(DeviceR.string.scan_failed)
                }
        } catch (_: Exception) {
            // No usable Google Play services on this device.
            toast(DeviceR.string.scan_failed)
        }
    }

    private fun requestMissingPermissions() {
        val missing = Permissions.runtimeMissing(this)
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun confirmUnpair() {
        if (BridgeRuntime.currentCallState() != null) {
            toast(DeviceR.string.pair_blocked_call)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(DeviceR.string.unpair_dialog_title)
            .setMessage(DeviceR.string.unpair_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(DeviceR.string.unpair_confirm) { _, _ -> unpair() }
            .show()
    }

    private fun unpair() {
        BridgeService.stop(this)
        if (!BridgeConfigLoader.delete(this)) {
            toast(DeviceR.string.unpair_failed)
        }
        BridgeRuntime.forgetPairing()
        shortIdTicket = null
        shortId = null
        refreshStatus()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}

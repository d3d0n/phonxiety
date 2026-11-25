package com.phonxiety.bridge

import android.app.Activity
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One-screen companion UI.  It pairs the phone with a daemon (QR scan or a
 * `phonxiety://pair` link), shows the live link and permission state, and
 * unpairs.  The bearer token is never rendered, and iroh calls stay off the
 * main thread.
 */
class MainActivity : Activity() {
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
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        scroll = findViewById(R.id.scroll)
        column = findViewById(R.id.column)
        hero = findViewById(R.id.hero)
        heroDot = findViewById(R.id.hero_dot)
        heroTitle = findViewById(R.id.hero_title)
        heroSupport = findViewById(R.id.hero_support)
        heroMeta = findViewById(R.id.hero_meta)
        computerSection = findViewById(R.id.computer_section)
        pairedWithValue = findViewById(R.id.paired_with_value)
        connectionValue = findViewById(R.id.connection_value)
        callPermissionsValue = findViewById(R.id.call_permissions_value)
        systemAccessValue = findViewById(R.id.system_access_value)
        simValue = findViewById(R.id.sim_value)
        grantButton = findViewById(R.id.grant_button)
        primaryAction = findViewById(R.id.primary_action)
        pairAnotherAction = findViewById(R.id.pair_another_action)
        unpairAction = findViewById(R.id.unpair_action)
        footer = findViewById(R.id.footer)

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

        footer.text = getString(R.string.footer_version, appVersion())
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
        permissions: Array<out String>,
        grantResults: IntArray,
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
                getString(R.string.hero_not_paired_title),
                getString(R.string.hero_not_paired_support),
                null,
            )
            privilegedMissing.isNotEmpty() || runtimeMissing.isNotEmpty() -> applyHero(
                Hero.ATTENTION,
                getString(R.string.hero_attention_title),
                attentionSupport(runtimeMissing, privilegedMissing),
                null,
            )
            callState != null -> applyHero(
                Hero.CALL,
                getString(R.string.hero_call_title),
                getString(callSupport(callState)),
                meta,
                metaIsId,
            )
            linkState == BridgeRuntime.LinkState.CONNECTED -> applyHero(
                Hero.CONNECTED,
                getString(R.string.hero_connected_title),
                getString(R.string.hero_connected_support),
                meta,
                metaIsId,
            )
            else -> applyHero(
                Hero.CONNECTING,
                getString(R.string.hero_connecting_title),
                getString(R.string.hero_connecting_support),
                meta,
                metaIsId,
            )
        }

        computerSection.visibility = if (paired) View.VISIBLE else View.GONE
        if (config != null) {
            val pairedWith = when (config) {
                is BridgeConfig.Address -> config.host
                is BridgeConfig.Iroh -> shortId ?: getString(R.string.value_none)
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
            setTextIfChanged(callPermissionsValue, getString(R.string.value_granted))
        } else {
            callPermissionsValue.visibility = View.GONE
            grantButton.visibility = View.VISIBLE
        }

        val active = privilegedMissing.isEmpty()
        setTextIfChanged(
            systemAccessValue,
            getString(if (active) R.string.value_active else R.string.value_module_inactive),
        )
        val systemAccessColor = getColor(
            if (active) R.color.on_surface_variant else R.color.error
        )
        if (systemAccessValue.currentTextColor != systemAccessColor) {
            systemAccessValue.setTextColor(systemAccessColor)
        }

        setTextIfChanged(simValue, simOperatorName() ?: getString(R.string.value_none))

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
        privilegedMissing: List<String>,
    ): String = if (privilegedMissing.isNotEmpty()) {
        getString(R.string.hero_attention_module)
    } else {
        getString(
            R.string.hero_attention_permission,
            getString(Permissions.labelRes(runtimeMissing.first())),
        )
    }

    private fun callSupport(state: String): Int = when (state) {
        "dialing" -> R.string.hero_call_dialing
        "ending" -> R.string.hero_call_ending
        else -> R.string.hero_call_active
    }

    /** Route or identity shown under the hero title. */
    private fun linkMeta(config: BridgeConfig?, route: DeviceLink.Route?): String? {
        val paired = config ?: return null
        if (route != null) return routeLabel(route)
        return when (paired) {
            is BridgeConfig.Address -> paired.host
            is BridgeConfig.Iroh -> shortId ?: getString(R.string.value_none)
        }
    }

    private fun routeLabel(route: DeviceLink.Route): String? {
        val rtt = route.rttMs ?: return null
        return getString(
            if (route.relay) R.string.route_relay else R.string.route_direct,
            rtt,
        )
    }

    private fun connectionLabel(config: BridgeConfig, route: DeviceLink.Route?): String =
        when (config) {
            is BridgeConfig.Address -> getString(
                if (config.certSha256 != null) {
                    R.string.connection_address_pinned
                } else {
                    R.string.connection_address_system
                }
            )
            is BridgeConfig.Iroh -> getString(
                when {
                    route == null -> R.string.value_none
                    route.relay -> R.string.connection_iroh_relay
                    else -> R.string.connection_iroh_direct
                }
            )
        }

    private fun applyHero(
        kind: Hero,
        title: String,
        support: String,
        meta: String?,
        metaIsId: Boolean = false,
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
                    background = R.drawable.bg_hero_primary
                    titleColor = R.color.on_primary_container
                    supportColor = R.color.on_primary_container
                    metaColor = R.color.on_primary_container
                    heroMeta.alpha = 0.75f
                    setDot(R.color.primary, hollow = false)
                }
                Hero.CALL -> {
                    background = R.drawable.bg_hero_tertiary
                    titleColor = R.color.on_tertiary_container
                    supportColor = R.color.on_tertiary_container
                    metaColor = R.color.on_tertiary_container
                    heroMeta.alpha = 0.75f
                    setDot(R.color.on_tertiary_container, hollow = false)
                }
                Hero.CONNECTING -> {
                    background = R.drawable.bg_hero_neutral
                    titleColor = R.color.on_surface
                    supportColor = R.color.on_surface
                    metaColor = R.color.on_surface_variant
                    heroMeta.alpha = 1f
                    setDot(R.color.outline, hollow = false)
                }
                Hero.NOT_PAIRED -> {
                    background = R.drawable.bg_hero_neutral
                    titleColor = R.color.on_surface
                    supportColor = R.color.on_surface
                    metaColor = R.color.on_surface_variant
                    heroMeta.alpha = 1f
                    setDot(R.color.on_surface_variant, hollow = true)
                }
                Hero.ATTENTION -> {
                    background = R.drawable.bg_hero_error
                    titleColor = R.color.on_error_container
                    supportColor = R.color.on_error_container
                    metaColor = R.color.on_error_container
                    heroMeta.alpha = 0.75f
                    setDot(R.color.error, hollow = false)
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
        heroDot.setBackgroundResource(if (hollow) R.drawable.bg_dot_hollow else R.drawable.bg_dot)
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
            1f,
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
                            toast(R.string.pair_error_iroh_invalid)
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
        BridgeConfigError.PAIR_LINK_INVALID -> R.string.pair_error_link
        BridgeConfigError.PAIR_URL_MISSING -> R.string.pair_error_url_missing
        BridgeConfigError.PAIR_IROH_MISSING -> R.string.pair_error_iroh_missing
        BridgeConfigError.PAIR_TOKEN_MISSING -> R.string.pair_error_token_missing
        BridgeConfigError.URL_INVALID -> R.string.pair_error_url_invalid
        BridgeConfigError.TOKEN_INVALID -> R.string.pair_error_token_invalid
        BridgeConfigError.CERT_INVALID -> R.string.pair_error_cert_invalid
        BridgeConfigError.PAIR_IROH_INVALID,
        BridgeConfigError.IROH_INVALID -> R.string.pair_error_iroh_invalid
        else -> R.string.pair_error_generic
    }

    private fun offerPairing(candidate: BridgeConfig, label: String) {
        if (BridgeRuntime.currentCallState() != null) {
            toast(R.string.pair_blocked_call)
            return
        }
        val message = when (candidate) {
            is BridgeConfig.Iroh -> getString(R.string.pair_dialog_iroh_message, label)
            is BridgeConfig.Address -> {
                val pinLine = candidate.certSha256
                    ?.let { getString(R.string.pair_dialog_pin, it.take(16)) }
                    ?: getString(R.string.pair_dialog_no_pin)
                getString(R.string.pair_dialog_message, candidate.host, pinLine)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_dialog_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pair_dialog_confirm) { _, _ -> applyPairing(candidate) }
            .show()
    }

    private fun applyPairing(candidate: BridgeConfig) {
        if (BridgeRuntime.currentCallState() != null) {
            toast(R.string.pair_blocked_call)
            return
        }
        val stored = try {
            BridgeConfigLoader.write(this, candidate)
        } catch (_: BridgeConfigException) {
            toast(R.string.pair_save_failed)
            return
        }
        BridgeRuntime.rememberPairing(stored.kind)
        shortIdTicket = null
        shortId = null
        BridgeService.stop(this)
        BridgeService.start(this)
        toast(R.string.pair_saved)
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
                    if (raw.isNullOrEmpty()) toast(R.string.scan_empty) else parsePairing(raw)
                }
                .addOnFailureListener {
                    if (isFinishing || isDestroyed) return@addOnFailureListener
                    toast(R.string.scan_failed)
                }
        } catch (_: Exception) {
            // No usable Google Play services on this device.
            toast(R.string.scan_failed)
        }
    }

    private fun requestMissingPermissions() {
        val missing = Permissions.runtimeMissing(this)
        if (missing.isEmpty()) return
        requestPermissions(missing.toTypedArray(), PERMISSION_REQUEST)
    }

    private fun confirmUnpair() {
        if (BridgeRuntime.currentCallState() != null) {
            toast(R.string.pair_blocked_call)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_dialog_title)
            .setMessage(R.string.unpair_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.unpair_confirm) { _, _ -> unpair() }
            .show()
    }

    private fun unpair() {
        BridgeService.stop(this)
        if (!BridgeConfigLoader.delete(this)) {
            toast(R.string.unpair_failed)
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

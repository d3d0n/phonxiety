package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.DoctorCheck
import com.phonxiety.bridge.model.DoctorFix
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.LinkKind
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.root.AppFacts
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.PrivilegedPermissions
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus

/**
 * Deterministic states for previews and the screenshot tests. They are built through
 * [DoctorEvaluator] from snapshots, so a sample cannot show a row the evaluator would never emit.
 */
object DoctorSamples {
    private val magisk = DetectedProvider(
        id = RootId.MAGISK,
        version = "31.1:MAGISK",
        managerPackage = "com.topjohnwu.magisk",
        managerInstalled = true
    )

    private val rootGranted = RootDetection(su = SuStatus.GRANTED, providers = listOf(magisk))

    private val healthyFacts = AppFacts(
        packageName = "com.phonxiety.bridge",
        isSystemApp = true,
        apkPath = "/system/priv-app/phonxiety-bridge/phonxiety-bridge.apk",
        grantedPermissions = PrivilegedPermissions.ALL
    )

    private val healthySnapshot = DoctorSnapshot(
        root = rootGranted,
        module = ModuleState.Enabled(version = "0.3.0", versionCode = 3),
        overlay = Overlay.BUILT_IN,
        appFacts = healthyFacts,
        runtimeMissing = emptySet(),
        notificationsEnabled = true,
        batteryExempt = true,
        checkedAtEpochMs = CHECKED_AT
    )

    private val healthyBridge = BridgeState(
        link = LinkState.Up(
            kind = LinkKind.IROH,
            path = PathKind.DIRECT,
            address = "iroh 9f42c1",
            rttMs = 38,
            sinceEpochMs = CHECKED_AT - 42 * 60_000,
            reconnects = 1
        )
    )

    private val healthyDaemon = DaemonProbe.Reached(
        DoctorReport(
            status = "ok",
            checks = listOf(
                DoctorCheck("daemon", "ok", "phonxietyd 0.9.2 answering (pid 4211)"),
                DoctorCheck("phone", "ok", "Pixel 6a connected"),
                DoctorCheck("iroh_relay", "ok", "iroh endpoint bound"),
                DoctorCheck("voice_backend", "ok", "gemini is configured"),
                DoctorCheck("disk", "ok", "18204 MiB free"),
                DoctorCheck("relays", "ok", "using iroh's public relays")
            ),
            fixes = listOf(
                DoctorFix("relay_config", "re-read the relay list from disk", restartRequired = true),
                DoctorFix("state_dirs", "recreate the archive and diagnostics directories", restartRequired = false)
            ),
            checkedAt = "2026-11-26T09:12:00+00:00"
        )
    )

    private val brokenSnapshot = DoctorSnapshot(
        root = rootGranted,
        module = ModuleState.Enabled(version = "0.3.0", versionCode = 3),
        overlay = Overlay.BUILT_IN,
        appFacts = AppFacts(
            packageName = "com.phonxiety.bridge",
            isSystemApp = false,
            apkPath = "/data/app/~~8Kd0/base.apk",
            grantedPermissions = PrivilegedPermissions.ALL - PrivilegedPermissions.CAPTURE_AUDIO_OUTPUT
        ),
        runtimeMissing = setOf(RuntimePermission.ACCESS_LOCAL_NETWORK, RuntimePermission.RECORD_AUDIO),
        notificationsEnabled = false,
        batteryExempt = false,
        checkedAtEpochMs = CHECKED_AT
    )

    private val brokenBridge = BridgeState(
        link = LinkState.Up(
            kind = LinkKind.IROH,
            path = PathKind.RELAY,
            address = "relay euw1-1",
            rttMs = 182,
            sinceEpochMs = CHECKED_AT - 8 * 60_000,
            reconnects = 4
        )
    )

    private val brokenDaemon = DaemonProbe.Reached(
        DoctorReport(
            status = "warn",
            checks = listOf(
                DoctorCheck("daemon", "ok", "phonxietyd 0.9.2 answering (pid 4211)"),
                DoctorCheck("phone", "fail", "no phone is connected"),
                DoctorCheck("iroh_relay", "warn", "iroh endpoint unavailable; pair by address instead"),
                DoctorCheck("voice_backend", "ok", "gemini is configured"),
                DoctorCheck("disk", "warn", "140 MiB free in /Users/user/.local/state/phonxiety"),
                DoctorCheck("relays", "ok", "2 custom relay(s): https://relay.example.net:443")
            ),
            fixes = listOf(
                DoctorFix("relay_config", "re-read the relay list from disk", restartRequired = true),
                DoctorFix("state_dirs", "recreate the archive and diagnostics directories", restartRequired = false)
            ),
            checkedAt = "2026-11-26T09:14:00+00:00"
        )
    )

    fun allGreen(): DoctorUiState = DoctorUiState(
        checking = false,
        rows = DoctorEvaluator.evaluate(
            snapshot = healthySnapshot,
            bridge = healthyBridge,
            paired = true,
            daemon = healthyDaemon,
            callActive = false
        ),
        daemonFixes = daemonFixes(healthyDaemon),
        changeLog = emptyList(),
        provider = RootId.MAGISK,
        checkedAtEpochMs = CHECKED_AT
    )

    fun severalFailures(): DoctorUiState = DoctorUiState(
        checking = false,
        rows = DoctorEvaluator.evaluate(
            snapshot = brokenSnapshot,
            bridge = brokenBridge,
            paired = true,
            daemon = brokenDaemon,
            callActive = false
        ),
        daemonFixes = daemonFixes(brokenDaemon),
        changeLog = listOf(
            DoctorChangeEntry(CHECKED_AT - 120_000, "Restarted the bridge service to reopen the link"),
            DoctorChangeEntry(CHECKED_AT - 60_000, "Asked for android.permission.ACCESS_LOCAL_NETWORK again")
        ),
        selected = DoctorEvaluator.ID_OVERLAY,
        provider = RootId.MAGISK,
        checkedAtEpochMs = CHECKED_AT
    )

    fun recovery(): DoctorUiState = severalFailures().copy(
        page = RecoveryBook.page(RecoveryId.BOOTLOOP, RootId.MAGISK)
    )

    fun pendingFix(): DoctorUiState {
        val state = severalFailures()
        val row = state.row(DoctorEvaluator.ID_OVERLAY) ?: return state
        val plan = row.plan ?: return state
        return state.copy(
            pending = FixRequest(rowId = row.id, title = row.title, plan = plan, subject = row.subject)
        )
    }

    fun duringCall(): DoctorUiState = severalFailures().copy(callActive = true)

    private fun daemonFixes(probe: DaemonProbe): List<DaemonFixOffer> = (probe as? DaemonProbe.Reached)?.report?.fixes.orEmpty().map { fix ->
        DaemonFixOffer(
            fix = fix.fix,
            description = fix.description,
            plan = DoctorPolicy.daemonPlan(fix.fix, fix.description, fix.restartRequired)
        )
    }

    private const val CHECKED_AT = 1_796_500_000_000L
}

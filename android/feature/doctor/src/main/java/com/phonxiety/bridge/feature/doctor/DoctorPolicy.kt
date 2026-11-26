package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.designsystem.UiText

/**
 * The decision tree: every failing check maps to one fix and one risk class, and the class decides
 * whether Doctor may act on its own. Nothing here touches a device, so every cell of the table is
 * unit-tested.
 *
 * [FixContext] changes only the wording: Magisk and APatch mount `system/` themselves, while a
 * KernelSU install without a metamodule needs one before any module is mounted.
 */
object DoctorPolicy {
    /**
     * The fix for a failing [kind], or null when the check only explains (root itself, daemon
     * self-check details) or needs a provider-specific procedure from the guided recovery pages.
     */
    fun planFor(kind: DoctorItemKind, context: FixContext = FixContext()): FixPlan? = when (kind) {
        // Root is a capability to restore by hand: the recovery page walks through re-rooting.
        DoctorItemKind.ROOT_PROVIDER -> null

        DoctorItemKind.MODULE_PRESENT -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(
                if (context.needsMetamodule) {
                    R.string.doctor_effect_module_install_metamodule
                } else {
                    R.string.doctor_effect_module_install
                }
            ),
            rollback = UiText.Res(R.string.doctor_rollback_module),
            needsReboot = true
        )

        DoctorItemKind.MODULE_ENABLED -> FixPlan(
            fix = DoctorFixId.ENABLE_MODULE,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_module_enable),
            rollback = UiText.Res(R.string.doctor_rollback_module_disable),
            needsReboot = true
        )

        DoctorItemKind.OVERLAY -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(
                if (context.needsMetamodule) {
                    R.string.doctor_effect_module_install_metamodule
                } else {
                    R.string.doctor_effect_module_install
                }
            ),
            rollback = UiText.Res(R.string.doctor_rollback_module),
            needsReboot = true
        )

        DoctorItemKind.PRIVILEGED_PERMISSION -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_allowlist),
            rollback = UiText.Res(R.string.doctor_rollback_module),
            needsReboot = true
        )

        DoctorItemKind.RUNTIME_PERMISSION -> FixPlan(
            fix = DoctorFixId.REGRANT_RUNTIME_PERMISSION,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_runtime_permission),
            rollback = UiText.Res(R.string.doctor_rollback_runtime_permission)
        )

        DoctorItemKind.NOTIFICATIONS -> FixPlan(
            fix = DoctorFixId.REGRANT_NOTIFICATION,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_notifications),
            rollback = UiText.Res(R.string.doctor_rollback_notifications)
        )

        DoctorItemKind.BATTERY_EXEMPTION -> FixPlan(
            fix = DoctorFixId.REQUEST_BATTERY_EXEMPTION,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_background),
            rollback = UiText.Res(R.string.doctor_rollback_background)
        )

        // A recorded crash or exit is evidence, not a symptom with a switch: the row explains the
        // reason and the trace goes with the diagnostics bundle.
        DoctorItemKind.LAST_CRASH -> null

        DoctorItemKind.PAIRING -> FixPlan(
            fix = DoctorFixId.REPAIR_PAIRING,
            risk = FixClass.CONFIRM,
            effect = UiText.Res(R.string.doctor_effect_pairing),
            rollback = UiText.Res(R.string.doctor_rollback_pairing)
        )

        // Restarting the bridge reopens the link and re-arms the heartbeat without a reboot.
        DoctorItemKind.LINK -> FixPlan(
            fix = DoctorFixId.RESTART_SERVICE,
            risk = FixClass.AUTOMATIC,
            effect = UiText.Res(R.string.doctor_effect_bridge_restart),
            rollback = UiText.Res(R.string.doctor_rollback_bridge)
        )

        DoctorItemKind.DAEMON -> FixPlan(
            fix = DoctorFixId.RESTART_SERVICE,
            risk = FixClass.AUTOMATIC,
            effect = UiText.Res(R.string.doctor_effect_bridge_reconnect),
            rollback = UiText.Res(R.string.doctor_rollback_bridge)
        )

        DoctorItemKind.RELAY -> daemonPlan(
            fix = RELAY_FIX,
            description = null,
            restartRequired = true
        )

        DoctorItemKind.DAEMON_CHECK -> null
    }

    /** A plan for one fix the daemon offers, classified by what it restarts. */
    fun daemonPlan(fix: String, description: String?, restartRequired: Boolean): FixPlan = FixPlan(
        fix = DoctorFixId.DAEMON_FIX,
        risk = if (restartRequired) FixClass.CONFIRM else FixClass.AUTOMATIC,
        effect = when {
            !description.isNullOrBlank() && restartRequired ->
                UiText.Res(R.string.doctor_effect_daemon_fix_described_restart, listOf(fix, description))

            !description.isNullOrBlank() -> UiText.Res(R.string.doctor_effect_daemon_fix_described, listOf(fix, description))

            restartRequired -> UiText.Res(R.string.doctor_effect_daemon_fix_restart, listOf(fix))

            else -> UiText.Res(R.string.doctor_effect_daemon_fix, listOf(fix))
        },
        rollback = UiText.Res(
            if (restartRequired) R.string.doctor_rollback_daemon_restart else R.string.doctor_rollback_daemon
        ),
        restartsDaemon = restartRequired,
        daemonFix = fix
    )

    /**
     * The class that applies right now: a fix that cuts a live call is never automatic while a call
     * is up, whatever the check says, and it is not offered as a one-tap confirm either.
     */
    fun effectiveRisk(plan: FixPlan, callActive: Boolean): FixClass = if (callActive && plan.cutsCall) FixClass.NEVER else plan.risk

    /**
     * Rows Doctor runs on its own when the screen opens, in the order the fixes must run. One row
     * per fix: a failed link and an unreachable daemon are one service restart, not two.
     */
    fun automaticFixes(rows: List<DoctorRow>, callActive: Boolean): List<DoctorRow> = rows
        .filter { it.needsFix && it.plan != null }
        .filter { effectiveRisk(requireNotNull(it.plan), callActive) == FixClass.AUTOMATIC }
        .sortedBy { orderOf(requireNotNull(it.plan).fix) }
        .distinctBy { requireNotNull(it.plan).fix }

    /** The guided recovery page that explains a failing check, when one exists. */
    fun recoveryFor(kind: DoctorItemKind): RecoveryId? = when (kind) {
        DoctorItemKind.ROOT_PROVIDER -> RecoveryId.ROOT_LOST_OTA

        DoctorItemKind.MODULE_ENABLED -> RecoveryId.MODULE_DISABLED

        DoctorItemKind.OVERLAY, DoctorItemKind.PRIVILEGED_PERMISSION -> RecoveryId.PRIVILEGED_GRANT_LOST

        DoctorItemKind.PAIRING -> RecoveryId.PAIRING_REJECTED

        DoctorItemKind.LINK, DoctorItemKind.DAEMON, DoctorItemKind.RELAY -> RecoveryId.DAEMON_UNREACHABLE

        DoctorItemKind.MODULE_PRESENT,
        DoctorItemKind.RUNTIME_PERMISSION,
        DoctorItemKind.NOTIFICATIONS,
        DoctorItemKind.BATTERY_EXEMPTION,
        DoctorItemKind.LAST_CRASH,
        DoctorItemKind.DAEMON_CHECK -> null
    }

    /** The daemon's own fix for the relay list; the daemon re-reads it from disk. */
    const val RELAY_FIX: String = "relay_config"

    private fun orderOf(fix: DoctorFixId): Int = when (fix) {
        DoctorFixId.DAEMON_FIX -> 0
        DoctorFixId.RESTART_SERVICE -> 1
        DoctorFixId.REGRANT_RUNTIME_PERMISSION -> 2
        DoctorFixId.REGRANT_NOTIFICATION -> 3
        DoctorFixId.REQUEST_BATTERY_EXEMPTION -> 4
        DoctorFixId.REPAIR_PAIRING -> 5
        DoctorFixId.ENABLE_MODULE -> 6
        DoctorFixId.REINSTALL_MODULE -> 7
    }
}

package com.phonxiety.bridge.feature.doctor

/**
 * The decision tree: every failing check maps to one fix and one risk class, and the class decides
 * whether Doctor may act on its own. Nothing here touches a device, so every cell of the table is
 * unit-tested.
 */
object DoctorPolicy {
    /**
     * The fix for a failing [kind], or null when the check only explains (root itself, daemon
     * self-check details) or needs a provider-specific procedure from the guided recovery pages.
     */
    fun planFor(kind: DoctorItemKind): FixPlan? = when (kind) {
        // Root is a capability to restore by hand: the recovery page walks through re-rooting.
        DoctorItemKind.ROOT_PROVIDER -> null

        DoctorItemKind.MODULE_PRESENT -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = "Rebuilds the module from this APK and reinstalls it through the root manager. " +
                "The phone reboots so the overlay is mounted and the privileged allowlist is re-read.",
            rollback = "The previous zip stays in Updates; reinstall it to go back.",
            needsReboot = true
        )

        DoctorItemKind.MODULE_ENABLED -> FixPlan(
            fix = DoctorFixId.ENABLE_MODULE,
            risk = FixClass.CONFIRM,
            effect = "Clears the module's disable flag through the root manager. The phone reboots so " +
                "the overlay is mounted from the next boot on.",
            rollback = "Disable the module again from the root manager; nothing on the phone is deleted.",
            needsReboot = true
        )

        DoctorItemKind.OVERLAY -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = "Reinstalls the module with its APK and privapp allowlist together. A KernelSU install " +
                "without a metamodule needs one, or the overlay is never mounted.",
            rollback = "The previous zip stays in Updates; reinstall it to go back.",
            needsReboot = true
        )

        DoctorItemKind.PRIVILEGED_PERMISSION -> FixPlan(
            fix = DoctorFixId.REINSTALL_MODULE,
            risk = FixClass.CONFIRM,
            effect = "Reinstalls the module so the privapp allowlist matches this build, then reboots. " +
                "An APK without its allowlist is the worst failure in this project.",
            rollback = "The previous zip stays in Updates; reinstall it to go back.",
            needsReboot = true
        )

        DoctorItemKind.RUNTIME_PERMISSION -> FixPlan(
            fix = DoctorFixId.REGRANT_RUNTIME_PERMISSION,
            risk = FixClass.CONFIRM,
            effect = "Shows the system permission dialog for this permission. The bridge checks again " +
                "as soon as the dialog closes.",
            rollback = "Revoke it in Settings → Apps → Phonxiety → Permissions."
        )

        DoctorItemKind.NOTIFICATIONS -> FixPlan(
            fix = DoctorFixId.REGRANT_NOTIFICATION,
            risk = FixClass.CONFIRM,
            effect = "Asks for the notification permission, or opens Phonxiety's notification settings when " +
                "the permission is granted but notifications are switched off.",
            rollback = "Turn notifications off again in Settings → Apps → Phonxiety → Notifications."
        )

        DoctorItemKind.BATTERY_EXEMPTION -> FixPlan(
            fix = DoctorFixId.REQUEST_BATTERY_EXEMPTION,
            risk = FixClass.CONFIRM,
            effect = "Opens the system dialog that exempts Phonxiety from battery optimisation, so the " +
                "heartbeat survives a locked, idle phone.",
            rollback = "Remove it in Settings → Apps → Phonxiety → Battery → Unrestricted → Optimised."
        )

        // A recorded crash or exit is evidence, not a symptom with a switch: the row explains the
        // reason and the trace goes with the diagnostics bundle.
        DoctorItemKind.LAST_CRASH -> null

        DoctorItemKind.PAIRING -> FixPlan(
            fix = DoctorFixId.REPAIR_PAIRING,
            risk = FixClass.CONFIRM,
            effect = "Opens the pairing recovery page. The computer prints a fresh QR with `phonxiety pair` and " +
                "the phone pairs again; the stored token is replaced.",
            rollback = "The old pairing is gone once the new one is stored; pair again to undo."
        )

        // Restarting the service reopens the link and re-arms the heartbeat without a reboot.
        DoctorItemKind.LINK -> FixPlan(
            fix = DoctorFixId.RESTART_SERVICE,
            risk = FixClass.AUTOMATIC,
            effect = "Stops and starts the bridge foreground service, which reopens the device link and " +
                "re-arms the heartbeat. The pairing is untouched.",
            rollback = "Nothing to undo: the service comes back with the stored pairing."
        )

        DoctorItemKind.DAEMON -> FixPlan(
            fix = DoctorFixId.RESTART_SERVICE,
            risk = FixClass.AUTOMATIC,
            effect = "Stops and starts the bridge foreground service so the app API reconnects from a clean " +
                "socket. The daemon itself is not touched.",
            rollback = "Nothing to undo: the service comes back with the stored pairing."
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
        effect = buildString {
            append("The daemon applies `$fix`")
            if (!description.isNullOrBlank()) append(": $description")
            append('.')
            if (restartRequired) append(" The daemon restarts, which drops a call that is running.")
        },
        rollback = if (restartRequired) {
            "Restart the daemon on the computer with `phonxiety stop` and `phonxiety start`."
        } else {
            "Nothing on the phone changes; the daemon recreates what it needs."
        },
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

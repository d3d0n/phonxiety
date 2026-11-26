package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.CrashReports
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.LinkState
import com.phonxiety.bridge.model.PathKind
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.PrivilegedPermissions
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RootShell
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.SuStatus

/** Managers that hide `su` from a package they have not allowed yet. */
private fun RootDetection.hidingManagers(): List<DetectedProvider> = providers.filter { it.id != RootId.MAGISK }

/** Leftovers of a provider that did not answer: reported so a migration makes sense. */
private fun leftoversSuffix(root: RootDetection): String = root.leftovers.takeIf { it.isNotEmpty() }
    ?.joinToString(prefix = " · inactive leftovers: ", separator = ", ") { "${it.id.doctorTitle()} ${it.version}" }
    .orEmpty()

/**
 * Turns one snapshot into the rows the screen shows. Pure: the same snapshot and bridge state always
 * produce the same rows, which is what the screenshot tests rely on.
 */
object DoctorEvaluator {
    fun evaluate(
        snapshot: DoctorSnapshot,
        bridge: BridgeState,
        paired: Boolean,
        daemon: DaemonProbe,
        callActive: Boolean
    ): List<DoctorRow> {
        val rows = mutableListOf<DoctorRow>()
        val root = snapshot.root
        val provider = root.provider

        rows += DoctorRow(
            id = ID_ROOT,
            kind = DoctorItemKind.ROOT_PROVIDER,
            title = "Root provider",
            severity = when {
                root.su == SuStatus.GRANTED && root.conflict -> DoctorSeverity.WARN
                root.su == SuStatus.GRANTED && provider == null -> DoctorSeverity.WARN
                root.su == SuStatus.GRANTED -> DoctorSeverity.OK
                root.su == SuStatus.DENIED -> DoctorSeverity.FAIL
                root.su == SuStatus.NOT_ROOTED -> DoctorSeverity.FAIL
                else -> DoctorSeverity.UNKNOWN
            },
            evidence = when {
                root.su == SuStatus.GRANTED && root.conflict ->
                    root.providers.joinToString(" + ") { "${it.id.doctorTitle()} ${it.version}" } +
                        " are present but nothing said which one answers su; installing would be a guess"

                root.su == SuStatus.GRANTED && provider == null ->
                    "root is granted but no supported provider was found"

                root.su == SuStatus.GRANTED && provider != null ->
                    "${provider.id.doctorTitle()} ${provider.version}" +
                        (if (provider.managerInstalled) " · manager installed" else " · manager app not installed") +
                        leftoversSuffix(root)

                root.su == SuStatus.DENIED -> "su answered but the manager refused this app"

                root.su == SuStatus.NOT_ROOTED && root.hidingManagers().isNotEmpty() ->
                    "the ${root.hidingManagers().joinToString(" and ") { it.id.doctorTitle() }} manager is installed, " +
                        "but su is hidden from this app until it is allowed in Superuser"

                root.su == SuStatus.NOT_ROOTED ->
                    "no su binary answered" +
                        if (root.providers.any { it.id == RootId.MAGISK }) {
                            " · the Magisk app is installed, so Magisk itself is not active"
                        } else {
                            ""
                        }

                else -> "su did not answer within ${RootShell.DEFAULT_TIMEOUT_MS / 1000} s"
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.ROOT_PROVIDER)
        )

        rows += moduleRow(snapshot)
        rows += enabledRow(snapshot)
        rows += overlayRow(snapshot)

        val facts = snapshot.appFacts
        PrivilegedPermissions.ALL.sorted().forEach { permission ->
            val granted = facts?.grantedPermissions?.contains(permission) == true
            rows += DoctorRow(
                id = "$ID_PRIVILEGED:$permission",
                kind = DoctorItemKind.PRIVILEGED_PERMISSION,
                title = privilegedTitle(permission),
                severity = when {
                    facts == null -> DoctorSeverity.UNKNOWN
                    granted -> DoctorSeverity.OK
                    else -> DoctorSeverity.FAIL
                },
                evidence = when {
                    facts == null -> "the package manager did not answer"
                    granted -> "granted by the module's privapp allowlist"
                    else -> "not granted; privapp-permissions-com.phonxiety.bridge.xml does not cover it"
                },
                subject = permission,
                plan = DoctorPolicy.planFor(DoctorItemKind.PRIVILEGED_PERMISSION)
            )
        }

        RuntimePermission.entries
            .filter { it != RuntimePermission.POST_NOTIFICATIONS }
            .forEach { permission ->
                val missing = permission in snapshot.runtimeMissing
                rows += DoctorRow(
                    id = "$ID_RUNTIME:${permission.androidName}",
                    kind = DoctorItemKind.RUNTIME_PERMISSION,
                    title = permission.doctorTitle(),
                    severity = if (missing) DoctorSeverity.FAIL else DoctorSeverity.OK,
                    evidence = when {
                        !missing -> "granted"

                        permission == RuntimePermission.ACCESS_LOCAL_NETWORK ->
                            "not granted; Android 17 reports the missing grant as a timeout, not as a denial"

                        else -> "not granted"
                    },
                    subject = permission.androidName,
                    plan = DoctorPolicy.planFor(DoctorItemKind.RUNTIME_PERMISSION)
                )
            }

        val notificationsMissing = RuntimePermission.POST_NOTIFICATIONS in snapshot.runtimeMissing
        rows += DoctorRow(
            id = ID_NOTIFICATIONS,
            kind = DoctorItemKind.NOTIFICATIONS,
            title = "Notifications",
            severity = if (notificationsMissing || !snapshot.notificationsEnabled) {
                DoctorSeverity.FAIL
            } else {
                DoctorSeverity.OK
            },
            evidence = when {
                notificationsMissing -> "POST_NOTIFICATIONS is not granted"
                !snapshot.notificationsEnabled -> "the permission is granted but notifications are switched off"
                else -> "notifications are allowed"
            },
            subject = when {
                notificationsMissing -> RuntimePermission.POST_NOTIFICATIONS.androidName
                !snapshot.notificationsEnabled -> SUBJECT_NOTIFICATION_SETTINGS
                else -> null
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.NOTIFICATIONS)
        )

        rows += DoctorRow(
            id = ID_BATTERY,
            kind = DoctorItemKind.BATTERY_EXEMPTION,
            title = "Battery exemption",
            severity = when (snapshot.batteryExempt) {
                true -> DoctorSeverity.OK
                false -> DoctorSeverity.WARN
                null -> DoctorSeverity.UNKNOWN
            },
            evidence = when (snapshot.batteryExempt) {
                true -> "exempt from battery optimisation"
                false -> "not exempt; a locked phone can miss the heartbeat"
                null -> "the power manager did not answer"
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.BATTERY_EXEMPTION)
        )

        rows += DoctorRow(
            id = ID_PAIRING,
            kind = DoctorItemKind.PAIRING,
            title = "Pairing",
            severity = if (paired) DoctorSeverity.OK else DoctorSeverity.FAIL,
            evidence = if (paired) "a pairing is stored" else "no pairing link is stored",
            plan = DoctorPolicy.planFor(DoctorItemKind.PAIRING)
        )

        // Only shown when the app actually recorded a death: a healthy install should not carry a
        // permanent "no crash" line.  The memory limiter is named explicitly because Android 17
        // kills background processes for it and that looks like a random restart otherwise.
        snapshot.lastCrash?.let { crash ->
            rows += DoctorRow(
                id = ID_LAST_CRASH,
                kind = DoctorItemKind.LAST_CRASH,
                title = "Last process exit",
                severity = when {
                    crash.memoryLimiter -> DoctorSeverity.WARN
                    crash.kind == CrashReports.Kind.ANR -> DoctorSeverity.WARN
                    crash.kind == CrashReports.Kind.CRASH -> DoctorSeverity.WARN
                    else -> DoctorSeverity.OK
                },
                evidence = when {
                    crash.memoryLimiter ->
                        "${crash.summary()}; the system reclaimed the process's memory, so keep the " +
                            "service heap small and the battery exemption granted"

                    crash.kind == CrashReports.Kind.ANR ->
                        "${crash.summary()}; the trace is in the diagnostics bundle"

                    else -> crash.summary()
                },
                plan = DoctorPolicy.planFor(DoctorItemKind.LAST_CRASH)
            )
        }

        rows += linkRow(bridge)
        rows += daemonRow(daemon)
        rows += relayRow(daemon)
        rows += daemonCheckRows(daemon)

        // A call in progress downgrades what may run, and says so next to the row.
        return rows.map { row -> row.blockedWhileCalling(callActive) }
    }

    private fun moduleRow(snapshot: DoctorSnapshot): DoctorRow {
        val module = snapshot.module
        val version = moduleVersion(module)
        return DoctorRow(
            id = ID_MODULE_PRESENT,
            kind = DoctorItemKind.MODULE_PRESENT,
            title = "Module installed",
            severity = when (module) {
                null, ModuleState.Unknown -> DoctorSeverity.UNKNOWN
                ModuleState.Absent -> DoctorSeverity.FAIL
                is ModuleState.Staged -> DoctorSeverity.WARN
                else -> DoctorSeverity.OK
            },
            evidence = when (module) {
                null -> "the root provider could not be asked"
                ModuleState.Unknown -> "the module directory under /data/adb could not be read"
                ModuleState.Absent -> "no ${RecoveryBook.MODULE_ID} under /data/adb/modules"
                is ModuleState.Staged -> "installed$version, waiting for the reboot that applies it"
                is ModuleState.Enabled -> "present$version"
                is ModuleState.Disabled -> "present$version, disabled"
                ModuleState.RemovePending -> "present, but the remove flag is set"
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.MODULE_PRESENT)
        )
    }

    private fun enabledRow(snapshot: DoctorSnapshot): DoctorRow {
        val module = snapshot.module
        return DoctorRow(
            id = ID_MODULE_ENABLED,
            kind = DoctorItemKind.MODULE_ENABLED,
            title = "Module enabled",
            severity = when (module) {
                is ModuleState.Enabled -> DoctorSeverity.OK
                is ModuleState.Staged -> DoctorSeverity.WARN
                is ModuleState.Disabled, ModuleState.Absent, ModuleState.RemovePending -> DoctorSeverity.FAIL
                else -> DoctorSeverity.UNKNOWN
            },
            evidence = when (module) {
                is ModuleState.Enabled -> "the disable flag is absent"
                is ModuleState.Staged -> "the overlay applies on the next boot"
                is ModuleState.Disabled -> "the disable flag is set; the overlay is not mounted"
                ModuleState.Absent -> "the module is not installed"
                ModuleState.RemovePending -> "the remove flag is set; it disappears on the next boot"
                ModuleState.Unknown, null -> "the module state could not be read"
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.MODULE_ENABLED)
        )
    }

    private fun overlayRow(snapshot: DoctorSnapshot): DoctorRow {
        val facts = snapshot.appFacts
        val path = facts?.apkPath
        val underPrivApp = path?.startsWith(PREFIX_PRIV_APP) == true
        val updatedSystem = facts?.updatedSystemApp == true
        return DoctorRow(
            id = ID_OVERLAY,
            kind = DoctorItemKind.OVERLAY,
            title = "System overlay applied",
            severity = when {
                facts == null -> DoctorSeverity.UNKNOWN
                underPrivApp || updatedSystem -> DoctorSeverity.OK
                else -> DoctorSeverity.FAIL
            },
            evidence = when {
                facts == null -> "the package manager did not answer"

                underPrivApp -> "pm path $path"

                updatedSystem -> "an updated system app runs from $path"

                else ->
                    "the APK runs from $path; the module's overlay is not mounted" +
                        if (snapshot.overlay == Overlay.METAMODULE_MISSING) {
                            " (the provider needs a metamodule to mount system/)"
                        } else {
                            ""
                        }
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.OVERLAY)
        )
    }

    private fun linkRow(bridge: BridgeState): DoctorRow {
        val link = bridge.link
        val severity = when (link) {
            is LinkState.Up -> if (link.path == PathKind.DIRECT) DoctorSeverity.OK else DoctorSeverity.WARN
            is LinkState.Connecting -> DoctorSeverity.WARN
            LinkState.Offline -> DoctorSeverity.FAIL
        }
        val evidence = when (link) {
            is LinkState.Up -> buildString {
                append("up over ${link.kind.name.lowercase()} · ${link.path.name.lowercase()}")
                link.rttMs?.let { append(" · $it ms") }
                append(" · ${link.reconnects} reconnects")
                link.address?.let { append(" · $it") }
            }

            is LinkState.Connecting -> "connecting over ${link.candidate.name.lowercase()}"

            LinkState.Offline -> buildString {
                append("offline; no path to the daemon")
                bridge.lastError?.let { append(": ${it.message} (${it.code})") }
            }
        }
        return DoctorRow(
            id = ID_LINK,
            kind = DoctorItemKind.LINK,
            title = "Device link",
            severity = severity,
            evidence = evidence,
            plan = DoctorPolicy.planFor(DoctorItemKind.LINK)
        )
    }

    private fun daemonRow(daemon: DaemonProbe): DoctorRow = when (daemon) {
        is DaemonProbe.Reached -> {
            val status = daemon.report.status?.lowercase()
            DoctorRow(
                id = ID_DAEMON,
                kind = DoctorItemKind.DAEMON,
                title = "Daemon reachable",
                severity = when (status) {
                    "ok" -> DoctorSeverity.OK
                    "warn" -> DoctorSeverity.WARN
                    "fail" -> DoctorSeverity.FAIL
                    else -> DoctorSeverity.UNKNOWN
                },
                evidence = buildString {
                    append("self-check ${status ?: "answered"}")
                    append(" · ${daemon.report.checks.size} checks")
                    daemon.report.checkedAt?.let { append(" · $it") }
                }
            )
        }

        is DaemonProbe.Unreachable -> DoctorRow(
            id = ID_DAEMON,
            kind = DoctorItemKind.DAEMON,
            title = "Daemon reachable",
            severity = DoctorSeverity.FAIL,
            evidence = "${daemon.code}: ${daemon.message}"
        )
    }.let { it.copy(plan = DoctorPolicy.planFor(DoctorItemKind.DAEMON)) }

    private fun relayRow(daemon: DaemonProbe): DoctorRow {
        val checks = (daemon as? DaemonProbe.Reached)?.report?.checks.orEmpty()
            .filter { it.name in RELAY_CHECKS }
        val worst = checks.map { severityOf(it.status) }.worstOrNull()
        return DoctorRow(
            id = ID_RELAY,
            kind = DoctorItemKind.RELAY,
            title = "Relay configuration",
            severity = worst ?: DoctorSeverity.UNKNOWN,
            evidence = if (checks.isEmpty()) {
                "the daemon did not report a relay state"
            } else {
                checks.joinToString(" · ") { "${it.name}: ${it.detail ?: it.status ?: "unknown"}" }
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.RELAY)
        )
    }

    private fun daemonCheckRows(daemon: DaemonProbe): List<DoctorRow> {
        val report: DoctorReport = (daemon as? DaemonProbe.Reached)?.report ?: return emptyList()
        return report.checks
            .filter { it.name !in RELAY_CHECKS }
            .map { check ->
                DoctorRow(
                    id = "$ID_DAEMON_CHECK:${check.name}",
                    kind = DoctorItemKind.DAEMON_CHECK,
                    title = daemonCheckTitle(check.name),
                    severity = severityOf(check.status),
                    evidence = check.detail ?: check.status ?: "the daemon reported no detail"
                )
            }
    }

    private fun DoctorRow.blockedWhileCalling(callActive: Boolean): DoctorRow {
        val plan = plan ?: return this
        if (!callActive || !plan.cutsCall) return this
        return copy(plan = plan.copy(risk = FixClass.NEVER))
    }

    private fun severityOf(status: String?): DoctorSeverity = when (status?.lowercase()) {
        "ok", "pass", "true" -> DoctorSeverity.OK
        "warn", "warning" -> DoctorSeverity.WARN
        "fail", "error", "false" -> DoctorSeverity.FAIL
        else -> DoctorSeverity.UNKNOWN
    }

    private fun moduleVersion(module: ModuleState?): String = when (module) {
        is ModuleState.Enabled -> module.version?.let { " · $it" } ?: ""
        is ModuleState.Disabled -> module.version?.let { " · $it" } ?: ""
        is ModuleState.Staged -> module.version?.let { " · $it" } ?: ""
        else -> ""
    }

    private fun List<DoctorSeverity>.worstOrNull(): DoctorSeverity? = when {
        isEmpty() -> null
        contains(DoctorSeverity.FAIL) -> DoctorSeverity.FAIL
        contains(DoctorSeverity.WARN) -> DoctorSeverity.WARN
        contains(DoctorSeverity.UNKNOWN) -> DoctorSeverity.UNKNOWN
        else -> DoctorSeverity.OK
    }

    private fun daemonCheckTitle(name: String): String = DAEMON_CHECK_TITLES[name]
        ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

    const val ID_ROOT: String = "root"
    const val ID_MODULE_PRESENT: String = "module_present"
    const val ID_MODULE_ENABLED: String = "module_enabled"
    const val ID_OVERLAY: String = "overlay"
    const val ID_PRIVILEGED: String = "privileged"
    const val ID_RUNTIME: String = "runtime"
    const val ID_NOTIFICATIONS: String = "notifications"
    const val ID_BATTERY: String = "battery"
    const val ID_LAST_CRASH: String = "last_crash"
    const val ID_PAIRING: String = "pairing"
    const val ID_LINK: String = "link"
    const val ID_DAEMON: String = "daemon"
    const val ID_RELAY: String = "relay"
    const val ID_DAEMON_CHECK: String = "daemon_check"

    /** Row subject meaning "the permission is granted but the channel is off". */
    const val SUBJECT_NOTIFICATION_SETTINGS: String = "notification_settings"

    private const val PREFIX_PRIV_APP = "/system/priv-app/"

    private val RELAY_CHECKS = setOf("relays", "iroh_relay")

    private val DAEMON_CHECK_TITLES = mapOf(
        "daemon" to "Daemon process",
        "phone" to "Phone connection",
        "codex_auth" to "Codex credentials",
        "voice_backend" to "Voice backend",
        "disk" to "Daemon storage"
    )
}

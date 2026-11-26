package com.phonxiety.bridge.feature.doctor

import androidx.annotation.StringRes
import com.phonxiety.bridge.CrashReports
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.privilegedTitle
import com.phonxiety.bridge.model.BridgeState
import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.model.LinkKind
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

/** The version without the provider's own banner suffix: "31.1:MAGISK" reads "31.1". */
private fun DetectedProvider.displayVersion(): String = version.substringBefore(':').ifBlank { version }

/** Leftovers of a provider that did not answer: reported so a migration makes sense. */
private fun leftovers(root: RootDetection): UiText? {
    if (root.leftovers.isEmpty()) return null
    val names = root.leftovers.joinToString(", ") { "${it.id.doctorTitle()} ${it.displayVersion()}" }
    return UiText.Res(R.string.doctor_evidence_leftovers, listOf(names))
}

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
        val context = FixContext(provider = provider?.id, overlay = snapshot.overlay)

        rows += DoctorRow(
            id = ID_ROOT,
            kind = DoctorItemKind.ROOT_PROVIDER,
            title = UiText.Res(R.string.doctor_row_root_provider),
            severity = when {
                root.su == SuStatus.GRANTED && root.conflict -> DoctorSeverity.WARN
                root.su == SuStatus.GRANTED && provider == null -> DoctorSeverity.WARN
                root.su == SuStatus.GRANTED -> DoctorSeverity.OK
                root.su == SuStatus.DENIED -> DoctorSeverity.FAIL
                root.su == SuStatus.NOT_ROOTED -> DoctorSeverity.FAIL
                else -> DoctorSeverity.UNKNOWN
            },
            summary = when {
                root.su == SuStatus.GRANTED && root.conflict -> listOf(
                    UiText.Res(
                        R.string.doctor_evidence_root_conflict,
                        listOf(root.providers.joinToString(" and ") { "${it.id.doctorTitle()} ${it.displayVersion()}" })
                    )
                )

                root.su == SuStatus.GRANTED && provider == null -> listOf(
                    UiText.Res(R.string.doctor_evidence_root_no_provider)
                )

                root.su == SuStatus.GRANTED && provider != null -> buildList {
                    add(
                        UiText.Res(
                            R.string.doctor_evidence_root_granted,
                            listOf(provider.id.doctorTitle(), provider.displayVersion())
                        )
                    )
                    add(
                        UiText.Res(
                            if (provider.managerInstalled) {
                                R.string.doctor_evidence_manager_installed
                            } else {
                                R.string.doctor_evidence_manager_missing
                            }
                        )
                    )
                    leftovers(root)?.let(::add)
                }

                root.su == SuStatus.DENIED -> listOf(UiText.Res(R.string.doctor_evidence_root_denied))

                root.su == SuStatus.NOT_ROOTED && root.hidingManagers().isNotEmpty() -> listOf(
                    UiText.Res(
                        R.string.doctor_evidence_root_hidden,
                        listOf(root.hidingManagers().joinToString(" and ") { it.id.doctorTitle() })
                    )
                )

                root.su == SuStatus.NOT_ROOTED -> listOf(
                    UiText.Res(
                        if (root.providers.any { it.id == RootId.MAGISK }) {
                            R.string.doctor_evidence_root_not_rooted_magisk
                        } else {
                            R.string.doctor_evidence_root_not_rooted
                        }
                    )
                )

                else -> listOf(
                    UiText.Res(
                        R.string.doctor_evidence_root_timeout,
                        listOf(RootShell.DEFAULT_TIMEOUT_MS / 1000)
                    )
                )
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.ROOT_PROVIDER, context)
        )

        rows += moduleRow(snapshot, context)
        rows += enabledRow(snapshot, context)
        rows += overlayRow(snapshot, context)

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
                summary = listOf(
                    UiText.Res(
                        when {
                            facts == null -> R.string.doctor_evidence_overlay_no_package_manager
                            granted -> R.string.doctor_evidence_privileged_granted
                            else -> R.string.doctor_evidence_privileged_missing
                        }
                    )
                ),
                subject = permission,
                plan = DoctorPolicy.planFor(DoctorItemKind.PRIVILEGED_PERMISSION, context)
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
                    summary = listOf(
                        UiText.Res(
                            when {
                                !missing -> R.string.doctor_evidence_granted

                                permission == RuntimePermission.ACCESS_LOCAL_NETWORK ->
                                    R.string.doctor_evidence_local_network_missing

                                else -> R.string.doctor_evidence_not_granted
                            }
                        )
                    ),
                    subject = permission.androidName,
                    plan = DoctorPolicy.planFor(DoctorItemKind.RUNTIME_PERMISSION, context)
                )
            }

        val notificationsMissing = RuntimePermission.POST_NOTIFICATIONS in snapshot.runtimeMissing
        rows += DoctorRow(
            id = ID_NOTIFICATIONS,
            kind = DoctorItemKind.NOTIFICATIONS,
            title = UiText.Res(R.string.doctor_row_notifications),
            severity = if (notificationsMissing || !snapshot.notificationsEnabled) {
                DoctorSeverity.FAIL
            } else {
                DoctorSeverity.OK
            },
            summary = listOf(
                UiText.Res(
                    when {
                        notificationsMissing -> R.string.doctor_evidence_notifications_permission
                        !snapshot.notificationsEnabled -> R.string.doctor_evidence_notifications_switched_off
                        else -> R.string.doctor_evidence_notifications_allowed
                    }
                )
            ),
            subject = when {
                notificationsMissing -> RuntimePermission.POST_NOTIFICATIONS.androidName
                !snapshot.notificationsEnabled -> SUBJECT_NOTIFICATION_SETTINGS
                else -> null
            },
            plan = DoctorPolicy.planFor(DoctorItemKind.NOTIFICATIONS, context)
        )

        rows += DoctorRow(
            id = ID_BATTERY,
            kind = DoctorItemKind.BATTERY_EXEMPTION,
            title = UiText.Res(R.string.doctor_row_battery),
            severity = when (snapshot.batteryExempt) {
                true -> DoctorSeverity.OK
                false -> DoctorSeverity.WARN
                null -> DoctorSeverity.UNKNOWN
            },
            summary = listOf(
                UiText.Res(
                    when (snapshot.batteryExempt) {
                        true -> R.string.doctor_evidence_battery_allowed
                        false -> R.string.doctor_evidence_battery_missing
                        null -> R.string.doctor_evidence_battery_unknown
                    }
                )
            ),
            plan = DoctorPolicy.planFor(DoctorItemKind.BATTERY_EXEMPTION, context)
        )

        rows += DoctorRow(
            id = ID_PAIRING,
            kind = DoctorItemKind.PAIRING,
            title = UiText.Res(R.string.doctor_row_pairing),
            severity = if (paired) DoctorSeverity.OK else DoctorSeverity.FAIL,
            summary = listOf(
                UiText.Res(
                    if (paired) R.string.doctor_evidence_pairing_stored else R.string.doctor_evidence_pairing_missing
                )
            ),
            plan = DoctorPolicy.planFor(DoctorItemKind.PAIRING, context)
        )

        // Only shown when the app actually recorded a death: a healthy install should not carry a
        // permanent "no crash" line.  The memory limiter is named explicitly because Android 17
        // kills background processes for it and that looks like a random restart otherwise.
        snapshot.lastCrash?.let { crash ->
            rows += DoctorRow(
                id = ID_LAST_CRASH,
                kind = DoctorItemKind.LAST_CRASH,
                title = UiText.Res(R.string.doctor_row_last_crash),
                severity = when {
                    crash.memoryLimiter -> DoctorSeverity.WARN
                    crash.kind == CrashReports.Kind.ANR -> DoctorSeverity.WARN
                    crash.kind == CrashReports.Kind.CRASH -> DoctorSeverity.WARN
                    else -> DoctorSeverity.OK
                },
                // The whole record in words, then when: the code itself stays under Details.
                summary = listOf(UiText.Res(lastExitLabel(crash))),
                summaryAtEpochMs = crash.atEpochMs,
                details = listOfNotNull(
                    UiText.Literal(crash.summary()),
                    crash.description?.takeIf { it.isNotBlank() }?.let(UiText::Literal)
                ),
                plan = DoctorPolicy.planFor(DoctorItemKind.LAST_CRASH, context)
            )
        }

        rows += linkRow(bridge)
        rows += daemonRow(daemon)
        rows += relayRow(daemon)
        rows += daemonCheckRows(daemon)

        // A call in progress downgrades what may run, and says so next to the row.
        return rows.map { row -> row.blockedWhileCalling(callActive) }
    }

    private fun moduleRow(snapshot: DoctorSnapshot, context: FixContext): DoctorRow {
        val module = snapshot.module
        val version = module.version
        return DoctorRow(
            id = ID_MODULE_PRESENT,
            kind = DoctorItemKind.MODULE_PRESENT,
            title = UiText.Res(R.string.doctor_row_module_installed),
            severity = when (module) {
                null, ModuleState.Unknown -> DoctorSeverity.UNKNOWN
                ModuleState.Absent -> DoctorSeverity.FAIL
                is ModuleState.Staged -> DoctorSeverity.WARN
                else -> DoctorSeverity.OK
            },
            summary = listOf(
                UiText.Res(
                    when (module) {
                        null -> R.string.doctor_evidence_module_not_asked

                        ModuleState.Unknown -> R.string.doctor_evidence_module_unreadable

                        ModuleState.Absent -> R.string.doctor_evidence_module_absent

                        is ModuleState.Staged -> if (version == null) {
                            R.string.doctor_evidence_module_staged_no_version
                        } else {
                            R.string.doctor_evidence_module_staged
                        }

                        is ModuleState.Enabled -> if (version == null) {
                            R.string.doctor_evidence_module_enabled_no_version
                        } else {
                            R.string.doctor_evidence_module_enabled
                        }

                        is ModuleState.Disabled -> if (version == null) {
                            R.string.doctor_evidence_module_disabled_no_version
                        } else {
                            R.string.doctor_evidence_module_disabled
                        }

                        ModuleState.RemovePending -> R.string.doctor_evidence_module_remove_pending
                    },
                    listOfNotNull(version)
                )
            ),
            plan = DoctorPolicy.planFor(DoctorItemKind.MODULE_PRESENT, context)
        )
    }

    private fun enabledRow(snapshot: DoctorSnapshot, context: FixContext): DoctorRow {
        val module = snapshot.module
        return DoctorRow(
            id = ID_MODULE_ENABLED,
            kind = DoctorItemKind.MODULE_ENABLED,
            title = UiText.Res(R.string.doctor_row_module_enabled),
            severity = when (module) {
                is ModuleState.Enabled -> DoctorSeverity.OK
                is ModuleState.Staged -> DoctorSeverity.WARN
                is ModuleState.Disabled, ModuleState.Absent, ModuleState.RemovePending -> DoctorSeverity.FAIL
                else -> DoctorSeverity.UNKNOWN
            },
            summary = listOf(
                UiText.Res(
                    when (module) {
                        null, ModuleState.Unknown -> R.string.doctor_evidence_module_unknown
                        is ModuleState.Enabled -> R.string.doctor_evidence_enabled_flag_absent
                        is ModuleState.Staged -> R.string.doctor_evidence_enabled_staged
                        is ModuleState.Disabled -> R.string.doctor_evidence_enabled_disabled
                        ModuleState.Absent -> R.string.doctor_evidence_enabled_absent
                        ModuleState.RemovePending -> R.string.doctor_evidence_enabled_remove_pending
                    }
                )
            ),
            plan = DoctorPolicy.planFor(DoctorItemKind.MODULE_ENABLED, context)
        )
    }

    private fun overlayRow(snapshot: DoctorSnapshot, context: FixContext): DoctorRow {
        val facts = snapshot.appFacts
        val path = facts?.apkPath
        val underPrivApp = path?.startsWith(PREFIX_PRIV_APP) == true
        val updatedSystem = facts?.updatedSystemApp == true
        return DoctorRow(
            id = ID_OVERLAY,
            kind = DoctorItemKind.OVERLAY,
            title = UiText.Res(R.string.doctor_row_overlay),
            severity = when {
                facts == null -> DoctorSeverity.UNKNOWN
                underPrivApp || updatedSystem -> DoctorSeverity.OK
                else -> DoctorSeverity.FAIL
            },
            summary = listOf(
                when {
                    facts == null -> UiText.Res(R.string.doctor_evidence_overlay_no_package_manager)

                    underPrivApp -> UiText.Res(R.string.doctor_evidence_overlay_privapp, listOfNotNull(path))

                    updatedSystem -> UiText.Res(R.string.doctor_evidence_overlay_updated, listOfNotNull(path))

                    snapshot.overlay == Overlay.METAMODULE_MISSING ->
                        UiText.Res(R.string.doctor_evidence_overlay_metamodule, listOfNotNull(path))

                    else -> UiText.Res(R.string.doctor_evidence_overlay_not_mounted, listOfNotNull(path))
                }
            ),
            plan = DoctorPolicy.planFor(DoctorItemKind.OVERLAY, context)
        )
    }

    private fun linkRow(bridge: BridgeState): DoctorRow {
        val link = bridge.link
        val severity = when (link) {
            is LinkState.Up -> if (link.path == PathKind.DIRECT) DoctorSeverity.OK else DoctorSeverity.WARN
            is LinkState.Connecting -> DoctorSeverity.WARN
            LinkState.Offline -> DoctorSeverity.FAIL
        }
        val summary = when (link) {
            is LinkState.Up -> buildList<UiText> {
                add(UiText.Res(pathLabel(link.path)))
                add(UiText.Res(kindLabel(link.kind)))
                add(UiText.Plural(R.plurals.doctor_evidence_reconnects, link.reconnects))
                link.rttMs?.let { add(UiText.Res(R.string.doctor_evidence_rtt, listOf(it))) }
            }

            is LinkState.Connecting -> listOf(
                UiText.Res(R.string.doctor_evidence_link_connecting),
                UiText.Res(kindLabel(link.candidate))
            )

            LinkState.Offline -> listOf(UiText.Res(R.string.doctor_evidence_link_offline))
        }
        // The endpoint id and the link failure are the daemon's own words; they belong under
        // Details, in monospace, not in the sentence.
        val details = when (link) {
            is LinkState.Up -> listOfNotNull(link.address?.let(UiText::Literal))

            is LinkState.Connecting -> emptyList()

            LinkState.Offline -> listOfNotNull(
                bridge.lastError?.let { UiText.Literal("${it.code}: ${it.message}") }
            )
        }
        return DoctorRow(
            id = ID_LINK,
            kind = DoctorItemKind.LINK,
            title = UiText.Res(R.string.doctor_row_link),
            severity = severity,
            summary = summary,
            details = details,
            plan = DoctorPolicy.planFor(DoctorItemKind.LINK)
        )
    }

    private fun daemonRow(daemon: DaemonProbe): DoctorRow = when (daemon) {
        is DaemonProbe.Reached -> {
            val status = daemon.report.status?.lowercase()
            DoctorRow(
                id = ID_DAEMON,
                kind = DoctorItemKind.DAEMON,
                title = UiText.Res(R.string.doctor_row_daemon),
                severity = when (status) {
                    "ok" -> DoctorSeverity.OK
                    "warn" -> DoctorSeverity.WARN
                    "fail" -> DoctorSeverity.FAIL
                    else -> DoctorSeverity.UNKNOWN
                },
                summary = buildList {
                    add(
                        when (status) {
                            "ok" -> UiText.Res(R.string.doctor_evidence_daemon_status_clear)

                            "warn" -> UiText.Res(R.string.doctor_evidence_daemon_status_warnings)

                            "fail" -> UiText.Res(R.string.doctor_evidence_daemon_status_failures)

                            // A status this build does not know is the daemon's word, not ours.
                            else -> UiText.Res(R.string.doctor_evidence_daemon_status_unknown)
                        }
                    )
                    add(UiText.Plural(R.plurals.doctor_evidence_daemon_checks, daemon.report.checks.size))
                },
                details = listOfNotNull(
                    daemon.report.status?.takeIf { status !in KNOWN_DAEMON_STATUSES }?.let(UiText::Literal),
                    daemon.report.checkedAt?.let(UiText::Literal)
                )
            )
        }

        is DaemonProbe.Unreachable -> DoctorRow(
            id = ID_DAEMON,
            kind = DoctorItemKind.DAEMON,
            title = UiText.Res(R.string.doctor_row_daemon),
            severity = DoctorSeverity.FAIL,
            summary = listOf(UiText.Res(R.string.doctor_evidence_daemon_unreachable)),
            details = listOf(UiText.Literal("${daemon.code}: ${daemon.message}"))
        )
    }.let { it.copy(plan = DoctorPolicy.planFor(DoctorItemKind.DAEMON)) }

    private fun relayRow(daemon: DaemonProbe): DoctorRow {
        val checks = (daemon as? DaemonProbe.Reached)?.report?.checks.orEmpty()
            .filter { it.name in RELAY_CHECKS }
        val worst = checks.map { severityOf(it.status) }.worstOrNull()
        return DoctorRow(
            id = ID_RELAY,
            kind = DoctorItemKind.RELAY,
            title = UiText.Res(R.string.doctor_row_relay),
            severity = worst ?: DoctorSeverity.UNKNOWN,
            // The relay check names and their details are the daemon's own report: the row claims
            // only that there was no report to read, and Details keeps the rest verbatim.
            summary = if (checks.isEmpty()) listOf(UiText.Res(R.string.doctor_evidence_relay_missing)) else emptyList(),
            details = checks.map { UiText.Literal("${it.name}: ${it.detail ?: it.status ?: "unknown"}") },
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
                    // No plain sentence is known for a check the app did not write, so the row keeps
                    // its title and status and the daemon's detail stays under Details.
                    details = listOf(
                        check.detail?.let(UiText::Literal)
                            ?: check.status?.let(UiText::Literal)
                            ?: UiText.Res(R.string.doctor_evidence_daemon_no_detail)
                    )
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

    private fun List<DoctorSeverity>.worstOrNull(): DoctorSeverity? = when {
        isEmpty() -> null
        contains(DoctorSeverity.FAIL) -> DoctorSeverity.FAIL
        contains(DoctorSeverity.WARN) -> DoctorSeverity.WARN
        contains(DoctorSeverity.UNKNOWN) -> DoctorSeverity.UNKNOWN
        else -> DoctorSeverity.OK
    }

    private fun pathLabel(path: PathKind): Int = when (path) {
        PathKind.DIRECT -> R.string.doctor_link_path_direct
        PathKind.RELAY -> R.string.doctor_link_path_relay
        PathKind.UNKNOWN -> R.string.doctor_link_path_unknown
    }

    private fun kindLabel(kind: LinkKind): Int = when (kind) {
        LinkKind.IROH -> R.string.doctor_link_kind_iroh
        LinkKind.WEBSOCKET -> R.string.doctor_link_kind_lan
        LinkKind.TAILSCALE -> R.string.doctor_link_kind_tailscale
    }

    /** A daemon check the app knows by name gets its own title; anything else keeps the daemon's name. */
    private fun daemonCheckTitle(name: String): UiText {
        val title: Int? = DAEMON_CHECK_TITLES[name]
        return title?.let(UiText::Res) ?: UiText.Literal(name)
    }

    private val ModuleState?.version: String?
        get() = when (this) {
            is ModuleState.Enabled -> version
            is ModuleState.Disabled -> version
            is ModuleState.Staged -> version
            else -> null
        }

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

    /** Daemon statuses the app states in its own words; anything else stays verbatim. */
    private val KNOWN_DAEMON_STATUSES = setOf("ok", "warn", "fail")

    private val DAEMON_CHECK_TITLES: Map<String, Int> = mapOf(
        "daemon" to R.string.doctor_check_daemon,
        "phone" to R.string.doctor_check_phone,
        "codex_auth" to R.string.doctor_check_codex_auth,
        "voice_backend" to R.string.doctor_check_voice_backend,
        "disk" to R.string.doctor_check_disk
    )
}

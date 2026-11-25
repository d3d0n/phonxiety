package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission

/** Where a row belongs on the screen. */
enum class DoctorGroup(val title: String) {
    ROOT("Root and module"),
    PERMISSIONS("Permissions"),
    DEVICE("Device"),
    LINK("Link"),
    DAEMON("Daemon")
}

/** How bad a row is; colour and the summary derive from it. */
enum class DoctorSeverity { OK, WARN, FAIL, UNKNOWN }

/** The check a row reports; the decision tree in [DoctorPolicy] is keyed by it. */
enum class DoctorItemKind(val group: DoctorGroup) {
    ROOT_PROVIDER(DoctorGroup.ROOT),
    MODULE_PRESENT(DoctorGroup.ROOT),
    MODULE_ENABLED(DoctorGroup.ROOT),
    OVERLAY(DoctorGroup.ROOT),
    PRIVILEGED_PERMISSION(DoctorGroup.PERMISSIONS),
    RUNTIME_PERMISSION(DoctorGroup.PERMISSIONS),
    NOTIFICATIONS(DoctorGroup.PERMISSIONS),
    BATTERY_EXEMPTION(DoctorGroup.DEVICE),
    LAST_CRASH(DoctorGroup.DEVICE),
    PAIRING(DoctorGroup.DEVICE),
    LINK(DoctorGroup.LINK),
    DAEMON(DoctorGroup.DAEMON),
    RELAY(DoctorGroup.DAEMON),
    DAEMON_CHECK(DoctorGroup.DAEMON)
}

/**
 * What may run a fix: [AUTOMATIC] runs while the screen loads and lands in the change log,
 * [CONFIRM] explains its effect and rollback in a dialog first, and [NEVER] is not offered while a
 * call is live (or at all, for a change that cannot be undone).
 */
enum class FixClass { AUTOMATIC, CONFIRM, NEVER }

/** Every executable fix Doctor knows. Daemon-side fixes carry their id in [FixPlan.daemonFix]. */
enum class DoctorFixId(val label: String) {
    RESTART_SERVICE("Restart the bridge service"),
    REGRANT_RUNTIME_PERMISSION("Grant again"),
    REGRANT_NOTIFICATION("Allow notifications"),
    REQUEST_BATTERY_EXEMPTION("Exempt from battery optimisation"),
    ENABLE_MODULE("Re-enable the module"),
    REINSTALL_MODULE("Reinstall the module"),
    REPAIR_PAIRING("Open pairing recovery"),
    DAEMON_FIX("Apply the daemon fix")
}

/** One fix with the strings the confirm dialog shows. */
data class FixPlan(
    val fix: DoctorFixId,
    val risk: FixClass,
    val effect: String,
    val rollback: String,
    val needsReboot: Boolean = false,
    val daemonFix: String? = null,
    val restartsDaemon: Boolean = false
) {
    /** True when running it ends or restarts something a live call depends on. */
    val cutsCall: Boolean
        get() = needsReboot || restartsDaemon || fix in CALL_PATH_FIXES

    private companion object {
        val CALL_PATH_FIXES = setOf(
            DoctorFixId.RESTART_SERVICE,
            DoctorFixId.ENABLE_MODULE,
            DoctorFixId.REINSTALL_MODULE
        )
    }
}

/** One self-check row: state, the evidence behind it, and the repair when it fails. */
data class DoctorRow(
    val id: String,
    val kind: DoctorItemKind,
    val title: String,
    val severity: DoctorSeverity,
    val evidence: String,
    val subject: String? = null,
    val plan: FixPlan? = null
) {
    val needsFix: Boolean
        get() = severity == DoctorSeverity.FAIL || severity == DoctorSeverity.WARN
}

/** A fix the daemon offers in its own report; the phone classifies and runs it. */
data class DaemonFixOffer(
    val fix: String,
    val description: String?,
    val plan: FixPlan
)

/** A fix waiting for the user's confirmation; [rowId] is a check id or `daemon_fix:<id>`. */
data class FixRequest(
    val rowId: String,
    val title: String,
    val plan: FixPlan,
    val subject: String? = null
)

/** One line of the change log Doctor keeps for the fixes it ran. */
data class DoctorChangeEntry(val atEpochMs: Long, val text: String)

/** Everything the Doctor screen renders. */
data class DoctorUiState(
    val checking: Boolean = true,
    val rows: List<DoctorRow> = emptyList(),
    val daemonFixes: List<DaemonFixOffer> = emptyList(),
    val changeLog: List<DoctorChangeEntry> = emptyList(),
    val running: DoctorFixId? = null,
    val pending: FixRequest? = null,
    val page: RecoveryPage? = null,
    val selected: String? = null,
    val message: String? = null,
    val provider: RootId? = null,
    val callActive: Boolean = false,
    val checkedAtEpochMs: Long = 0L,
    val readError: String? = null
) {
    val overall: DoctorSeverity
        get() = when {
            rows.any { it.severity == DoctorSeverity.FAIL } -> DoctorSeverity.FAIL
            rows.any { it.severity == DoctorSeverity.WARN } -> DoctorSeverity.WARN
            rows.isEmpty() -> DoctorSeverity.UNKNOWN
            else -> DoctorSeverity.OK
        }

    val failing: List<DoctorRow> get() = rows.filter { it.needsFix }

    val passed: Int get() = rows.count { it.severity == DoctorSeverity.OK }

    fun rowsOf(group: DoctorGroup): List<DoctorRow> = rows.filter { it.kind.group == group }

    fun row(id: String?): DoctorRow? = if (id == null) null else rows.firstOrNull { it.id == id }
}

/** Human titles for the runtime permissions the bridge asks for. */
fun RuntimePermission.doctorTitle(): String = when (this) {
    RuntimePermission.RECORD_AUDIO -> "Record audio"
    RuntimePermission.CALL_PHONE -> "Place calls"
    RuntimePermission.READ_PHONE_STATE -> "Read phone state"
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> "Change audio settings"
    RuntimePermission.POST_NOTIFICATIONS -> "Post notifications"
    RuntimePermission.ACCESS_LOCAL_NETWORK -> "Local network (Android 17)"
}

/** Human titles for the privileged permissions the module's allowlist grants. */
fun privilegedTitle(permission: String): String = when (permission) {
    "android.permission.CAPTURE_AUDIO_OUTPUT" -> "Capture audio output"
    "android.permission.MODIFY_PHONE_STATE" -> "Modify phone state"
    "android.permission.CONTROL_INCALL_EXPERIENCE" -> "Control in-call experience"
    "android.permission.INSTALL_SELF_UPDATES" -> "Install self updates"
    "android.permission.REBOOT" -> "Reboot"
    else -> permission.substringAfterLast('.')
}

/** The provider's own name, as its manager shows it. */
fun RootId.doctorTitle(): String = when (this) {
    RootId.MAGISK -> "Magisk"
    RootId.KERNELSU -> "KernelSU"
    RootId.KERNELSU_NEXT -> "KernelSU-Next"
    RootId.APATCH -> "APatch"
}

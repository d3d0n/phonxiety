package com.phonxiety.bridge.feature.doctor

import android.app.ApplicationExitInfo
import androidx.annotation.StringRes
import com.phonxiety.bridge.CrashReports
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission

/** Where a row belongs on the screen. */
enum class DoctorGroup(@param:StringRes val titleRes: Int) {
    ROOT(R.string.doctor_group_root),
    PERMISSIONS(R.string.doctor_group_permissions),
    DEVICE(R.string.doctor_group_device),
    LINK(R.string.doctor_group_link),
    DAEMON(R.string.doctor_group_daemon)
}

/** How bad a row is; color and the summary derive from it. */
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

/** Every executable fix Doctor knows; [labelRes] is the button that runs it. */
enum class DoctorFixId(@param:StringRes val labelRes: Int) {
    RESTART_SERVICE(R.string.doctor_fix_restart_bridge),
    REGRANT_RUNTIME_PERMISSION(R.string.doctor_fix_grant_again),
    REGRANT_NOTIFICATION(R.string.doctor_fix_allow_notifications),
    REQUEST_BATTERY_EXEMPTION(R.string.doctor_fix_allow_background),
    ENABLE_MODULE(R.string.doctor_fix_reenable_module),
    REINSTALL_MODULE(R.string.doctor_fix_reinstall_module),
    REPAIR_PAIRING(R.string.doctor_fix_open_pairing),
    DAEMON_FIX(R.string.doctor_fix_run)
}

/** One fix with the copy the detail card and the confirm dialog show. */
data class FixPlan(
    val fix: DoctorFixId,
    val risk: FixClass,
    val effect: UiText,
    val rollback: UiText,
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

/**
 * What the phone knows about its root provider when a plan is built. Only the wording of the
 * overlay and module fixes depends on it: the fix and its risk never do.
 */
data class FixContext(
    val provider: RootId? = null,
    val overlay: Overlay? = null
) {
    /** The provider mounts `system/` through a metamodule only when the probe says one is missing. */
    val needsMetamodule: Boolean get() = overlay == Overlay.METAMODULE_MISSING
}

/** One self-check row: state, the plain sentence, the evidence behind it, and the repair. */
data class DoctorRow(
    val id: String,
    val kind: DoctorItemKind,
    val title: UiText,
    val severity: DoctorSeverity,
    /** The plain words the list reads, joined with " · " by the screen; authored copy only. */
    val summary: List<UiText> = emptyList(),
    /** A moment the sentence ends with, formatted on the phone in its own locale and zone. */
    val summaryAtEpochMs: Long? = null,
    /** The verbatim evidence — daemon text, codes and paths — behind the row's Details. */
    val details: List<UiText> = emptyList(),
    val subject: String? = null,
    val plan: FixPlan? = null
) {
    val needsFix: Boolean
        get() = severity == DoctorSeverity.FAIL || severity == DoctorSeverity.WARN
}

/**
 * A process death's reason as one short phrase a person reads. [CrashReports][com.phonxiety.bridge.CrashReports]
 * stores the `ApplicationExitInfo` name when it knows one and `reason_<n>` for anything newer, so
 * both forms are read against the SDK's documented values. A reason this build cannot explain
 * reads "Reason unavailable": a number in prose tells the reader nothing.
 */
@StringRes
fun exitReasonLabel(reason: String?): Int = when (exitReasonName(reason)) {
    "anr" -> R.string.doctor_exit_not_responding

    "crash" -> R.string.doctor_exit_crashed

    "crash_native" -> R.string.doctor_exit_crashed_native

    "low_memory" -> R.string.doctor_exit_memory

    "signaled" -> R.string.doctor_exit_signaled

    "exit_self" -> R.string.doctor_exit_self

    "initialization_failure" -> R.string.doctor_exit_startup

    "permission_change" -> R.string.doctor_exit_permission

    "excessive_resource_usage" -> R.string.doctor_exit_resources

    "user_requested" -> R.string.doctor_exit_user

    "user_stopped" -> R.string.doctor_exit_user_stopped

    "dependency_died" -> R.string.doctor_exit_dependency

    "freezer" -> R.string.doctor_exit_frozen

    "package_state_change" -> R.string.doctor_exit_package

    "package_updated" -> R.string.doctor_exit_updated

    // REASON_UNKNOWN and REASON_OTHER name no cause, and so does a value newer than this build.
    else -> R.string.doctor_exit_unavailable
}

/** The recorder's name for a death, or the SDK's name for a numeric `reason_<n>`, or null. */
private fun exitReasonName(reason: String?): String? {
    val key = reason?.trim()?.lowercase().orEmpty()
    if (!key.startsWith("reason_")) return key.ifEmpty { null }
    return when (key.removePrefix("reason_").toIntOrNull()) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization_failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive_resource_usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency_died"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package_state_change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package_updated"
        else -> null
    }
}

/**
 * The last process exit as one phrase, from the whole record the recorder wrote. A `reason` alone
 * is not the death: the crash handler stores the exception class there while marking the kind, and
 * the system reports the memory limiter as a separate flag. Reading only the reason would call a
 * crash "Reason unavailable" and drop a memory-limiter diagnosis, so the flag and the kind are read
 * first and the reason names the rest. "Reason unavailable" is left for a record where none of them
 * describes the cause; the raw fields stay under Details either way.
 */
@StringRes
fun lastExitLabel(report: CrashReports.Report): Int {
    if (report.memoryLimiter) return R.string.doctor_exit_memory
    return when (report.kind) {
        CrashReports.Kind.ANR -> R.string.doctor_exit_not_responding

        CrashReports.Kind.LOW_MEMORY -> R.string.doctor_exit_memory

        // A handler-written crash has an exception class as its reason; that is still a crash.
        CrashReports.Kind.CRASH -> {
            val named = exitReasonLabel(report.reason)
            if (named == R.string.doctor_exit_unavailable) R.string.doctor_exit_crashed else named
        }

        CrashReports.Kind.EXIT -> exitReasonLabel(report.reason)
    }
}

/** A fix the daemon offers in its own report; the phone classifies and runs it. */
data class DaemonFixOffer(
    val fix: String,
    val description: String?,
    val plan: FixPlan
)

/** A fix waiting for the user's confirmation; [checkTitle] is the check that failed. */
data class FixRequest(
    val rowId: String,
    val checkTitle: UiText,
    val plan: FixPlan,
    val subject: String? = null
)

/** One thing Doctor did, stored as a stable event so the log outlives a resource-id change. */
enum class DoctorLogEvent {
    BRIDGE_RESTARTED,
    PERMISSION_ASKED,
    NOTIFICATIONS_ASKED,
    BACKGROUND_ASKED,
    MODULE_ENABLED,
    MODULE_ENABLED_OUTPUT,
    MODULE_REINSTALLED,
    MODULE_REINSTALLED_OUTPUT,
    REBOOT_REQUESTED,
    REBOOTED,
    FIX_FAILED,
    REBOOT_FAILED,
    PAIRING_RECOVERY_OPENED,
    DAEMON_FIX_APPLIED,
    DAEMON_FIX_APPLIED_MORE
}

/**
 * One line of the change log. [legacyText] carries a line written before the copy moved to
 * resources, so an upgrade never loses the record.
 */
data class DoctorChangeEntry(
    val atEpochMs: Long,
    val event: DoctorLogEvent? = null,
    val args: List<String> = emptyList(),
    val legacyText: String? = null
)

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
    val message: UiText? = null,
    val provider: RootId? = null,
    val callActive: Boolean = false,
    val checkedAtEpochMs: Long = 0L,
    val readError: UiText? = null
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
fun RuntimePermission.doctorTitle(): UiText = UiText.Res(
    when (this) {
        RuntimePermission.RECORD_AUDIO -> R.string.doctor_runtime_record_audio
        RuntimePermission.CALL_PHONE -> R.string.doctor_runtime_call_phone
        RuntimePermission.READ_PHONE_STATE -> R.string.doctor_runtime_read_phone_state
        RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.doctor_runtime_modify_audio
        RuntimePermission.POST_NOTIFICATIONS -> R.string.doctor_runtime_post_notifications
        RuntimePermission.ACCESS_LOCAL_NETWORK -> R.string.doctor_runtime_local_network
    }
)

/** The provider's own name, as its manager shows it; a brand is spelled the same everywhere. */
fun RootId.doctorTitle(): String = when (this) {
    RootId.MAGISK -> "Magisk"
    RootId.KERNELSU -> "KernelSU"
    RootId.KERNELSU_NEXT -> "KernelSU-Next"
    RootId.APATCH -> "APatch"
}

package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.model.DoctorReport
import com.phonxiety.bridge.root.AppFacts
import com.phonxiety.bridge.root.ModuleState
import com.phonxiety.bridge.root.Overlay
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RootDetection
import com.phonxiety.bridge.root.RuntimePermission

/** One pass of the device-side checks; everything Android-specific is behind [DoctorSource]. */
data class DoctorSnapshot(
    val root: RootDetection,
    val module: ModuleState? = null,
    val overlay: Overlay? = null,
    val appFacts: AppFacts? = null,
    val runtimeMissing: Set<RuntimePermission> = emptySet(),
    val notificationsEnabled: Boolean = true,
    val batteryExempt: Boolean? = null,
    val checkedAtEpochMs: Long = 0L,
    val readError: String? = null
)

/** What the phone learned from the daemon: its self-check, or why it could not ask. */
sealed interface DaemonProbe {
    data class Reached(val report: DoctorReport) : DaemonProbe

    data class Unreachable(val code: String, val message: String) : DaemonProbe
}

/**
 * The device reads and the three mutations Doctor performs. Production is [AndroidDoctorSource];
 * tests substitute a fake so the decision tree and the ViewModel are exercised without a phone.
 */
interface DoctorSource {
    suspend fun read(): DoctorSnapshot

    suspend fun enableModule(): ProviderResult

    suspend fun reinstallModule(): ProviderResult

    suspend fun reboot(): ProviderResult
}

/** Starts and stops the foreground service; restarting it is what reopens the link. */
interface DoctorServiceControl {
    fun restart()
}

/** The change log Doctor keeps for the fixes it ran without asking. */
interface DoctorChangeLog {
    fun entries(): List<DoctorChangeEntry>

    fun record(entry: DoctorChangeEntry)
}

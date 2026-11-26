package com.phonxiety.bridge.feature.doctor

import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.root.RootId

/** One failure mode Doctor can walk a user through. */
enum class RecoveryId {
    BOOTLOOP,
    MODULE_DISABLED,
    ROOT_LOST_OTA,
    PRIVILEGED_GRANT_LOST,
    DAEMON_UNREACHABLE,
    PAIRING_REJECTED,
    LINK_UP_AUDIO_SILENT,
    CALL_DROPPED
}

/** One readable step; [command] is shown in monospace with a copy button, never inside prose. */
data class RecoveryStep(val text: UiText, val command: String? = null)

/** A guided recovery page: what happened, what to do on the phone, what to do from the computer. */
data class RecoveryPage(
    val id: RecoveryId,
    val title: UiText,
    val happened: UiText,
    val onPhone: List<RecoveryStep>,
    val fromComputer: List<RecoveryStep>
)

/**
 * The recovery table, with the provider's own commands filled in. A provider that is
 * not detected gets the flag-file and `adb` forms every manager honors, never a command invented
 * for it.
 */
object RecoveryBook {
    const val MODULE_ID: String = "phonxiety_bridge"

    /** The provider's own name, or null when nothing said which manager answers su. */
    fun providerName(provider: RootId?): String? = provider?.doctorTitle()

    fun pages(provider: RootId?): List<RecoveryPage> = RecoveryId.entries.map { page(it, provider) }

    fun page(id: RecoveryId, provider: RootId?): RecoveryPage = when (id) {
        RecoveryId.BOOTLOOP -> bootloop(provider)
        RecoveryId.MODULE_DISABLED -> moduleDisabled(provider)
        RecoveryId.ROOT_LOST_OTA -> rootLost(provider)
        RecoveryId.PRIVILEGED_GRANT_LOST -> privilegedGrantLost()
        RecoveryId.DAEMON_UNREACHABLE -> daemonUnreachable()
        RecoveryId.PAIRING_REJECTED -> pairingRejected()
        RecoveryId.LINK_UP_AUDIO_SILENT -> linkUpAudioSilent()
        RecoveryId.CALL_DROPPED -> callDropped()
    }

    private fun bootloop(provider: RootId?): RecoveryPage = RecoveryPage(
        id = RecoveryId.BOOTLOOP,
        title = UiText.Res(R.string.doctor_recovery_bootloop_title),
        happened = UiText.Res(R.string.doctor_recovery_bootloop_happened),
        onPhone = listOf(
            if (provider == null) {
                RecoveryStep(UiText.Res(R.string.doctor_recovery_bootloop_safe_mode_unknown))
            } else {
                RecoveryStep(
                    UiText.Res(R.string.doctor_recovery_bootloop_safe_mode, listOf(provider.doctorTitle()))
                )
            },
            RecoveryStep(
                UiText.Res(
                    when (provider) {
                        RootId.MAGISK -> R.string.doctor_recovery_bootloop_phone_magisk
                        RootId.KERNELSU, RootId.KERNELSU_NEXT -> R.string.doctor_recovery_bootloop_phone_kernelsu
                        RootId.APATCH -> R.string.doctor_recovery_bootloop_phone_apatch
                        null -> R.string.doctor_recovery_bootloop_phone_unknown
                    }
                )
            ),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_bootloop_open_manager))
        ),
        fromComputer = listOf(
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_bootloop_remove),
                when (provider) {
                    RootId.MAGISK -> "adb shell magisk --remove-modules"
                    RootId.KERNELSU, RootId.KERNELSU_NEXT -> "adb shell su -c 'ksud module disable $MODULE_ID'"
                    RootId.APATCH -> "adb shell su -c 'apd module disable $MODULE_ID'"
                    null -> "adb shell su -c 'ls /data/adb/modules'"
                }
            ),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_bootloop_authorize))
        )
    )

    private fun moduleDisabled(provider: RootId?): RecoveryPage = RecoveryPage(
        id = RecoveryId.MODULE_DISABLED,
        title = UiText.Res(R.string.doctor_recovery_module_disabled_title),
        happened = UiText.Res(R.string.doctor_recovery_module_disabled_happened),
        onPhone = listOf(
            if (provider == null) {
                RecoveryStep(UiText.Res(R.string.doctor_recovery_module_enable_unknown))
            } else {
                RecoveryStep(UiText.Res(R.string.doctor_recovery_module_enable, listOf(provider.doctorTitle())))
            },
            RecoveryStep(UiText.Res(R.string.doctor_recovery_module_reboot))
        ),
        fromComputer = listOf(
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_module_clear_flag),
                when (provider) {
                    RootId.MAGISK -> "adb shell su -c 'rm -f /data/adb/modules/$MODULE_ID/disable'"
                    RootId.KERNELSU, RootId.KERNELSU_NEXT -> "adb shell su -c 'ksud module enable $MODULE_ID'"
                    RootId.APATCH -> "adb shell su -c 'apd module enable $MODULE_ID'"
                    null -> "adb shell su -c 'rm -f /data/adb/modules/$MODULE_ID/disable'"
                }
            ),
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_module_check_dir),
                "adb shell su -c 'ls -1 /data/adb/modules/$MODULE_ID'"
            )
        )
    )

    private fun rootLost(provider: RootId?): RecoveryPage = RecoveryPage(
        id = RecoveryId.ROOT_LOST_OTA,
        title = UiText.Res(R.string.doctor_recovery_root_lost_title),
        happened = UiText.Res(R.string.doctor_recovery_root_lost_happened),
        onPhone = listOf(
            RecoveryStep(
                UiText.Res(
                    when (provider) {
                        RootId.MAGISK -> R.string.doctor_recovery_root_magisk
                        RootId.KERNELSU, RootId.KERNELSU_NEXT -> R.string.doctor_recovery_root_kernelsu
                        RootId.APATCH -> R.string.doctor_recovery_root_apatch
                        null -> R.string.doctor_recovery_root_unknown
                    }
                )
            ),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_root_recheck))
        ),
        fromComputer = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_root_confirm), "adb shell su -c id"),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_root_pairing_kept))
        )
    )

    private fun privilegedGrantLost(): RecoveryPage = RecoveryPage(
        id = RecoveryId.PRIVILEGED_GRANT_LOST,
        title = UiText.Res(R.string.doctor_recovery_grants_lost_title),
        happened = UiText.Res(R.string.doctor_recovery_grants_lost_happened),
        onPhone = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_grants_open_doctor)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_grants_reinstall)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_grants_reboot))
        ),
        fromComputer = listOf(
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_grants_package_path),
                "adb shell su -c 'pm path com.phonxiety.bridge'"
            ),
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_grants_granted),
                "adb shell dumpsys package com.phonxiety.bridge | grep -i permission"
            )
        )
    )

    private fun daemonUnreachable(): RecoveryPage = RecoveryPage(
        id = RecoveryId.DAEMON_UNREACHABLE,
        title = UiText.Res(R.string.doctor_recovery_daemon_unreachable_title),
        happened = UiText.Res(R.string.doctor_recovery_daemon_unreachable_happened),
        onPhone = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_check_link)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_grant_local)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_recheck_network))
        ),
        fromComputer = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_status), "phonxiety status"),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_link_test), "phonxiety link-test --seconds 5"),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_daemon_restart), "phonxiety stop && phonxiety start")
        )
    )

    private fun pairingRejected(): RecoveryPage = RecoveryPage(
        id = RecoveryId.PAIRING_REJECTED,
        title = UiText.Res(R.string.doctor_recovery_pairing_rejected_title),
        happened = UiText.Res(R.string.doctor_recovery_pairing_rejected_happened),
        onPhone = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_pairing_row)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_pairing_again))
        ),
        fromComputer = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_pairing_new_qr), "phonxiety pair"),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_pairing_versions), "phonxiety status --json")
        )
    )

    private fun linkUpAudioSilent(): RecoveryPage = RecoveryPage(
        id = RecoveryId.LINK_UP_AUDIO_SILENT,
        title = UiText.Res(R.string.doctor_recovery_audio_silent_title),
        happened = UiText.Res(R.string.doctor_recovery_audio_silent_happened),
        onPhone = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_audio_lab)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_audio_service))
        ),
        fromComputer = listOf(
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_audio_hardening),
                "adb shell cmd audio set-enable-hardening throw"
            ),
            RecoveryStep(
                UiText.Res(R.string.doctor_recovery_audio_rules),
                "adb shell dumpsys audio | grep AudioHardening"
            )
        )
    )

    private fun callDropped(): RecoveryPage = RecoveryPage(
        id = RecoveryId.CALL_DROPPED,
        title = UiText.Res(R.string.doctor_recovery_call_dropped_title),
        happened = UiText.Res(R.string.doctor_recovery_call_dropped_happened),
        onPhone = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_call_counters)),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_call_battery))
        ),
        fromComputer = listOf(
            RecoveryStep(UiText.Res(R.string.doctor_recovery_call_last_error), "phonxiety status --json"),
            RecoveryStep(UiText.Res(R.string.doctor_recovery_call_measure), "phonxiety link-test --seconds 5")
        )
    )
}

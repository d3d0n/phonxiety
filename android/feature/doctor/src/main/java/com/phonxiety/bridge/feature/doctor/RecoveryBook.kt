package com.phonxiety.bridge.feature.doctor

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

/** One readable step; [command] is shown in monospace with a copy button. */
data class RecoveryStep(val text: String, val command: String? = null)

/** A guided recovery page: what happened, what to do on the phone, what to do from the computer. */
data class RecoveryPage(
    val id: RecoveryId,
    val title: String,
    val happened: String,
    val onPhone: List<RecoveryStep>,
    val fromComputer: List<RecoveryStep>
)

/**
 * The recovery table, with the provider's own commands filled in. A provider that is
 * not detected gets the flag-file and `adb` forms every manager honours, never a command invented
 * for it.
 */
object RecoveryBook {
    const val MODULE_ID: String = "phonxiety_bridge"

    fun providerName(provider: RootId?): String = provider?.doctorTitle() ?: "your root manager"

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
        title = "The phone boot-loops after a module update",
        happened = "The overlay or the privapp allowlist stopped the system server from starting. " +
            "Every supported root solution can boot with its modules disabled, and no user data is touched.",
        onPhone = listOf(
            RecoveryStep("Enter safe mode for ${providerName(provider)}, which disables every module for the next boot."),
            RecoveryStep(
                when (provider) {
                    RootId.MAGISK -> "Hold volume-down while the boot animation starts; Magisk then boots without modules."

                    RootId.KERNELSU, RootId.KERNELSU_NEXT ->
                        "Press volume-down three times while the boot animation runs; KernelSU disables every module."

                    RootId.APATCH -> "Boot into APatch's rescue from the manager's safe mode; it disables every module."

                    null -> "Use the rescue or safe mode of your root manager; it disables every module."
                }
            ),
            RecoveryStep("Once Android starts, open the manager and delete or disable the Phonxiety module before rebooting again.")
        ),
        fromComputer = listOf(
            RecoveryStep(
                "Remove the module from a root shell:",
                when (provider) {
                    RootId.MAGISK -> "adb shell magisk --remove-modules"
                    RootId.KERNELSU, RootId.KERNELSU_NEXT -> "adb shell su -c 'ksud module disable $MODULE_ID'"
                    RootId.APATCH -> "adb shell su -c 'apd module disable $MODULE_ID'"
                    null -> "adb shell su -c 'ls /data/adb/modules'"
                }
            ),
            RecoveryStep(
                "If adb is not authorised yet, authorise it with the provider's safe mode first; " +
                    "the manager's remove button does the same thing."
            )
        )
    )

    private fun moduleDisabled(provider: RootId?): RecoveryPage = RecoveryPage(
        id = RecoveryId.MODULE_DISABLED,
        title = "The module is disabled",
        happened = "Safe mode or the manager turned the module off. The overlay is not mounted, so the " +
            "privileged grants are absent and calls fail without a clear error.",
        onPhone = listOf(
            RecoveryStep("Open ${providerName(provider)}, open Modules, and enable Phonxiety."),
            RecoveryStep("Reboot: the overlay is mounted at boot, never while the phone runs.")
        ),
        fromComputer = listOf(
            RecoveryStep(
                "Clear the disable flag:",
                when (provider) {
                    RootId.MAGISK -> "adb shell su -c 'rm -f /data/adb/modules/$MODULE_ID/disable'"
                    RootId.KERNELSU, RootId.KERNELSU_NEXT -> "adb shell su -c 'ksud module enable $MODULE_ID'"
                    RootId.APATCH -> "adb shell su -c 'apd module enable $MODULE_ID'"
                    null -> "adb shell su -c 'rm -f /data/adb/modules/$MODULE_ID/disable'"
                }
            ),
            RecoveryStep("Then check the module directory:", "adb shell su -c 'ls -1 /data/adb/modules/$MODULE_ID'")
        )
    )

    private fun rootLost(provider: RootId?): RecoveryPage = RecoveryPage(
        id = RecoveryId.ROOT_LOST_OTA,
        title = "Root is gone after an update",
        happened = "An over-the-air update flashed a stock boot image, so `su` is gone. The pairing lives " +
            "in device-protected storage and survives the update.",
        onPhone = listOf(
            RecoveryStep(
                when (provider) {
                    RootId.MAGISK -> "Patch the new boot image in the Magisk app and flash it with fastboot."

                    RootId.KERNELSU, RootId.KERNELSU_NEXT ->
                        "Install the KernelSU LKM to the inactive slot from the KernelSU app, then reboot."

                    RootId.APATCH -> "Re-patch the boot image in APatch and flash it."

                    null -> "Re-root with the tool you used before; the manager's own flow is the only supported path."
                }
            ),
            RecoveryStep("Open Phonxiety and press Re-check; the module and the pairing are still in place.")
        ),
        fromComputer = listOf(
            RecoveryStep("Confirm root before changing anything else:", "adb shell su -c id"),
            RecoveryStep("The pairing is kept; a lost module is reinstalled from Doctor once root answers.")
        )
    )

    private fun privilegedGrantLost(): RecoveryPage = RecoveryPage(
        id = RecoveryId.PRIVILEGED_GRANT_LOST,
        title = "The privileged grants are missing",
        happened = "The module is enabled but this APK does not run from /system/priv-app, or a permission " +
            "is missing from privapp-permissions-com.phonxiety.bridge.xml. Capture and in-call control then fail " +
            "without a clear error.",
        onPhone = listOf(
            RecoveryStep("Open Doctor: the missing permission is named in the Permissions group."),
            RecoveryStep("Reinstall the module from Doctor or Updates; the APK and its allowlist ship together."),
            RecoveryStep("Reboot, then press Re-check.")
        ),
        fromComputer = listOf(
            RecoveryStep("Where the package actually runs from:", "adb shell su -c 'pm path com.phonxiety.bridge'"),
            RecoveryStep("What the platform granted it:", "adb shell dumpsys package com.phonxiety.bridge | grep -i permission")
        )
    )

    private fun daemonUnreachable(): RecoveryPage = RecoveryPage(
        id = RecoveryId.DAEMON_UNREACHABLE,
        title = "The daemon cannot be reached",
        happened = "The link is down, the daemon is not running, or the path is blocked. Android 17 reports a " +
            "missing local-network grant as a timeout, so the permission comes before any network theory.",
        onPhone = listOf(
            RecoveryStep("Check the Link group: a relay path works but adds latency; offline means no path at all."),
            RecoveryStep("If `Local network` is missing from Permissions, grant it — without it the phone cannot reach a LAN address."),
            RecoveryStep("Re-check after switching between Wi-Fi and mobile data.")
        ),
        fromComputer = listOf(
            RecoveryStep("What the daemon sees:", "phonxiety status"),
            RecoveryStep("Measure the link from the computer side:", "phonxiety link-test --seconds 5"),
            RecoveryStep("Restart the daemon if it is not answering:", "phonxiety stop && phonxiety start")
        )
    )

    private fun pairingRejected(): RecoveryPage = RecoveryPage(
        id = RecoveryId.PAIRING_REJECTED,
        title = "The pairing was rejected",
        happened = "The daemon answered but refused the credentials: the token expired, the certificate " +
            "changed, or the protocol versions differ.",
        onPhone = listOf(
            RecoveryStep("Doctor's pairing row says whether the failure is the token, the certificate or the protocol."),
            RecoveryStep("Open Setup and pair again with a fresh QR from the computer; the stored token is replaced.")
        ),
        fromComputer = listOf(
            RecoveryStep("Print a fresh pairing QR:", "phonxiety pair"),
            RecoveryStep("The daemon's versions, including the app API:", "phonxiety status --json")
        )
    )

    private fun linkUpAudioSilent(): RecoveryPage = RecoveryPage(
        id = RecoveryId.LINK_UP_AUDIO_SILENT,
        title = "The link is up but audio is silent",
        happened = "On Android 17 the audio hardening and the foreground-service type decide whether the bridge " +
            "may touch audio outside a call, and a silent link looks exactly like a connected one.",
        onPhone = listOf(
            RecoveryStep("Run a test call from the Test lab: the call foreground service is the only place capture and playback are allowed."),
            RecoveryStep("Check that the call service is running in the Daemon group; a stopped service means the audio path is gone.")
        ),
        fromComputer = listOf(
            RecoveryStep("Turn the audio hardening into a hard failure so the next attempt names the rule:", "adb shell cmd audio set-enable-hardening throw"),
            RecoveryStep("Read the hardening rules in force:", "adb shell dumpsys audio | grep AudioHardening")
        )
    )

    private fun callDropped(): RecoveryPage = RecoveryPage(
        id = RecoveryId.CALL_DROPPED,
        title = "The call dropped at the phone",
        happened = "The call ended on the phone side. The link counters and the daemon's error say whether the " +
            "link or the audio path went first.",
        onPhone = listOf(
            RecoveryStep("Doctor's link row carries the reconnect count and the last error."),
            RecoveryStep("If it dropped with the screen off, the battery exemption is the first suspect.")
        ),
        fromComputer = listOf(
            RecoveryStep("The last error and the link counters:", "phonxiety status --json"),
            RecoveryStep("A fresh measurement of the path:", "phonxiety link-test --seconds 5")
        )
    )
}

package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.SetupStep
import com.phonxiety.bridge.designsystem.component.SetupStepList
import com.phonxiety.bridge.designsystem.component.SetupStepState
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.designsystem.component.StatusTone
import com.phonxiety.bridge.root.ApatchProvider
import com.phonxiety.bridge.root.DoctorReason
import com.phonxiety.bridge.root.KernelSuProvider
import com.phonxiety.bridge.root.MagiskProvider
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.VerifyReport
import com.phonxiety.bridge.root.WizardState

/** What the wizard shows, plus the pairing rejection that belongs next to the scanner. */
data class OnboardingUiState(
    val wizard: WizardState = WizardState.Checking,
    val pairingError: String? = null
)

/** Every callback the screen needs; the route wires them to the ViewModel and the app. */
data class OnboardingCallbacks(
    val onPrimary: () -> Unit = {},
    val onSecondary: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onPairLink: (String) -> Unit = {},
    val onDismissPairingError: () -> Unit = {},
    val onOpenManager: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenDoctor: () -> Unit = {},
    val onRebootLater: () -> Unit = {},
    val onFinished: () -> Unit = {}
)

/**
 * The one-button setup screen. It renders one [WizardState] and offers at most two actions; the
 * happy path is detect → install → reboot, and every deviation has its own card rather than a
 * generic spinner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier
) {
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var pastedLink by rememberSaveable { mutableStateOf("") }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text(text = "Set up phonxiety", style = MaterialTheme.typography.headlineMedium)
                Text(
                    text = "One button. You grant root once and accept one reboot; everything else is derived.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SetupStepList(steps = state.wizard.toSteps())
            WizardCard(
                state = state,
                callbacks = callbacks,
                onPasteLink = { showPairing = true }
            )
            ActionBar(
                state = state,
                callbacks = callbacks,
                onScanQr = callbacks.onScanQr,
                onPasteLink = { showPairing = true }
            )
            Text(
                text = "Nothing leaves this phone: no analytics and no cloud. The daemon stays on your computer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPairing) {
        ModalBottomSheet(onDismissRequest = { showPairing = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(text = "Pair with your computer", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Scan the QR code the daemon prints, or paste the phonxiety://pair link it gives you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        showPairing = false
                        callbacks.onScanQr()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan QR code")
                }
                OutlinedTextField(
                    value = pastedLink,
                    onValueChange = { pastedLink = it },
                    label = { Text("phonxiety://pair?…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val link = pastedLink.trim()
                        if (link.isNotEmpty()) {
                            showPairing = false
                            pastedLink = ""
                            callbacks.onPairLink(link)
                        }
                    },
                    enabled = pastedLink.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Pair with this link")
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    onScanQr: () -> Unit,
    onPasteLink: () -> Unit
) {
    val wizard = state.wizard
    val deniedManager = (wizard as? WizardState.RootDenied)?.provider?.let { provider ->
        provider.managerPackage ?: provider.id.managerPackage()
    }
    val busy = wizard is WizardState.Checking ||
        wizard is WizardState.Installing ||
        wizard is WizardState.FirstLinkTest ||
        (wizard is WizardState.Verify && wizard.report == null)
    val primaryLabel = when (wizard) {
        WizardState.Checking -> "Checking…"

        is WizardState.NoRoot -> "Recheck after rooting"

        is WizardState.RootDenied -> if (deniedManager != null) {
            "Open ${wizard.provider?.id?.managerName() ?: "root manager"}"
        } else {
            "Recheck root permission"
        }

        is WizardState.ProviderPrep -> "Install metamodule"

        is WizardState.ReadyToInstall -> "Install module"

        is WizardState.Installing -> "Installing…"

        is WizardState.InstallFailed -> "Retry"

        is WizardState.RebootPending -> "Reboot now"

        is WizardState.Verify -> "Verify now"

        is WizardState.NeedsRuntime -> "Grant permissions"

        is WizardState.NeedsPairing -> "Scan QR code"

        is WizardState.FirstLinkTest -> "Testing the link…"

        WizardState.Ready -> "Open dashboard"

        is WizardState.Doctor -> "Recheck"

        is WizardState.Indeterminate -> "Recheck"

        is WizardState.Conflict -> "Recheck"
    }
    val primary: () -> Unit = when (wizard) {
        is WizardState.NeedsPairing -> onScanQr

        is WizardState.RootDenied -> if (deniedManager != null) {
            { callbacks.onOpenManager(deniedManager) }
        } else {
            callbacks.onPrimary
        }

        WizardState.Ready -> callbacks.onFinished

        WizardState.Checking, is WizardState.Installing, is WizardState.FirstLinkTest -> ({})

        else -> callbacks.onPrimary
    }
    PrimaryActionButton(
        label = primaryLabel,
        onClick = primary,
        enabled = !busy,
        busy = busy
    )
    when (wizard) {
        is WizardState.RebootPending -> SecondaryText("Later", callbacks.onRebootLater)
        is WizardState.RootDenied -> SecondaryText("Recheck now", callbacks.onPrimary)
        is WizardState.ProviderPrep -> SecondaryText("Install later", callbacks.onSecondary)
        is WizardState.NeedsRuntime -> SecondaryText("Allow battery optimisation exemption", callbacks.onSecondary)
        is WizardState.NeedsPairing -> SecondaryText("Paste the pairing link", onPasteLink)
        is WizardState.Doctor -> SecondaryText("Open Doctor", callbacks.onOpenDoctor)
        else -> Unit
    }
}

@Composable
private fun SecondaryText(label: String, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun WizardCard(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    onPasteLink: () -> Unit
) {
    val wizard = state.wizard
    SectionCard(
        title = wizard.title(),
        subtitle = wizard.subtitle(),
        status = { StatusPill(wizard.pillLabel(), wizard.pillTone(), pulse = wizard.isTransitional()) }
    ) {
        when (wizard) {
            WizardState.Checking -> LoadingState("Reading /data/adb, su, package flags and permissions…")

            is WizardState.NoRoot -> {
                Text(
                    text = "This phone has no root. The app still runs in diagnose-only mode; installing the module needs one of these managers.",
                    style = MaterialTheme.typography.bodyMedium
                )
                PROVIDER_GUIDES.forEach { (label, url) ->
                    TextButton(onClick = { callbacks.onOpenUrl(url) }) { Text(label) }
                }
            }

            is WizardState.RootDenied -> {
                val provider = wizard.provider
                ErrorCard(
                    title = "Root is not granted yet",
                    message = provider?.id?.grantInstruction()
                        ?: "Open the root manager's Superuser list, grant Phonxiety, then come back. The app probes again by itself.",
                    output = wizard.output
                )
                Text(
                    text = "The app probes again when it returns to the foreground; once su works it continues to the install step without another tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WizardState.ProviderPrep -> {
                Text(
                    text = "${wizard.provider.id.managerName()} mounts module system/ directories through a metamodule. Without one, an enabled module is silently not mounted and the privileged permissions never appear.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Install it here: the pinned meta-overlayfs 1.3.1 archive is downloaded, its SHA-256 checked, then installed together with Phonxiety — one reboot applies both.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyValueRow(label = "Provider", value = "${wizard.provider.id.managerName()} ${wizard.provider.version}")
            }

            is WizardState.ReadyToInstall -> ReadyToInstallContent(wizard)

            is WizardState.Installing -> {
                LoadingState("${wizard.provider.id.managerName()} is installing the module…")
                Text(
                    text = "The install journal is on disk, so a process death or a reboot resumes here instead of starting over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WizardState.InstallFailed -> {
                ErrorCard(
                    title = "${wizard.operation.name.lowercase().replace('_', ' ')} failed",
                    message = "The provider's own output is below. Retrying is safe: a module install is idempotent.",
                    output = wizard.output(),
                    actionLabel = "Retry",
                    onAction = callbacks.onPrimary
                )
            }

            is WizardState.RebootPending -> {
                Text(
                    text = "One reboot, then everything else is automatic.",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "The module's privileged allowlist and the priv-app APK only appear after a boot overlay. The wizard resumes here by itself when the phone comes back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                KeyValueRow(label = "Reason", value = wizard.reason.humanLabel())
            }

            is WizardState.Verify -> VerifyContent(wizard)

            is WizardState.NeedsRuntime -> {
                Text(
                    text = "Each permission has one job. Denied ones stay visible on the dashboard instead of being hidden.",
                    style = MaterialTheme.typography.bodyMedium
                )
                wizard.missing.forEach { permission -> PermissionRow(permission) }
            }

            is WizardState.NeedsPairing -> {
                Text(
                    text = "On your computer, run the pairing command and show the QR code. The link contains the daemon's endpoint, certificate and app token; nothing is typed by hand.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.pairingError != null) {
                    ErrorCard(
                        title = "That pairing was rejected",
                        message = state.pairingError,
                        actionLabel = "Dismiss",
                        onAction = callbacks.onDismissPairingError
                    )
                }
                TextButton(onClick = onPasteLink) { Text("Paste the link instead") }
            }

            is WizardState.FirstLinkTest -> {
                LoadingState("Probing the link for 5 s…")
                wizard.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            WizardState.Ready -> EmptyState(
                title = "Everything is ready",
                message = "The service is running and the dashboard shows the numbers behind the connection."
            )

            is WizardState.Doctor -> {
                ErrorCard(
                    title = wizard.reason.humanLabel(),
                    message = "The wizard stopped instead of pretending everything is fine. Recheck after fixing, or open Doctor for the full self-check.",
                    output = wizard.output(),
                    actionLabel = "Open Doctor",
                    onAction = callbacks.onOpenDoctor
                )
            }

            is WizardState.Indeterminate -> {
                ErrorCard(
                    title = "Could not tell",
                    message = wizard.message,
                    output = wizard.output,
                    actionLabel = "Recheck",
                    onAction = callbacks.onPrimary
                )
            }

            is WizardState.Conflict -> {
                ErrorCard(
                    title = "More than one root provider",
                    message = "Installing would be a guess, so the wizard refuses until one provider remains. Present: " +
                        wizard.providers.joinToString { "${it.id.managerName()} ${it.version}" },
                    actionLabel = "Recheck",
                    onAction = callbacks.onPrimary
                )
            }
        }
    }
}

@Composable
private fun ReadyToInstallContent(state: WizardState.ReadyToInstall) {
    Text(
        text = "Exactly this will happen:",
        style = MaterialTheme.typography.titleSmall
    )
    KeyValueRow(label = "Module", value = "${ModuleController.MODULE_NAME} (${ModuleController.MODULE_ID})")
    KeyValueRow(label = "Provider", value = "${state.provider.id.managerName()} ${state.provider.version}")
    KeyValueRow(label = "Privileged grants", value = "5 permissions")
    PRIVILEGED_GRANTS.forEach { grant ->
        KeyValueRow(label = grant.first, value = grant.second)
    }
    HorizontalDivider()
    Text(
        text = "A reboot follows the install. Rollback is removal of the module: the provider restores the stock overlay on the next boot.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun VerifyContent(state: WizardState.Verify) {
    val report = state.report
    if (report == null) {
        LoadingState("Checking the module, the overlay and the privileged grants…")
        return
    }
    report.checks.forEach { (check, outcome) ->
        KeyValueRow(label = check.label(), value = outcome.label())
    }
    if (!report.passed) {
        Text(
            text = "Failing checks are named above; the Doctor walks the recovery for each one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun PermissionRow(permission: RuntimePermission) {
    Column {
        Text(text = permission.humanLabel(), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = permission.reason(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Wizard state → labels
// ---------------------------------------------------------------------------------------------

private val PROVIDER_GUIDES = listOf(
    "Magisk install guide" to "https://topjohnwu.github.io/Magisk/install.html",
    "KernelSU guide" to "https://kernelsu.org/guide/installation.html",
    "KernelSU-Next" to "https://github.com/KernelSU-Next/KernelSU-Next",
    "APatch install" to "https://apatch.dev/install.html"
)

private val PRIVILEGED_GRANTS = listOf(
    "Audio capture" to "CAPTURE_AUDIO_OUTPUT",
    "Call state" to "MODIFY_PHONE_STATE",
    "In-call control" to "CONTROL_INCALL_EXPERIENCE",
    "Self updates" to "INSTALL_SELF_UPDATES",
    "Reboot" to "REBOOT"
)

internal fun RootId.managerName(): String = when (this) {
    RootId.MAGISK -> "Magisk"
    RootId.KERNELSU -> "KernelSU"
    RootId.KERNELSU_NEXT -> "KernelSU-Next"
    RootId.APATCH -> "APatch"
}

/** The manager app to launch; it is also the only place a grant can be given. */
internal fun RootId.managerPackage(): String = when (this) {
    RootId.MAGISK -> MagiskProvider.MANAGER_PACKAGE
    RootId.KERNELSU -> KernelSuProvider.KERNELSU_MANAGER_PACKAGE
    RootId.KERNELSU_NEXT -> KernelSuProvider.KERNELSU_NEXT_MANAGER_PACKAGE
    RootId.APATCH -> ApatchProvider.MANAGER_PACKAGE
}

/** Where the grant lives per manager, in the manager's own vocabulary. */
internal fun RootId.grantInstruction(): String = when (this) {
    RootId.MAGISK ->
        "Open Magisk → Superuser → + → Phonxiety and enable it. Magisk 31.1 grants root ahead of time; there is no prompt to answer."

    RootId.KERNELSU -> "Open KernelSU → Superuser → Phonxiety → allow."

    RootId.KERNELSU_NEXT -> "Open KernelSU-Next → Superuser → Phonxiety → allow."

    RootId.APATCH -> "Open APatch → Superuser → Phonxiety → allow."
}

internal fun WizardState.title(): String = when (this) {
    WizardState.Checking -> "Checking this phone"
    is WizardState.NoRoot -> "No root on this phone"
    is WizardState.RootDenied -> "Root is not granted yet"
    is WizardState.ProviderPrep -> "The provider needs a metamodule first"
    is WizardState.ReadyToInstall -> "Ready to install"
    is WizardState.Installing -> "Installing the module"
    is WizardState.InstallFailed -> "The install did not finish"
    is WizardState.RebootPending -> "Reboot pending"
    is WizardState.Verify -> "Verifying after the boot"
    is WizardState.NeedsRuntime -> "Runtime permissions"
    is WizardState.NeedsPairing -> "Pair with your computer"
    is WizardState.FirstLinkTest -> "First link test"
    WizardState.Ready -> "Ready"
    is WizardState.Doctor -> "Needs a repair"
    is WizardState.Indeterminate -> "Could not tell"
    is WizardState.Conflict -> "Two root providers found"
}

internal fun WizardState.subtitle(): String? = when (this) {
    is WizardState.RootDenied -> provider?.let { "${it.id.managerName()} ${it.version}" }
    is WizardState.ProviderPrep -> provider.id.managerName()
    is WizardState.ReadyToInstall -> "${provider.id.managerName()} ${provider.version}"
    is WizardState.RebootPending -> provider.id.managerName()
    is WizardState.NeedsPairing -> provider?.id?.managerName()
    is WizardState.Doctor -> provider?.id?.managerName()
    else -> null
}

internal fun WizardState.pillLabel(): String = when (this) {
    WizardState.Checking -> "Checking"
    is WizardState.NoRoot -> "No root"
    is WizardState.RootDenied -> "Denied"
    is WizardState.ProviderPrep -> "Prep"
    is WizardState.ReadyToInstall -> "Ready"
    is WizardState.Installing -> "Installing"
    is WizardState.InstallFailed -> "Failed"
    is WizardState.RebootPending -> "Reboot"
    is WizardState.Verify -> "Verify"
    is WizardState.NeedsRuntime -> "${missing.size} missing"
    is WizardState.NeedsPairing -> "Unpaired"
    is WizardState.FirstLinkTest -> "Testing"
    WizardState.Ready -> "Ready"
    is WizardState.Doctor -> "Doctor"
    is WizardState.Indeterminate -> "Unknown"
    is WizardState.Conflict -> "Conflict"
}

internal fun WizardState.pillTone(): StatusTone = when (this) {
    WizardState.Ready, is WizardState.ReadyToInstall, is WizardState.FirstLinkTest -> StatusTone.POSITIVE

    is WizardState.InstallFailed, is WizardState.NoRoot, is WizardState.RootDenied, is WizardState.Doctor,
    is WizardState.Conflict -> StatusTone.NEGATIVE

    is WizardState.Indeterminate -> StatusTone.NEUTRAL

    is WizardState.NeedsRuntime, is WizardState.Verify -> StatusTone.WARNING

    else -> StatusTone.ACCENT
}

internal fun WizardState.isTransitional(): Boolean = this is WizardState.Checking ||
    this is WizardState.Installing ||
    this is WizardState.FirstLinkTest

internal fun WizardState.toSteps(): List<SetupStep> {
    val titles = listOf("Detect root", "Install module", "Reboot", "Permissions", "Pair with your computer", "Link test")
    val detectDetail = (this as? WizardState.RebootPending)?.provider?.id?.managerName()
    val provider = when (this) {
        is WizardState.ReadyToInstall -> this.provider
        is WizardState.Installing -> this.provider
        is WizardState.InstallFailed -> this.provider
        is WizardState.RebootPending -> this.provider
        is WizardState.ProviderPrep -> this.provider
        is WizardState.RootDenied -> this.provider
        else -> null
    }
    val moduleDetail = (this as? WizardState.InstallFailed)?.let { "failed" }
    val doneBefore: Int
    val active: Int?
    val failed: Int?
    when (this) {
        WizardState.Checking -> {
            doneBefore = 0
            active = 0
            failed = null
        }

        is WizardState.NoRoot, is WizardState.Indeterminate, is WizardState.Conflict -> {
            doneBefore = 0
            active = null
            failed = 0
        }

        is WizardState.RootDenied -> {
            doneBefore = 0
            active = 0
            failed = null
        }

        is WizardState.ProviderPrep, is WizardState.ReadyToInstall, is WizardState.Installing -> {
            doneBefore = 1
            active = 1
            failed = null
        }

        is WizardState.InstallFailed -> {
            doneBefore = 1
            active = null
            failed = 1
        }

        is WizardState.RebootPending -> {
            doneBefore = 2
            active = 2
            failed = null
        }

        is WizardState.Verify, is WizardState.NeedsRuntime -> {
            doneBefore = 3
            active = 3
            failed = null
        }

        is WizardState.NeedsPairing -> {
            doneBefore = 4
            active = 4
            failed = null
        }

        is WizardState.FirstLinkTest -> {
            doneBefore = 5
            active = 5
            failed = null
        }

        WizardState.Ready -> {
            doneBefore = 6
            active = null
            failed = null
        }

        is WizardState.Doctor -> {
            val step = when (reason) {
                DoctorReason.VERIFY_FAILED -> 3
                DoctorReason.MODULE_DISABLED, DoctorReason.MODULE_PENDING_REMOVAL -> 1
                DoctorReason.LINK_TEST_FAILED -> 5
            }
            doneBefore = step
            active = null
            failed = step
        }
    }
    return titles.mapIndexed { index, title ->
        SetupStep(
            title = title,
            state = when {
                failed == index -> SetupStepState.FAILED
                doneBefore > index -> SetupStepState.DONE
                active == index -> SetupStepState.ACTIVE
                else -> SetupStepState.PENDING
            },
            detail = when {
                index == 0 && failed != 0 -> detectDetail ?: provider?.let { "${it.id.managerName()} ${it.version}" }
                index == 1 && moduleDetail != null -> moduleDetail
                index == 1 && doneBefore > 1 -> "installed"
                index == 4 && doneBefore > 4 -> "paired"
                else -> null
            }
        )
    }
}

private fun ProviderResult.output(): String = tail(lines = 20).joinToString("\n").ifEmpty { "the provider printed nothing" }

private fun WizardState.InstallFailed.output(): String = result.output()

private fun WizardState.Doctor.output(): String {
    val reportText = report?.let { report ->
        report.checks.entries.joinToString("\n") { (check, outcome) -> "${check.label()}: ${outcome.label()}" }
    }
    return listOfNotNull(detail, reportText).joinToString("\n").ifEmpty { "no captured output" }
}

internal fun VerifyCheck.label(): String = when (this) {
    VerifyCheck.MODULE_PRESENT -> "Module directory"
    VerifyCheck.MODULE_ENABLED -> "Module enabled"
    VerifyCheck.NO_DISABLE_FLAG -> "No disable flag"
    VerifyCheck.NO_REMOVE_FLAG -> "No remove flag"
    VerifyCheck.PACKAGE_IS_SYSTEM -> "Package is a system app"
    VerifyCheck.APK_IN_PRIV_APP -> "APK under /system/priv-app"
    VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED -> "Privileged grants"
}

private fun VerifyOutcome.label(): String = when (this) {
    VerifyOutcome.PASS -> "pass"
    VerifyOutcome.FAIL -> "fail"
    VerifyOutcome.UNKNOWN -> "unknown"
}

private fun RebootReason.humanLabel(): String = when (this) {
    RebootReason.MODULE_INSTALL -> "module install"
    RebootReason.MODULE_UPDATE -> "module update"
    RebootReason.MODULE_ROLLBACK -> "module rollback"
    RebootReason.USER_REQUEST -> "user request"
}

private fun DoctorReason.humanLabel(): String = when (this) {
    DoctorReason.VERIFY_FAILED -> "A post-boot check failed"
    DoctorReason.MODULE_DISABLED -> "The module is disabled"
    DoctorReason.MODULE_PENDING_REMOVAL -> "The module is queued for removal"
    DoctorReason.LINK_TEST_FAILED -> "The first link test failed"
}

internal fun RuntimePermission.humanLabel(): String = when (this) {
    RuntimePermission.RECORD_AUDIO -> "Microphone"
    RuntimePermission.CALL_PHONE -> "Phone calls"
    RuntimePermission.READ_PHONE_STATE -> "Phone state"
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> "Audio settings"
    RuntimePermission.POST_NOTIFICATIONS -> "Notifications"
    RuntimePermission.ACCESS_LOCAL_NETWORK -> "Local network"
}

internal fun RuntimePermission.reason(): String = when (this) {
    RuntimePermission.RECORD_AUDIO -> "The agent's voice and yours travel through this app."
    RuntimePermission.CALL_PHONE -> "The agent places and ends the call."
    RuntimePermission.READ_PHONE_STATE -> "To know when the call is ringing, active or gone."
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> "To route call audio between the handset and the link."
    RuntimePermission.POST_NOTIFICATIONS -> "The foreground service must stay visible."
    RuntimePermission.ACCESS_LOCAL_NETWORK -> "Without it, the same-Wi-Fi path times out silently and looks like a network fault."
}

// ---------------------------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------------------------

@Preview(name = "No root", showBackground = true)
@Composable
private fun NoRootPreview() {
    OnboardingThemePreview(WizardState.NoRoot(listOf(OnboardingSamples.magisk)))
}

@Preview(name = "Ready to install", showBackground = true)
@Composable
private fun ReadyPreview() {
    OnboardingThemePreview(WizardState.ReadyToInstall(OnboardingSamples.magisk))
}

@Preview(name = "Needs permissions", showBackground = true)
@Composable
private fun NeedsRuntimePreview() {
    OnboardingThemePreview(
        WizardState.NeedsRuntime(
            OnboardingSamples.magisk,
            listOf(RuntimePermission.RECORD_AUDIO, RuntimePermission.ACCESS_LOCAL_NETWORK)
        )
    )
}

@Composable
private fun OnboardingThemePreview(wizard: WizardState) {
    PhonxietyTheme(dynamicColor = false) {
        OnboardingScreen(state = OnboardingUiState(wizard), callbacks = OnboardingCallbacks())
    }
}

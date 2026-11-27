package com.phonxiety.bridge.feature.onboarding

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.BrandLockup
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
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
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.DoctorReason
import com.phonxiety.bridge.root.IndeterminateReason
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.KernelSuProvider
import com.phonxiety.bridge.root.MagiskProvider
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.ProviderResult
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RootId
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.WizardState

/** What the wizard shows, plus the pairing rejection that belongs next to the scanner. */
data class OnboardingUiState(
    val wizard: WizardState = WizardState.Checking,
    /** The repository's rejection reason; the screen turns it into words and keeps it as evidence. */
    val pairingError: String? = null,
    /**
     * A stored pairing the repository can actually read. The paste sheet closes and clears only on
     * this proof, never on the wizard merely leaving "Not paired": a resume probe passes through
     * "Checking" while it re-reads the device, and the typed link has to survive that.
     */
    val pairingConfirmed: Boolean = false,
    /**
     * Providers that did not answer `su` but left files or a manager app behind. Reported so a
     * migration makes sense; this app never deletes them.
     */
    val leftovers: List<DetectedProvider> = emptyList(),
    /** The metamodule download/install step while it runs. */
    val installPhase: SetupInstallPhase? = null
)

/** Every callback the screen needs; the route wires them to the ViewModel and the app. */
data class OnboardingCallbacks(
    val onPrimary: () -> Unit = {},
    val onSecondary: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    /** Opens the in-app CameraX scanner on purpose, for phones without the Google scanner. */
    val onCameraScan: () -> Unit = {},
    val onPairLink: (String) -> Unit = {},
    val onDismissPairingError: () -> Unit = {},
    val onOpenManager: (String) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenDoctor: () -> Unit = {},
    val onRebootLater: () -> Unit = {},
    val onFinished: () -> Unit = {}
)

/**
 * The one-button setup screen. The answer comes first: the current state and its one action sit
 * above the six-step plan, which is a one-line summary until it is opened. The action bar is pinned
 * to the bottom, so no state ever pushes its own action below the fold.
 *
 * [pairingSheetVisible] is controlled by the host when the scanner needs to hand the person over to
 * the paste field; when it is null the screen owns the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    modifier: Modifier = Modifier,
    pairingSheetVisible: Boolean? = null,
    onPairingSheetVisibleChange: (Boolean) -> Unit = {}
) {
    var ownedSheet by rememberSaveable { mutableStateOf(false) }
    var pastedLink by rememberSaveable { mutableStateOf("") }
    var stepsExpanded by rememberSaveable { mutableStateOf(false) }
    val sheetVisible = pairingSheetVisible ?: ownedSheet

    fun setSheetVisible(visible: Boolean) {
        if (pairingSheetVisible == null) ownedSheet = visible
        onPairingSheetVisibleChange(visible)
    }

    // Only a confirmed pairing closes and clears the sheet that produced it. Leaving "Not paired"
    // is not proof: a resume probe shows "Checking" for a moment and then settles back, and the
    // link someone pasted must still be there when they return from another app.
    LaunchedEffect(state.pairingConfirmed) {
        if (state.pairingConfirmed && sheetVisible) {
            setSheetVisible(false)
            pastedLink = ""
        }
    }

    // The screen owns its background in tests and in the app: without this, text outside a Surface
    // inherits the platform's black instead of the scheme's onSurface.
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    SetupHeader()
                    // A rejection is inline next to the field at "Not paired". A deep link that
                    // arrives while the wizard is at another step has no such place, so the note
                    // stays here in the person's words instead of the feedback being dropped.
                    state.pairingError?.takeIf { state.wizard !is WizardState.NeedsPairing }?.let { error ->
                        PairingRejectionNote(error, callbacks.onDismissPairingError)
                    }
                    WizardCard(state = state, callbacks = callbacks)
                    StepsSection(
                        wizard = state.wizard,
                        expanded = stepsExpanded,
                        onToggle = { stepsExpanded = !stepsExpanded }
                    )
                    state.leftovers.forEach { leftover -> LeftoverRow(leftover) }
                    Text(
                        text = stringResource(R.string.onboarding_privacy_footer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ActionBar(
                    state = state,
                    callbacks = callbacks,
                    onPasteLink = { setSheetVisible(true) }
                )
            }
        }
    }

    if (sheetVisible) {
        PairingSheet(
            pastedLink = pastedLink,
            onPastedLinkChange = { pastedLink = it },
            pairingError = state.pairingError,
            callbacks = callbacks,
            onDismiss = { setSheetVisible(false) },
            onScanQr = {
                setSheetVisible(false)
                callbacks.onScanQr()
            }
        )
    }
}

/**
 * The pairing rejection when no pairing surface is on screen. It carries the same localized words as
 * the field's supporting text and stays until dismissed, so a deep link opened at another step is
 * not silently dropped.
 */
@Composable
private fun PairingRejectionNote(error: String, onDismiss: () -> Unit) {
    ErrorCard(
        title = stringResource(R.string.onboarding_pair_rejected_title),
        message = pairingRejectionText(error).asString(),
        dismissLabel = stringResource(R.string.onboarding_action_dismiss),
        onDismiss = onDismiss
    )
}

@Composable
private fun SetupHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BrandLockup(height = 24.dp)
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepsSection(wizard: WizardState, expanded: Boolean, onToggle: () -> Unit) {
    val steps = wizard.toSteps()
    val completed = steps.count { it.state == SetupStepState.DONE }
    val currentIndex = steps.indexOfFirst { it.state == SetupStepState.ACTIVE }
        .takeIf { it >= 0 }
        ?: steps.indexOfFirst { it.state == SetupStepState.FAILED }.takeIf { it >= 0 }
        ?: completed.coerceAtMost(steps.lastIndex)
    val summary = if (completed == steps.size) {
        pluralStringResource(R.plurals.onboarding_steps_done, steps.size, steps.size)
    } else {
        stringResource(
            R.string.onboarding_steps_summary,
            (currentIndex + 1).coerceAtMost(steps.size),
            steps.size,
            steps[currentIndex.coerceIn(0, steps.lastIndex)].title
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The toggle drops under the summary when the fonts are large instead of squeezing it.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onToggle) {
                Text(
                    stringResource(
                        if (expanded) {
                            R.string.onboarding_steps_hide
                        } else {
                            R.string.onboarding_steps_show
                        }
                    )
                )
            }
        }
        // The plan is history, not the answer: it opens on request and reads as states, not actions.
        if (expanded) {
            SetupStepList(steps = steps)
        }
    }
}

@Composable
private fun ActionBar(
    state: OnboardingUiState,
    callbacks: OnboardingCallbacks,
    onPasteLink: () -> Unit
) {
    val wizard = state.wizard
    if (wizard.isTransitional()) return
    val deniedManager = (wizard as? WizardState.RootDenied)?.provider?.let { provider ->
        provider.managerPackage ?: provider.id.managerPackage()
    }
    val primaryLabel = when (wizard) {
        is WizardState.NoRoot -> stringResource(R.string.onboarding_action_check_again)

        is WizardState.RootDenied -> if (deniedManager != null) {
            stringResource(R.string.onboarding_action_open_manager)
        } else {
            stringResource(R.string.onboarding_action_check_again)
        }

        is WizardState.ProviderPrep -> stringResource(R.string.onboarding_action_install_metamodule)

        is WizardState.ReadyToInstall -> if (wizard.update) {
            stringResource(R.string.onboarding_action_update_module)
        } else {
            stringResource(R.string.onboarding_action_install_module)
        }

        is WizardState.InstallFailed -> stringResource(R.string.onboarding_action_retry)

        is WizardState.RebootPending -> stringResource(R.string.onboarding_action_reboot_now)

        is WizardState.Verify -> stringResource(R.string.onboarding_action_verify_now)

        is WizardState.NeedsRuntime -> stringResource(R.string.onboarding_action_grant_permissions)

        is WizardState.NeedsPairing -> stringResource(R.string.onboarding_action_scan)

        WizardState.Ready -> stringResource(R.string.onboarding_action_open_dashboard)

        is WizardState.Doctor, is WizardState.Indeterminate, is WizardState.Conflict ->
            stringResource(R.string.onboarding_action_check_again)

        WizardState.Checking, is WizardState.Installing, is WizardState.FirstLinkTest -> ""
    }
    val primary: (() -> Unit) = when (wizard) {
        is WizardState.NeedsPairing -> callbacks.onScanQr

        is WizardState.RootDenied -> if (deniedManager != null) {
            { callbacks.onOpenManager(deniedManager) }
        } else {
            callbacks.onPrimary
        }

        WizardState.Ready -> callbacks.onFinished

        else -> callbacks.onPrimary
    }
    val secondary: Pair<String, () -> Unit>? = when (wizard) {
        is WizardState.RebootPending ->
            stringResource(R.string.onboarding_action_later) to callbacks.onRebootLater

        is WizardState.RootDenied ->
            stringResource(R.string.onboarding_action_check_again) to callbacks.onPrimary

        is WizardState.ProviderPrep ->
            stringResource(R.string.onboarding_action_install_later) to callbacks.onSecondary

        is WizardState.NeedsRuntime ->
            stringResource(R.string.onboarding_action_allow_background) to callbacks.onSecondary

        is WizardState.NeedsPairing ->
            stringResource(R.string.onboarding_action_paste_link) to onPasteLink

        is WizardState.Verify ->
            if (wizard.report?.passed == false) {
                stringResource(R.string.onboarding_action_open_doctor) to callbacks.onOpenDoctor
            } else {
                null
            }

        is WizardState.Doctor ->
            stringResource(R.string.onboarding_action_open_doctor) to callbacks.onOpenDoctor

        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PrimaryActionButton(label = primaryLabel, onClick = primary)
                if (secondary != null) {
                    TextButton(onClick = secondary.second) { Text(secondary.first) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingSheet(
    pastedLink: String,
    onPastedLinkChange: (String) -> Unit,
    pairingError: String?,
    callbacks: OnboardingCallbacks,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    val title = stringResource(R.string.onboarding_pair_sheet_title)
    var submitting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairingError) {
        if (pairingError != null) submitting = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.onboarding_pair_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_pair_scan))
            }
            TextButton(
                onClick = {
                    onDismiss()
                    callbacks.onCameraScan()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_pair_camera))
            }
            OutlinedTextField(
                value = pastedLink,
                onValueChange = { value ->
                    onPastedLinkChange(value)
                    submitting = false
                    if (pairingError != null) callbacks.onDismissPairingError()
                },
                label = { Text(stringResource(R.string.onboarding_pair_field_label)) },
                placeholder = { Text(stringResource(R.string.onboarding_pair_field_placeholder)) },
                supportingText = {
                    Text(
                        text = if (pairingError != null) {
                            pairingRejectionText(pairingError).asString()
                        } else {
                            stringResource(R.string.onboarding_pair_field_support)
                        }
                    )
                },
                isError = pairingError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryActionButton(
                label = stringResource(R.string.onboarding_pair_commit),
                onClick = {
                    submitting = true
                    callbacks.onPairLink(pastedLink.trim())
                },
                enabled = pastedLink.isNotBlank() && !submitting,
                supportingText = if (submitting) {
                    stringResource(R.string.onboarding_pair_checking)
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun WizardCard(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
    val wizard = state.wizard
    SectionCard(
        title = wizard.title(),
        subtitle = wizard.subtitle(),
        status = {
            StatusPill(
                label = wizard.pillLabel(),
                tone = wizard.pillTone(),
                pulse = wizard.isTransitional()
            )
        }
    ) {
        when (wizard) {
            WizardState.Checking ->
                LoadingState(stringResource(R.string.onboarding_checking_body))

            is WizardState.NoRoot -> {
                Text(
                    text = stringResource(R.string.onboarding_no_root_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                PROVIDER_GUIDES.forEach { guide ->
                    TextButton(onClick = { callbacks.onOpenUrl(guide.url) }) {
                        Text(stringResource(guide.label))
                    }
                }
            }

            is WizardState.RootDenied -> {
                val pending = listOfNotNull(wizard.provider) + wizard.coProviders
                val grantLines = pending.map { provider ->
                    stringResource(
                        R.string.onboarding_grant_line,
                        provider.id.managerName(),
                        provider.id.grantInstruction()
                    )
                }
                ErrorCard(
                    title = if (pending.isEmpty()) {
                        stringResource(R.string.onboarding_grant_title_any)
                    } else {
                        stringResource(
                            R.string.onboarding_grant_title,
                            pending.first().id.managerName()
                        )
                    },
                    message = if (pending.isEmpty()) {
                        stringResource(R.string.onboarding_grant_any_manager)
                    } else {
                        grantLines.joinToString("\n\n")
                    },
                    output = wizard.output
                )
                Text(
                    text = stringResource(R.string.onboarding_grant_resume_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WizardState.ProviderPrep -> {
                Text(
                    text = stringResource(
                        R.string.onboarding_prep_body,
                        wizard.provider.id.managerName()
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.onboarding_prep_install, METAMODULE_VERSION),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WizardState.ReadyToInstall -> ReadyToInstallContent(wizard)

            is WizardState.Installing -> {
                if (wizard.operation == InstallOperation.PREPARE_METAMODULE) {
                    MetamoduleProgress(state.installPhase)
                } else {
                    LoadingState(stringResource(R.string.onboarding_installing_module))
                    Text(
                        text = stringResource(R.string.onboarding_installing_journal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is WizardState.InstallFailed -> {
                ErrorCard(
                    title = stringResource(installFailureTitle(wizard.operation)),
                    message = stringResource(
                        if (wizard.operation == InstallOperation.PREPARE_METAMODULE) {
                            R.string.onboarding_failed_metamodule_message
                        } else {
                            R.string.onboarding_failed_module_message
                        }
                    ),
                    output = wizard.output()
                )
            }

            is WizardState.RebootPending -> RebootContent(wizard)

            is WizardState.Verify -> VerifyContent(wizard)

            is WizardState.NeedsRuntime -> {
                Text(
                    text = stringResource(R.string.onboarding_runtime_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                wizard.missing.forEach { permission -> PermissionRow(permission) }
                Text(
                    text = stringResource(R.string.onboarding_runtime_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            is WizardState.NeedsPairing -> {
                Text(
                    text = stringResource(R.string.onboarding_pair_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.onboarding_pair_command),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
                Text(
                    text = stringResource(R.string.onboarding_pair_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.pairingError != null) {
                    ErrorCard(
                        title = stringResource(R.string.onboarding_pair_rejected_title),
                        message = pairingRejectionText(state.pairingError).asString(),
                        output = state.pairingError,
                        dismissLabel = stringResource(R.string.onboarding_action_dismiss),
                        onDismiss = callbacks.onDismissPairingError
                    )
                }
            }

            is WizardState.FirstLinkTest -> {
                LoadingState(stringResource(R.string.onboarding_link_test_body))
                wizard.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            WizardState.Ready -> EmptyState(
                title = stringResource(R.string.onboarding_ready_title),
                message = stringResource(R.string.onboarding_ready_body),
                icon = Icons.Filled.Check
            )

            is WizardState.Doctor -> {
                ErrorCard(
                    title = stringResource(doctorTitle(wizard.reason)),
                    message = stringResource(doctorMessage(wizard.reason)),
                    output = wizard.output()
                )
            }

            is WizardState.Indeterminate -> {
                ErrorCard(
                    title = stringResource(unknownTitle(wizard.reason)),
                    message = stringResource(unknownMessage(wizard.reason)),
                    output = listOfNotNull(wizard.detail, wizard.output).joinToString("\n")
                        .ifEmpty { null }
                )
            }

            is WizardState.Conflict -> {
                ErrorCard(
                    title = stringResource(R.string.onboarding_conflict_title),
                    message = stringResource(
                        R.string.onboarding_conflict_message,
                        wizard.providers.joinToString { it.providerLabel() }
                    )
                )
                Text(
                    text = stringResource(R.string.onboarding_conflict_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReadyToInstallContent(state: WizardState.ReadyToInstall) {
    Text(
        text = stringResource(R.string.onboarding_changes_heading),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { heading() }
    )
    KeyValueRow(
        label = stringResource(R.string.onboarding_row_module),
        value = ModuleController.MODULE_NAME
    )
    KeyValueRow(
        label = stringResource(R.string.onboarding_row_provider),
        value = state.provider.providerLabel()
    )
    // The identifiers are long, so each grant is its own stacked line: side by side, the longest
    // one (START_FOREGROUND_SERVICES_FROM_BACKGROUND) squeezes its label into single characters.
    PRIVILEGED_GRANTS.forEach { grant ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(grant.label),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = grant.permission,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider()
    Text(
        text = stringResource(R.string.onboarding_install_footnote),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MetamoduleProgress(phase: SetupInstallPhase?) {
    val downloading = phase is SetupInstallPhase.Downloading
    val installed = phase is SetupInstallPhase.Installing
    val steps = listOf(
        SetupStep(
            title = stringResource(R.string.onboarding_downloading_metamodule),
            state = when {
                downloaded(phase) -> SetupStepState.DONE
                downloading || phase == null -> SetupStepState.ACTIVE
                else -> SetupStepState.DONE
            }
        ),
        SetupStep(
            title = stringResource(R.string.onboarding_installing_metamodule),
            state = if (installed) SetupStepState.ACTIVE else SetupStepState.PENDING
        )
    )
    SetupStepList(steps = steps)
    if (phase is SetupInstallPhase.Downloading) {
        val total = phase.total
        if (total != null && total > 0) {
            val fraction = (phase.bytes.toFloat() / total).coerceIn(0f, 1f)
            val context = LocalContext.current
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }
            )
            Text(
                text = stringResource(
                    R.string.onboarding_download_size,
                    Formatter.formatFileSize(context, phase.bytes),
                    Formatter.formatFileSize(context, total)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            )
        }
    }
    Text(
        text = stringResource(R.string.onboarding_metamodule_checked),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun downloaded(phase: SetupInstallPhase?): Boolean = phase is SetupInstallPhase.Downloading && phase.bytes > 0 && phase.total != null &&
    phase.bytes >= phase.total

@Composable
private fun RebootContent(state: WizardState.RebootPending) {
    val metamodule = state.reason == RebootReason.METAMODULE_INSTALL
    Text(
        text = stringResource(
            if (metamodule) {
                R.string.onboarding_reboot_metamodule_title
            } else {
                R.string.onboarding_reboot_module_title
            }
        ),
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        text = stringResource(
            if (metamodule) {
                R.string.onboarding_reboot_metamodule_body
            } else {
                R.string.onboarding_reboot_module_body
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.reason != RebootReason.MODULE_INSTALL &&
        state.reason != RebootReason.METAMODULE_INSTALL
    ) {
        KeyValueRow(
            label = stringResource(R.string.onboarding_reboot_why),
            value = stringResource(state.reason.humanLabel())
        )
    }
    if (state.failure != null) {
        ErrorCard(
            title = stringResource(R.string.onboarding_reboot_failed_title),
            message = stringResource(R.string.onboarding_reboot_failed_message),
            output = state.failure
        )
    }
}

@Composable
private fun VerifyContent(state: WizardState.Verify) {
    val report = state.report
    if (report == null) {
        LoadingState(stringResource(R.string.onboarding_verify_body))
        return
    }
    var showPassing by rememberSaveable(state) { mutableStateOf(false) }
    val ordered = report.checks.entries.sortedBy { outcomeRank(it.value) }
    val failed = ordered.filter { it.value != VerifyOutcome.PASS }
    val passing = ordered.filter { it.value == VerifyOutcome.PASS }
    failed.forEach { (check, outcome) -> CheckRow(check = check, outcome = outcome) }
    if (passing.isNotEmpty()) {
        TextButton(onClick = { showPassing = !showPassing }) {
            Text(
                text = if (showPassing) {
                    stringResource(R.string.onboarding_verify_hide_passing)
                } else {
                    pluralStringResource(
                        R.plurals.onboarding_verify_show_passing,
                        passing.size,
                        passing.size
                    )
                }
            )
        }
        if (showPassing) {
            passing.forEach { (check, outcome) -> CheckRow(check = check, outcome = outcome) }
        }
    }
    if (!report.passed) {
        Text(
            text = stringResource(R.string.onboarding_verify_failed_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One check with its result as a word. The word sits under the name, never beside it: at 200 % a
 * trailing word squeezes the name into five characters and breaks it mid-word.
 */
@Composable
private fun CheckRow(check: VerifyCheck, outcome: VerifyOutcome) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val icon = when (outcome) {
            VerifyOutcome.PASS -> Icons.Filled.Check
            VerifyOutcome.FAIL -> Icons.Filled.Close
            VerifyOutcome.UNKNOWN -> Icons.Filled.Info
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when (outcome) {
                VerifyOutcome.PASS -> MaterialTheme.colorScheme.onSurfaceVariant
                VerifyOutcome.FAIL -> MaterialTheme.colorScheme.error
                VerifyOutcome.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(check.label()),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(outcome.label()),
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome == VerifyOutcome.FAIL) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

private fun outcomeRank(outcome: VerifyOutcome): Int = when (outcome) {
    VerifyOutcome.FAIL -> 0
    VerifyOutcome.UNKNOWN -> 1
    VerifyOutcome.PASS -> 2
}

@Composable
private fun PermissionRow(permission: RuntimePermission) {
    Column {
        Text(text = stringResource(permission.humanLabel()), style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(permission.reason()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LeftoverRow(provider: DetectedProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.onboarding_leftovers_title, provider.id.managerName()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        provider.artefacts.forEach { artefact ->
            Text(
                text = artefact,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                if (provider.managerInstalled) {
                    R.string.onboarding_leftovers_app
                } else {
                    R.string.onboarding_leftovers_app_missing
                },
                provider.id.managerName()
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Wizard state → labels
// ---------------------------------------------------------------------------------------------

private data class ProviderGuide(val label: Int, val url: String)

private val PROVIDER_GUIDES = listOf(
    ProviderGuide(R.string.onboarding_guide_magisk, "https://topjohnwu.github.io/Magisk/install.html"),
    ProviderGuide(R.string.onboarding_guide_kernelsu, "https://kernelsu.org/guide/installation.html"),
    ProviderGuide(R.string.onboarding_guide_kernelsu_next, "https://github.com/KernelSU-Next/KernelSU-Next"),
    ProviderGuide(R.string.onboarding_guide_apatch, "https://apatch.dev/install.html")
)

private data class PrivilegedGrant(val label: Int, val permission: String)

private val PRIVILEGED_GRANTS = listOf(
    PrivilegedGrant(R.string.onboarding_grant_audio_capture, "CAPTURE_AUDIO_OUTPUT"),
    PrivilegedGrant(R.string.onboarding_grant_call_state, "MODIFY_PHONE_STATE"),
    PrivilegedGrant(R.string.onboarding_grant_in_call_control, "CONTROL_INCALL_EXPERIENCE"),
    PrivilegedGrant(R.string.onboarding_grant_self_updates, "INSTALL_SELF_UPDATES"),
    PrivilegedGrant(R.string.onboarding_grant_reboot, "REBOOT"),
    PrivilegedGrant(
        R.string.onboarding_grant_start_after_boot,
        "START_FOREGROUND_SERVICES_FROM_BACKGROUND"
    )
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
@Composable
internal fun RootId.grantInstruction(): String = stringResource(
    when (this) {
        RootId.MAGISK -> R.string.onboarding_grant_magisk
        RootId.KERNELSU -> R.string.onboarding_grant_kernelsu
        RootId.KERNELSU_NEXT -> R.string.onboarding_grant_kernelsu_next
        RootId.APATCH -> R.string.onboarding_grant_apatch
    }
)

/**
 * The provider's own version in the shape a person checks. The APatch binary answers with a build
 * hash and a build code; the code is the version people know, so the hash is dropped here:
 * `APatch 11282`, not `APatch e8e4651 (11282)`.
 */
private val VERSION_WITH_CODE = Regex("""(.*)\s+\((\d+)\)$""")
private val BUILD_HASH = Regex("[0-9a-f]{7,40}")

internal fun DetectedProvider.versionLabel(): String? {
    val raw = version.trim()
    if (raw.isEmpty() || raw.equals("unknown", ignoreCase = true)) return null
    val match = VERSION_WITH_CODE.matchEntire(raw)
    val name = match?.groupValues?.get(1)?.trim() ?: raw
    val code = match?.groupValues?.get(2)
    val nameIsHash = BUILD_HASH.matches(name)
    return when {
        nameIsHash && code != null -> code
        nameIsHash -> null
        code != null -> "$name ($code)"
        else -> name
    }
}

/** `Magisk 31.1`, `APatch 11282`, or just `APatch` when the device printed no version. */
internal fun DetectedProvider.providerLabel(): String {
    val version = versionLabel()
    return if (version == null) id.managerName() else "${id.managerName()} $version"
}

@Composable
internal fun WizardState.title(): String = stringResource(
    when (this) {
        WizardState.Checking -> R.string.onboarding_card_checking

        is WizardState.NoRoot -> R.string.onboarding_card_no_root

        is WizardState.RootDenied -> R.string.onboarding_card_root_denied

        is WizardState.ProviderPrep -> R.string.onboarding_card_provider_prep

        is WizardState.ReadyToInstall -> if (update) {
            R.string.onboarding_card_ready_to_update
        } else {
            R.string.onboarding_card_ready_to_install
        }

        is WizardState.Installing -> R.string.onboarding_card_installing

        is WizardState.InstallFailed -> R.string.onboarding_card_install_failed

        is WizardState.RebootPending -> R.string.onboarding_card_reboot_pending

        is WizardState.Verify -> R.string.onboarding_card_verify

        is WizardState.NeedsRuntime -> R.string.onboarding_card_needs_runtime

        is WizardState.NeedsPairing -> R.string.onboarding_card_needs_pairing

        is WizardState.FirstLinkTest -> R.string.onboarding_card_first_link_test

        WizardState.Ready -> R.string.onboarding_card_ready

        is WizardState.Doctor -> R.string.onboarding_card_doctor

        is WizardState.Indeterminate -> R.string.onboarding_card_indeterminate

        is WizardState.Conflict -> R.string.onboarding_card_conflict
    }
)

@Composable
internal fun WizardState.subtitle(): String? {
    val provider = when (this) {
        is WizardState.RootDenied -> this.provider
        is WizardState.ProviderPrep -> this.provider
        is WizardState.ReadyToInstall -> this.provider
        is WizardState.RebootPending -> this.provider
        is WizardState.NeedsPairing -> this.provider
        is WizardState.Doctor -> this.provider
        else -> null
    }
    return provider?.providerLabel()
}

@Composable
internal fun WizardState.pillLabel(): String = when (this) {
    WizardState.Checking -> stringResource(R.string.onboarding_pill_checking)

    is WizardState.NoRoot -> stringResource(R.string.onboarding_pill_no_root)

    is WizardState.RootDenied -> stringResource(R.string.onboarding_pill_root_denied)

    is WizardState.ProviderPrep -> stringResource(R.string.onboarding_pill_provider_prep)

    is WizardState.ReadyToInstall -> stringResource(R.string.onboarding_pill_ready)

    is WizardState.Installing -> stringResource(R.string.onboarding_pill_installing)

    is WizardState.InstallFailed -> stringResource(R.string.onboarding_pill_failed)

    is WizardState.RebootPending -> stringResource(R.string.onboarding_pill_reboot_needed)

    is WizardState.Verify -> {
        val verified = report
        when {
            verified == null -> stringResource(R.string.onboarding_pill_checking)
            verified.passed -> stringResource(R.string.onboarding_pill_verified)
            else -> stringResource(R.string.onboarding_pill_failed)
        }
    }

    is WizardState.NeedsRuntime -> pluralStringResource(
        R.plurals.onboarding_pill_permissions,
        missing.size,
        missing.size
    )

    is WizardState.NeedsPairing -> stringResource(R.string.onboarding_pill_not_paired)

    is WizardState.FirstLinkTest -> stringResource(R.string.onboarding_pill_testing)

    WizardState.Ready -> stringResource(R.string.onboarding_pill_ready)

    is WizardState.Doctor -> stringResource(R.string.onboarding_pill_doctor)

    is WizardState.Indeterminate -> stringResource(R.string.onboarding_pill_unknown)

    is WizardState.Conflict -> stringResource(R.string.onboarding_pill_conflict)
}

internal fun WizardState.pillTone(): StatusTone = when (this) {
    is WizardState.ReadyToInstall -> StatusTone.POSITIVE

    is WizardState.Verify -> if (report?.passed == true) StatusTone.POSITIVE else StatusTone.WARNING

    WizardState.Ready -> StatusTone.POSITIVE

    is WizardState.InstallFailed, is WizardState.NoRoot, is WizardState.RootDenied, is WizardState.Doctor,
    is WizardState.Conflict -> StatusTone.NEGATIVE

    is WizardState.Indeterminate -> StatusTone.NEUTRAL

    is WizardState.NeedsRuntime -> StatusTone.WARNING

    else -> StatusTone.ACCENT
}

/** A state where the screen is already showing what is happening and offers no action. */
internal fun WizardState.isTransitional(): Boolean = this is WizardState.Checking ||
    this is WizardState.Installing ||
    this is WizardState.FirstLinkTest ||
    (this is WizardState.Verify && report == null)

@Composable
internal fun WizardState.toSteps(): List<SetupStep> {
    val needsMetamodule = this is WizardState.ProviderPrep ||
        (this is WizardState.RebootPending && reason == RebootReason.METAMODULE_INSTALL) ||
        (this is WizardState.Installing && operation == InstallOperation.PREPARE_METAMODULE) ||
        (this is WizardState.InstallFailed && operation == InstallOperation.PREPARE_METAMODULE)
    // Noun phrases only: a step title must not read like the button beside it.
    val titles = listOf(
        stringResource(R.string.onboarding_step_root),
        stringResource(
            if (needsMetamodule) R.string.onboarding_step_metamodule else R.string.onboarding_step_module
        ),
        stringResource(R.string.onboarding_step_reboot),
        stringResource(R.string.onboarding_step_permissions),
        stringResource(R.string.onboarding_step_pairing),
        stringResource(R.string.onboarding_step_link_test)
    )
    val detectDetail = (this as? WizardState.RebootPending)?.provider?.providerLabel()
    val provider = when (this) {
        is WizardState.ReadyToInstall -> this.provider
        is WizardState.Installing -> this.provider
        is WizardState.InstallFailed -> this.provider
        is WizardState.RebootPending -> this.provider
        is WizardState.ProviderPrep -> this.provider
        is WizardState.RootDenied -> this.provider
        else -> null
    }
    val metamoduleReboot = (this as? WizardState.RebootPending)?.reason == RebootReason.METAMODULE_INSTALL
    val moduleDetail = when {
        this is WizardState.InstallFailed && operation == InstallOperation.PREPARE_METAMODULE ->
            stringResource(R.string.onboarding_step_failed)

        this is WizardState.InstallFailed -> stringResource(R.string.onboarding_step_failed)

        metamoduleReboot -> stringResource(R.string.onboarding_step_overlay_staged)

        else -> null
    }
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
                index == 0 && this is WizardState.Conflict ->
                    stringResource(R.string.onboarding_step_two_managers)

                index == 0 && failed != 0 ->
                    detectDetail ?: provider?.providerLabel()

                index == 1 && moduleDetail != null -> moduleDetail

                index == 1 && doneBefore > 1 && !needsMetamodule ->
                    stringResource(R.string.onboarding_step_installed)

                index == 4 && doneBefore > 4 -> stringResource(R.string.onboarding_step_paired)

                else -> null
            }
        )
    }
}

private fun WizardState.InstallFailed.output(): String = result.output()

private fun WizardState.Doctor.output(): String {
    val reportText = report?.let { report ->
        report.checks.entries.joinToString("\n") { (check, outcome) ->
            "${check.debugLabel()}: ${outcome.debugLabel()}"
        }
    }
    return listOfNotNull(detail, reportText).joinToString("\n").ifEmpty { "no captured output" }
}

private fun ProviderResult.output(): String = tail(lines = 20).joinToString("\n").ifEmpty { "the provider printed nothing" }

@StringRes
private fun installFailureTitle(operation: InstallOperation): Int = when (operation) {
    InstallOperation.INSTALL -> R.string.onboarding_failed_install
    InstallOperation.UPDATE -> R.string.onboarding_failed_update
    InstallOperation.ROLLBACK -> R.string.onboarding_failed_rollback
    InstallOperation.PREPARE_METAMODULE -> R.string.onboarding_failed_metamodule
}

/** The pairing repository answers with a code; the sheet and the card say what it means. */
internal fun pairingRejectionText(code: String): UiText = when (code) {
    "pair_link_invalid" -> UiText.Res(R.string.onboarding_pair_code_invalid)
    "pair_url_missing" -> UiText.Res(R.string.onboarding_pair_code_url_missing)
    "pair_iroh_missing", "pair_iroh_invalid" -> UiText.Res(R.string.onboarding_pair_code_iroh)
    "pair_token_missing" -> UiText.Res(R.string.onboarding_pair_code_token_missing)
    "pairing_store_failed" -> UiText.Res(R.string.onboarding_pair_code_store_failed)
    else -> UiText.Res(R.string.onboarding_pair_code_unknown)
}

@StringRes
private fun VerifyCheck.label(): Int = when (this) {
    VerifyCheck.MODULE_PRESENT -> R.string.onboarding_check_module_present
    VerifyCheck.MODULE_ENABLED -> R.string.onboarding_check_module_enabled
    VerifyCheck.NO_DISABLE_FLAG -> R.string.onboarding_check_no_disable_flag
    VerifyCheck.NO_REMOVE_FLAG -> R.string.onboarding_check_no_remove_flag
    VerifyCheck.PACKAGE_IS_SYSTEM -> R.string.onboarding_check_package_is_system
    VerifyCheck.APK_IN_PRIV_APP -> R.string.onboarding_check_apk_in_priv_app
    VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED -> R.string.onboarding_check_privileged_grants
}

/** The details pane is evidence, not prose: its words stay stable in every locale. */
private fun VerifyCheck.debugLabel(): String = when (this) {
    VerifyCheck.MODULE_PRESENT -> "Module folder"
    VerifyCheck.MODULE_ENABLED -> "Module enabled"
    VerifyCheck.NO_DISABLE_FLAG -> "No disable flag"
    VerifyCheck.NO_REMOVE_FLAG -> "No remove flag"
    VerifyCheck.PACKAGE_IS_SYSTEM -> "System app"
    VerifyCheck.APK_IN_PRIV_APP -> "APK under /system/priv-app"
    VerifyCheck.PRIVILEGED_PERMISSIONS_GRANTED -> "Privileged grants"
}

@StringRes
private fun VerifyOutcome.label(): Int = when (this) {
    VerifyOutcome.PASS -> R.string.onboarding_check_passed
    VerifyOutcome.FAIL -> R.string.onboarding_check_failed
    VerifyOutcome.UNKNOWN -> R.string.onboarding_check_not_checked
}

private fun VerifyOutcome.debugLabel(): String = when (this) {
    VerifyOutcome.PASS -> "pass"
    VerifyOutcome.FAIL -> "fail"
    VerifyOutcome.UNKNOWN -> "unknown"
}

@StringRes
private fun RebootReason.humanLabel(): Int = when (this) {
    RebootReason.MODULE_INSTALL -> R.string.onboarding_reason_module_install
    RebootReason.METAMODULE_INSTALL -> R.string.onboarding_reason_metamodule_install
    RebootReason.MODULE_UPDATE -> R.string.onboarding_reason_module_update
    RebootReason.MODULE_ROLLBACK -> R.string.onboarding_reason_module_rollback
    RebootReason.USER_REQUEST -> R.string.onboarding_reason_user_request
}

@StringRes
private fun doctorTitle(reason: DoctorReason): Int = when (reason) {
    DoctorReason.VERIFY_FAILED -> R.string.onboarding_doctor_verify_failed
    DoctorReason.MODULE_DISABLED -> R.string.onboarding_doctor_module_disabled
    DoctorReason.MODULE_PENDING_REMOVAL -> R.string.onboarding_doctor_module_pending_removal
    DoctorReason.LINK_TEST_FAILED -> R.string.onboarding_doctor_link_test_failed
}

@StringRes
private fun doctorMessage(reason: DoctorReason): Int = when (reason) {
    DoctorReason.VERIFY_FAILED -> R.string.onboarding_doctor_verify_failed_message
    DoctorReason.MODULE_DISABLED -> R.string.onboarding_doctor_module_disabled_message
    DoctorReason.MODULE_PENDING_REMOVAL -> R.string.onboarding_doctor_module_pending_removal_message
    DoctorReason.LINK_TEST_FAILED -> R.string.onboarding_doctor_link_test_failed_message
}

@StringRes
private fun unknownTitle(reason: IndeterminateReason): Int = when (reason) {
    IndeterminateReason.SHELL_TIMED_OUT -> R.string.onboarding_unknown_shell_title
    IndeterminateReason.NO_PROVIDER -> R.string.onboarding_unknown_no_provider_title
    IndeterminateReason.MODULE_UNREADABLE -> R.string.onboarding_unknown_module_title
    IndeterminateReason.OVERLAY_PROBE_FAILED -> R.string.onboarding_unknown_overlay_title
    IndeterminateReason.PROBE_FAILED -> R.string.onboarding_unknown_probe_title
}

@StringRes
private fun unknownMessage(reason: IndeterminateReason): Int = when (reason) {
    IndeterminateReason.SHELL_TIMED_OUT -> R.string.onboarding_unknown_shell_message
    IndeterminateReason.NO_PROVIDER -> R.string.onboarding_unknown_no_provider_message
    IndeterminateReason.MODULE_UNREADABLE -> R.string.onboarding_unknown_module_message
    IndeterminateReason.OVERLAY_PROBE_FAILED -> R.string.onboarding_unknown_overlay_message
    IndeterminateReason.PROBE_FAILED -> R.string.onboarding_unknown_probe_message
}

@StringRes
internal fun RuntimePermission.humanLabel(): Int = when (this) {
    RuntimePermission.RECORD_AUDIO -> R.string.onboarding_permission_microphone
    RuntimePermission.CALL_PHONE -> R.string.onboarding_permission_calls
    RuntimePermission.READ_PHONE_STATE -> R.string.onboarding_permission_phone_state
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.onboarding_permission_audio_settings
    RuntimePermission.POST_NOTIFICATIONS -> R.string.onboarding_permission_notifications
    RuntimePermission.ACCESS_LOCAL_NETWORK -> R.string.onboarding_permission_local_network
}

@StringRes
internal fun RuntimePermission.reason(): Int = when (this) {
    RuntimePermission.RECORD_AUDIO -> R.string.onboarding_permission_microphone_reason
    RuntimePermission.CALL_PHONE -> R.string.onboarding_permission_calls_reason
    RuntimePermission.READ_PHONE_STATE -> R.string.onboarding_permission_phone_state_reason
    RuntimePermission.MODIFY_AUDIO_SETTINGS -> R.string.onboarding_permission_audio_settings_reason
    RuntimePermission.POST_NOTIFICATIONS -> R.string.onboarding_permission_notifications_reason
    RuntimePermission.ACCESS_LOCAL_NETWORK -> R.string.onboarding_permission_local_network_reason
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

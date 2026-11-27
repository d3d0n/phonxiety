package com.phonxiety.bridge.feature.onboarding

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.LocalPhonxietyType
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.EmptyState
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.KeyValueRow
import com.phonxiety.bridge.designsystem.component.LoadingState
import com.phonxiety.bridge.designsystem.component.SectionCard
import com.phonxiety.bridge.designsystem.component.SetupStep
import com.phonxiety.bridge.designsystem.component.SetupStepList
import com.phonxiety.bridge.designsystem.component.SetupStepState
import com.phonxiety.bridge.designsystem.component.StatusPill
import com.phonxiety.bridge.root.DetectedProvider
import com.phonxiety.bridge.root.InstallOperation
import com.phonxiety.bridge.root.ModuleController
import com.phonxiety.bridge.root.RebootReason
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.VerifyCheck
import com.phonxiety.bridge.root.VerifyOutcome
import com.phonxiety.bridge.root.WizardState

/** The state card: what the wizard is doing, and the one thing to read about it. */
@Composable
internal fun WizardCard(state: OnboardingUiState, callbacks: OnboardingCallbacks) {
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
                    style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodyMedium)
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
                        style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
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
            BreakableConstant(
                text = grant.permission,
                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
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

/**
 * A long technical constant that may break only after its underscores. The zero-width spaces are a
 * display aid: they are invisible, shape nothing and add no width, but they give the line breaker a
 * place to break between words instead of mid-word at 320 dp with 200 % text.
 *
 * The node's semantics are replaced with the exact constant, so a screen reader and any copy read
 * the identifier as it is; the layout result is republished so the node still answers text-navigation
 * requests. The holder is not state: assigning it during layout must not recompose.
 */
@Composable
private fun BreakableConstant(text: String, style: TextStyle, color: Color) {
    val display = remember(text) { breakableConstant(text) }
    val layout = remember(text) { arrayOfNulls<TextLayoutResult>(1) }
    Text(
        text = display,
        style = style,
        color = color,
        onTextLayout = { result -> layout[0] = result },
        modifier = Modifier.clearAndSetSemantics {
            this.text = AnnotatedString(text)
            // Registered unconditionally: the semantics tree can be built before the first layout,
            // so the action reads the holder when it is invoked, not when it is registered.
            getTextLayoutResult { results ->
                val result = layout[0] ?: return@getTextLayoutResult false
                results.add(result)
                true
            }
        }
    )
}

/**
 * The constant with a break opportunity after every underscore. Stripping the zero-width spaces
 * gives the constant back, so the display string carries no text a person could copy.
 */
internal fun breakableConstant(constant: String): String = constant.replace("_", "_\u200B")

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
internal fun LeftoverRow(provider: DetectedProvider) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.onboarding_leftovers_title, provider.id.managerName()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        provider.artefacts.forEach { artefact ->
            Text(
                text = artefact,
                style = LocalPhonxietyType.current.code(MaterialTheme.typography.bodySmall),
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

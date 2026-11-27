package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.BrandLockup
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SetupStepList
import com.phonxiety.bridge.designsystem.component.SetupStepState
import com.phonxiety.bridge.root.RuntimePermission
import com.phonxiety.bridge.root.WizardState

/**
 * The one-button setup screen. The answer comes first: the current state and its one action sit
 * above the six-step plan, which is a one-line summary until it is opened. The action bar is pinned
 * to the bottom, so no state ever pushes its own action below the fold.
 *
 * [pairingSheetVisible] is controlled by the host when the scanner needs to hand the person over to
 * the paste field; when it is null the screen owns the sheet.
 */
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

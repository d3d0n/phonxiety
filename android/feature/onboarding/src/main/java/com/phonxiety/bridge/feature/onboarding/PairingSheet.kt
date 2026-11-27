package com.phonxiety.bridge.feature.onboarding

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.designsystem.asString
import com.phonxiety.bridge.designsystem.component.ErrorCard
import com.phonxiety.bridge.designsystem.component.PrimaryActionButton
import com.phonxiety.bridge.designsystem.component.SheetScaffold

/**
 * The pairing rejection when no pairing surface is on screen. It carries the same localized words as
 * the field's supporting text and stays until dismissed, so a deep link opened at another step is
 * not silently dropped.
 */
@Composable
internal fun PairingRejectionNote(error: String, onDismiss: () -> Unit) {
    ErrorCard(
        title = stringResource(R.string.onboarding_pair_rejected_title),
        message = pairingRejectionText(error).asString(),
        dismissLabel = stringResource(R.string.onboarding_action_dismiss),
        onDismiss = onDismiss
    )
}

/**
 * The pairing link is a URI, never a sentence: no autocorrect, no capitalisation, and Done submits
 * it the same way the button does.
 */
internal val PAIRING_FIELD_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Done
)

/**
 * The paste sheet: scan, open the in-app camera, or paste the link. The sheet surface already pads
 * for the navigation bar, so the frame is handed a zero inset and does not pad it twice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairingSheet(
    pastedLink: String,
    onPastedLinkChange: (String) -> Unit,
    pairingError: String?,
    callbacks: OnboardingCallbacks,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    var submitting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pairingError) {
        if (pairingError != null) submitting = false
    }

    fun submit() {
        if (pastedLink.isBlank() || submitting) return
        submitting = true
        callbacks.onPairLink(pastedLink.trim())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetScaffold(
            title = stringResource(R.string.onboarding_pair_sheet_title),
            subtitle = stringResource(R.string.onboarding_pair_sheet_body),
            contentPadding = PaddingValues(start = 20.dp, top = 0.dp, end = 20.dp, bottom = 28.dp),
            navigationBarInsets = WindowInsets(0)
        ) {
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
                keyboardOptions = PAIRING_FIELD_KEYBOARD,
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryActionButton(
                label = stringResource(R.string.onboarding_pair_commit),
                onClick = { submit() },
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

/** The pairing repository answers with a code; the sheet and the card say what it means. */
internal fun pairingRejectionText(code: String): UiText = when (code) {
    "pair_link_invalid" -> UiText.Res(R.string.onboarding_pair_code_invalid)
    "pair_url_missing" -> UiText.Res(R.string.onboarding_pair_code_url_missing)
    "pair_iroh_missing", "pair_iroh_invalid" -> UiText.Res(R.string.onboarding_pair_code_iroh)
    "pair_token_missing" -> UiText.Res(R.string.onboarding_pair_code_token_missing)
    "pairing_store_failed" -> UiText.Res(R.string.onboarding_pair_code_store_failed)
    else -> UiText.Res(R.string.onboarding_pair_code_unknown)
}

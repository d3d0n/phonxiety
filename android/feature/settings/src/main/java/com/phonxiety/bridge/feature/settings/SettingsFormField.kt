package com.phonxiety.bridge.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** The daemon form's text fields, in reading order; see `DaemonCard`. */
internal const val DAEMON_FORM_FIELDS = 11

/** One field's place in a form's focus chain. */
internal class FieldLink {
    val requester = FocusRequester()
    var next: FocusRequester? = null
    var previous: FocusRequester? = null
}

/** Builds a chain of [size] links whose Next and Previous walk the fields in reading order. */
@Composable
internal fun rememberFocusChain(size: Int): List<FieldLink> = remember(size) {
    List(size) { FieldLink() }.also { fields ->
        fields.forEachIndexed { index, field ->
            field.previous = fields.getOrNull(index - 1)?.requester
            field.next = fields.getOrNull(index + 1)?.requester
        }
    }
}

/**
 * A labelled text field. Single-line fields advance with the keyboard's own Next unless they say
 * otherwise; a multiline field keeps Enter for its newline. URL and address fields switch the
 * keyboard's autocorrect and capitalisation off, because a relay address is not prose.
 */
@Composable
internal fun FormField(
    label: String,
    value: String,
    singleLine: Boolean = true,
    numeric: Boolean = false,
    hint: String? = null,
    isError: Boolean = false,
    url: Boolean = false,
    imeAction: ImeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
    onDone: (() -> Unit)? = null,
    focus: FieldLink? = null,
    onChange: (String) -> Unit
) {
    val focusModifier = if (focus == null) {
        Modifier
    } else {
        Modifier
            .focusRequester(focus.requester)
            .focusProperties {
                next = focus.next ?: FocusRequester.Default
                previous = focus.previous ?: FocusRequester.Default
            }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = when {
            isError -> {
                { Text(stringResource(R.string.settings_error_whole_number)) }
            }

            hint != null -> {
                { Text(hint) }
            }

            else -> null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (url) KeyboardCapitalization.None else KeyboardCapitalization.Unspecified,
            autoCorrectEnabled = if (url) false else null,
            imeAction = imeAction
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.fillMaxWidth().then(focusModifier)
    )
}

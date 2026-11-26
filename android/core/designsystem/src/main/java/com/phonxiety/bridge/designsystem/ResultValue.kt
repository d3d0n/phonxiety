package com.phonxiety.bridge.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A result field's value as a card shows it: the words a person reads, and whether they keep
 * tabular figures. A boolean becomes a word, prose stays prose, and only a technical value stays
 * monospace.
 */
data class ResultText(val text: String, val mono: Boolean)

/** The value with the module's own words for a boolean. */
@Composable
fun resultText(raw: String, boolean: Boolean?): ResultText = resultText(
    raw = raw,
    boolean = boolean,
    yes = stringResource(R.string.designsystem_value_yes),
    no = stringResource(R.string.designsystem_value_no)
)

/** The value as a card shows it, with the boolean's words supplied by the caller. */
fun resultText(raw: String, boolean: Boolean?, yes: String, no: String): ResultText = when {
    boolean == true -> ResultText(yes, mono = false)
    boolean == false -> ResultText(no, mono = false)
    isTechnicalValue(raw) -> ResultText(raw, mono = true)
    else -> ResultText(raw, mono = false)
}

/**
 * Technical values are the ones a person parses, not reads: a measurement, a code, a time. Anything
 * with a space or no figure in it is prose. A sentence answer never lands in monospace because the
 * daemon happened to return it as a string.
 */
internal fun isTechnicalValue(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.isWhitespace() }) return false
    return trimmed.any { it.isDigit() }
}

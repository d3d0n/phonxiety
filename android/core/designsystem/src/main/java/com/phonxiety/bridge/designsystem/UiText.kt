package com.phonxiety.bridge.designsystem

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * User-visible text that a view model can carry without a [android.content.Context]: a string
 * resource, a plural, or literal content that came from outside the app (a daemon diagnostic, a
 * contact name, a transcript line).
 *
 * Args compare by value, so two instances built from the same content are equal.
 */
sealed interface UiText {
    data class Res(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList()
    ) : UiText

    /**
     * [quantity] selects the plural form and is also passed as the first format argument, because
     * the resource normally starts with the count ("%1$d calls"). Extra [args] follow it.
     */
    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList()
    ) : UiText

    data class Literal(val text: String) : UiText
}

/** Resolves [this] in composition, so locale and configuration changes re-read the resource. */
@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, quantity, quantity, *args.toTypedArray())
    is UiText.Literal -> text
}

/** Resolves [this] outside composition, for snackbars, notifications and other effects. */
fun UiText.asString(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, quantity, quantity, *args.toTypedArray())
    is UiText.Literal -> text
}

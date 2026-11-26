package com.phonxiety.bridge.feature.livecall

import android.telephony.PhoneNumberUtils

/**
 * The E.164 number as a person reads it, keeping the country: `+998 90 123 45 67`. The region comes
 * from the number's own calling code, never from the phone's locale, so a call to another country
 * still shows its own country code.
 */
internal fun formatDestination(e164: String): String {
    val trimmed = e164.trim()
    // A service short number is dialled as typed; there is no country to format against.
    if (!trimmed.startsWith("+")) return trimmed
    val formatted = runCatching {
        PhoneNumberUtils.formatNumber(trimmed, regionOf(trimmed))
    }.getOrNull().orEmpty()
    val value = formatted.ifBlank { trimmed }
    // The leading "+" is part of the number: Android may drop it when it formats nationally.
    return if (value.startsWith("+")) value else "+$value"
}

/**
 * The ISO region for an E.164 number's calling code. Only the codes this app is expected to dial
 * are listed; an unknown code formats from the digits alone, which is still honest.
 */
internal fun regionOf(e164: String): String {
    val digits = e164.filter { it.isDigit() }
    return when {
        digits.startsWith("998") -> "UZ"
        digits.startsWith("1") -> "US"
        digits.startsWith("44") -> "GB"
        digits.startsWith("7") -> "RU"
        digits.startsWith("90") -> "TR"
        digits.startsWith("994") -> "AZ"
        digits.startsWith("996") -> "KG"
        digits.startsWith("992") -> "TJ"
        digits.startsWith("993") -> "TM"
        digits.startsWith("971") -> "AE"
        digits.startsWith("49") -> "DE"
        digits.startsWith("33") -> "FR"
        digits.startsWith("86") -> "CN"
        else -> ""
    }
}

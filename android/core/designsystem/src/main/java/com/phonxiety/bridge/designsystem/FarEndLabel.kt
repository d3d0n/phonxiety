package com.phonxiety.bridge.designsystem

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/**
 * What answered at the far end, from the daemon's key: a person, a menu or a mailbox. Calls and the
 * live call read the same words, so the same call never gets two names. An unknown key is titled
 * as it was written rather than hidden, and an acronym such as `ivr` keeps its capitals.
 */
@Composable
fun farEndLabel(farEnd: String): String = farEndLabel(farEnd, LocalResources.current)

fun farEndLabel(farEnd: String, resources: Resources): String {
    val written = farEnd.trim()
    return when (written.lowercase(Locale.US)) {
        "person" -> resources.getString(R.string.designsystem_far_end_person)

        "ivr" -> resources.getString(R.string.designsystem_far_end_ivr)

        "voicemail" -> resources.getString(R.string.designsystem_far_end_voicemail)

        // A place or a name the daemon already wrote keeps its capitals; a lowercase key gets one.
        else -> written.replace('_', ' ').replace('-', ' ').let { words ->
            if (words.none { it.isUpperCase() }) words.replaceFirstChar { it.uppercase() } else words
        }
    }
}

package com.phonxiety.bridge.feature.updates

import com.phonxiety.bridge.data.updates.CompatibilityNote
import com.phonxiety.bridge.designsystem.UiText
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The feature's sentences for the data layer's notes: which resource, and with which values. */
class UpdateTextTest {
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `an Android version note names both releases and both API levels`() {
        assertEquals(
            UiText.Res(
                R.string.updates_note_android_version,
                listOf("Android 14", 34, "Android 13", 33)
            ),
            CompatibilityNote.NeedsNewerAndroid(requiredSdk = 34, phoneSdk = 33).text()
        )
    }

    @Test
    fun `an Android release this app does not know falls back to the API levels`() {
        assertEquals(
            UiText.Res(R.string.updates_note_android_api, listOf(40, 33)),
            CompatibilityNote.NeedsNewerAndroid(requiredSdk = 40, phoneSdk = 33).text()
        )
    }

    @Test
    fun `the Android release names known SDK levels`() {
        assertEquals("Android 14", androidName(34))
        assertEquals("Android 15", androidName(35))
        assertEquals("Android 16", androidName(36))
        assertEquals("Android 17", androidName(37))
        assertNull(androidName(99))
    }

    @Test
    fun `new privileged grants are a count, never a list of identifiers`() {
        assertEquals(
            UiText.Plural(R.plurals.updates_note_privileged, quantity = 3),
            CompatibilityNote.NewPrivilegedGrants(listOf("a", "b", "c")).text()
        )
    }

    @Test
    fun `the daemon protocol note joins the versions it accepts`() {
        assertEquals(
            UiText.Res(R.string.updates_note_daemon_protocol, listOf("2, 3", 4)),
            CompatibilityNote.DaemonProtocolUnsupported(listOf(2, 3), appProtocol = 4).text()
        )
    }

    @Test
    fun `the daemon's own words are never rewritten`() {
        assertEquals(
            UiText.Literal("protocol ranges do not overlap"),
            CompatibilityNote.Reported("protocol ranges do not overlap").text()
        )
    }

    @Test
    fun `a known privileged permission gets a human title`() {
        assertEquals(
            UiText.Res(R.string.updates_permission_capture_audio),
            privilegedTitle("android.permission.CAPTURE_AUDIO_OUTPUT")
        )
    }

    @Test
    fun `an unknown privileged permission keeps its own name`() {
        assertEquals(
            UiText.Literal("SOME_FUTURE_PERMISSION"),
            privilegedTitle("android.permission.SOME_FUTURE_PERMISSION")
        )
    }

    @Test
    fun `a download percentage is localised and has no space`() {
        assertEquals("42%", downloadPercent(0.42f))
        assertEquals("0%", downloadPercent(0f))
        assertEquals("100%", downloadPercent(1f))
        assertEquals("100%", downloadPercent(1.7f))
    }
}

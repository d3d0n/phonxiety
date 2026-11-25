package com.phonxiety.bridge.feature.livecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.model.AudioState
import com.phonxiety.bridge.model.CallMark
import com.phonxiety.bridge.model.PartialTurn
import com.phonxiety.bridge.model.TranscriptTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The live call in the two layouts it has to work in: a stacked phone and a two-pane wide one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class LiveCallScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, state: LiveCallUiState) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LiveCallScreen(
                        state = state,
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = {},
                        onReload = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/live_call_$name.png")
    }

    @Test
    fun livePortrait() = capture("portrait", LiveCallSamples.live)

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-xxhdpi")
    fun liveInLandscape() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    LiveCallScreen(
                        state = LiveCallSamples.live,
                        onBack = {},
                        onInstructionChange = {},
                        onSendInstruction = {},
                        onTogglePause = {},
                        onSummarize = {},
                        onDtmf = {},
                        onMark = {},
                        onHangUp = {},
                        onReload = {},
                        onOpenDetail = {},
                        nowEpochMs = NOW
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/live_call_landscape.png")
    }

    @Test
    fun pausedWithAnInstructionDraft() = capture(
        "paused",
        LiveCallSamples.live.copy(
            muted = true,
            instruction = "ask for Thursday",
            activity = "waiting"
        )
    )

    @Test
    fun reconnecting() = capture(
        "reconnecting",
        LiveCallSamples.live.copy(reconnecting = true, partials = emptyList())
    )

    @Test
    fun endedWithResult() = capture("ended", LiveCallSamples.ended)

    private companion object {
        const val NOW = 1_764_000_074_000L
    }
}

/** A call that has been going for a minute, with all three surfaces of the screen occupied. */
object LiveCallSamples {
    val live = LiveCallUiState(
        callId = "call-2026-11-26",
        loaded = true,
        destination = "+998901234567",
        farEnd = "Bella Napoli",
        state = "active",
        activity = "idle",
        turns = listOf(
            TranscriptTurn(1, "assistant", "Good evening, I would like to book a table for two on Thursday at eight.", 1_764_000_006.0, true),
            TranscriptTurn(2, "user", "Thursday at eight is full. We have seven or nine.", 1_764_000_014.0, true),
            TranscriptTurn(3, "assistant", "Nine would work. Two people, under the name Rasulov.", 1_764_000_021.0, true),
            TranscriptTurn(4, "user", "Nine it is. Anything else?", 1_764_000_031.0, true),
            TranscriptTurn(5, "assistant", "One question: is there a window seat?", 1_764_000_040.0, true)
        ),
        partials = listOf(PartialTurn("user", "Let me check the", null)),
        marks = listOf(CallMark("agreed", 1_764_000_041.0)),
        summary = "Table for two confirmed for Thursday at 21:00 under Rasulov.",
        audioCodec = "opus",
        connectedAtMs = 1_764_000_005_000L,
        createdAtMs = 1_764_000_000_000L,
        audio = AudioState(
            codec = "opus",
            bitrate = 32_000,
            rxLevelDbfs = -18f,
            txLevelDbfs = -26f,
            framesOut = 2_400,
            framesIn = 2_398,
            drops = 0
        )
    )

    val ended = live.copy(
        state = "ended",
        callOutcome = "answered",
        resultStatus = "ready",
        ended = true,
        endedAtMs = 1_764_000_052_000L,
        activity = "idle",
        partials = emptyList(),
        muted = false,
        summaryFields = JsonObject(
            mapOf(
                "reservation_time" to JsonPrimitive("Thursday 21:00"),
                "party_size" to JsonPrimitive("2"),
                "window_seat" to JsonPrimitive("requested")
            )
        ),
        marks = listOf(CallMark("agreed", 1_764_000_041.0), CallMark("follow-up", 1_764_000_050.0, "confirm the window seat"))
    )
}

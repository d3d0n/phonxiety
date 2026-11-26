package com.phonxiety.bridge.feature.updates

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import com.phonxiety.bridge.designsystem.testing.Screenshot
import com.phonxiety.bridge.designsystem.testing.assertAccessible
import com.phonxiety.bridge.designsystem.testing.pinAnimationFrame
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Updates in three shots: every state at 411 dp light, one overview in 411 dp dark, and the states
 * that carry the most layout weight at 320 dp with 200 % text. The accessibility gate runs on each
 * state before its image is accepted.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xxhdpi")
@Category(Screenshot::class)
class UpdatesScreenshotTest(private val matrixCase: UpdatesMatrixCase) {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun pinClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreClock() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    private fun setScreen(state: UpdatesUiState) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, matrixCase.variant.fontScale)
            ) {
                PhonxietyTheme(darkTheme = matrixCase.variant.dark, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(
                            modifier = Modifier
                                .testTag(CAPTURE_TAG)
                                .width(matrixCase.variant.widthDp.dp)
                        ) {
                            UpdatesScreen(
                                state = state,
                                onBack = {},
                                onRefresh = {},
                                onInstallApp = {},
                                onInstallModule = {},
                                onRollback = {},
                                onConfirmRollback = {},
                                onDismissRollback = {},
                                onDismissRollbackRecovery = {},
                                onDismiss = {}
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun rendersOneMatrixState() {
        setScreen(matrixCase.state.build())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CAPTURE_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(CAPTURE_TAG)
            .captureRoboImage(
                "src/test/screenshots/updates_${matrixCase.state.slug}_${matrixCase.variant.slug}.png"
            )
    }

    companion object {
        private const val CAPTURE_TAG = "updates_matrix_capture"

        private val LIGHT = UpdatesVariant(411, dark = false, fontScale = 1f, slug = "411_light")
        private val DARK = UpdatesVariant(411, dark = true, fontScale = 1f, slug = "411_dark")
        private val LARGE_TEXT = UpdatesVariant(320, dark = false, fontScale = 2f, slug = "320_font200_light")

        private val MATRIX_STATES = setOf(
            UpdatesState.UP_TO_DATE,
            UpdatesState.UPDATE_AVAILABLE,
            UpdatesState.INCOMPATIBLE,
            UpdatesState.DOWNLOADING,
            UpdatesState.FAILED,
            UpdatesState.CALL_ACTIVE,
            UpdatesState.ROLLBACK_APP_ONLY,
            UpdatesState.MODULE_PERMISSIONS
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun matrix(): List<Array<Any>> = UpdatesState.entries.flatMap { state ->
            val variants = buildList {
                add(LIGHT)
                if (state == UpdatesState.UP_TO_DATE) add(DARK)
                if (state in MATRIX_STATES) add(LARGE_TEXT)
            }
            variants.map { variant -> arrayOf<Any>(UpdatesMatrixCase(state, variant)) }
        }
    }
}

/** The two confirmations are dialogs: they are captured on their own node, at their own sizes. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h1400dp-xxhdpi")
@Category(Screenshot::class)
class UpdatesDialogScreenshotTest(private val case: UpdatesDialogCase) {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun pinLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    fun rendersTheConfirmation() {
        // A dialog is its own window, so it reads the configuration's font scale, not a density
        // override inside the screen's composition: set both here or the 200 % case is a lie.
        RuntimeEnvironment.setQualifiers("w${case.widthDp}dp-h1400dp-xxhdpi")
        // A device-configuration change can reset the process defaults; re-pin before composing.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
        val application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.fontScale = case.fontScale
        application.resources.updateConfiguration(configuration, application.resources.displayMetrics)

        composeRule.setContent {
            PhonxietyTheme(darkTheme = case.dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.testTag(DIALOG_BACKGROUND_TAG)) {
                        UpdatesScreen(
                            state = case.state.build(),
                            onBack = {},
                            onRefresh = {},
                            onInstallApp = {},
                            onInstallModule = {},
                            onRollback = {},
                            onConfirmRollback = {},
                            onDismissRollback = {},
                            onDismissRollbackRecovery = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }
        // Let the dialog's enter animation finish: a half-faded scrim is a different image.
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composeRule.onAllNodes(isDialog()).onLast().assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onAllNodes(isDialog()).onLast()
            .captureRoboImage("src/test/screenshots/updates_${case.state.slug}_${case.slug}.png")
    }

    companion object {
        private const val DIALOG_BACKGROUND_TAG = "updates_dialog_background"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = UpdatesDialogState.entries.map { state ->
            arrayOf<Any>(UpdatesDialogCase(state, widthDp = 411, dark = false, fontScale = 1f, slug = "411_light"))
        }
    }
}

data class UpdatesVariant(
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
    val slug: String
)

data class UpdatesMatrixCase(val state: UpdatesState, val variant: UpdatesVariant) {
    override fun toString(): String = "${state.slug}_${variant.slug}"
}

data class UpdatesDialogCase(
    val state: UpdatesDialogState,
    val widthDp: Int,
    val dark: Boolean,
    val fontScale: Float,
    val slug: String
) {
    override fun toString(): String = "${state.slug}_$slug"
}

enum class UpdatesState(val slug: String, private val observation: () -> UpdatesUiState) {
    UP_TO_DATE("up_to_date", UpdatesSamples::upToDate),
    UPDATE_AVAILABLE("update_available", UpdatesSamples::updateAvailable),
    INCOMPATIBLE("incompatible", UpdatesSamples::incompatible),
    DOWNLOADING("downloading", UpdatesSamples::downloading),
    INSTALLING("installing", UpdatesSamples::installing),
    AWAITING_CONFIRMATION("awaiting_confirmation", UpdatesSamples::awaitingConfirmation),
    FAILED("failed", UpdatesSamples::failed),
    CHECK_FAILED("check_failed", UpdatesSamples::checkFailed),
    MANIFEST_MISSING("manifest_missing", UpdatesSamples::manifestMissing),
    MODULE_PERMISSIONS("module_permissions", UpdatesSamples::modulePermissions),
    MODULE_DISABLED("module_disabled", UpdatesSamples::moduleDisabled),
    MODULE_GRANT_MISSING("module_grant_missing", UpdatesSamples::moduleGrantMissing),
    CALL_ACTIVE("call_active", UpdatesSamples::callActive),
    ROLLBACK_APP_ONLY("rollback_app_only", UpdatesSamples::rollbackAppOnly),
    ROLLBACK_WITH_MODULE("rollback_with_module", UpdatesSamples::rollbackWithModule),
    ROLLBACK_BLOCKED("rollback_blocked", UpdatesSamples::rollbackBlocked),
    ROLLBACK_UNFINISHED("rollback_unfinished", UpdatesSamples::rollbackUnfinished),
    ROLLBACK_COMPLETED("rollback_completed", UpdatesSamples::rollbackCompleted),
    ROLLBACK_STARTED("rollback_started", UpdatesSamples::rollbackStarted),
    ROLLBACK_FAILED("rollback_failed", UpdatesSamples::rollbackFailed),
    ROLLBACK_APP_FAILED("rollback_app_failed", UpdatesSamples::rollbackAppOnlyFailedOffline),
    ROLLBACK_APP_RUNNING("rollback_app_running", UpdatesSamples::rollbackAppOnlyRunningOffline);

    fun build(): UpdatesUiState = observation()
}

enum class UpdatesDialogState(val slug: String, private val observation: () -> UpdatesUiState) {
    MODULE("rollback_confirm", UpdatesSamples::rollbackConfirming),
    APP_ONLY("rollback_confirm_app", UpdatesSamples::rollbackConfirmingAppOnly);

    fun build(): UpdatesUiState = observation()
}

/** The bottom of the screen — the rollback card and the last action — is recorded too. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdatesLowerSectionTest {
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun pinLocaleAndTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocaleAndTimeZone() {
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
    }

    @Test
    @Category(Screenshot::class)
    fun rendersTheLowerSections() {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.testTag(LOWER_TAG)) {
                        UpdatesScreen(
                            state = UpdatesSamples.rollbackUnfinished(),
                            onBack = {},
                            onRefresh = {},
                            onInstallApp = {},
                            onInstallModule = {},
                            onRollback = {},
                            onConfirmRollback = {},
                            onDismissRollback = {},
                            onDismissRollbackRecovery = {},
                            onDismiss = {}
                        )
                    }
                }
            }
        }
        // Scroll to the recovery's actions, not its title: the capture must show the buttons.
        composeRule.onNodeWithText("Finish the rollback").performScrollTo()
        composeRule.onNodeWithTag(LOWER_TAG).assertAccessible()
        composeRule.pinAnimationFrame()
        composeRule.onNodeWithTag(LOWER_TAG)
            .captureRoboImage("src/test/screenshots/updates_rollback_unfinished_lower_411_light.png")
    }

    companion object {
        private const val LOWER_TAG = "updates_lower_capture"
    }
}

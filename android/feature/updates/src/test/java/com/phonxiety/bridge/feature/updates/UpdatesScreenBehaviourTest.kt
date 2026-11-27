package com.phonxiety.bridge.feature.updates

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.phonxiety.bridge.data.updates.RollbackBlocker
import com.phonxiety.bridge.data.updates.RollbackPlan
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Updates screen has to do: a failure can be retried, a rollback cannot start without its
 * confirmation, a call blocks every disruptive action, and the numbers and notes stay readable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdatesScreenBehaviourTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private class Callbacks(
        val refresh: () -> Unit = {},
        val installApp: () -> Unit = {},
        val installModule: () -> Unit = {},
        val confirmUpdate: () -> Unit = {},
        val dismissUpdate: () -> Unit = {},
        val rollback: () -> Unit = {},
        val confirmRollback: () -> Unit = {},
        val dismissRollback: () -> Unit = {},
        val dismiss: () -> Unit = {}
    )

    private fun render(state: UpdatesUiState, callbacks: Callbacks = Callbacks()) {
        composeRule.setContent {
            PhonxietyTheme(dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    UpdatesScreen(
                        state = state,
                        onBack = {},
                        onRefresh = callbacks.refresh,
                        onInstallApp = callbacks.installApp,
                        onInstallModule = callbacks.installModule,
                        onConfirmUpdate = callbacks.confirmUpdate,
                        onDismissUpdate = callbacks.dismissUpdate,
                        onRollback = callbacks.rollback,
                        onConfirmRollback = callbacks.confirmRollback,
                        onDismissRollback = callbacks.dismissRollback,
                        onDismissRollbackRecovery = {},
                        onDismiss = callbacks.dismiss
                    )
                }
            }
        }
    }

    @Test
    fun aFailedInstallOffersARetryThatRunsTheInstall() {
        var installs = 0
        render(UpdatesSamples.failed(), Callbacks(installApp = { installs++ }))

        composeRule.onNodeWithText("Install again").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, installs)
    }

    @Test
    fun aFailedRollbackOffersARetryThatRunsIt() {
        var rollbacks = 0
        render(UpdatesSamples.rollbackFailed(), Callbacks(rollback = { rollbacks++ }))

        composeRule.onNodeWithText("Roll back again").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(1, rollbacks)
    }

    @Test
    fun cancellingTheAppOnlyConfirmationChangesNothing() {
        var rollbacks = 0
        var confirmations = 0
        render(
            UpdatesSamples.rollbackConfirmingAppOnly(),
            Callbacks(rollback = { rollbacks++ }, confirmRollback = { confirmations++ })
        )

        composeRule.onNodeWithText("This replaces the running app", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, rollbacks)
        assertEquals(0, confirmations)
    }

    @Test
    fun confirmingTheAppOnlyRollbackRunsIt() {
        var confirmations = 0
        render(
            UpdatesSamples.rollbackConfirmingAppOnly(),
            Callbacks(confirmRollback = { confirmations++ })
        )

        composeRule.onNodeWithText("Reinstall").performClick()

        assertEquals(1, confirmations)
    }

    @Test
    fun theAppUpdateDialogNamesTheVersionsItReplacesAndRunsOnlyOnConfirm() {
        var confirmations = 0
        var installs = 0
        render(
            UpdatesSamples.updateConfirmingApp(),
            Callbacks(installApp = { installs++ }, confirmUpdate = { confirmations++ })
        )

        composeRule
            .onNodeWithText("This replaces the running app 0.3.0 with 0.4.0.", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Install").performClick()

        assertEquals(1, confirmations)
        assertEquals("the row's tap asked, the dialog runs the install", 0, installs)
    }

    @Test
    fun theModuleUpdateDialogSaysItRebootsAndCanBeCancelled() {
        var confirmations = 0
        var dismissals = 0
        render(
            UpdatesSamples.updateConfirmingModule(),
            Callbacks(
                confirmUpdate = { confirmations++ },
                dismissUpdate = { dismissals++ }
            )
        )

        composeRule.onNodeWithText("Install module 0.4.0?").assertIsDisplayed()
        composeRule.onNodeWithText("then reboots the phone", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(0, confirmations)
        assertEquals(1, dismissals)
    }

    @Test
    fun aCallBlocksEveryDisruptiveAction() {
        var installs = 0
        var moduleInstalls = 0
        var rollbacks = 0
        render(
            UpdatesSamples.callActive(),
            Callbacks(
                installApp = { installs++ },
                installModule = { moduleInstalls++ },
                rollback = { rollbacks++ }
            )
        )

        composeRule.onNodeWithText("A call is running.", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Install 0.4.0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Install module 0.4.0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Reinstall the previous app").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Not during a call: the module install reboots the phone.").assertIsDisplayed()

        assertEquals(0, installs)
        assertEquals(0, moduleInstalls)
        assertEquals(0, rollbacks)
    }

    @Test
    fun aCompletedRollbackOffersNothingToReinstall() {
        render(UpdatesSamples.rollbackCompleted())

        composeRule.onNodeWithText("Rolled back").assertIsDisplayed()
        composeRule.onNodeWithText("Reinstall the previous app").assertDoesNotExist()
    }

    @Test
    fun aPhoneThatRunsTheKeptVersionHasNoRollbackOffer() {
        render(
            UpdatesSamples.upToDate().copy(
                rollbackPlan = RollbackPlan.Unavailable(
                    blocker = RollbackBlocker.APP_NOT_NEWER,
                    keptVersionName = "0.3.0",
                    keptVersionCode = 3
                )
            )
        )

        composeRule.onNodeWithText("Rollback").assertDoesNotExist()
        composeRule.onNodeWithText("Reinstall the previous app").assertDoesNotExist()
    }

    @Test
    fun aDownloadShowsItsPercentage() {
        render(UpdatesSamples.downloading())

        composeRule.onNodeWithText("Downloading 42%").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anInstallNamesTheVersionItInstalls() {
        render(UpdatesSamples.installing())

        composeRule.onNodeWithText("Installing 0.4.0…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theSystemDialogWaitIsAStatusNotADeadButton() {
        render(UpdatesSamples.awaitingConfirmation())

        composeRule.onNodeWithText("Finish in the system dialog").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("The system installer is waiting for you.").assertIsDisplayed()
        composeRule.onNodeWithText("Confirm in the system dialog").assertDoesNotExist()
    }

    @Test
    fun aMissingDocumentShowsThePublishCommand() {
        render(UpdatesSamples.manifestMissing())

        composeRule.onNodeWithText("No update document is published").assertIsDisplayed()
        composeRule.onNodeWithText("On the computer, run this to publish the update document. Then check again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("mise run phone:publish").assertIsDisplayed()
    }

    @Test
    fun aFailedCheckIsCalledAFailedCheck() {
        render(UpdatesSamples.checkFailed())

        composeRule.onNodeWithText("Check failed").assertIsDisplayed()
        composeRule.onNodeWithText("The daemon did not answer").assertIsDisplayed()
    }

    @Test
    fun anIncompatibleReleaseNamesBothAndroidVersionsOnce() {
        render(UpdatesSamples.incompatible())

        val sentence = "This release needs Android 14 (API 34). This phone runs Android 13 (API 33)."
        composeRule.onAllNodesWithText(sentence).assertCountEquals(1)
    }

    @Test
    fun newPermissionsAreCountedAndTheirIdentifiersStayBehindDetails() {
        render(UpdatesSamples.modulePermissions())

        composeRule.onNodeWithText("3 new privileged permissions in this release. Reboot to apply them.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("android.permission.CONTROL_INCALL_EXPERIENCE").assertDoesNotExist()

        composeRule.onNodeWithText("Show details").performScrollTo().performClick()

        composeRule.onNodeWithText("Control in-call experience").assertIsDisplayed()
        composeRule.onNodeWithText("android.permission.CONTROL_INCALL_EXPERIENCE").assertIsDisplayed()
    }

    @Test
    fun theModuleInstallWaitsForTheAppUpdateThatCarriesIt() {
        var moduleInstalls = 0
        render(UpdatesSamples.modulePermissions(), Callbacks(installModule = { moduleInstalls++ }))

        composeRule.onNodeWithText(
            "This app update is step 1. Step 2 installs the module from it, then the phone reboots."
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Install module 0.4.0").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Install the app update first.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Install module 0.4.0").performClick()

        assertEquals(0, moduleInstalls)
    }

    @Test
    fun aMissingGrantOnTheSameModuleVersionIsAnInstallNotUpToDate() {
        render(UpdatesSamples.moduleGrantMissing())

        composeRule.onAllNodesWithText("Update available").assertCountEquals(1)
        composeRule.onNodeWithText("Install module 0.4.0").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText("1 new privileged permission in this release. Reboot to apply it.")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithText("Show details").performScrollTo().performClick()
        composeRule.onNodeWithText("Start after boot").assertIsDisplayed()
    }

    @Test
    fun aDisabledModuleIsItsOwnRowNotPartOfTheVersion() {
        render(UpdatesSamples.moduleDisabled())

        composeRule.onNodeWithText("Disabled").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("0.3.0 · disabled").assertDoesNotExist()
    }

    @Test
    fun anOlderDaemonShowsCommandsOnTheirOwnLines() {
        render(UpdatesUiState(check = UpdatesSamples.check(UpdatesSamples.installed(daemonVersion = "0.0.9"))))

        composeRule.onNodeWithText("Update the daemon on your computer to 0.1.0 or newer.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("On the computer, update the daemon, then restart it:").assertIsDisplayed()
        composeRule.onNodeWithText("mise run setup").assertIsDisplayed()
        composeRule.onNodeWithText("mise run daemon:install").assertIsDisplayed()
    }
}

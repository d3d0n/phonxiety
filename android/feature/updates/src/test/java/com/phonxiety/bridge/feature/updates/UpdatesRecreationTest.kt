package com.phonxiety.bridge.feature.updates

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import com.phonxiety.bridge.designsystem.PhonxietyTheme
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update confirmation is the view model's state, not the screen's: an activity recreation shows
 * the same dialog over the same pending install, on the same view model instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdatesRecreationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val service = FakeUpdatesService().apply {
        result = UpdatesSamples.updateAvailable().check ?: UpdatesSamples.check()
    }

    /** The view model a real host would keep across the recreation: bound to the activity's store. */
    private fun viewModel(activity: ComponentActivity): UpdatesViewModel {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(UpdatesViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return UpdatesViewModel(service, FakeBridgeState()) as T
            }
        }
        return ViewModelProvider(activity.viewModelStore, factory)[UpdatesViewModel::class.java]
    }

    private fun ComponentActivity.showUpdates(viewModel: UpdatesViewModel) {
        setContentView(
            ComposeView(this).apply {
                setContent {
                    PhonxietyTheme(dynamicColor = false) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            UpdatesScreen(
                                state = viewModel.state.collectAsStateWithLifecycle().value,
                                onBack = {},
                                onRefresh = viewModel::refresh,
                                onInstallApp = viewModel::installApp,
                                onInstallModule = viewModel::installModule,
                                onConfirmUpdate = viewModel::confirmUpdate,
                                onDismissUpdate = viewModel::dismissUpdateConfirmation,
                                onRollback = viewModel::rollback,
                                onConfirmRollback = viewModel::confirmRollback,
                                onDismissRollback = viewModel::dismissRollbackConfirmation,
                                onDismissRollbackRecovery = viewModel::dismissRollbackRecovery,
                                onDismiss = viewModel::dismiss
                            )
                        }
                    }
                }
            }
        )
    }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    @Test
    fun theUpdateConfirmationSurvivesRecreation() {
        var first: UpdatesViewModel? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val viewModel = viewModel(activity)
                first = viewModel
                activity.showUpdates(viewModel)
            }
            settle()
            first!!.installApp()
            settle()
            compose.onNodeWithText("Install 0.4.0?").assertExists()

            scenario.recreate()

            scenario.onActivity { activity ->
                val viewModel = viewModel(activity)
                assertSame("the recreation keeps the view model that holds the confirmation", first, viewModel)
                activity.showUpdates(viewModel)
            }
            settle()
            compose.onNodeWithText("Install 0.4.0?").assertExists()
        }
    }
}

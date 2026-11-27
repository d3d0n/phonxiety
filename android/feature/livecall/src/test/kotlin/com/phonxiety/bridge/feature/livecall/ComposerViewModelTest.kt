package com.phonxiety.bridge.feature.livecall

import com.phonxiety.bridge.data.ApiOutcome
import com.phonxiety.bridge.data.LiveCallFront
import com.phonxiety.bridge.designsystem.UiText
import com.phonxiety.bridge.model.ApiLimits
import com.phonxiety.bridge.model.DaemonInfo
import com.phonxiety.bridge.model.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The composer's two promises: validation is the daemon's and it is debounced, and what is dialed
 * is exactly what the confirmation listed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        LiveCallFront.resetForTest()
    }

    @After
    fun tearDown() {
        LiveCallFront.resetForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun validationWaitsForTheTypingPause() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(limits = ApiLimits(maxDurationSeconds = 1800, ringTimeoutSeconds = 90)))
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true, warnings = listOf("no result fields")))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()

        viewModel.onDestinationChange("+998 90 123 45 67")
        viewModel.onGoalChange("Book a table for two on Thursday at 20:00")
        advanceTimeBy(ComposerViewModel.VALIDATE_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(api.validateRequests.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, api.validateRequests.size)
        assertEquals("+998901234567", api.validateRequests.single().destination)
        assertTrue(viewModel.state.value.valid == true)
        assertEquals(listOf(UiText.Literal("no result fields")), viewModel.state.value.warnings)
    }

    @Test
    fun onlyTheLatestBriefIsValidated() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()

        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book")
        advanceTimeBy(100)
        viewModel.onGoalChange("Book a table for two")
        advanceUntilIdle()

        assertEquals(1, api.validateRequests.size)
        assertEquals("Book a table for two", api.validateRequests.single().goal)
    }

    @Test
    fun aFieldErrorNamesItsField() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = fieldInvalidError("destination", "emergency numbers cannot be called by the agent")

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("112")
        viewModel.onGoalChange("Ask something")
        advanceUntilIdle()

        assertEquals(
            UiText.Res(R.string.composer_error_destination_emergency),
            viewModel.state.value.destinationError
        )
        assertNull(viewModel.state.value.goalError)
        assertFalse(viewModel.state.value.ready)
    }

    @Test
    fun reviewAndPlaceSendTheConfirmationId() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(limits = ApiLimits(maxDurationSeconds = 1800, ringTimeoutSeconds = 90)))
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        api.place = ApiOutcome.Ok(200, snapshot(state = "dialing"))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two on Thursday")
        advanceUntilIdle()

        viewModel.review()
        assertTrue(viewModel.state.value.confirming)

        viewModel.confirm()
        advanceUntilIdle()

        val placed = api.placedRequests.single()
        assertNotNull(placed.confirmationId)
        assertTrue(placed.confirmationId!!.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
        assertEquals("+998901234567", placed.destination)
        assertEquals("Book a table for two on Thursday", placed.goal)
        assertEquals(900, placed.maxDurationSeconds)
        assertEquals("call-2026-11-26", viewModel.state.value.placedCallId)
        assertFalse(viewModel.state.value.confirming)
    }

    @Test
    fun reviewIsRefusedWithoutAValidBrief() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()

        viewModel.review()
        assertFalse(viewModel.state.value.confirming)
        assertFalse(viewModel.state.value.ready)
    }

    @Test
    fun limitsComeFromTheDaemonAndClamp() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo(limits = ApiLimits(maxDurationSeconds = 1800, ringTimeoutSeconds = 90)))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()

        viewModel.onMaxDurationChange(5_000)
        assertEquals(1800, viewModel.state.value.maxDurationSeconds)

        viewModel.onMaxDurationChange(5)
        assertEquals(ComposerViewModel.MIN_DURATION, viewModel.state.value.maxDurationSeconds)
    }

    @Test
    fun fieldsAreUniqueAndBounded() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()

        viewModel.onFieldDraftChange("reservation_time")
        viewModel.addField()
        viewModel.onFieldDraftChange("reservation_time")
        viewModel.addField()
        advanceUntilIdle()

        assertEquals(listOf("reservation_time"), viewModel.state.value.resultFields)
        assertEquals("", viewModel.state.value.fieldDraft)
    }

    @Test
    fun prefillOnlyFillsEmptyFields() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onGoalChange("Something else")

        viewModel.preload(destination = "+998901234567", goal = "Old goal", context = null, constraints = null)
        advanceUntilIdle()

        assertEquals("+998901234567", viewModel.state.value.destination)
        assertEquals("Something else", viewModel.state.value.goal)
    }

    @Test
    fun placingArmsTheOneShotReturnForThePlacedCall() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        api.place = ApiOutcome.Ok(200, snapshot(state = "dialing"))

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two on Thursday")
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()

        assertEquals("call-2026-11-26", LiveCallFront.pendingCallId())
    }

    @Test
    fun aFailedCheckKeepsTheDaemonsSentenceAndItsCode() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Failure(503, "link_down", "the bridge service is not running")

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two")
        advanceUntilIdle()

        val issue = viewModel.state.value.requestIssue
        assertNotNull(issue)
        assertEquals(UiText.Literal("the bridge service is not running"), issue?.headline)
        assertEquals("link_down", issue?.detail)
        assertFalse(viewModel.state.value.ready)
    }

    @Test
    fun aFailedPlacementKeepsTheMessageAndTheCodeApart() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        api.place = ApiOutcome.Failure(502, "link_down", "the daemon is unreachable")

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two on Thursday")
        advanceUntilIdle()
        viewModel.confirm()
        advanceUntilIdle()

        assertEquals(UiText.Literal("the daemon is unreachable"), viewModel.state.value.placeError)
        assertEquals("link_down", viewModel.state.value.placeErrorDetail)
        assertFalse(viewModel.state.value.placing)
    }

    @Test
    fun retryingAFailedPlacementAfterAnEditReviewsTheEditedBriefAndPlacesNothing() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        api.place = ApiOutcome.Failure(503, "link_down", "the daemon is unreachable")

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two on Thursday")
        advanceUntilIdle()
        viewModel.confirm()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.placeError)
        assertEquals(1, api.placedRequests.size)

        // The card's Retry is review(): the edit is validated again and shown by the sheet.
        viewModel.onGoalChange("Book a table for two at eight instead")
        advanceUntilIdle()
        viewModel.review()

        assertTrue(viewModel.state.value.confirming)
        assertNull(viewModel.state.value.placeError)
        assertEquals("the edited goal is what was validated", "Book a table for two at eight instead", api.validateRequests.last().goal)
        assertEquals("nothing was placed by the retry", 1, api.placedRequests.size)
    }

    @Test
    fun aFailedPlacementLeavesNoReturnToken() = runTest(dispatcher) {
        val api = FakeDaemonApi()
        api.info = ApiOutcome.Ok(200, DaemonInfo())
        api.validation = ApiOutcome.Ok(200, ValidationResult(valid = true))
        api.place = ApiOutcome.Failure(502, "link_down", "the daemon is unreachable")

        val viewModel = ComposerViewModel(api)
        advanceUntilIdle()
        viewModel.onDestinationChange("+998901234567")
        viewModel.onGoalChange("Book a table for two on Thursday")
        advanceUntilIdle()

        viewModel.confirm()
        advanceUntilIdle()

        assertNull(LiveCallFront.pendingCallId())
        assertFalse(LiveCallFront.awaitsCover())
    }
}

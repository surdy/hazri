package dev.surdy.hazri.vm

import dev.surdy.hazri.data.SourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The survey's half of the foreground service.
 *
 * What matters on Android is that every way a recording can end reaches [SurveyKeepAlive.stop],
 * because the one that does not is an ongoing notification with no recording behind it and
 * a scan the user cannot switch off. The notification's own Stop action and the swipe-out
 * path both call `SurveyViewModel.stop`, so they are the first case here twice over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurveyKeepAliveTest {

    private val clock = TestClock(500_000L)

    private class Harness(scope: TestScope, clock: TestClock) {
        val keepAlive = FakeSurveyKeepAlive()
        val repository = testRepository()
        val engine = SignalEngine(repository, scope.backgroundScope, clock)
        val viewModel =
            SurveyViewModel(repository, engine, scope.backgroundScope, clock, keepAlive)
        val source = FakeSignalSource()
    }

    private fun TestScope.harness() = Harness(this, clock)

    private suspend fun Harness.recordKitchen() {
        engine.useSource(SourceKind.SIMULATED, source)
        viewModel.addRoom("Kitchen")
        viewModel.start()
    }

    @Test
    fun `a recording announces its room and publishes at once`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.recordKitchen()

            assertEquals(listOf("Kitchen"), harness.keepAlive.started)
            // The first tick is not throttled: a notification that read "0:00 · 0 samples"
            // for its first second would look like a recording that had not started.
            assertEquals(1, harness.keepAlive.updates.size)
            assertEquals("Kitchen", harness.keepAlive.updates.single().room)
        }

    @Test
    fun `no room means no recording and nothing to keep alive`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.viewModel.start()

            assertTrue(harness.keepAlive.started.isEmpty())
            assertEquals(0, harness.keepAlive.stops)
        }

    @Test
    fun `updates are throttled to one a second`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.recordKitchen()

        // The screen ticks between two and three times a second. Within one second of the
        // clock the notification's text cannot have changed, so neither should the calls.
        advanceTimeBy(1_000)
        assertEquals(1, harness.keepAlive.updates.size)

        harness.source.emit("kitchen", -55, clock.advance(1_000))
        advanceTimeBy(1_000)
        assertEquals(2, harness.keepAlive.updates.size)

        val latest = harness.keepAlive.updates.last()
        assertEquals(1_000L, latest.elapsedMillis)
        assertEquals(1, latest.sampleCount)
    }

    @Test
    fun `the user stopping ends it`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.recordKitchen()
        harness.source.emit("kitchen", -55, clock.advance(100))

        harness.viewModel.stop()
        assertEquals(1, harness.keepAlive.stops)
    }

    @Test
    fun `switching source ends it`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.recordKitchen()
        harness.source.emit("kitchen", -55, clock.advance(100))

        harness.engine.useSource(SourceKind.DIRECT, FakeSignalSource())
        assertEquals(1, harness.keepAlive.stops)
    }

    @Test
    fun `a recording that took nothing still ends it`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.recordKitchen()

        // Nothing was heard, so no survey is filed — but the service was started, and a
        // stop that only ran on the filing path would leave it running.
        harness.viewModel.stop()
        assertTrue(harness.repository.latestSurveys().isEmpty())
        assertEquals(1, harness.keepAlive.stops)
    }

    @Test
    fun `stopping when nothing is recording says nothing`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.viewModel.stop()
        harness.engine.useSource(SourceKind.DIRECT, harness.source)

        assertEquals(0, harness.keepAlive.stops)
    }

    @Test
    fun `a second recording starts a second time`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.recordKitchen()
        harness.viewModel.stop()

        harness.viewModel.addRoom("Garage")
        harness.viewModel.start()

        assertEquals(listOf("Kitchen", "Garage"), harness.keepAlive.started)
        assertEquals(1, harness.keepAlive.stops)
    }
}

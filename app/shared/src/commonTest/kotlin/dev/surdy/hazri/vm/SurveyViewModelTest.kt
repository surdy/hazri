package dev.surdy.hazri.vm

import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.Verdict
import dev.surdy.hazri.source.SimulatedSignalSource
import dev.surdy.hazri.source.VirtualHouse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyViewModelTest {

    private val clock = TestClock(500_000L)

    private class Harness(scope: TestScope, val clock: TestClock) {
        val repository = testRepository()
        val engine = SignalEngine(repository, scope.backgroundScope, clock)
        val viewModel = SurveyViewModel(repository, engine, scope.backgroundScope, clock)
        val source = FakeSignalSource()
    }

    private fun TestScope.harness() = Harness(this, clock)

    @Test
    fun `recording needs a room`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.viewModel.start()
        assertFalse(harness.viewModel.uiState.value.isRecording)
    }

    @Test
    fun `adding a room selects it and offers it as a chip`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.viewModel.addRoom("  Landing  ")

        assertEquals("Landing", harness.viewModel.uiState.value.selectedRoom)
        assertEquals(listOf("Landing"), harness.viewModel.uiState.value.rooms)
    }

    @Test
    fun `a blank room is not a room`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.viewModel.addRoom("   ")
        assertTrue(harness.viewModel.uiState.value.rooms.isEmpty())
    }

    @Test
    fun `recording accumulates and files a survey`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.SIMULATED, harness.source)
        harness.viewModel.addRoom("Kitchen")
        harness.viewModel.start()

        repeat(4) { harness.source.emit("kitchen", -55, clock.advance(100)) }
        harness.source.emit("hall", -75, clock.advance(100))
        testScheduler.advanceTimeBy(1_000)

        assertTrue(harness.viewModel.uiState.value.isRecording)
        assertEquals(5, harness.viewModel.uiState.value.sampleCount)
        assertEquals(Verdict.CLEAR, harness.viewModel.uiState.value.runningVerdict!!.verdict)

        harness.viewModel.stop()

        val survey = harness.repository.latestSurveys().single()
        assertEquals("Kitchen", survey.room)
        assertEquals(mapOf(NodeId("kitchen") to -55.0, NodeId("hall") to -75.0), survey.means())
        assertFalse(harness.viewModel.uiState.value.isRecording)
    }

    @Test
    fun `a recording with nothing in it is not filed`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.SIMULATED, harness.source)
        harness.viewModel.addRoom("Kitchen")
        harness.viewModel.start()
        harness.viewModel.stop()

        assertTrue(harness.repository.surveys.value.isEmpty())
    }

    @Test
    fun `the room cannot be changed mid-recording`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.SIMULATED, harness.source)
        harness.viewModel.addRoom("Kitchen")
        harness.viewModel.addRoom("Hallway")
        harness.viewModel.selectRoom("Kitchen")
        harness.viewModel.start()
        harness.viewModel.selectRoom("Hallway")

        assertEquals("Kitchen", harness.viewModel.uiState.value.selectedRoom)
    }

    @Test
    fun `the simulated walk is pinned for the recording and released after`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            val simulator = SimulatedSignalSource(scope = backgroundScope, clock = clock)
            harness.engine.useSource(SourceKind.SIMULATED, simulator)
            harness.viewModel.addRoom("Garage")
            harness.viewModel.start()

            // The whole point: a Garage recording taken while the scripted loop happens to be
            // in the kitchen would be won by the Kitchen node and look entirely plausible.
            assertEquals("Garage", simulator.currentRoom)
            repeat(50) { simulator.tick(clock.advance(120)) }
            assertEquals("Garage", simulator.currentRoom)

            harness.viewModel.stop()
            repeat(500) { simulator.tick(clock.advance(120)) }
            assertTrue(simulator.currentRoom != "Garage", "the walk should resume its loop")
        }

    @Test
    fun `pinning to a room the virtual house does not have keeps the walk moving`() =
        runTest(UnconfinedTestDispatcher()) {
            val simulator = SimulatedSignalSource(scope = backgroundScope, clock = clock)
            simulator.pinTo("Boiler cupboard")

            val visited = mutableSetOf<String>()
            repeat(3_000) { index ->
                simulator.tick(index.toLong())
                visited += simulator.currentRoom
            }
            assertEquals(VirtualHouse.DEFAULT.rooms.map { it.name }.toSet(), visited)
        }

    @Test
    fun `surveyed rooms are listed newest first with their verdict and age`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.engine.useSource(SourceKind.SIMULATED, harness.source)

            harness.viewModel.addRoom("Kitchen")
            harness.viewModel.start()
            harness.source.emit("kitchen", -55, clock.advance(100))
            harness.viewModel.stop()

            clock.advance(120_000)
            harness.viewModel.addRoom("Garage")
            harness.viewModel.start()
            harness.source.emit("hall", -92, clock.advance(100))
            harness.viewModel.stop()

            val surveyed = harness.viewModel.uiState.value.surveyed
            assertEquals(listOf("Garage", "Kitchen"), surveyed.map { it.survey.room })
            assertEquals(Verdict.BLIND, surveyed.first().verdict.verdict)
            assertTrue(surveyed.last().ageMillis >= 120_000)
        }

    @Test
    fun `discarding a room forgets its surveys`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.SIMULATED, harness.source)
        harness.viewModel.addRoom("Kitchen")
        harness.viewModel.start()
        harness.source.emit("kitchen", -55, clock.advance(100))
        harness.viewModel.stop()

        harness.viewModel.discard("Kitchen")
        assertTrue(harness.viewModel.uiState.value.surveyed.isEmpty())
    }

    @Test
    fun `switching source ends the recording rather than mixing two measurements`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.engine.useSource(SourceKind.DIRECT, harness.source)
            harness.viewModel.addRoom("Kitchen")
            harness.viewModel.start()
            harness.source.emit("kitchen", -55, clock.advance(100))

            val overMqtt = FakeSignalSource()
            harness.engine.useSource(SourceKind.MQTT, overMqtt)
            overMqtt.emit("kitchen", -90, clock.advance(100))

            // The walk is filed against the source it was taken with, and the new source's
            // samples are not averaged into it.
            assertFalse(harness.viewModel.uiState.value.isRecording)
            val survey = harness.repository.latestSurveys().single()
            assertEquals(-55.0, survey.means()[NodeId("kitchen")]!!, 1e-9)
            assertEquals(1, survey.sampleCount)
        }

    @Test
    fun `switching source releases the simulated pin`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        val simulator = SimulatedSignalSource(scope = backgroundScope, clock = clock)
        harness.engine.useSource(SourceKind.SIMULATED, simulator)
        harness.viewModel.addRoom("Garage")
        harness.viewModel.start()
        assertEquals("Garage", simulator.currentRoom)

        harness.engine.useSource(SourceKind.DIRECT, FakeSignalSource())
        repeat(500) { simulator.tick(clock.advance(120)) }

        // Left pinned, the simulator would still be standing in the Garage the next time it
        // was selected, with no recording to explain why.
        assertTrue(simulator.currentRoom != "Garage")
    }

    @Test
    fun `nothing is recording before start`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        assertNull(harness.viewModel.uiState.value.runningVerdict)
        assertEquals(0, harness.viewModel.uiState.value.sampleCount)
    }
}

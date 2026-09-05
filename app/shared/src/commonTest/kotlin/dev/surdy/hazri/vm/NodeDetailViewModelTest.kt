package dev.surdy.hazri.vm

import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.protocol.Espresense
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
class NodeDetailViewModelTest {

    private val kitchen = NodeId("kitchen")

    /** Records what would have been published, and can be made to refuse. */
    private class RecordingPublisher(val accepts: Boolean = true) : SettingPublisher {
        val pushed = mutableListOf<NodeConfig>()
        override suspend fun pushTuning(config: NodeConfig): Boolean {
            pushed += config
            return accepts
        }
    }

    private class Harness(scope: TestScope, publisher: SettingPublisher) {
        val clock = TestClock(700_000L)
        val repository = testRepository()
        val engine = SignalEngine(repository, scope.backgroundScope, clock)
        val source = FakeSignalSource()

        fun viewModel(scope: TestScope, publisher: SettingPublisher) =
            NodeDetailViewModel(NodeId("kitchen"), repository, engine, publisher, scope.backgroundScope, clock)

        val viewModel = viewModel(scope, publisher)
    }

    private fun TestScope.harness(publisher: SettingPublisher = RecordingPublisher()) =
        Harness(this, publisher)

    @Test
    fun `an unknown node still has a workable config`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        assertEquals("kitchen", harness.viewModel.uiState.value.config.room)
        assertFalse(harness.viewModel.uiState.value.refRssiIsKnown)
    }

    @Test
    fun `editing tuning cannot move the room`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.repository.noteNode(kitchen)
        harness.viewModel.editConfig { it.copy(room = "somewhere-else", absorption = 3.1) }

        assertEquals("kitchen", harness.viewModel.uiState.value.config.room)
        assertEquals(3.1, harness.viewModel.uiState.value.config.absorption, 1e-9)
        assertEquals("kitchen", harness.repository.node(kitchen)!!.config().room)
    }

    @Test
    fun `the copyable config is the wire format for the firmware room`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.repository.noteNode(kitchen)
            // A display name that would slugify to something else entirely.
            harness.repository.renameNode(kitchen, "Under the stairs")
            harness.viewModel.editConfig { it.copy(refRssi = -61, absorption = 3.0, maxDistance = 6.0) }

            val commands = harness.viewModel.configAsCommands().lines()
            assertEquals(
                listOf(
                    "espresense/rooms/kitchen/ref_rssi/set −61".replace('−', '-'),
                    "espresense/rooms/kitchen/absorption/set 3",
                    "espresense/rooms/kitchen/max_distance/set 6",
                ),
                commands,
            )
            assertTrue(commands.none { it.contains("under") })
        }

    @Test
    fun `a push records that ref_rssi is now known`() = runTest(UnconfinedTestDispatcher()) {
        val publisher = RecordingPublisher()
        val harness = harness(publisher)
        harness.repository.noteNode(kitchen)
        harness.viewModel.editConfig { it.copy(refRssi = -61) }

        assertEquals(PushState.NOT_PUSHED, harness.viewModel.uiState.value.pushState)
        harness.viewModel.pushConfig()

        assertEquals(PushState.PUSHED, harness.viewModel.uiState.value.pushState)
        assertEquals("kitchen", publisher.pushed.single().room)
        assertTrue(harness.viewModel.uiState.value.refRssiIsKnown)
        assertEquals(700_000L, harness.repository.node(kitchen)!!.refRssiPushedAt)
    }

    @Test
    fun `a refused push leaves ref_rssi unknown`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RecordingPublisher(accepts = false))
        harness.repository.noteNode(kitchen)
        harness.viewModel.pushConfig()

        assertEquals(PushState.FAILED, harness.viewModel.uiState.value.pushState)
        assertFalse(harness.viewModel.uiState.value.refRssiIsKnown)
    }

    @Test
    fun `a room the broker announces reaches the screen without a reopen`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.repository.noteNode(kitchen)
            harness.repository.learnBrokerRoom(Espresense.nodeFingerprint(41, 7), "kitchen")

            assertTrue(harness.viewModel.uiState.value.roomIsConfirmed)
        }

    @Test
    fun `calibration needs enough samples and then reports the power at one metre`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.engine.useSource(SourceKind.MQTT, harness.source)
            harness.repository.noteNode(kitchen)
            harness.viewModel.startCalibration()

            repeat(19) { harness.source.emit("kitchen", -59, harness.clock.advance(50)) }
            assertNull(harness.viewModel.uiState.value.calibration!!.result)

            harness.source.emit("kitchen", -59, harness.clock.advance(50))
            val result = harness.viewModel.uiState.value.calibration!!.result!!
            assertEquals(-59, result.measuredPowerAtOneMetre)
            assertEquals(20, result.sampleCount)
        }

    @Test
    fun `calibration ignores other nodes`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.MQTT, harness.source)
        harness.viewModel.startCalibration()

        harness.source.emit("hall", -40, harness.clock.advance(50))
        assertEquals(0, harness.viewModel.uiState.value.calibration!!.sampleCount)
    }

    @Test
    fun `the calibration reference follows the running source`() =
        runTest(UnconfinedTestDispatcher()) {
            // Two metres from the node, reading -70. Projected back to one metre the answer
            // depends only on absorption, so both modes agree on the number — but they mean
            // different devices, which is what calibratesPhoneBeacon gates the copy on.
            val harness = harness()
            harness.repository.noteNode(kitchen)

            harness.engine.useSource(SourceKind.MQTT, harness.source)
            assertTrue(harness.viewModel.calibratesPhoneBeacon)

            harness.engine.useSource(SourceKind.DIRECT, FakeSignalSource())
            assertFalse(harness.viewModel.calibratesPhoneBeacon)
        }

    @Test
    fun `stopping a calibration clears it`() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.engine.useSource(SourceKind.MQTT, harness.source)
        harness.viewModel.startCalibration()
        harness.viewModel.stopCalibration()

        assertNull(harness.viewModel.uiState.value.calibration)
    }

    @Test
    fun `the age shown is the gap between now and the last sample`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = harness()
            harness.engine.useSource(SourceKind.MQTT, harness.source)
            harness.source.emit("kitchen", -60, harness.clock.now())
            harness.clock.advance(2_500)
            testScheduler.advanceTimeBy(SignalEngine.REFRESH_MILLIS * 2)

            assertEquals(2_500L, harness.viewModel.uiState.value.sinceLastSeenMillis)
        }
}

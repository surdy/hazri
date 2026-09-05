package dev.surdy.hazri.vm

import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.protocol.NodeTelemetry
import dev.surdy.hazri.source.MqttConnectionState
import dev.surdy.hazri.source.MqttMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsViewModelTest {

    private class Gateway {
        val connection = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
        val telemetry = MutableStateFlow<Map<String, NodeTelemetry>>(emptyMap())
        val inspector = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 32)
    }

    private class Harness(scope: TestScope) {
        val clock = TestClock(1_000L)
        val repository = testRepository()
        val engine = SignalEngine(repository, scope.backgroundScope, clock)
        val viewModel = ToolsViewModel(repository, engine, scope.backgroundScope)
    }

    @Test
    fun `connection state and messages reach the state`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        val gateway = Gateway()
        harness.viewModel.observeMqtt(gateway.connection, gateway.inspector, gateway.telemetry)

        gateway.connection.value = MqttConnectionState.Connected("10.0.0.12", 1883)
        gateway.inspector.emit(MqttMessage("espresense/devices/x/kitchen", "{}", 1_000L))
        gateway.telemetry.value = mapOf("kitchen" to NodeTelemetry(ip = "10.0.0.31"))

        val state = harness.viewModel.uiState.value
        assertEquals(MqttConnectionState.Connected("10.0.0.12", 1883), state.mqtt)
        assertEquals(1, state.inspector.size)
        assertEquals("10.0.0.31", state.telemetry["kitchen"]?.ip)
    }

    @Test
    fun `re-observing detaches from the previous gateway`() = runTest(UnconfinedTestDispatcher()) {
        // MQTT mode is re-entered every time the source picker is touched, and each entry
        // brings a new gateway. Leaving the old collectors alive meant the connection pill
        // showed whichever gateway emitted last.
        val harness = Harness(this)
        val old = Gateway()
        val fresh = Gateway()

        harness.viewModel.observeMqtt(old.connection, old.inspector, old.telemetry)
        old.connection.value = MqttConnectionState.Connected("old", 1883)

        harness.viewModel.observeMqtt(fresh.connection, fresh.inspector, fresh.telemetry)
        old.connection.value = MqttConnectionState.Connected("old-again", 1883)
        old.inspector.emit(MqttMessage("stale", "{}", 1L))

        assertEquals(MqttConnectionState.Disconnected, harness.viewModel.uiState.value.mqtt)
        assertTrue(harness.viewModel.uiState.value.inspector.isEmpty())

        fresh.connection.value = MqttConnectionState.Connected("fresh", 1883)
        assertEquals(
            MqttConnectionState.Connected("fresh", 1883),
            harness.viewModel.uiState.value.mqtt,
        )
    }

    @Test
    fun `detaching reports the connection as gone`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        val gateway = Gateway()
        harness.viewModel.observeMqtt(gateway.connection, gateway.inspector, gateway.telemetry)
        gateway.connection.value = MqttConnectionState.Connected("10.0.0.12", 1883)

        harness.viewModel.stopObservingMqtt()
        gateway.connection.value = MqttConnectionState.Connected("10.0.0.12", 1883)

        assertEquals(MqttConnectionState.Disconnected, harness.viewModel.uiState.value.mqtt)
    }

    @Test
    fun `renaming a node does not move its topic`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        harness.repository.noteNode(NodeId("kitchen"))
        harness.viewModel.renameNode(NodeId("kitchen"), "Bread bin")

        val record = harness.viewModel.uiState.value.nodes.single()
        assertEquals("Bread bin", record.displayName)
        assertEquals("kitchen", record.espresenseRoom)
    }

    @Test
    fun `the firmware room can be corrected by hand`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        harness.repository.noteNode(NodeId("node-41-7"))
        harness.viewModel.setEspresenseRoom(NodeId("node-41-7"), "Living Room")

        val record = harness.viewModel.uiState.value.nodes.single()
        assertEquals("living_room", record.espresenseRoom)
        assertTrue(record.roomIsConfirmed)
    }

    @Test
    fun `hiding a node is reflected in the list`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        harness.repository.noteNode(NodeId("kitchen"))
        harness.viewModel.setHidden(NodeId("kitchen"), true)

        assertTrue(harness.viewModel.uiState.value.nodes.single().hidden)
        assertTrue(harness.repository.visibleNodes().isEmpty())
    }

    @Test
    fun `a comparison differences the two streams per node`() =
        runTest(UnconfinedTestDispatcher()) {
            val harness = Harness(this)
            val primary = FakeSignalSource()
            val secondary = FakeSignalSource()
            harness.engine.useSource(SourceKind.SIMULATED, primary)

            harness.viewModel.startComparison("Direct", "Sim B", secondary)
            primary.emit("kitchen", -60, 1_000L)
            secondary.emit("kitchen", -70, 1_000L)

            val delta = harness.viewModel.uiState.value.deltas.single()
            assertEquals("Direct", harness.viewModel.uiState.value.comparisonPrimary)
            assertEquals(-60.0, delta.primaryRssi!!, 1e-9)
            assertEquals(-70.0, delta.secondaryRssi!!, 1e-9)
            assertEquals(10.0, delta.deltaDb!!, 1e-9)
        }

    @Test
    fun `a node heard by only one stream has no delta`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        val primary = FakeSignalSource()
        val secondary = FakeSignalSource()
        harness.engine.useSource(SourceKind.SIMULATED, primary)

        harness.viewModel.startComparison("Direct", "Sim B", secondary)
        primary.emit("kitchen", -60, 1_000L)

        assertEquals(null, harness.viewModel.uiState.value.deltas.single().deltaDb)
    }

    @Test
    fun `stopping a comparison stops the second source`() = runTest(UnconfinedTestDispatcher()) {
        val harness = Harness(this)
        val secondary = FakeSignalSource()
        harness.viewModel.startComparison("Direct", "Sim B", secondary)
        harness.viewModel.stopComparison()

        assertFalse(harness.viewModel.uiState.value.comparing)
        assertTrue(secondary.stopped >= 1)
    }
}

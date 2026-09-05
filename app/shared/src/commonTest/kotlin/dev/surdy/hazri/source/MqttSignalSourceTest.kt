package dev.surdy.hazri.source

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.protocol.EspresenseSetting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A broker that records what was published and hands back whatever is fed to it. */
private class FakeGateway : MqttGateway {
    val published = mutableListOf<Pair<String, String>>()
    val subscriptions = mutableListOf<String>()
    val incoming = MutableSharedFlow<MqttMessage>(extraBufferCapacity = 64)

    private val state = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    override val connection: StateFlow<MqttConnectionState> = state
    override val messages: Flow<MqttMessage> = incoming

    override suspend fun connect(config: BrokerConfig) {
        state.value = MqttConnectionState.Connected(config.host, config.port)
    }

    override suspend fun subscribe(topicFilter: String) {
        subscriptions += topicFilter
    }

    override suspend fun publish(topic: String, payload: String, retain: Boolean): Boolean {
        published += topic to payload
        return true
    }

    override suspend fun disconnect() {
        state.value = MqttConnectionState.Disconnected
    }
}

/**
 * The source is exercised on an unconfined test dispatcher so that a launched collector
 * subscribes before the test emits into the gateway. On the standard dispatcher the
 * subscription would still be queued when the first message went out, and a
 * [MutableSharedFlow] with no replay drops what nobody is listening for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttSignalSourceTest {

    private val phoneId = "iBeacon:1d4b2e16-481e-4579-8b35-ffc32e4a1758-100-1"

    @Test
    fun `it subscribes to devices telemetry and settings`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        source(gateway).start()

        assertTrue(gateway.subscriptions.contains("espresense/devices/$phoneId/#"))
        assertTrue(gateway.subscriptions.contains("espresense/rooms/+/telemetry"))
        assertTrue(gateway.subscriptions.contains("espresense/settings/+/config"))
    }

    @Test
    fun `a device report becomes a sample keyed on the room`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)
        val received = mutableListOf<SignalSample>()
        backgroundScope.launch { source.samples.collect { received += it } }
        source.start()

        gateway.incoming.emit(
            MqttMessage(
                topic = "espresense/devices/$phoneId/kitchen",
                payload = """{"id":"$phoneId","rssi":-72.35,"distance":3.42}""",
                receivedAt = 1_700L,
            )
        )

        assertEquals(1, received.size)
        assertEquals(NodeId("kitchen"), received.first().nodeId)
        assertEquals(-72, received.first().rssi)
        assertEquals(1_700L, received.first().timestamp)
        assertEquals(Source.MQTT, received.first().source)
    }

    @Test
    fun `a report is keyed on the node already known for that room`() =
        runTest(UnconfinedTestDispatcher()) {
            // A device topic names a room, not a node. On a live broker the node has already
            // announced itself under its beacon id, so keying on the room regardless would
            // make one board into two records.
            val gateway = FakeGateway()
            val source = MqttSignalSource(
                gateway = gateway,
                phoneId = phoneId,
                scope = backgroundScope,
                clock = MillisClock { 0L },
                nodeIdForRoom = { room -> if (room == "kitchen") NodeId("node-41-7") else null },
            )
            val received = mutableListOf<SignalSample>()
            backgroundScope.launch { source.samples.collect { received += it } }
            source.start()

            gateway.incoming.emit(
                MqttMessage("espresense/devices/$phoneId/kitchen", """{"rssi":-70.0}""", 1L)
            )

            assertEquals(NodeId("node-41-7"), received.single().nodeId)
        }

    @Test
    fun `a room nothing is known for falls back to the room itself`() =
        runTest(UnconfinedTestDispatcher()) {
            val gateway = FakeGateway()
            val source = source(gateway)
            val received = mutableListOf<SignalSample>()
            backgroundScope.launch { source.samples.collect { received += it } }
            source.start()

            gateway.incoming.emit(
                MqttMessage("espresense/devices/$phoneId/attic", """{"rssi":-70.0}""", 1L)
            )

            assertEquals(NodeId("attic"), received.single().nodeId)
        }

    @Test
    fun `reports about another phone are ignored`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)
        val received = mutableListOf<SignalSample>()
        backgroundScope.launch { source.samples.collect { received += it } }
        source.start()

        gateway.incoming.emit(
            MqttMessage("espresense/devices/someone-else/kitchen", """{"rssi":-60.0}""", 1L)
        )

        assertTrue(received.isEmpty())
    }

    @Test
    fun `malformed payloads are dropped rather than crashing the stream`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)
        val received = mutableListOf<SignalSample>()
        backgroundScope.launch { source.samples.collect { received += it } }
        source.start()

        gateway.incoming.emit(MqttMessage("espresense/devices/$phoneId/hall", "not json", 1L))
        gateway.incoming.emit(MqttMessage("espresense/devices/$phoneId/hall", """{"batt":90}""", 2L))
        gateway.incoming.emit(MqttMessage("espresense/devices/$phoneId/hall", """{"rssi":-70.0}""", 3L))

        assertEquals(listOf(-70), received.map { it.rssi })
    }

    @Test
    fun `telemetry is kept per room`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)
        source.start()

        gateway.incoming.emit(
            MqttMessage("espresense/rooms/hall/telemetry", """{"ip":"10.0.0.31","uptime":90}""", 1L)
        )

        assertEquals("10.0.0.31", source.telemetry.value["hall"]?.ip)
    }

    @Test
    fun `a node announcing itself reports its room and fingerprint`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val announcements = mutableListOf<Pair<String, String>>()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        val source = MqttSignalSource(
            gateway = gateway,
            phoneId = phoneId,
            scope = backgroundScope,
            clock = MillisClock { 0L },
            onNodeAnnouncement = { id, room -> announcements += id to room },
        )
        source.start()

        gateway.incoming.emit(
            MqttMessage(
                topic = "espresense/settings/$fingerprint/config",
                payload = """{"id":"node:kitchen","name":"kitchen"}""",
                receivedAt = 1L,
            )
        )

        assertEquals(listOf(fingerprint to "kitchen"), announcements)
    }

    @Test
    fun `an enrolled device is not treated as a node announcement`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val announcements = mutableListOf<Pair<String, String>>()
        val source = MqttSignalSource(
            gateway = gateway,
            phoneId = phoneId,
            scope = backgroundScope,
            onNodeAnnouncement = { id, room -> announcements += id to room },
        )
        source.start()

        gateway.incoming.emit(
            MqttMessage("espresense/settings/$phoneId/config", """{"id":"$phoneId","name":"me"}""", 1L)
        )

        assertTrue(announcements.isEmpty())
    }

    @Test
    fun `a setting is published to the firmware's set topic`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)

        source.publishSetting("Kitchen", EspresenseSetting.MAX_DISTANCE, "6.0")
        assertEquals("espresense/rooms/kitchen/max_distance/set" to "6.0", gateway.published.last())
    }

    @Test
    fun `a reset publishes an empty payload`() = runTest(UnconfinedTestDispatcher()) {
        val gateway = FakeGateway()
        val source = source(gateway)

        source.resetSetting("kitchen", EspresenseSetting.ABSORPTION)
        assertEquals("espresense/rooms/kitchen/absorption/set" to "", gateway.published.last())
    }

    private fun TestScope.source(gateway: FakeGateway) = MqttSignalSource(
        gateway = gateway,
        phoneId = phoneId,
        scope = backgroundScope,
        clock = MillisClock { 0L },
    )
}

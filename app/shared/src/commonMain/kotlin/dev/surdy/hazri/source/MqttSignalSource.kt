package dev.surdy.hazri.source

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.protocol.EspresenseParser
import dev.surdy.hazri.protocol.EspresenseSetting
import dev.surdy.hazri.protocol.NodeTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the nodes report about this phone, over MQTT.
 *
 * This is ground truth: it is the same number Home Assistant will act on, produced by the
 * same node, through the same firmware. Direct scan is a proxy for it; when the two
 * disagree, this one is right.
 *
 * The phone must be advertising for anything to arrive — the Home Assistant Companion
 * app's BLE transmitter is the intended source of that advertisement, and [phoneId] is the
 * fingerprint it advertises under, which is the third segment of the device topic.
 *
 * Everything here is common code. The only platform-specific part of MQTT mode is the
 * [MqttGateway] implementation this is handed.
 */
class MqttSignalSource(
    private val gateway: MqttGateway,
    private val phoneId: String,
    private val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
    /**
     * Called when a node announces itself on
     * `espresense/settings/iBeacon:<uuid>-<major>-<minor>/config`.
     *
     * This is the only place the fingerprint-to-room mapping exists, and it is what lets a
     * direct scan name its nodes. The repository decides whether to apply it — a room the
     * user has named by hand wins over one the broker reports.
     */
    private val onNodeAnnouncement: (fingerprint: String, room: String) -> Unit = { _, _ -> },
    /**
     * The node id already known for a firmware room, consulted for every device report.
     *
     * A device topic names a room, not a node, and a node is normally already on record by
     * the time its first report arrives — announced by its own retained settings config,
     * under the id its beacon gives it. Keying samples on the room regardless would create a
     * second record for the same physical node, with direct samples landing on one and MQTT
     * samples on the other. Falls back to the room when nothing is known, which is the
     * first-contact case.
     */
    private val nodeIdForRoom: (String) -> NodeId? = { null },
) : SignalSource {

    override val source: Source = Source.MQTT

    private val emitted = MutableSharedFlow<SignalSample>(extraBufferCapacity = 256)
    override val samples: Flow<SignalSample> = emitted.asSharedFlow()

    private val telemetryByRoom = MutableStateFlow<Map<String, NodeTelemetry>>(emptyMap())

    /** The most recent telemetry per room, for the node health line on Tools. */
    val telemetry: StateFlow<Map<String, NodeTelemetry>> = telemetryByRoom.asStateFlow()

    private val rawMessages = MutableSharedFlow<MqttMessage>(
        replay = INSPECTOR_REPLAY,
        extraBufferCapacity = 128,
    )

    /** Every message received, with a short replay so the MQTT inspector opens populated. */
    val inspector: Flow<MqttMessage> = rawMessages.asSharedFlow()

    /** The broker connection, passed through from the gateway. */
    val connection: StateFlow<MqttConnectionState> get() = gateway.connection

    private var job: Job? = null

    override suspend fun start() {
        if (job != null) return
        job = scope.launch {
            gateway.messages.collect { message ->
                rawMessages.emit(message)
                dispatch(message)
            }
        }
        gateway.subscribe(Espresense.deviceSubscription(phoneId))
        gateway.subscribe(Espresense.telemetrySubscription())
        gateway.subscribe(Espresense.settingsSubscription())
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Stops collecting and closes the broker connection.
     *
     * Separate from [stop] because [SignalSource.stop] is not suspending and closing an MQTT
     * connection is: a source that is merely stopped keeps its socket, its reconnect timer
     * and its subscriptions, which is right when pausing and wrong when replacing it.
     */
    suspend fun shutdown() {
        stop()
        gateway.disconnect()
    }

    /**
     * Writes one setting on one node, or on every node when [room] is
     * [Espresense.ALL_ROOMS].
     *
     * An empty [value] is meaningful: ESPresense reads it as "reset this setting to its
     * default". [resetSetting] is the same call spelled so that nobody has to know that.
     *
     * @return whether the broker accepted the publish. It says nothing about whether the
     *   node applied it — ESPresense does not acknowledge a `set`, and a `name/set` does not
     *   even take effect until the node restarts. That is why the Node detail screen says
     *   "not pushed" rather than "not applied".
     */
    suspend fun publishSetting(room: String, setting: EspresenseSetting, value: String): Boolean =
        gateway.publish(Espresense.settingTopic(room, setting), value)

    /** [publishSetting] by raw key, for settings outside [EspresenseSetting]. */
    suspend fun publishSetting(room: String, key: String, value: String): Boolean =
        gateway.publish(Espresense.settingTopic(room, key), value)

    /** Resets one setting to the firmware default, by publishing an empty payload. */
    suspend fun resetSetting(room: String, setting: EspresenseSetting): Boolean =
        gateway.publish(Espresense.settingTopic(room, setting), "")

    private suspend fun dispatch(message: MqttMessage) {
        Espresense.parseDeviceTopic(message.topic)?.let { topic ->
            if (topic.deviceId != phoneId) return@let
            val report = EspresenseParser.parseDeviceReport(message.payload) ?: return@let
            val rssi = report.rssiOrNull() ?: return@let
            emitted.emit(
                SignalSample(
                    nodeId = nodeIdForRoom(topic.room) ?: NodeId(topic.room),
                    rssi = rssi,
                    timestamp = message.receivedAt.takeIf { it > 0 } ?: clock.now(),
                    source = Source.MQTT,
                )
            )
            return
        }
        Espresense.parseTelemetryTopic(message.topic)?.let { room ->
            val parsed = EspresenseParser.parseTelemetry(message.payload) ?: return@let
            telemetryByRoom.value = telemetryByRoom.value + (room to parsed)
            return
        }
        Espresense.parseSettingsConfigTopic(message.topic)?.let { fingerprint ->
            val config = EspresenseParser.parseDeviceConfig(message.payload) ?: return@let
            val room = config.nodeRoom ?: return@let
            onNodeAnnouncement(fingerprint, room)
        }
    }

    companion object {
        private const val INSPECTOR_REPLAY: Int = 50
    }
}

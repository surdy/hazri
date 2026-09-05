package dev.surdy.hazri.vm

import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.NodeRecord
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.protocol.NodeTelemetry
import dev.surdy.hazri.source.MqttConnectionState
import dev.surdy.hazri.source.MqttMessage
import dev.surdy.hazri.source.SignalSource
import dev.surdy.hazri.source.UnidentifiedAdvertiser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One node's per-source readings, for the Compare sources tool. */
data class SourceDelta(
    val nodeId: NodeId,
    val displayName: String,
    val primaryRssi: Double?,
    val secondaryRssi: Double?,
) {
    /** Primary minus secondary, or `null` if either side has not been heard yet. */
    val deltaDb: Double?
        get() = if (primaryRssi == null || secondaryRssi == null) null
        else primaryRssi - secondaryRssi
}

/** Everything the Tools screen and its sub-screens render. */
data class ToolsState(
    val mqtt: MqttConnectionState = MqttConnectionState.Disconnected,
    val mqttMessageRate: Double = 0.0,
    val telemetry: Map<String, NodeTelemetry> = emptyMap(),
    val inspector: List<MqttMessage> = emptyList(),
    val nodes: List<NodeRecord> = emptyList(),
    val rooms: List<String> = emptyList(),
    val comparing: Boolean = false,
    val comparisonPrimary: String = "",
    val comparisonSecondary: String = "",
    val deltas: List<SourceDelta> = emptyList(),
    /** Advertisers the scan heard that are not ESPresense nodes. Empty outside direct mode. */
    val unidentified: List<UnidentifiedAdvertiser> = emptyList(),
)

/**
 * The Tools screen: connection status, the MQTT inspector, Compare sources and the node
 * alias list.
 *
 * Compare sources is the reason this holds a second [SignalSource]. The primary is
 * whatever the app is running on; the secondary is started alongside it and read into a
 * separate set of means, so the two can be differenced per node. With no hardware the two
 * are simulated sources with different seeds, which exercises the screen and is honest
 * about what it is showing.
 */
class ToolsViewModel(
    private val repository: HazriRepository,
    private val engine: SignalEngine,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(ToolsState())
    val uiState: StateFlow<ToolsState> = state.asStateFlow()

    private val primaryMeans = LinkedHashMap<NodeId, Double>()
    private val secondaryMeans = LinkedHashMap<NodeId, Double>()
    private var secondary: SignalSource? = null
    private val compareJobs = mutableListOf<Job>()

    /**
     * The three collectors [observeMqtt] starts.
     *
     * Held so they can be cancelled: MQTT mode is re-entered every time the source picker is
     * touched, and each entry brings a new gateway. Without cancelling, every switch left
     * three collectors alive on a dead gateway, and the connection pill showed whichever of
     * them emitted last.
     */
    private val mqttJobs = mutableListOf<Job>()

    private var scannerJob: Job? = null
    private var clearScanner: () -> Unit = {}

    init {
        scope.launch {
            repository.nodes.collect { nodes -> state.value = state.value.copy(nodes = nodes) }
        }
        scope.launch {
            repository.rooms.collect { rooms -> state.value = state.value.copy(rooms = rooms) }
        }
    }

    /**
     * Attaches the live MQTT connection state, message stream and telemetry.
     *
     * Replaces any previous attachment. The inspector backlog is cleared with it, because a
     * message list that spans two different gateways is not one feed.
     */
    fun observeMqtt(
        connection: StateFlow<MqttConnectionState>,
        inspector: Flow<MqttMessage>,
        telemetry: StateFlow<Map<String, NodeTelemetry>>,
    ) {
        stopObservingMqtt()
        state.value = state.value.copy(inspector = emptyList(), mqttMessageRate = 0.0)
        mqttJobs += scope.launch {
            connection.collect { state.value = state.value.copy(mqtt = it) }
        }
        mqttJobs += scope.launch {
            telemetry.collect { state.value = state.value.copy(telemetry = it) }
        }
        mqttJobs += scope.launch {
            inspector.collect { message ->
                val next = (listOf(message) + state.value.inspector).take(INSPECTOR_LIMIT)
                state.value = state.value.copy(
                    inspector = next,
                    mqttMessageRate = rateOf(next),
                )
            }
        }
    }

    /**
     * Attaches the running scanner's debug list.
     *
     * Node beacons become nodes, so what lands here is unrelated hardware — which is exactly
     * the capture the node-identity TODO needs, and the reason it is shown rather than
     * collected and discarded.
     */
    fun observeScanner(
        unidentified: StateFlow<List<UnidentifiedAdvertiser>>,
        clear: () -> Unit,
    ) {
        scannerJob?.cancel()
        clearScanner = clear
        scannerJob = scope.launch {
            unidentified.collect { state.value = state.value.copy(unidentified = it) }
        }
    }

    /** Empties the scanner's debug list. Useful when moving to a different part of a house. */
    fun clearUnidentified() {
        clearScanner()
    }

    /** Detaches from the scanner and empties the debug list. */
    fun stopObservingScanner() {
        scannerJob?.cancel()
        scannerJob = null
        clearScanner = {}
        state.value = state.value.copy(unidentified = emptyList())
    }

    /** Detaches from whatever gateway was being observed. */
    fun stopObservingMqtt() {
        mqttJobs.forEach { it.cancel() }
        mqttJobs.clear()
        state.value = state.value.copy(mqtt = MqttConnectionState.Disconnected)
    }

    /** Renames a node. Display only — see [dev.surdy.hazri.data.NodeRecord]. */
    fun renameNode(nodeId: NodeId, displayName: String) {
        repository.renameNode(nodeId, displayName)
    }

    /**
     * Corrects the firmware room a node's settings are published to.
     *
     * The one way to make a scan-discovered node pushable without a broker: the room is the
     * topic segment, and nothing in a BLE advertisement carries it.
     */
    fun setEspresenseRoom(nodeId: NodeId, room: String) {
        repository.setEspresenseRoom(nodeId, room)
    }

    /** Hides or unhides a node everywhere in the app. */
    fun setHidden(nodeId: NodeId, hidden: Boolean) {
        repository.updateNode(nodeId) { it.copy(hidden = hidden) }
    }

    /**
     * Starts a comparison between the running source and [other].
     *
     * Both means are exponentially weighted with the same alpha so neither side is
     * advantaged by arriving faster.
     */
    fun startComparison(primaryLabel: String, secondaryLabel: String, other: SignalSource) {
        stopComparison()
        primaryMeans.clear()
        secondaryMeans.clear()
        secondary = other
        state.value = state.value.copy(
            comparing = true,
            comparisonPrimary = primaryLabel,
            comparisonSecondary = secondaryLabel,
            deltas = emptyList(),
        )
        compareJobs += scope.launch { engine.samples.collect { fold(primaryMeans, it) } }
        compareJobs += scope.launch { other.samples.collect { fold(secondaryMeans, it) } }
        compareJobs += scope.launch { other.start() }
    }

    /** Stops the comparison and its second source. */
    fun stopComparison() {
        compareJobs.forEach { it.cancel() }
        compareJobs.clear()
        secondary?.stop()
        secondary = null
        state.value = state.value.copy(comparing = false)
    }

    /** The label for a source kind, used by Compare sources and the source picker. */
    fun label(kind: SourceKind): String = when (kind) {
        SourceKind.DIRECT -> "Direct"
        SourceKind.MQTT -> "MQTT"
        SourceKind.SIMULATED -> "Simulated"
    }

    private fun fold(into: MutableMap<NodeId, Double>, sample: SignalSample) {
        val previous = into[sample.nodeId]
        into[sample.nodeId] =
            if (previous == null) sample.rssi.toDouble()
            else previous + COMPARE_ALPHA * (sample.rssi - previous)
        publishDeltas()
    }

    private fun publishDeltas() {
        val names = repository.displayNames()
        val ids = (primaryMeans.keys + secondaryMeans.keys).toList()
        state.value = state.value.copy(
            deltas = ids.map { nodeId ->
                SourceDelta(
                    nodeId = nodeId,
                    displayName = names[nodeId] ?: nodeId.value,
                    primaryRssi = primaryMeans[nodeId],
                    secondaryRssi = secondaryMeans[nodeId],
                )
            }.sortedByDescending { it.primaryRssi ?: it.secondaryRssi ?: Double.NEGATIVE_INFINITY },
        )
    }

    private fun rateOf(messages: List<MqttMessage>): Double {
        if (messages.size < 2) return 0.0
        val span = messages.first().receivedAt - messages.last().receivedAt
        return if (span <= 0L) 0.0 else messages.size * 60_000.0 / span
    }

    private companion object {
        const val INSPECTOR_LIMIT = 120
        const val COMPARE_ALPHA = 0.2
    }
}

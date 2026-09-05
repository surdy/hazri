package dev.surdy.hazri.vm

import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.NodeRecord
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.DistanceModel
import dev.surdy.hazri.domain.Node
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.RoomVerdict
import dev.surdy.hazri.domain.RssiSmoother
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.SignalStats
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.source.MillisClock
import dev.surdy.hazri.source.SignalSource
import dev.surdy.hazri.source.SimulatedSignalSource
import dev.surdy.hazri.source.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** One node's current state, as the Live screen and Node detail read it. */
data class NodeLive(
    val node: Node,
    val smoothedRssi: Double,
    val stats: SignalStats,
    val distanceMetres: Double,
    val lastSeen: Long,
    /** Raw samples inside the history window, oldest first. Drives both charts. */
    val history: List<SignalSample>,
    /**
     * The second line of the Live card: the node id and, when one is known, the beacon it
     * advertises under. Never the room — for an MQTT-discovered node the id *is* the room,
     * and printing both spelled the same word twice.
     */
    val subtitle: String,
) {
    /** How far above the noise floor this reading sits, as a 0..1 fraction for the bar. */
    fun strengthFraction(floor: Double = FLOOR_DBM, ceiling: Double = CEILING_DBM): Float =
        (((smoothedRssi - floor) / (ceiling - floor)).coerceIn(0.0, 1.0)).toFloat()

    companion object {
        /** Bottom of the bar and heat scales, matching the Coverage legend. */
        const val FLOOR_DBM: Double = -95.0

        /** Top of the bar and heat scales. */
        const val CEILING_DBM: Double = -50.0
    }
}

/** Who is winning right now, and by how much. The banner on the Live screen. */
data class LeadInfo(
    val best: Node,
    val runnerUp: Node?,
    val marginDb: Double?,
)

/** Everything the Live screen renders. */
data class LiveState(
    val sourceKind: SourceKind = SourceKind.SIMULATED,
    val isRunning: Boolean = false,
    val nodes: List<NodeLive> = emptyList(),
    val lead: LeadInfo? = null,
    val packetsPerSecond: Double = 0.0,
    val error: String? = null,
    /**
     * The clock reading the rest of this state was computed at.
     *
     * Carried rather than read at the call site so that "seen 0.3 s ago" is the gap between
     * now and the node's last sample. Reading the clock in the composable and the timestamp
     * from the same sample the age is measured against always produced zero.
     */
    val updatedAt: Long = 0L,
    /** The room the simulated walker is standing in, or `null` outside simulated mode. */
    val simulatedRoom: String? = null,
)

/**
 * The one place samples are turned into state.
 *
 * Everything downstream — Live, Node detail, Survey's running means, the calibration
 * capture, Compare sources — reads from here rather than subscribing to a source directly,
 * so there is exactly one smoother per node and one definition of "now".
 *
 * State is recomputed on a [REFRESH_MILLIS] tick rather than per sample. At five nodes and
 * forty packets a second, per-sample recomposition would mean forty frames of work a
 * second for a number that moves by fractions of a dB; the tick is what keeps the sparkline
 * animating and the CPU quiet.
 */
class SignalEngine(
    private val repository: HazriRepository,
    private val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
) {
    private class Track(val smoother: RssiSmoother) {
        val history = ArrayDeque<SignalSample>()
        var lastSeen: Long = 0L
    }

    private val tracks = LinkedHashMap<NodeId, Track>()

    private val state = MutableStateFlow(LiveState())

    /** The Live screen's state. */
    val live: StateFlow<LiveState> = state.asStateFlow()

    private val relayed = MutableSharedFlow<SignalSample>(extraBufferCapacity = 256)

    /**
     * Every sample, after the engine has seen it.
     *
     * Survey and calibration collect this rather than the source, so that switching source
     * mid-survey does not need either of them to know.
     */
    val samples: Flow<SignalSample> = relayed.asSharedFlow()

    private var source: SignalSource? = null
    private var collectJob: Job? = null
    private var tickJob: Job? = null

    private val sourceChangeListeners = mutableListOf<() -> Unit>()

    init {
        // Smoothing weights and verdict thresholds are edited on a screen two taps away from
        // the one that shows their effect. Collecting them here is what makes that edit
        // visible without restarting the source.
        scope.launch { repository.settings.collect { applySettings(it) } }
    }

    /**
     * Registers something to run just before the source changes.
     *
     * There is one caller — the survey — and one reason: a recording spans a source, and
     * carrying one across a switch would average a direct-scan RSSI with an MQTT one and
     * file the result as a measurement of a room. Invoked synchronously so the recording is
     * sealed against the old source's samples, not the new one's.
     */
    fun onSourceChange(listener: () -> Unit) {
        sourceChangeListeners += listener
    }

    /**
     * Switches to [next], stopping whatever was running.
     *
     * Per-node history is cleared: a direct-scan RSSI and an MQTT RSSI are two different
     * measurements, and averaging across the switch would produce a number that means
     * nothing.
     */
    suspend fun useSource(kind: SourceKind, next: SignalSource) {
        sourceChangeListeners.toList().forEach { it() }
        stop()
        tracks.clear()
        source = next
        state.value = LiveState(sourceKind = kind, isRunning = false)
        start()
    }

    /**
     * Reports that a source could not be started at all — a permission refused, a broker
     * that is not configured.
     *
     * Without this the Live screen shows an empty node list and no explanation, which looks
     * exactly like a house with no nodes in it.
     */
    fun reportError(message: String) {
        state.value = state.value.copy(isRunning = false, error = message)
    }

    /**
     * Holds the simulated walk in [room] for the duration of a survey, or releases it.
     *
     * A no-op unless the running source is the simulator.
     */
    fun pinSimulatedRoom(room: String?) {
        (source as? SimulatedSignalSource)?.pinTo(room)
    }

    /** Starts the current source. No-op when there is none or it is already running. */
    suspend fun start() {
        val active = source ?: return
        if (collectJob != null) return
        collectJob = scope.launch {
            active.samples.collect { sample ->
                accept(sample)
                relayed.emit(sample)
            }
        }
        tickJob = scope.launch {
            while (isActive) {
                publish()
                delay(REFRESH_MILLIS)
            }
        }
        runCatching { active.start() }
            .onSuccess { state.value = state.value.copy(isRunning = true, error = null) }
            .onFailure { cause ->
                stop()
                state.value = state.value.copy(
                    isRunning = false,
                    error = cause.message ?: "Could not start source",
                )
            }
    }

    /** Stops the current source and the tick, keeping the last published state on screen. */
    fun stop() {
        source?.stop()
        collectJob?.cancel()
        collectJob = null
        tickJob?.cancel()
        tickJob = null
        state.value = state.value.copy(isRunning = false)
    }

    /** The live state of one node, or `null` if it has not been heard. */
    fun nodeLive(nodeId: NodeId): NodeLive? = state.value.nodes.firstOrNull { it.node.id == nodeId }

    /** Re-reads the smoothing settings. Existing history is kept; the smoothers restart. */
    fun applySettings(settings: AppSettings) {
        val kind = state.value.sourceKind
        tracks.keys.toList().forEach { nodeId ->
            val old = tracks.getValue(nodeId)
            val fresh = Track(settings.newSmoother(kind))
            fresh.history.addAll(old.history)
            fresh.lastSeen = old.lastSeen
            old.history.forEach { fresh.smoother.add(it) }
            tracks[nodeId] = fresh
        }
    }

    private fun accept(sample: SignalSample) {
        val settings = repository.settings.value
        val track = tracks.getOrPut(sample.nodeId) {
            repository.noteNode(sample.nodeId)
            Track(settings.newSmoother(state.value.sourceKind))
        }
        track.smoother.add(sample)
        track.lastSeen = sample.timestamp
        track.history.addLast(sample)
        val cutoff = sample.timestamp - HISTORY_MILLIS
        while (track.history.isNotEmpty() && track.history.first().timestamp < cutoff) {
            track.history.removeFirst()
        }
    }

    private fun publish() {
        val now = clock.now()
        val kind = state.value.sourceKind
        val records = repository.nodes.value.associateBy { it.id }

        val nodes = tracks.mapNotNull { (nodeId, track) ->
            val smoothed = track.smoother.smoothed ?: return@mapNotNull null
            val stats = track.smoother.stats(now) ?: return@mapNotNull null
            val record = records[nodeId.value]
            if (record?.hidden == true) return@mapNotNull null
            val config = record?.config() ?: NodeConfig(room = nodeId.value)
            NodeLive(
                node = record?.toNode() ?: Node(nodeId, nodeId.value, nodeId.value),
                smoothedRssi = smoothed,
                stats = stats,
                distanceMetres = referenceModelFor(kind, config).distanceMetres(smoothed),
                lastSeen = track.lastSeen,
                history = track.history.toList(),
                subtitle = subtitleFor(nodeId, record),
            )
        }.sortedByDescending { it.smoothedRssi }

        state.value = state.value.copy(
            nodes = nodes,
            lead = leadOf(nodes),
            packetsPerSecond = nodes.sumOf { it.stats.packetRate },
            updatedAt = now,
            simulatedRoom = (source as? SimulatedSignalSource)?.currentRoom,
        )
    }

    private fun subtitleFor(nodeId: NodeId, record: NodeRecord?): String {
        val fingerprint = record?.beaconFingerprint ?: return nodeId.value
        val beacon = Espresense.parseBeaconFingerprint(fingerprint) ?: return nodeId.value
        return "${nodeId.value} · beacon ${beacon.major}-${beacon.minor}"
    }

    private fun leadOf(nodes: List<NodeLive>): LeadInfo? {
        val best = nodes.firstOrNull() ?: return null
        val runnerUp = nodes.getOrNull(1)
        return LeadInfo(
            best = best.node,
            runnerUp = runnerUp?.node,
            marginDb = runnerUp?.let { best.smoothedRssi - it.smoothedRssi },
        )
    }

    companion object {
        /** How often the published state is recomputed. */
        const val REFRESH_MILLIS: Long = 250L

        /** How much raw history is kept per node. The Node detail chart's window. */
        const val HISTORY_MILLIS: Long = 60_000L

        /**
         * The verdict the live readings would give, treating the current position as a room.
         *
         * Used by Survey for its running verdict before the walk is finished.
         */
        fun verdictOf(room: String, nodes: List<NodeLive>, settings: AppSettings): RoomVerdict =
            RoomVerdict.of(
                room = room,
                means = nodes.associate { it.node.id to it.stats.mean },
                thresholds = settings.thresholds(),
            )

        /**
         * The distance model to read a reading through, which is not the same in both modes.
         *
         * In **MQTT** mode the node is the receiver and the phone the transmitter, so the
         * node's own `ref_rssi` and `absorption` are the right pair — the same numbers
         * ESPresense used to compute the `distance` it published.
         *
         * In **direct** mode the roles are swapped: the phone is hearing the node's own
         * iBeacon, whose calibrated power is `tx_ref_rssi` (-59), not the node's receive
         * reference (-65). Six dB of reference is a constant factor on every reading — at
         * absorption 2.7 it reports 3.6 m where the model means 6.0 m — so using the receive
         * reference in direct mode under-estimates every distance in the house. The
         * absorption is still the node's, because that is a property of the walls.
         */
        fun referenceModelFor(kind: SourceKind, config: NodeConfig): DistanceModel =
            when (kind) {
                SourceKind.MQTT -> config.distanceModel()
                SourceKind.DIRECT, SourceKind.SIMULATED ->
                    DistanceModel(DistanceModel.TX_REF_RSSI, config.absorption)
            }

        /** Mean, sigma, min and max of a raw history window. Node detail's stat tiles. */
        fun statsOf(history: List<SignalSample>): SignalStats? {
            if (history.isEmpty()) return null
            val values = history.map { it.rssi }
            val mean = values.sumOf { it.toDouble() } / values.size
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            val span = history.last().timestamp - history.first().timestamp
            return SignalStats(
                count = values.size,
                mean = mean,
                sigma = sqrt(variance),
                min = values.min(),
                max = values.max(),
                packetRate = if (values.size < 2 || span <= 0L) 0.0 else values.size * 1000.0 / span,
            )
        }
    }
}

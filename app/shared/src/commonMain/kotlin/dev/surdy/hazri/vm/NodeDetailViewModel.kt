package dev.surdy.hazri.vm

import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.data.NodeRecord
import dev.surdy.hazri.data.SourceKind
import dev.surdy.hazri.domain.CalibrationResult
import dev.surdy.hazri.domain.CalibrationSession
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalStats
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.protocol.EspresenseSetting
import dev.surdy.hazri.source.MillisClock
import dev.surdy.hazri.source.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What happened to the last push. */
enum class PushState {
    /** Nothing has been pushed since the config was last edited. */
    NOT_PUSHED,
    PUSHING,
    PUSHED,
    FAILED,
}

/** Everything the Node detail screen renders. */
data class NodeDetailState(
    val nodeId: NodeId,
    val record: NodeRecord? = null,
    val live: NodeLive? = null,
    val windowStats: SignalStats? = null,
    val config: NodeConfig = NodeConfig(room = ""),
    val refRssiIsKnown: Boolean = false,
    /** Whether [NodeRecord.espresenseRoom] came from the node rather than being assumed. */
    val roomIsConfirmed: Boolean = false,
    val sourceKind: SourceKind = SourceKind.SIMULATED,
    /** Milliseconds since this node was last heard, or `null` if it never has been. */
    val sinceLastSeenMillis: Long? = null,
    val pushState: PushState = PushState.NOT_PUSHED,
    val calibration: CalibrationProgress? = null,
    val message: String? = null,
)

/** A calibration in flight, or its result. */
data class CalibrationProgress(
    val sampleCount: Int,
    val minSamples: Int,
    val meanRssi: Double?,
    val result: CalibrationResult?,
)

/**
 * One node's detail screen: the 60 s chart, the stat tiles, the config block, calibration
 * and the two push actions.
 */
class NodeDetailViewModel(
    val nodeId: NodeId,
    private val repository: HazriRepository,
    private val engine: SignalEngine,
    private val push: SettingPublisher,
    private val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
) {
    private val state = MutableStateFlow(NodeDetailState(nodeId))
    val uiState: StateFlow<NodeDetailState> = state.asStateFlow()

    private var session: CalibrationSession? = null
    private var calibrationJob: Job? = null

    init {
        val record = repository.node(nodeId)
        state.value = state.value.copy(
            record = record,
            config = record?.config() ?: NodeConfig(room = nodeId.value),
            refRssiIsKnown = record?.refRssiIsKnown == true,
            roomIsConfirmed = record?.roomIsConfirmed == true,
        )
        scope.launch {
            engine.live.collect { live ->
                val node = live.nodes.firstOrNull { it.node.id == nodeId }
                state.value = state.value.copy(
                    live = node,
                    windowStats = node?.let { SignalEngine.statsOf(it.history) },
                    sourceKind = live.sourceKind,
                    sinceLastSeenMillis = node?.let { live.updatedAt - it.lastSeen },
                )
            }
        }
        scope.launch {
            repository.nodes.collect {
                val current = repository.node(nodeId) ?: return@collect
                state.value = state.value.copy(
                    record = current,
                    config = current.config(),
                    refRssiIsKnown = current.refRssiIsKnown,
                    roomIsConfirmed = current.roomIsConfirmed,
                )
            }
        }
    }

    /**
     * Edits the tuning in place. Nothing reaches the node until it is pushed.
     *
     * The room is not editable here and is not taken from [transform]: it is the topic
     * segment, and the only things allowed to set it are the node itself and the explicit
     * correction on Tools -> Nodes & rooms.
     */
    fun editConfig(transform: (NodeConfig) -> NodeConfig) {
        val next = transform(state.value.config).copy(room = state.value.config.room)
        repository.updateNodeConfig(nodeId, next)
        state.value = state.value.copy(
            config = next,
            record = repository.node(nodeId),
            pushState = PushState.NOT_PUSHED,
        )
    }

    /**
     * The `set` commands for this node's tuning block, as text.
     *
     * The Copy config action. Deliberately the wire format rather than a prose summary:
     * pasted into `mosquitto_pub` or an MQTT client it does the same thing the Push button
     * does, which makes it a way to check what Push would send.
     */
    fun configAsCommands(): String {
        val config = state.value.config
        val values = mapOf(
            EspresenseSetting.REF_RSSI to config.refRssi.toString(),
            EspresenseSetting.ABSORPTION to formatDouble(config.absorption),
            EspresenseSetting.MAX_DISTANCE to formatDouble(config.maxDistance),
        )
        return values.entries.joinToString("\n") { (setting, value) ->
            "${Espresense.settingTopic(config.room, setting)} $value"
        }
    }

    /** Pushes the tuning block over MQTT. */
    fun pushConfig() {
        val config = state.value.config
        state.value = state.value.copy(pushState = PushState.PUSHING, message = null)
        scope.launch {
            val ok = push.pushTuning(config)
            if (ok) {
                repository.updateNode(nodeId) { it.copy(refRssiPushedAt = clock.now()) }
            }
            state.value = state.value.copy(
                pushState = if (ok) PushState.PUSHED else PushState.FAILED,
                record = repository.node(nodeId),
                refRssiIsKnown = repository.node(nodeId)?.refRssiIsKnown == true,
                message = if (ok) null else "Broker refused the publish",
            )
        }
    }

    /**
     * Starts a calibration capture.
     *
     * Collects this node's samples until [CalibrationSession.minSamples] have arrived. What
     * the result is *for* is the subtle part — see [CalibrationResult].
     */
    /**
     * Whether the calibration result is the phone's transmit power.
     *
     * Only in MQTT mode. There the node measures the phone, so the mean at one metre is the
     * phone beacon's measured power and belongs in the Companion app. In direct mode the
     * phone is measuring the *node's* beacon, so the same capture calibrates
     * `tx_ref_rssi` on the node instead — a different number for a different device, and
     * the screen has to say which.
     */
    val calibratesPhoneBeacon: Boolean get() = state.value.sourceKind == SourceKind.MQTT

    fun startCalibration(distanceMetres: Double = CalibrationSession.ONE_METRE) {
        stopCalibration()
        val fresh = CalibrationSession(nodeId, distanceMetres)
        session = fresh
        state.value = state.value.copy(
            calibration = CalibrationProgress(0, fresh.minSamples, null, null),
        )
        calibrationJob = scope.launch {
            engine.samples.collect { sample ->
                if (!fresh.add(sample)) return@collect
                state.value = state.value.copy(
                    calibration = CalibrationProgress(
                        sampleCount = fresh.sampleCount,
                        minSamples = fresh.minSamples,
                        meanRssi = fresh.meanRssi(),
                        // Projected back to one metre through whichever model the running
                        // source implies — see SignalEngine.referenceModelFor.
                        result = fresh.result(
                            SignalEngine.referenceModelFor(state.value.sourceKind, state.value.config)
                        ),
                    ),
                )
            }
        }
    }

    /** Abandons a calibration in flight and clears its result. */
    fun stopCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        session = null
        state.value = state.value.copy(calibration = null)
    }

    /**
     * Pushes the calibration as this node's `ref_rssi`.
     *
     * The secondary action, and it is not what most users want: ESPresense ranges an
     * iBeacon — which is what the phone is — from the power the beacon advertises, not from
     * the node's `ref_rssi`. This only changes how the node ranges devices that advertise
     * no calibrated power of their own.
     */
    fun applyCalibrationAsRefRssi() {
        val result = state.value.calibration?.result ?: return
        editConfig { it.copy(refRssi = result.refRssiForNonBeaconDevices) }
        pushConfig()
    }

    private fun formatDouble(value: Double): String {
        val rounded = (value * 100).toLong() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
        else rounded.toString()
    }
}

/** Whatever can write settings to a node. Implemented by the MQTT source; a no-op offline. */
fun interface SettingPublisher {
    /** Publishes `ref_rssi`, `absorption` and `max_distance` for [config]'s room. */
    suspend fun pushTuning(config: NodeConfig): Boolean
}

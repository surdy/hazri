package dev.surdy.hazri.domain

import kotlin.math.roundToInt
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The four ESPresense settings Hazri edits and pushes, per node.
 *
 * The names are the MQTT setting keys verbatim (see
 * [dev.surdy.hazri.protocol.EspresenseSetting]) so that what the Node detail screen shows
 * and what is published cannot drift apart.
 *
 * @param room the node's ESPresense room name, which is also its topic segment.
 * @param maxDistance metres beyond which the node stops reporting a device at all. This is
 *   the setting that fixes a Tight room: it stops a neighbouring node claiming the room.
 */
data class NodeConfig(
    val room: String,
    val refRssi: Int = DistanceModel.DEFAULT_REF_RSSI,
    val absorption: Double = DistanceModel.DEFAULT_ABSORPTION,
    val maxDistance: Double = DEFAULT_MAX_DISTANCE,
) {
    /** The distance model this configuration implies. */
    fun distanceModel(): DistanceModel = DistanceModel(refRssi, absorption)

    companion object {
        /** `DEFAULT_MAX_DISTANCE` from ESPresense `include/defaults.h`, in metres. */
        const val DEFAULT_MAX_DISTANCE: Double = 16.0
    }
}

/**
 * What a finished [CalibrationSession] concluded.
 *
 * One measurement, two possible destinations — and the important one is not the node.
 *
 * ESPresense computes an **iBeacon's** distance from the power the beacon itself
 * advertises (`rssi@1m` in the device report), not from the node's `ref_rssi`. The phone
 * is an iBeacon: the Home Assistant Companion app's BLE transmitter is what advertises it,
 * and that app has a "Measured power at 1 m" setting. So the number this calibration
 * produces belongs in the **Companion app**, not in a `ref_rssi/set`. Pushing it to the
 * node changes nothing for a phone.
 *
 * [refRssiForNonBeaconDevices] is the same number offered for the other case: a node's
 * `ref_rssi` is what it falls back to for advertisers that carry no calibrated power of
 * their own. Hazri offers it as a secondary action, clearly labelled, rather than as the
 * primary one.
 */
data class CalibrationResult(
    val nodeId: NodeId,
    /**
     * Mean RSSI projected back to one metre. The value to type into the Companion app's
     * BLE transmitter settings as "Measured power at 1 m".
     */
    val measuredPowerAtOneMetre: Int,
    val meanRssi: Double,
    val sigma: Double,
    val sampleCount: Int,
    val distanceMetres: Double,
) {
    /**
     * The same measurement, read as a node `ref_rssi`.
     *
     * Only affects devices that advertise no calibrated transmit power. It will not change
     * how this node ranges the phone.
     */
    val refRssiForNonBeaconDevices: Int get() = measuredPowerAtOneMetre
}

/**
 * Captures RSSI between the phone and one node while the two are one metre apart, and
 * reports the mean as the power at one metre.
 *
 * That is the whole of the calibration: the log-distance model defines its reference as
 * the reading at one metre, so measuring it there is the measurement. What the number is
 * then *used for* is the subtlety, and [CalibrationResult] is where that is written down.
 *
 * [minSamples] exists because a mean of three readings taken through a moving hand is not
 * a mean — [isReady] is what the UI gates its Apply button on.
 */
class CalibrationSession(
    val nodeId: NodeId,
    val distanceMetres: Double = ONE_METRE,
    val minSamples: Int = DEFAULT_MIN_SAMPLES,
) {
    init {
        require(distanceMetres > 0.0) { "distance must be positive" }
        require(minSamples >= 1) { "minSamples must be at least 1" }
    }

    private var count: Int = 0
    private var sum: Double = 0.0
    private var sumOfSquares: Double = 0.0

    /** Samples accepted so far. Samples for other nodes are ignored. */
    val sampleCount: Int get() = count

    /** Whether [result] will return something. */
    val isReady: Boolean get() = count >= minSamples

    /** Accepts [sample] if it belongs to [nodeId]. Returns whether it was accepted. */
    fun add(sample: SignalSample): Boolean {
        if (sample.nodeId != nodeId) return false
        count += 1
        sum += sample.rssi
        sumOfSquares += sample.rssi.toDouble() * sample.rssi
        return true
    }

    /** Mean RSSI so far, or `null` before the first sample. */
    fun meanRssi(): Double? = if (count == 0) null else sum / count

    /**
     * The calibration, or `null` until [minSamples] have arrived.
     *
     * When [distanceMetres] is not one metre the mean is projected back to one metre
     * through the [reference] model's absorption, so a calibration taken at two metres in a
     * cramped hallway is still usable.
     */
    fun result(reference: DistanceModel = DistanceModel.DEFAULT): CalibrationResult? {
        if (!isReady) return null
        val mean = sum / count
        val variance = (sumOfSquares / count) - mean * mean
        val atOneMetre = mean + 10.0 * reference.absorption * log10(distanceMetres)
        return CalibrationResult(
            nodeId = nodeId,
            measuredPowerAtOneMetre = atOneMetre.roundToInt(),
            meanRssi = mean,
            sigma = sqrt(variance.coerceAtLeast(0.0)),
            sampleCount = count,
            distanceMetres = distanceMetres,
        )
    }

    /** Throws everything away and starts again. */
    fun reset() {
        count = 0
        sum = 0.0
        sumOfSquares = 0.0
    }

    companion object {
        const val ONE_METRE: Double = 1.0
        const val DEFAULT_MIN_SAMPLES: Int = 20
    }
}

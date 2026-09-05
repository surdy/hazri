package dev.surdy.hazri.domain

import kotlin.math.log10
import kotlin.math.pow

/**
 * The log-distance path-loss model ESPresense estimates distance with:
 *
 * ```
 * d = 10 ^ ((refRssi - rssi) / (10 * absorption))
 * ```
 *
 * [refRssi] is the RSSI expected from the device at exactly one metre and [absorption] is
 * the path-loss exponent — about 2.0 in free space, 2.5-3.0 through drywall, 3.0-3.5
 * through brick.
 *
 * The defaults are the firmware's own, read from `include/defaults.h` on ESPresense
 * `master` (v4.0.6) on 2026-09-04: `DEFAULT_RX_REF_RSSI (-65)` and `DEFAULT_ABSORPTION
 * (2.7)`. Note that `-59` — which several write-ups, and this app's own brief, call the
 * reference RSSI — is `DEFAULT_TX_REF_RSSI`, the power a *node* advertises in its own
 * iBeacon. It is not this number. See [TX_REF_RSSI].
 *
 * The model is a per-node calibration, not a physical truth: two nodes with different
 * antennas in the same spot need different [refRssi] to agree, which is the entire reason
 * Hazri has a calibration screen.
 */
data class DistanceModel(
    val refRssi: Int = DEFAULT_REF_RSSI,
    val absorption: Double = DEFAULT_ABSORPTION,
) {
    init {
        require(absorption > 0.0) { "absorption must be positive, was $absorption" }
    }

    /** Estimated distance in metres for [rssi] dBm. Monotonically decreasing in [rssi]. */
    fun distanceMetres(rssi: Double): Double =
        10.0.pow((refRssi - rssi) / (10.0 * absorption))

    /** Estimated distance in metres for an integer reading. */
    fun distanceMetres(rssi: Int): Double = distanceMetres(rssi.toDouble())

    /** The RSSI this model expects at [metres]. The inverse of [distanceMetres]. */
    fun rssiAt(metres: Double): Double {
        require(metres > 0.0) { "distance must be positive, was $metres" }
        return refRssi - 10.0 * absorption * log10(metres)
    }

    companion object {
        /** `DEFAULT_RX_REF_RSSI`: the RSSI a node expects from a 0 dBm transmitter at 1 m. */
        const val DEFAULT_REF_RSSI: Int = -65

        /** `DEFAULT_ABSORPTION`: the path-loss exponent. */
        const val DEFAULT_ABSORPTION: Double = 2.7

        /**
         * `DEFAULT_TX_REF_RSSI`: the power a node advertises in its *own* iBeacon.
         *
         * Here only so that the -59 in circulation has somewhere correct to live. It is
         * not a receive-side reference and must not be used as one.
         */
        const val TX_REF_RSSI: Int = -59

        val DEFAULT: DistanceModel = DistanceModel()
    }
}

package dev.surdy.hazri.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.round

/**
 * One `espresense/devices/<device id>/<room>` payload.
 *
 * Deliberately lenient. The firmware omits most of these depending on what the advertiser
 * sent, the shape has changed across releases, and a phone walking a house is the worst
 * possible place to discover that a parser is strict.
 *
 * ## Fields, from `BleFingerprint::fill()` on master, cross-checked against
 * `docs/espresense-topics.md` (both 2026-09-04)
 *
 *  - [id] the fingerprint, e.g. `iBeacon:<uuid>-<major>-<minor>` or `irk:<32 hex>`.
 *  - [rssi] the node's *filtered* RSSI in dBm, two decimals — a float, not an integer.
 *    This is the value Hazri charts and the one the margin is computed from.
 *  - [raw] raw RSSI. **Not published by 4.x**, despite older write-ups mentioning it; kept
 *    because a fleet is rarely all on one firmware. [rawRssi] falls back to [rssi].
 *  - [distance] the node's own estimate, `10^((rssi@1m - rssi) / (10 * absorption))`.
 *  - [refRssiAtOneMetre] the reference power used for this device: an iBeacon's own
 *    measured power when it has one, otherwise the node's `ref_rssi`.
 *  - [rxAdj] the node's `rx_adj_rssi` at the time of the reading, `20` on the S3 builds.
 *  - [interval] mean advertisement interval in ms, `(millis - firstSeen) / seenCount`.
 */
@Serializable
data class DeviceReport(
    val id: String? = null,
    val name: String? = null,
    val mac: String? = null,
    val rssi: Double? = null,
    val raw: Double? = null,
    val distance: Double? = null,
    @SerialName("rssi@1m") val refRssiAtOneMetre: Double? = null,
    @SerialName("rxAdj") val rxAdj: Double? = null,
    @SerialName("rssiVar") val rssiVariance: Double? = null,
    @SerialName("var") val distanceVariance: Double? = null,
    @SerialName("int") val interval: Long? = null,
    val close: Boolean = false,
) {
    /**
     * The best available RSSI: [raw] when an older firmware sent it, otherwise the
     * filtered [rssi]. `null` when the payload carried neither, which happens for the
     * sub-reports that only announce a battery level.
     */
    val rawRssi: Double? get() = raw ?: rssi

    /** [rawRssi] rounded to the integer the rest of the app works in. */
    fun rssiOrNull(): Int? = rawRssi?.let { round(it).toInt() }
}

/**
 * One `espresense/settings/<fingerprint>/config` payload, retained.
 *
 * Two uses, and Hazri only reads the first: a node announces *itself* here as
 * `{"id":"node:<room>","name":"<room>"}` under its own iBeacon fingerprint, which is the
 * mapping from a BLE advertisement to a room name; and any device can be enrolled by
 * publishing the same shape under its fingerprint.
 */
@Serializable
data class DeviceConfig(
    val id: String? = null,
    val name: String? = null,
) {
    /** The room this config names, when it is a node announcing itself. Otherwise `null`. */
    val nodeRoom: String?
        get() = id?.takeIf { it.startsWith(Espresense.NODE_ID_PREFIX) }
            ?.removePrefix(Espresense.NODE_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }
}

/** One `espresense/rooms/<room>/telemetry` payload. Every field is optional in the firmware. */
@Serializable
data class NodeTelemetry(
    val ip: String? = null,
    /** Seconds since boot. */
    val uptime: Long? = null,
    val firm: String? = null,
    val ver: String? = null,
    /** The node's own Wi-Fi RSSI, not any device's. */
    val rssi: Int? = null,
    val adverts: Long? = null,
    val seen: Long? = null,
    val reported: Long? = null,
    val fingerprints: Int? = null,
    val freeHeap: Long? = null,
    val maxHeap: Long? = null,
)

/**
 * Parses ESPresense payloads.
 *
 * Every entry point returns `null` rather than throwing: malformed JSON on a topic Hazri
 * subscribed to is a fact about the network, not a programming error, and the MQTT
 * inspector is where it should become visible.
 */
object EspresenseParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Parses a device report, or `null` if the payload is not one. */
    fun parseDeviceReport(payload: String): DeviceReport? =
        runCatching { json.decodeFromString<DeviceReport>(payload) }.getOrNull()

    /** Parses a retained device config, or `null` if the payload is not one. */
    fun parseDeviceConfig(payload: String): DeviceConfig? =
        runCatching { json.decodeFromString<DeviceConfig>(payload) }.getOrNull()

    /** Parses a telemetry payload, or `null` if it is not one. */
    fun parseTelemetry(payload: String): NodeTelemetry? =
        runCatching { json.decodeFromString<NodeTelemetry>(payload) }.getOrNull()
}

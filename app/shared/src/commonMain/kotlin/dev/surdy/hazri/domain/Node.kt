package dev.surdy.hazri.domain

import kotlin.jvm.JvmInline

/**
 * Stable identity for a sensing node.
 *
 * Never a MAC address: iOS hides those, and ESPresense keys everything on the room name
 * the node was configured with. In direct-scan mode the id comes from what the node
 * advertises; in MQTT mode it is the last segment of `espresense/devices/<phone>/<room>`.
 */
@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "NodeId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A node as the app knows it: its identity, the name shown in the UI, and the ESPresense
 * room it claims. [room] is the routing key — it is what appears in every MQTT topic.
 */
data class Node(
    val id: NodeId,
    val displayName: String,
    val room: String,
)

/** Where a [SignalSample] came from. Kept on every sample so the two can be compared. */
enum class Source {
    /** The phone scanned the node's own advertisement. */
    DIRECT,

    /** The node reported what it heard from the phone, over MQTT. */
    MQTT,

    /** [dev.surdy.hazri.source.SimulatedSignalSource]. Never mixed with real data. */
    SIMULATED,
}

/**
 * One RSSI reading for one node.
 *
 * @param rssi received signal strength in dBm, always negative in practice.
 * @param timestamp epoch milliseconds. Plain [Long] rather than a date-time type: every
 *   consumer here does arithmetic on it, and nothing formats it as a calendar date.
 */
data class SignalSample(
    val nodeId: NodeId,
    val rssi: Int,
    val timestamp: Long,
    val source: Source,
)

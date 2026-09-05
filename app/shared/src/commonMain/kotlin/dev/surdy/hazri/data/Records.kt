package dev.surdy.hazri.data

import dev.surdy.hazri.domain.DistanceModel
import dev.surdy.hazri.domain.Node
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.Source
import kotlinx.serialization.Serializable

/**
 * A survey as stored.
 *
 * A separate type from [RoomSurvey] because [NodeId] is a value class and the domain
 * types have no business carrying serialization annotations. The mapping is mechanical
 * and lives in this file so the two shapes cannot drift apart unnoticed.
 */
@Serializable
data class StoredSurvey(
    val room: String,
    val startedAt: Long,
    val endedAt: Long,
    val source: Source,
    val stats: List<StoredNodeStat>,
) {
    fun toDomain(): RoomSurvey = RoomSurvey(
        room = room,
        startedAt = startedAt,
        endedAt = endedAt,
        source = source,
        stats = stats.map { it.toDomain() },
    )

    companion object {
        fun of(survey: RoomSurvey): StoredSurvey = StoredSurvey(
            room = survey.room,
            startedAt = survey.startedAt,
            endedAt = survey.endedAt,
            source = survey.source,
            stats = survey.stats.map(StoredNodeStat::of),
        )
    }
}

/** One node's contribution to a [StoredSurvey]. */
@Serializable
data class StoredNodeStat(
    val nodeId: String,
    val mean: Double,
    val sigma: Double,
    val count: Int,
) {
    fun toDomain(): NodeSurveyStat = NodeSurveyStat(NodeId(nodeId), mean, sigma, count)

    companion object {
        fun of(stat: NodeSurveyStat): StoredNodeStat =
            StoredNodeStat(stat.nodeId.value, stat.mean, stat.sigma, stat.count)
    }
}

/**
 * What the user has decided about one node, and what the firmware calls it.
 *
 * Two names, and keeping them apart is load-bearing:
 *
 *  - [displayName] is the user's. It appears on every card and in every suggestion, and
 *    nothing is ever published to it. "Under the stairs" is a legitimate value.
 *  - [espresenseRoom] is the firmware's. It is the segment in
 *    `espresense/rooms/<room>/<setting>/set`, so a wrong value here writes settings to a
 *    node that does not exist. It is only ever set from the node itself: the last segment
 *    of a device topic, or the `node:<room>` in a retained settings config. The user can
 *    correct it explicitly in Tools -> Nodes & rooms, and slugifying [displayName] into it
 *    is exactly the bug that must not come back.
 *
 * [beaconFingerprint] is the `iBeacon:<uuid>-<major>-<minor>` the node advertises under —
 * the only thing a direct scan can see, and the key that ties a scanned advertisement to a
 * room the broker announced.
 *
 * [nameIsUserSet] stops a retained config renaming a node the user has already named.
 */
@Serializable
data class NodeRecord(
    val id: String,
    val displayName: String,
    /** The firmware room slug. The MQTT topic segment; never derived from [displayName]. */
    val espresenseRoom: String,
    val beaconFingerprint: String? = null,
    val nameIsUserSet: Boolean = false,
    /** Whether [espresenseRoom] came from the node itself rather than being assumed. */
    val roomIsConfirmed: Boolean = false,
    val hidden: Boolean = false,
    val refRssi: Int = DistanceModel.DEFAULT_REF_RSSI,
    val absorption: Double = DistanceModel.DEFAULT_ABSORPTION,
    val maxDistance: Double = NodeConfig.DEFAULT_MAX_DISTANCE,
    /**
     * When the app last published `ref_rssi/set` for this node, or `null`.
     *
     * `ref_rssi` is the one tuning setting a node never publishes back: its retained
     * snapshot carries `max_distance`, `absorption`, `tx_ref_rssi` and `rx_adj_rssi` but
     * not this one (`sendTelemetry()` in `src/main.cpp`). So the value in [refRssi] is
     * either what Hazri pushed, or a guess at the firmware default — and the UI has to say
     * which. This is how it knows.
     */
    val refRssiPushedAt: Long? = null,
) {
    /** Whether [refRssi] is a value this app pushed, rather than an assumed default. */
    val refRssiIsKnown: Boolean get() = refRssiPushedAt != null

    val nodeId: NodeId get() = NodeId(id)

    fun toNode(): Node = Node(nodeId, displayName, espresenseRoom)

    fun config(): NodeConfig = NodeConfig(espresenseRoom, refRssi, absorption, maxDistance)

    /**
     * Applies a tuning edit.
     *
     * [NodeConfig.room] is deliberately ignored: the room is not a tuning value, and the
     * config block on Node detail must not be able to redirect a push.
     */
    fun withConfig(config: NodeConfig): NodeRecord = copy(
        refRssi = config.refRssi,
        absorption = config.absorption,
        maxDistance = config.maxDistance,
    )

    /**
     * Applies a room the node announced about itself.
     *
     * The room is always taken — it is the firmware's own answer and the topic depends on
     * it. The display name follows only when the user has not chosen one.
     */
    fun withAnnouncedRoom(room: String): NodeRecord = copy(
        espresenseRoom = room,
        roomIsConfirmed = true,
        displayName = if (nameIsUserSet) displayName else room.replaceFirstChar { it.uppercase() },
    )

    companion object {
        /**
         * The record a newly discovered node gets.
         *
         * [espresenseRoom] defaults to the node id because that is what the id *is* for an
         * MQTT-discovered node: the last segment of `espresense/devices/<phone>/<room>`.
         * For a beacon discovered by scanning it is a placeholder, and [roomIsConfirmed] is
         * false until the broker or the user says otherwise.
         */
        fun forDiscovered(
            nodeId: NodeId,
            beaconFingerprint: String? = null,
            espresenseRoom: String = nodeId.value,
            roomIsConfirmed: Boolean = false,
        ): NodeRecord = NodeRecord(
            id = nodeId.value,
            displayName = nodeId.value.replaceFirstChar { it.uppercase() },
            espresenseRoom = espresenseRoom,
            beaconFingerprint = beaconFingerprint,
            roomIsConfirmed = roomIsConfirmed,
        )
    }
}

/** The rooms the user has named, in the order the Survey screen shows their chips. */
@Serializable
data class RoomList(val rooms: List<String> = emptyList())

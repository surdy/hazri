package dev.surdy.hazri.domain

import kotlin.math.sqrt

/** What one node was heard at, across a whole room walk. */
data class NodeSurveyStat(
    val nodeId: NodeId,
    val mean: Double,
    val sigma: Double,
    val count: Int,
)

/**
 * A finished walk of one room: every node's mean and spread over the samples collected
 * between [startedAt] and [endedAt].
 *
 * A survey is the unit Coverage is built from. It is deliberately a plain record — the
 * accumulation happens in [SurveyAccumulator] and the judgement in [RoomVerdict].
 */
data class RoomSurvey(
    val room: String,
    val startedAt: Long,
    val endedAt: Long,
    val source: Source,
    val stats: List<NodeSurveyStat>,
) {
    /** Duration of the walk in milliseconds. */
    val durationMillis: Long get() = endedAt - startedAt

    /** Total samples across every node. */
    val sampleCount: Int get() = stats.sumOf { it.count }

    /** Mean RSSI per node, the input [RoomVerdict.of] takes. */
    fun means(): Map<NodeId, Double> = stats.associate { it.nodeId to it.mean }

    /** This room's verdict under [thresholds]. */
    fun verdict(thresholds: VerdictThresholds = VerdictThresholds.DEFAULT): RoomVerdict =
        RoomVerdict.of(room, means(), thresholds)
}

/**
 * Accumulates samples for one room walk and turns them into a [RoomSurvey].
 *
 * Running sums rather than a sample list: a five-minute walk over five nodes is tens of
 * thousands of readings, and the only things anyone looks at are the mean and the spread.
 * Live means are available at any point through [statsNow], which is what drives the
 * running verdict on the Survey screen.
 */
class SurveyAccumulator(
    val room: String,
    val startedAt: Long,
    val source: Source,
) {
    private class Running {
        var count: Int = 0
        var sum: Double = 0.0
        var sumOfSquares: Double = 0.0
    }

    private val perNode = LinkedHashMap<NodeId, Running>()
    private var lastAt: Long = startedAt

    /** Total samples accepted so far. */
    var sampleCount: Int = 0
        private set

    /** Adds one sample. Samples for any node are accepted; nothing is filtered by room. */
    fun add(sample: SignalSample) {
        val running = perNode.getOrPut(sample.nodeId) { Running() }
        running.count += 1
        running.sum += sample.rssi
        running.sumOfSquares += sample.rssi.toDouble() * sample.rssi
        sampleCount += 1
        if (sample.timestamp > lastAt) lastAt = sample.timestamp
    }

    /** Per-node mean and sigma over everything added so far, strongest first. */
    fun statsNow(): List<NodeSurveyStat> =
        perNode.map { (nodeId, running) ->
            val mean = running.sum / running.count
            val variance = (running.sumOfSquares / running.count) - mean * mean
            NodeSurveyStat(
                nodeId = nodeId,
                mean = mean,
                sigma = sqrt(variance.coerceAtLeast(0.0)),
                count = running.count,
            )
        }.sortedByDescending { it.mean }

    /** The verdict the walk would produce if it stopped now. */
    fun verdictNow(thresholds: VerdictThresholds = VerdictThresholds.DEFAULT): RoomVerdict =
        RoomVerdict.of(room, statsNow().associate { it.nodeId to it.mean }, thresholds)

    /** Seals the walk. [endedAt] defaults to the newest sample's timestamp. */
    fun finish(endedAt: Long = lastAt): RoomSurvey = RoomSurvey(
        room = room,
        startedAt = startedAt,
        endedAt = endedAt,
        source = source,
        stats = statsNow(),
    )
}

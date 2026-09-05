package dev.surdy.hazri.domain

/** One cell of the coverage grid. A `null` [mean] is a node that was never heard in that room. */
data class CoverageCell(
    val room: String,
    val nodeId: NodeId,
    val mean: Double?,
    val isStrongestInRoom: Boolean,
)

/**
 * Rooms x nodes of surveyed mean RSSI, with each room's verdict.
 *
 * Built from the most recent survey per room, so a room walked twice contributes once and
 * a node added after a survey shows as an empty cell until that room is walked again.
 */
data class CoverageMatrix(
    val rooms: List<String>,
    val nodes: List<NodeId>,
    val cells: Map<String, Map<NodeId, CoverageCell>>,
    val verdicts: Map<String, RoomVerdict>,
) {
    /** The cell at [room] x [nodeId], or `null` if that room was never surveyed. */
    fun cell(room: String, nodeId: NodeId): CoverageCell? = cells[room]?.get(nodeId)

    /** Rooms whose verdict is not [Verdict.CLEAR], in the order they appear in [rooms]. */
    fun problemRooms(): List<RoomVerdict> =
        rooms.mapNotNull { verdicts[it] }.filter { it.verdict != Verdict.CLEAR }

    companion object {
        /**
         * Folds surveys into a matrix.
         *
         * @param surveys any number per room; only the one with the latest [RoomSurvey.endedAt]
         *   is used.
         * @param nodeOrder columns, left to right. Nodes not in this list are appended in the
         *   order the surveys mention them, so a node discovered mid-session still shows.
         */
        fun of(
            surveys: List<RoomSurvey>,
            nodeOrder: List<NodeId> = emptyList(),
            thresholds: VerdictThresholds = VerdictThresholds.DEFAULT,
        ): CoverageMatrix {
            val latest = surveys
                .groupBy { it.room }
                .mapValues { (_, forRoom) -> forRoom.maxBy { it.endedAt } }

            val rooms = latest.keys.sorted()
            val discovered = latest.values.flatMap { survey -> survey.stats.map { it.nodeId } }
            val nodes = nodeOrder + discovered.filterNot { it in nodeOrder }.distinct()

            val verdicts = rooms.associateWith { room -> latest.getValue(room).verdict(thresholds) }

            val cells = rooms.associateWith { room ->
                val survey = latest.getValue(room)
                val means = survey.means()
                // A Blind room has no strongest node worth pointing at: the ring means "this
                // one owns the room", and in a Blind room nothing does.
                val strongest = if (verdicts.getValue(room).verdict == Verdict.BLIND) null
                else means.maxByOrNull { it.value }?.key
                nodes.associateWith { nodeId ->
                    CoverageCell(
                        room = room,
                        nodeId = nodeId,
                        mean = means[nodeId],
                        isStrongestInRoom = strongest != null && nodeId == strongest,
                    )
                }
            }

            return CoverageMatrix(rooms = rooms, nodes = nodes, cells = cells, verdicts = verdicts)
        }
    }
}

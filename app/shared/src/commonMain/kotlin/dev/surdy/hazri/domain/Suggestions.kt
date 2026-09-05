package dev.surdy.hazri.domain

import kotlin.math.roundToInt

/**
 * One piece of advice about one room. Plain data — the Coverage screen renders it, but
 * nothing about the wording depends on the UI, and the text is asserted in tests.
 */
data class Suggestion(
    val room: String,
    val verdict: Verdict,
    val text: String,
)

/**
 * Turns verdicts into the sentences on the Coverage screen.
 *
 * Two shapes, because only two verdicts are actionable:
 *
 *  - **Tight** — two nodes are close enough that Home Assistant will flip between them.
 *    Either move the room's own node so it wins by more, or stop the intruder from
 *    claiming the room by lowering its `max_distance` to just inside where it heard the
 *    phone from. The second number is computed from the intruder's own distance model,
 *    so it is a setting that can be pushed rather than a guess.
 *  - **Blind** — nothing is above the floor, so there is no tuning to do; the room needs a
 *    node or it needs to be accepted as unmonitored.
 *
 * [Verdict.CLEAR] rooms produce nothing.
 */
object Suggestions {

    /** Advice for one room, or `null` when the room is [Verdict.CLEAR]. */
    fun forRoom(
        verdict: RoomVerdict,
        displayNames: Map<NodeId, String> = emptyMap(),
        models: Map<NodeId, DistanceModel> = emptyMap(),
    ): Suggestion? = when (verdict.verdict) {
        Verdict.CLEAR -> null
        Verdict.TIGHT -> Suggestion(verdict.room, Verdict.TIGHT, tightText(verdict, displayNames, models))
        Verdict.BLIND -> Suggestion(verdict.room, Verdict.BLIND, blindText(verdict, displayNames))
    }

    /** Advice for every room in [matrix] that has any, in the matrix's room order. */
    fun forMatrix(
        matrix: CoverageMatrix,
        displayNames: Map<NodeId, String> = emptyMap(),
        models: Map<NodeId, DistanceModel> = emptyMap(),
    ): List<Suggestion> =
        matrix.problemRooms().mapNotNull { forRoom(it, displayNames, models) }

    private fun tightText(
        verdict: RoomVerdict,
        displayNames: Map<NodeId, String>,
        models: Map<NodeId, DistanceModel>,
    ): String {
        val best = name(verdict.best, displayNames)
        val intruder = name(verdict.runnerUp, displayNames)
        val gap = verdict.margin?.roundToInt() ?: 0
        val cap = verdict.runnerUpMean?.let { mean ->
            val model = models[verdict.runnerUp] ?: DistanceModel.DEFAULT
            model.distanceMetres(mean).roundToInt().coerceAtLeast(1)
        }
        val capClause = if (cap == null) {
            "or lower $intruder max_distance"
        } else {
            "or set $intruder max_distance to $cap m"
        }
        return "$best and $intruder nodes are within $gap dB. Move the $best node 1-2 m " +
            "further into ${verdict.room}, $capClause."
    }

    private fun blindText(verdict: RoomVerdict, displayNames: Map<NodeId, String>): String {
        val best = verdict.best
        val mean = verdict.bestMean
        val opener = if (best == null || mean == null) {
            "No node hears the phone in ${verdict.room}."
        } else {
            "Only the ${name(best, displayNames)} node hears the phone here, at " +
                "${mean.roundToInt()} dBm."
        }
        return "$opener Add a node, or treat ${verdict.room} as away."
    }

    private fun name(nodeId: NodeId?, displayNames: Map<NodeId, String>): String =
        nodeId?.let { displayNames[it] ?: it.value } ?: "unknown"
}

package dev.surdy.hazri.domain

/**
 * Whether a room is unambiguously owned by one node.
 *
 * This is the number the whole app exists to produce. Home Assistant picks the room whose
 * node hears the phone loudest; when two nodes are within a few dB of each other that
 * choice flips on noise and the automation flaps.
 */
enum class Verdict {
    /** One node leads the runner-up by at least the margin threshold. */
    CLEAR,

    /** A node leads, but by less than the margin threshold. Expect flapping. */
    TIGHT,

    /** No node hears the phone above the floor. Nothing to be ambiguous about. */
    BLIND,
}

/**
 * Thresholds behind [Verdict]. Configurable because both numbers are judgement calls that
 * depend on how noisy a particular house is.
 *
 * @param marginDb the best-minus-runner-up gap, in dB, at or above which a room is [Verdict.CLEAR].
 * @param floorDbm the mean RSSI a room's best node must beat to be heard at all.
 */
data class VerdictThresholds(
    val marginDb: Double = DEFAULT_MARGIN_DB,
    val floorDbm: Double = DEFAULT_FLOOR_DBM,
) {
    companion object {
        const val DEFAULT_MARGIN_DB: Double = 5.0
        const val DEFAULT_FLOOR_DBM: Double = -85.0

        val DEFAULT: VerdictThresholds = VerdictThresholds()
    }
}

/**
 * A room's verdict together with the evidence for it.
 *
 * @param margin best mean minus runner-up mean, in dB. `null` when there is no runner-up
 *   or the room is [Verdict.BLIND] — there is no meaningful gap to report.
 */
data class RoomVerdict(
    val room: String,
    val verdict: Verdict,
    val margin: Double?,
    val best: NodeId?,
    val runnerUp: NodeId?,
    val bestMean: Double?,
    val runnerUpMean: Double?,
) {
    companion object {
        /**
         * Decides a room from its per-node mean RSSI.
         *
         * @param means mean RSSI in dBm per node. Nodes that heard nothing must be absent
         *   rather than present with a floor value, or they will be read as a runner-up.
         */
        fun of(
            room: String,
            means: Map<NodeId, Double>,
            thresholds: VerdictThresholds = VerdictThresholds.DEFAULT,
        ): RoomVerdict {
            val ranked = means.entries.sortedByDescending { it.value }
            val best = ranked.firstOrNull()
            val runnerUp = ranked.getOrNull(1)

            if (best == null || best.value <= thresholds.floorDbm) {
                return RoomVerdict(
                    room = room,
                    verdict = Verdict.BLIND,
                    margin = null,
                    best = best?.key,
                    runnerUp = runnerUp?.key,
                    bestMean = best?.value,
                    runnerUpMean = runnerUp?.value,
                )
            }

            val margin = runnerUp?.let { best.value - it.value }
            val verdict = when {
                margin == null -> Verdict.CLEAR
                margin >= thresholds.marginDb -> Verdict.CLEAR
                else -> Verdict.TIGHT
            }

            return RoomVerdict(
                room = room,
                verdict = verdict,
                margin = margin,
                best = best.key,
                runnerUp = runnerUp?.key,
                bestMean = best.value,
                runnerUpMean = runnerUp?.value,
            )
        }
    }
}

package dev.surdy.hazri.domain

import kotlin.math.sqrt

/**
 * Summary of the samples inside a smoother's window.
 *
 * @param packetRate samples per second across the window. Zero until two samples are in,
 *   because one sample spans no time.
 */
data class SignalStats(
    val count: Int,
    val mean: Double,
    val sigma: Double,
    val min: Int,
    val max: Int,
    val packetRate: Double,
)

/**
 * Per-node RSSI smoothing: median of the last [medianWindow] raw values, then an
 * exponential moving average with weight [alpha] on the newest median.
 *
 * The median is what removes the single deep fade that a moving average would smear
 * across five readings; the EMA is what stops the number twitching while the phone is
 * standing still. This is the pairing the plan specifies and the one ESPresense's own
 * `rssi` field is produced by.
 *
 * Not thread-safe. One instance per node, owned by whatever collects that node's samples.
 *
 * @param alpha EMA weight on the newest value, in `(0, 1]`. Higher reacts faster.
 * @param medianWindow number of raw samples the median runs over. Must be odd and >= 1.
 * @param statsWindowMillis how far back [stats] looks. Also the window [SignalStats.packetRate]
 *   is measured over.
 */
class RssiSmoother(
    val alpha: Double = DEFAULT_ALPHA,
    val medianWindow: Int = DEFAULT_MEDIAN_WINDOW,
    val statsWindowMillis: Long = DEFAULT_STATS_WINDOW_MILLIS,
) {
    init {
        require(alpha > 0.0 && alpha <= 1.0) { "alpha must be in (0, 1], was $alpha" }
        require(medianWindow >= 1 && medianWindow % 2 == 1) {
            "medianWindow must be odd and >= 1, was $medianWindow"
        }
        require(statsWindowMillis > 0) { "statsWindowMillis must be positive" }
    }

    private val rawWindow = ArrayDeque<Int>()
    private val history = ArrayDeque<SignalSample>()
    private var ema: Double? = null

    /** The smoothed value, or `null` before the first sample. */
    val smoothed: Double? get() = ema

    /** Every sample still inside [statsWindowMillis], oldest first. */
    val window: List<SignalSample> get() = history.toList()

    /**
     * Feeds one sample in and returns the new smoothed value.
     *
     * Samples are assumed to arrive in time order; an out-of-order sample still updates
     * the EMA but will be evicted from the stats window on the next in-order arrival.
     */
    fun add(sample: SignalSample): Double {
        rawWindow.addLast(sample.rssi)
        while (rawWindow.size > medianWindow) rawWindow.removeFirst()

        val median = rawWindow.sorted()[rawWindow.size / 2].toDouble()
        val previous = ema
        val next = if (previous == null) median else previous + alpha * (median - previous)
        ema = next

        history.addLast(sample)
        evictOlderThan(sample.timestamp)
        return next
    }

    /**
     * Statistics over the samples inside the window, or `null` if the window is empty.
     *
     * @param now epoch milliseconds to measure the window from. Pass the wall clock so a
     *   node that has gone quiet reports a falling packet rate rather than a stale one.
     */
    fun stats(now: Long): SignalStats? {
        evictOlderThan(now)
        if (history.isEmpty()) return null

        val values = history.map { it.rssi }
        val mean = values.sumOf { it.toDouble() } / values.size
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        val span = history.last().timestamp - history.first().timestamp
        val rate = if (values.size < 2 || span <= 0L) 0.0 else values.size * 1000.0 / span

        return SignalStats(
            count = values.size,
            mean = mean,
            sigma = sqrt(variance),
            min = values.min(),
            max = values.max(),
            packetRate = rate,
        )
    }

    /** Drops the smoothed value and the whole window, as if the node had never been heard. */
    fun reset() {
        rawWindow.clear()
        history.clear()
        ema = null
    }

    private fun evictOlderThan(now: Long) {
        val cutoff = now - statsWindowMillis
        while (history.isNotEmpty() && history.first().timestamp < cutoff) history.removeFirst()
    }

    companion object {
        const val DEFAULT_ALPHA: Double = 0.2
        const val DEFAULT_MEDIAN_WINDOW: Int = 5
        const val DEFAULT_STATS_WINDOW_MILLIS: Long = 10_000L
    }
}

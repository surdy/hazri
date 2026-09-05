package dev.surdy.hazri.vm

/**
 * How far a recording has got, as the ongoing notification shows it.
 *
 * A value class rather than three parameters because it is carried across a platform seam
 * and, on Android, across a process-local flow into a service.
 */
data class SurveyProgress(
    val room: String,
    val elapsedMillis: Long,
    val sampleCount: Int,
) {
    /**
     * Elapsed time as `m:ss`, or `h:mm:ss` once a walk passes an hour.
     *
     * A survey is minutes long, so the hour field is omitted rather than shown as a
     * permanent `0:`. Negative input — a clock that went backwards — reads as zero.
     */
    val elapsedLabel: String
        get() {
            val totalSeconds = (elapsedMillis / MILLIS_PER_SECOND).coerceAtLeast(0L)
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
            val hours = totalSeconds / SECONDS_PER_HOUR
            return if (hours > 0) "$hours:${pad(minutes)}:${pad(seconds)}"
            else "$minutes:${pad(seconds)}"
        }

    /** The notification's second line: elapsed time and how much has been heard. */
    val summary: String
        get() = "$elapsedLabel · $sampleCount ${if (sampleCount == 1) "sample" else "samples"}"

    private fun pad(value: Long): String = if (value < 10) "0$value" else value.toString()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60L
        const val MINUTES_PER_HOUR = 60L
        const val SECONDS_PER_HOUR = 3_600L
    }
}

/**
 * Keeps the phone collecting while a survey records.
 *
 * A survey is a walk around a room, which means the phone is in a pocket with the screen
 * off — exactly the state in which Android stops an app's BLE scan and its network reads.
 * The platform answer is a foreground service, which no shared code can start, so the
 * survey view model calls this instead and Android supplies the implementation.
 *
 * [update] is called on the survey's own tick, several times a second, and an
 * implementation that redraws a notification each time would be doing far more work than
 * a changing second counter is worth — [SurveyViewModel] throttles it to once a second.
 */
interface SurveyKeepAlive {
    /** A recording of [room] has begun. */
    fun start(room: String)

    /** The recording has advanced. Called at most once a second. */
    fun update(progress: SurveyProgress)

    /** The recording has ended, however it ended. Safe to call when nothing is running. */
    fun stop()

    companion object {
        /**
         * The default: a platform with nothing to keep alive.
         *
         * Every test and every non-Android build gets this, so the view model needs no
         * knowledge of whether it is running somewhere with a service to start.
         */
        val None: SurveyKeepAlive = object : SurveyKeepAlive {
            override fun start(room: String) = Unit
            override fun update(progress: SurveyProgress) = Unit
            override fun stop() = Unit
        }
    }
}

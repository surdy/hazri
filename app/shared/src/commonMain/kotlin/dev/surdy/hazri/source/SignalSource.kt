package dev.surdy.hazri.source

import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Anything that produces RSSI readings: a BLE scan, an MQTT subscription, or the simulator.
 *
 * The three are interchangeable by construction, which is what lets the Compare sources
 * tool run two of them side by side and what lets every screen be exercised with no
 * hardware present.
 */
interface SignalSource {
    /** Which of the three this is. Stamped onto every sample it emits. */
    val source: Source

    /**
     * The readings. Hot: samples produced before a collector attaches are lost, because a
     * reading from thirty seconds ago is not worth replaying to a placement tool.
     */
    val samples: Flow<SignalSample>

    /** Starts producing. Returns once producing has begun, or throws if it cannot. */
    suspend fun start()

    /** Stops producing. Safe to call when not started. */
    fun stop()
}

/** Epoch milliseconds. The one thing every source needs and none of them can compute alone. */
fun interface MillisClock {
    fun now(): Long
}

/** The wall clock. */
@OptIn(ExperimentalTime::class)
val SystemClock: MillisClock = MillisClock { Clock.System.now().toEpochMilliseconds() }

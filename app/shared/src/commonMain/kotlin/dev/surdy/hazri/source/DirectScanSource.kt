package dev.surdy.hazri.source

import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Scans for the nodes' own BLE advertisements and reports what the phone hears from each.
 *
 * The reverse of MQTT mode: here the phone listens and the nodes advertise. RSSI is
 * roughly symmetric, so this is a usable proxy for what the nodes hear from the phone —
 * and it needs no broker, no Wi-Fi and no Home Assistant, which is what makes it the mode
 * to do placement work in.
 *
 * Platform-specific because BLE scanning is. The Android actual is Kable; an iOS actual is
 * the same library and the same shape.
 */
expect class DirectScanSource : SignalSource {
    override val source: Source
    override val samples: Flow<SignalSample>
    override suspend fun start()
    override fun stop()

    /**
     * Advertisers heard that are not ESPresense nodes at all, newest reading first.
     *
     * Node beacons no longer land here — they become nodes with a
     * [dev.surdy.hazri.source.beaconNodeId] and appear in Tools -> Nodes & rooms. What is
     * left is unrelated hardware, kept so that a node whose identity mapping is wrong looks
     * like a bug rather than like a node out of range.
     */
    val unidentified: StateFlow<List<UnidentifiedAdvertiser>>

    /** Forgets everything in [unidentified]. */
    fun clearUnidentified()
}

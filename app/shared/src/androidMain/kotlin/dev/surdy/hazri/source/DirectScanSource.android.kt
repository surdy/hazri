package dev.surdy.hazri.source

import android.bluetooth.le.ScanSettings
import com.juul.kable.Filter
import com.juul.kable.ObsoleteKableApi
import com.juul.kable.PlatformAdvertisement
import com.juul.kable.Scanner
import com.juul.kable.logs.Logging
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Android BLE scanning through Kable.
 *
 * Kable rather than `BluetoothLeScanner` directly for one reason: the same class with the
 * same API exists for Darwin, so the iOS actual of [DirectScanSource] is this file with
 * the platform imports changed.
 *
 * The resting scan is deliberately unfiltered. Filtering in the platform scanner would be
 * cheaper, but ESPresense nodes advertise a manufacturer-data iBeacon and no service UUID,
 * and the point of [unidentified] is to see what is out there — a filter that dropped the
 * thing the scan spike is looking for would defeat it.
 *
 * A recording is the exception, and not for cost: Android stops delivering an unfiltered
 * scan's results once the screen is off, so a survey walked with the phone in a pocket
 * would hear nothing at all. [NodeBeaconFilter] is the narrowest filter that lifts that.
 *
 * Node beacons with no known room are still emitted, under the `node-<major>-<minor>` id
 * [dev.surdy.hazri.source.beaconNodeId] gives them, so they appear on the Live screen and
 * in Tools -> Nodes & rooms where the user can supply the firmware room. Only genuinely
 * unrelated advertisers reach [unidentified].
 *
 * The scan lives on the caller's scope, which on Android is the application's. While a
 * survey records, `SurveyForegroundService` holds the process in the foreground so the
 * platform keeps delivering with the screen off; outside a recording the Activity stops
 * the engine when it is backgrounded, and the scan stops with it.
 */
actual class DirectScanSource(
    private val identifier: NodeIdentifier,
    private val scope: CoroutineScope,
    private val clock: MillisClock = SystemClock,
    /** Called when the platform ends the scan, so the failure reaches the Live screen. */
    private val onScanFailed: (String) -> Unit = {},
    /**
     * Called for every advertisement that resolves to a node, with the beacon fingerprint
     * when there is one.
     *
     * The scanner is the only place a fingerprint is visible, and the repository is the only
     * place it is useful — this is the wire between them. Without it a scan-discovered node
     * had no fingerprint on its record, so a later retained config from the broker had
     * nothing to match and the room was thrown away.
     */
    private val onNodeSeen: (NodeId, String?) -> Unit = { _, _ -> },
    /**
     * Builds a scanner at the given duty cycle. A function rather than a scanner because
     * the mode is baked into the platform's `ScanSettings` at scan time, so changing it
     * means a new scanner and a restarted scan. See [useSurveyScanRate].
     */
    private val scannerAt: (Boolean) -> Scanner<PlatformAdvertisement> = ::scannerFor,
) : SignalSource {

    actual override val source: Source = Source.DIRECT

    private val emitted = MutableSharedFlow<SignalSample>(extraBufferCapacity = 256)
    actual override val samples: Flow<SignalSample> = emitted.asSharedFlow()

    private val unknown = MutableStateFlow<List<UnidentifiedAdvertiser>>(emptyList())
    actual val unidentified: StateFlow<List<UnidentifiedAdvertiser>> = unknown.asStateFlow()

    private var job: Job? = null
    private var surveyRate = false

    actual override suspend fun start() {
        if (job != null) return
        launchScan()
    }

    actual override fun stop() {
        job?.cancel()
        job = null
    }

    actual fun useSurveyScanRate(active: Boolean) {
        if (surveyRate == active) return
        surveyRate = active
        if (job == null) return
        stop()
        launchScan()
    }

    private fun launchScan() {
        val scanner = scannerAt(surveyRate)
        job = scope.launch {
            scanner.advertisements
                // A scan that dies mid-flight — Bluetooth switched off, the permission
                // revoked from Settings — surfaces through the engine's error state, which
                // is the one place the Live screen already looks.
                .catch { cause -> onScanFailed(cause.message ?: "The scan stopped") }
                .collect { advertisement -> onAdvertisement(advertisement.toReading()) }
        }
    }

    /** Forgets everything heard so far. The debug list's clear action. */
    actual fun clearUnidentified() {
        unknown.value = emptyList()
    }

    private suspend fun onAdvertisement(reading: BleAdvertisement) {
        val nodeId = identifier.identify(reading)
        if (nodeId == null) {
            remember(reading)
            return
        }
        onNodeSeen(nodeId, identifier.fingerprintOf(reading))
        emitted.emit(
            SignalSample(
                nodeId = nodeId,
                rssi = reading.rssi,
                timestamp = clock.now(),
                source = Source.DIRECT,
            )
        )
    }

    private fun remember(reading: BleAdvertisement) {
        val current = unknown.value
        val existing = current.firstOrNull { it.address == reading.address }
        val updated = UnidentifiedAdvertiser(
            address = reading.address,
            localName = reading.localName ?: existing?.localName,
            lastRssi = reading.rssi,
            lastSeen = clock.now(),
            seenCount = (existing?.seenCount ?: 0) + 1,
            companyIds = reading.manufacturerData.keys.sorted(),
        )
        unknown.value = (listOf(updated) + current.filterNot { it.address == reading.address })
            .take(MAX_UNIDENTIFIED)
    }

    private companion object {
        const val MAX_UNIDENTIFIED = 60

        /**
         * A scanner at one of the two duty cycles.
         *
         * The platform's default is `SCAN_MODE_LOW_POWER`, which listens for a fraction of
         * a second every few seconds — fine for finding a device to pair with, far too
         * coarse for reading how loud a node is from where the user is standing. Balanced
         * is the resting rate; a recording gets low latency, which is a continuous scan.
         *
         * A recording is also the only scan that carries a filter, because an unfiltered
         * one goes silent when the screen does. See [NodeBeaconFilter].
         */
        @OptIn(ObsoleteKableApi::class)
        fun scannerFor(surveyRate: Boolean): Scanner<PlatformAdvertisement> = Scanner {
            logging { level = Logging.Level.Warnings }
            scanSettings = ScanSettings.Builder()
                .setScanMode(
                    if (surveyRate) ScanSettings.SCAN_MODE_LOW_LATENCY
                    else ScanSettings.SCAN_MODE_BALANCED
                )
                .build()
            if (surveyRate) {
                filters {
                    match {
                        manufacturerData = listOf(
                            Filter.ManufacturerData(
                                id = NodeBeaconFilter.COMPANY_ID,
                                data = NodeBeaconFilter.DATA,
                                dataMask = NodeBeaconFilter.DATA_MASK,
                            )
                        )
                    }
                }
            }
        }

        fun PlatformAdvertisement.toReading(): BleAdvertisement {
            val company = manufacturerData
            return BleAdvertisement(
                address = address,
                localName = name ?: peripheralName,
                rssi = rssi,
                manufacturerData = if (company == null) emptyMap() else mapOf(company.code to company.data),
            )
        }
    }
}

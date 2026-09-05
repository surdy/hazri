package dev.surdy.hazri.android

import android.content.Context
import dev.surdy.hazri.data.AppSettings
import dev.surdy.hazri.data.HazriRepository
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.source.DefaultNodeIdentifier
import dev.surdy.hazri.source.DirectScanSource
import dev.surdy.hazri.source.HiveMqGateway
import dev.surdy.hazri.source.MqttSignalSource
import dev.surdy.hazri.source.SignalSource
import dev.surdy.hazri.source.SimulatedSignalSource
import dev.surdy.hazri.vm.SourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Builds the three sources on Android.
 *
 * The direct scanner is created once and kept: Kable's scanner holds a platform callback,
 * and building a new one on every source switch would leak the old one. The MQTT source is
 * rebuilt each time MQTT mode is selected, because the broker settings may have changed
 * since the last time.
 */
class AndroidSourceFactory(
    private val context: Context,
    private val repository: HazriRepository,
    /** Where a scan that dies mid-flight is reported. Wired to the engine's error state. */
    private val onScanFailed: (String) -> Unit = {},
) : SourceFactory {

    private var scanner: DirectScanSource? = null

    override fun directScan(scope: CoroutineScope): SignalSource? {
        if (!BluetoothAvailability.hasScanPermission(context)) return null
        return scanner ?: DirectScanSource(
            identifier = identifier(),
            scope = scope,
            onNodeSeen = repository::noteNode,
            onScanFailed = onScanFailed,
        ).also { scanner = it }
    }

    override fun mqtt(scope: CoroutineScope, settings: AppSettings): MqttSignalSource? {
        if (!settings.broker.isConfigured) return null
        val gateway = HiveMqGateway()
        val source = MqttSignalSource(
            gateway = gateway,
            phoneId = settings.phoneId,
            scope = scope,
            onNodeAnnouncement = { fingerprint, room ->
                repository.learnBrokerRoom(fingerprint, Espresense.slugifyRoom(room))
            },
            nodeIdForRoom = repository::nodeIdForRoom,
        )
        scope.launch { gateway.connect(settings.broker.toConfig(clientIdFor(settings))) }
        return source
    }

    override fun simulated(scope: CoroutineScope): SimulatedSignalSource =
        SimulatedSignalSource(scope = scope)

    /**
     * The identifier the scan uses.
     *
     * The lookup is a function, not a snapshot: the scanner is built once and kept for the
     * life of the process, so a map captured here would be whatever the repository held at
     * the moment Direct mode was first selected. Reading it per advertisement means a room
     * the broker announces, or a name the user types, applies to the next packet.
     */
    private fun identifier(): DefaultNodeIdentifier = DefaultNodeIdentifier(
        nodeIdForFingerprint = repository::nodeIdForFingerprint,
    )

    /** A client id the broker will not confuse with another Hazri install. */
    private fun clientIdFor(settings: AppSettings): String {
        val suffix = settings.phoneId.filter { it.isLetterOrDigit() }.takeLast(CLIENT_ID_SUFFIX)
        return "hazri-$suffix"
    }

    private companion object {
        const val CLIENT_ID_SUFFIX = 8
    }
}

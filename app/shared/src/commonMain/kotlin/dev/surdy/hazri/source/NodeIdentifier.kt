package dev.surdy.hazri.source

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.protocol.Espresense

/**
 * One BLE advertisement, reduced to the fields node identity could possibly live in.
 *
 * @param address the platform's handle for the advertiser. A MAC on Android, an opaque
 *   UUID on iOS, and randomised on both for most peripherals — usable as a session-local
 *   key for "the same unidentified thing" and for nothing else.
 * @param manufacturerData company identifier to payload, as advertised.
 */
data class BleAdvertisement(
    val address: String,
    val localName: String?,
    val rssi: Int,
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        other is BleAdvertisement &&
            address == other.address &&
            localName == other.localName &&
            rssi == other.rssi &&
            manufacturerData.keys == other.manufacturerData.keys

    override fun hashCode(): Int =
        address.hashCode() * 31 + (localName?.hashCode() ?: 0)
}

/** An iBeacon frame, as carried inside Apple's manufacturer-specific data. */
data class IBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val measuredPower: Int,
)

/**
 * Reads iBeacon frames out of Apple manufacturer data.
 *
 * The layout, after the two company-identifier bytes the platform has already stripped:
 * `02 15` then 16 bytes of proximity UUID, two of major, two of minor, and one signed
 * byte of measured power. 23 bytes in total.
 */
object IBeaconParser {
    /** Apple's Bluetooth SIG company identifier. */
    const val APPLE_COMPANY_ID: Int = 0x004C

    private const val TYPE_PROXIMITY: Byte = 0x02
    private const val LENGTH_PROXIMITY: Byte = 0x15
    private const val FRAME_SIZE: Int = 23

    /** Parses [data], or returns `null` if it is not a proximity beacon frame. */
    fun parse(data: ByteArray): IBeacon? {
        if (data.size < FRAME_SIZE) return null
        if (data[0] != TYPE_PROXIMITY || data[1] != LENGTH_PROXIMITY) return null

        val uuid = buildString {
            for (index in 2 until 18) {
                if (length == 8 || length == 13 || length == 18 || length == 23) append('-')
                append(HEX[(data[index].toInt() shr 4) and 0xF])
                append(HEX[data[index].toInt() and 0xF])
            }
        }
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        return IBeacon(uuid = uuid, major = major, minor = minor, measuredPower = data[22].toInt())
    }

    private const val HEX: String = "0123456789abcdef"
}

/** Turns an advertisement into a node identity, or admits it cannot. */
interface NodeIdentifier {
    /** The node this advertisement belongs to, or `null` if it is not a node at all. */
    fun identify(advertisement: BleAdvertisement): NodeId?

    /**
     * The `iBeacon:<uuid>-<major>-<minor>` this advertiser would be known by on MQTT, or
     * `null` if it is not an ESPresense node beacon.
     *
     * On the interface rather than on the implementation because the scanner needs it for
     * every advertisement — identified or not — and downcasting to get at it was how the
     * scanner ended up unable to record a fingerprint for anything it named.
     */
    fun fingerprintOf(advertisement: BleAdvertisement): String? = null
}

/**
 * The node id Hazri gives a node beacon before anything has told it the room.
 *
 * `node-<major>-<minor>`: stable across reboots, derived from the advertisement alone, and
 * distinguishable at a glance from a room slug. When the broker later announces the room,
 * [dev.surdy.hazri.data.HazriRepository.learnBrokerRoom] fills it in on this same record
 * rather than creating a second one.
 */
fun beaconNodeId(fingerprint: String): NodeId? =
    Espresense.parseBeaconFingerprint(fingerprint)?.let { NodeId("node-${it.major}-${it.minor}") }

/**
 * Identifies ESPresense nodes from their own iBeacon advertisement.
 *
 * ## How a node names itself
 *
 * Verified 2026-09-04 against ESPresense `master` (v4.0.6) and this repository's
 * `docs/espresense-topics.md`. Outside enrollment every node advertises a
 * non-connectable iBeacon under one fleet-wide UUID, [Espresense.NODE_BEACON_UUID]
 * (`e5ca1ade-f007-ba11-…`), with major and minor derived from the top bytes of the chip's
 * eFuse MAC. The node then publishes itself retained on
 * `espresense/settings/iBeacon:<uuid>-<major>-<minor>/config` as
 * `{"id":"node:<room>","name":"<room>"}`.
 *
 * So the UUID says "this is an ESPresense node", major/minor say *which* node, and the
 * retained config says which room that node is.
 *
 * ## Naming a node with no broker
 *
 * The room only exists on MQTT, so a scan on its own cannot produce one. It can still
 * produce a stable identity: an unmapped node beacon resolves to [beaconNodeId], which is
 * what puts it on the Live screen and in Tools -> Nodes & rooms where the user can name it.
 * Dropping it instead — which is what this class used to do — meant direct-scan mode could
 * never name anything and the debug list nothing read.
 *
 * [nodeIdForFingerprint] is a **live** lookup, not a snapshot. It is called per
 * advertisement and reads whatever the repository knows now, so a room announced by the
 * broker or typed by the user takes effect on the next packet rather than on the next scan.
 *
 * The advertised local name is checked first and is weaker than it looks: `scanTask` calls
 * `NimBLEDevice::init("ESPresense")`, so on current firmware every node in the house
 * advertises the same constant name. [localNamePrefix] therefore separates ESPresense from
 * a fridge and nothing finer — unless a build suffixes it, in which case the suffix wins.
 *
 * ## TODO — confirm on hardware
 *
 * The UUID, the major/minor derivation and the retained-config shape are read from source,
 * not measured. The first task with two AtomS3 Lites on the bench is to capture one node's
 * advertisement and check three things: that the UUID matches byte for byte, that major and
 * minor are stable across reboots, and that the retained config appears under exactly that
 * fingerprint.
 */
class DefaultNodeIdentifier(
    val localNamePrefix: String = DEFAULT_LOCAL_NAME_PREFIX,
    val nodeBeaconUuid: String = Espresense.NODE_BEACON_UUID,
    private val nodeIdForFingerprint: (String) -> NodeId? = { null },
) : NodeIdentifier {

    override fun identify(advertisement: BleAdvertisement): NodeId? =
        byLocalName(advertisement) ?: byBeacon(advertisement)

    override fun fingerprintOf(advertisement: BleAdvertisement): String? {
        val beacon = beaconIn(advertisement) ?: return null
        if (!beacon.uuid.equals(nodeBeaconUuid, ignoreCase = true)) return null
        return Espresense.nodeFingerprint(beacon.major, beacon.minor)
    }

    private fun byLocalName(advertisement: BleAdvertisement): NodeId? {
        val name = advertisement.localName?.trim().orEmpty()
        if (name.isEmpty()) return null
        if (!name.startsWith(localNamePrefix, ignoreCase = true)) return null
        // "espresense-kitchen" names a room; a bare "ESPresense" — which is what current
        // firmware advertises — names the fleet and is not an identity.
        val suffix = name.drop(localNamePrefix.length).trimStart('-', '_', ' ')
        return suffix.takeIf { it.isNotEmpty() }?.lowercase()?.let(::NodeId)
    }

    private fun byBeacon(advertisement: BleAdvertisement): NodeId? {
        val fingerprint = fingerprintOf(advertisement) ?: return null
        return nodeIdForFingerprint(fingerprint) ?: beaconNodeId(fingerprint)
    }

    private fun beaconIn(advertisement: BleAdvertisement): IBeacon? {
        val apple = advertisement.manufacturerData[IBeaconParser.APPLE_COMPANY_ID] ?: return null
        return IBeaconParser.parse(apple)
    }

    companion object {
        const val DEFAULT_LOCAL_NAME_PREFIX: String = "espresense"
    }
}

/**
 * An advertiser the scan heard and could not name at all — not an ESPresense node beacon,
 * and no matching local name.
 *
 * Node beacons no longer land here: they become nodes with a [beaconNodeId] instead. What
 * is left is genuinely unrelated hardware, kept so that a node whose identity mapping is
 * wrong looks like a bug rather than like a node out of range.
 */
data class UnidentifiedAdvertiser(
    val address: String,
    val localName: String?,
    val lastRssi: Int,
    val lastSeen: Long,
    val seenCount: Int,
    val companyIds: List<Int>,
)

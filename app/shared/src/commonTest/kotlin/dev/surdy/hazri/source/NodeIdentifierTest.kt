package dev.surdy.hazri.source

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.protocol.Espresense
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NodeIdentifierTest {

    /** An Apple proximity-beacon frame: `02 15`, 16 UUID bytes, major, minor, power. */
    private fun beaconFrame(uuidHex: String, major: Int, minor: Int, power: Int = -59): ByteArray {
        val uuid = uuidHex.replace("-", "").chunked(2).map { it.toInt(16).toByte() }
        return byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(
            ((major shr 8) and 0xFF).toByte(),
            (major and 0xFF).toByte(),
            ((minor shr 8) and 0xFF).toByte(),
            (minor and 0xFF).toByte(),
            power.toByte(),
        )
    }

    private fun nodeBeacon(major: Int, minor: Int) = advertisement(
        manufacturerData = mapOf(
            IBeaconParser.APPLE_COMPANY_ID to beaconFrame(Espresense.NODE_BEACON_UUID, major, minor)
        )
    )

    private fun advertisement(
        localName: String? = null,
        manufacturerData: Map<Int, ByteArray> = emptyMap(),
    ) = BleAdvertisement("AA:BB:CC:DD:EE:FF", localName, -62, manufacturerData)

    @Test
    fun `an ibeacon frame parses into uuid major and minor`() {
        val beacon = IBeaconParser.parse(beaconFrame(Espresense.NODE_BEACON_UUID, 258, 7))!!
        assertEquals(Espresense.NODE_BEACON_UUID, beacon.uuid)
        assertEquals(258, beacon.major)
        assertEquals(7, beacon.minor)
        assertEquals(-59, beacon.measuredPower)
    }

    @Test
    fun `data that is not a proximity frame parses to nothing`() {
        assertNull(IBeaconParser.parse(byteArrayOf(0x0C, 0x0E)))
        assertNull(IBeaconParser.parse(byteArrayOf(0x02, 0x15)))
        assertNull(IBeaconParser.parse(ByteArray(23)))
    }

    @Test
    fun `a node beacon yields the fingerprint it publishes itself under`() {
        val identifier = DefaultNodeIdentifier()
        val reading = advertisement(
            manufacturerData = mapOf(
                IBeaconParser.APPLE_COMPANY_ID to beaconFrame(Espresense.NODE_BEACON_UUID, 41, 7)
            )
        )
        assertEquals("iBeacon:${Espresense.NODE_BEACON_UUID}-41-7", identifier.fingerprintOf(reading))
    }

    @Test
    fun `some other vendor's beacon is not a node`() {
        val identifier = DefaultNodeIdentifier()
        val reading = advertisement(
            manufacturerData = mapOf(
                IBeaconParser.APPLE_COMPANY_ID to beaconFrame(
                    "11111111-2222-3333-4444-555555555555", 1, 1
                )
            )
        )
        assertNull(identifier.fingerprintOf(reading))
        assertNull(identifier.identify(reading))
    }

    @Test
    fun `a node beacon the repository already knows resolves to that node`() {
        val fingerprint = "iBeacon:${Espresense.NODE_BEACON_UUID}-41-7"
        val identifier = DefaultNodeIdentifier(
            nodeIdForFingerprint = { if (it == fingerprint) NodeId("kitchen") else null },
        )
        assertEquals(NodeId("kitchen"), identifier.identify(nodeBeacon(41, 7)))
    }

    @Test
    fun `the lookup is consulted per advertisement, not captured once`() {
        // The scanner is built once and kept for the life of the process, so a snapshot here
        // would freeze whatever the repository held when Direct mode was first selected.
        var known: NodeId? = null
        val identifier = DefaultNodeIdentifier(nodeIdForFingerprint = { known })

        assertEquals(NodeId("node-41-7"), identifier.identify(nodeBeacon(41, 7)))
        known = NodeId("kitchen")
        assertEquals(NodeId("kitchen"), identifier.identify(nodeBeacon(41, 7)))
    }

    @Test
    fun `a node beacon with no mapping still becomes a node`() {
        // The room lives only in a retained MQTT topic, so a scan alone cannot supply one —
        // but it can supply a stable identity, which is what puts the node on screen where
        // the user can name it. Dropping it is what made direct-scan mode useless.
        assertEquals(NodeId("node-41-7"), DefaultNodeIdentifier().identify(nodeBeacon(41, 7)))
    }

    @Test
    fun `the fallback id is derived from the beacon alone and is stable`() {
        assertEquals(NodeId("node-258-7"), beaconNodeId(Espresense.nodeFingerprint(258, 7)))
        assertNull(beaconNodeId("irk:0123456789abcdef"))
    }

    @Test
    fun `the fingerprint is available for any advertisement, identified or not`() {
        val identifier = DefaultNodeIdentifier(
            nodeIdForFingerprint = { NodeId("kitchen") },
        )
        // Named by the lookup, and the fingerprint still comes back — the scanner needs both
        // so that a record can carry the fingerprint the broker will announce against.
        assertEquals(NodeId("kitchen"), identifier.identify(nodeBeacon(41, 7)))
        assertEquals(Espresense.nodeFingerprint(41, 7), identifier.fingerprintOf(nodeBeacon(41, 7)))
        assertNull(identifier.fingerprintOf(advertisement("Fridge")))
    }

    @Test
    fun `a suffixed local name names the room`() {
        val identifier = DefaultNodeIdentifier()
        assertEquals(NodeId("kitchen"), identifier.identify(advertisement("ESPresense-Kitchen")))
        assertEquals(NodeId("hall"), identifier.identify(advertisement("espresense_hall")))
    }

    @Test
    fun `the bare firmware name identifies no room`() {
        // Current firmware calls NimBLEDevice::init("ESPresense"), so every node in the
        // house advertises this same constant. It names the fleet, not a node.
        assertNull(DefaultNodeIdentifier().identify(advertisement("ESPresense")))
    }

    @Test
    fun `an unrelated advertiser is not a node`() {
        assertNull(DefaultNodeIdentifier().identify(advertisement("Fridge")))
        assertNull(DefaultNodeIdentifier().identify(advertisement(null)))
    }

    @Test
    fun `the local name prefix is configurable`() {
        val identifier = DefaultNodeIdentifier(localNamePrefix = "bermuda")
        assertEquals(NodeId("attic"), identifier.identify(advertisement("bermuda-attic")))
        assertNull(identifier.identify(advertisement("ESPresense-Kitchen")))
    }
}

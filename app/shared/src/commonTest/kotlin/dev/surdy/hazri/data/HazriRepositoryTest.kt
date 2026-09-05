package dev.surdy.hazri.data

import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.domain.Verdict
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.source.BleAdvertisement
import dev.surdy.hazri.source.DefaultNodeIdentifier
import dev.surdy.hazri.source.IBeaconParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HazriRepositoryTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")

    /**
     * Writes are scheduled rather than performed inline, so tests run them on an unconfined
     * scope where a `launch` body completes before the call returns.
     */
    private fun repository(store: FileStore = InMemoryFileStore()) =
        HazriRepository(store, CoroutineScope(Dispatchers.Unconfined))

    private fun survey(room: String, endedAt: Long, means: Map<NodeId, Double>) = RoomSurvey(
        room = room,
        startedAt = endedAt - 1_000L,
        endedAt = endedAt,
        source = Source.SIMULATED,
        stats = means.map { (nodeId, mean) -> NodeSurveyStat(nodeId, mean, 1.0, 10) },
    )

    @Test
    fun `a fresh repository is empty and has the defaults`() {
        val repository = repository()
        assertTrue(repository.isEmpty())
        assertEquals(SourceKind.SIMULATED, repository.settings.value.sourceKind)
    }

    @Test
    fun `state survives a restart through the store`() {
        val store = InMemoryFileStore()
        repository(store).apply {
            addSurvey(survey("Kitchen", 1_000L, mapOf(kitchen to -55.0)))
            updateSettings { it.copy(phoneId = "iBeacon:x-1-2", marginDb = 7.0) }
        }

        val reopened = repository(store)
        assertEquals(1, reopened.surveys.value.size)
        assertEquals("iBeacon:x-1-2", reopened.settings.value.phoneId)
        assertEquals(7.0, reopened.settings.value.marginDb)
    }

    @Test
    fun `a corrupt document falls back to the default rather than throwing`() {
        val store = InMemoryFileStore(mapOf("settings.json" to "{ this is not json"))
        assertEquals(AppSettings.DEFAULT, repository(store).settings.value)
    }

    @Test
    fun `recording a survey also records its room`() {
        val repository = repository()
        repository.addSurvey(survey("Landing", 1_000L, mapOf(kitchen to -60.0)))
        assertEquals(listOf("Landing"), repository.rooms.value)
    }

    @Test
    fun `only the newest survey per room reaches coverage`() {
        val repository = repository()
        repository.addSurvey(survey("Kitchen", 1_000L, mapOf(kitchen to -90.0)))
        repository.addSurvey(survey("Kitchen", 9_000L, mapOf(kitchen to -55.0)))

        assertEquals(2, repository.surveys.value.size)
        assertEquals(1, repository.latestSurveys().size)
        assertEquals(-55.0, repository.coverage().cell("Kitchen", kitchen)!!.mean)
    }

    @Test
    fun `coverage uses the configured thresholds`() {
        val repository = repository()
        repository.addSurvey(survey("Hallway", 1_000L, mapOf(kitchen to -60.0, hall to -66.0)))
        assertEquals(Verdict.CLEAR, repository.coverage().verdicts["Hallway"]!!.verdict)

        repository.updateSettings { it.copy(marginDb = 10.0) }
        assertEquals(Verdict.TIGHT, repository.coverage().verdicts["Hallway"]!!.verdict)
    }

    @Test
    fun `a discovered node gets a default record once`() {
        val repository = repository()
        repository.noteNode(kitchen)
        repository.noteNode(kitchen)

        assertEquals(1, repository.nodes.value.size)
        assertEquals("Kitchen", repository.node(kitchen)!!.displayName)
    }

    @Test
    fun `noting a node again attaches a fingerprint learned later`() {
        val repository = repository()
        repository.noteNode(kitchen)
        repository.noteNode(kitchen, beaconFingerprint = "iBeacon:uuid-41-7")

        assertEquals("iBeacon:uuid-41-7", repository.node(kitchen)!!.beaconFingerprint)
        assertEquals(1, repository.nodes.value.size)
    }

    @Test
    fun `a broker-learned room fills in a node the user has not named`() {
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        repository.noteNode(NodeId("node-41-7"), beaconFingerprint = fingerprint)
        repository.learnBrokerRoom(fingerprint, "kitchen")

        val record = repository.node(NodeId("node-41-7"))!!
        assertEquals("kitchen", record.espresenseRoom)
        assertEquals("Kitchen", record.displayName)
        assertTrue(record.roomIsConfirmed)
    }

    @Test
    fun `a broker-learned room never overwrites a name the user set`() {
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        repository.noteNode(NodeId("node-41-7"), beaconFingerprint = fingerprint)
        repository.renameNode(NodeId("node-41-7"), "Under the stairs")
        repository.learnBrokerRoom(fingerprint, "kitchen")

        val record = repository.node(NodeId("node-41-7"))!!
        assertEquals("Under the stairs", record.displayName)
        // The room is still taken: it is the topic segment and the firmware's own answer.
        assertEquals("kitchen", record.espresenseRoom)
        assertTrue(record.nameIsUserSet)
    }

    @Test
    fun `an announcement for a node nothing has scanned creates the record`() {
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(99, 9)
        repository.learnBrokerRoom(fingerprint, "attic")

        val record = repository.node(NodeId("node-99-9"))!!
        assertEquals("attic", record.espresenseRoom)
        assertEquals(fingerprint, record.beaconFingerprint)
    }

    @Test
    fun `an announcement attaches the fingerprint to a node discovered over MQTT`() {
        // The case that made direct-scan naming impossible: an MQTT-discovered node has no
        // fingerprint, so the announcement matched nothing and the mapping was thrown away.
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        repository.noteNode(kitchen)
        repository.learnBrokerRoom(fingerprint, "kitchen")

        assertEquals(1, repository.nodes.value.size)
        assertEquals(fingerprint, repository.node(kitchen)!!.beaconFingerprint)
        assertEquals(kitchen, repository.nodeIdForFingerprint(fingerprint))
    }

    @Test
    fun `MQTT announcement then direct scan resolves to the same node and room`() {
        // The end-to-end path: a node announces itself, the scanner's live lookup finds the
        // record by fingerprint, and a push goes to the room the firmware named — not to a
        // slug of whatever the user called it.
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        repository.noteNode(kitchen)
        repository.learnBrokerRoom(fingerprint, "kitchen")
        repository.renameNode(kitchen, "Under the stairs")

        val identifier = DefaultNodeIdentifier(
            nodeIdForFingerprint = repository::nodeIdForFingerprint,
        )
        val scanned = identifier.identify(nodeBeaconAdvertisement(41, 7))

        assertEquals(kitchen, scanned)
        assertEquals("Under the stairs", repository.node(kitchen)!!.displayName)
        assertEquals("kitchen", repository.node(kitchen)!!.config().room)
    }

    @Test
    fun `announcement, then device report, then scan, is one record`() {
        // The normal order on a live broker: the retained settings config arrives before the
        // first device report. Keying the report's samples on the room rather than on the
        // node already announced under that room is what used to produce two records for one
        // board — direct samples on one, MQTT samples on the other.
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)

        repository.learnBrokerRoom(fingerprint, "kitchen")
        val fromReport = repository.nodeIdForRoom("kitchen") ?: NodeId("kitchen")
        repository.noteNode(fromReport)
        val fromScan = DefaultNodeIdentifier(nodeIdForFingerprint = repository::nodeIdForFingerprint)
            .identify(nodeBeaconAdvertisement(41, 7))

        assertEquals(NodeId("node-41-7"), fromReport)
        assertEquals(fromReport, fromScan)
        assertEquals(1, repository.nodes.value.size)
        assertEquals("kitchen", repository.node(fromReport)!!.config().room)
    }

    @Test
    fun `device report, then announcement, then scan, is one record`() {
        // The other order: first contact is a device report, so the record is created under
        // the room, and the announcement has to attach the fingerprint to it rather than
        // create a second one.
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)

        val fromReport = repository.nodeIdForRoom("kitchen") ?: NodeId("kitchen")
        repository.noteNode(fromReport)
        repository.learnBrokerRoom(fingerprint, "kitchen")
        val fromScan = DefaultNodeIdentifier(nodeIdForFingerprint = repository::nodeIdForFingerprint)
            .identify(nodeBeaconAdvertisement(41, 7))

        assertEquals(kitchen, fromReport)
        assertEquals(fromReport, fromScan)
        assertEquals(1, repository.nodes.value.size)
        assertEquals(fingerprint, repository.node(kitchen)!!.beaconFingerprint)
    }

    @Test
    fun `an unconfirmed room is not offered as a match for a device topic`() {
        // A scan-only record's room is a placeholder equal to its own id. Binding a real
        // MQTT room to a guess would be the same bug pointing the other way.
        val repository = repository()
        repository.noteNode(NodeId("node-41-7"), Espresense.nodeFingerprint(41, 7))

        assertNull(repository.nodeIdForRoom("node-41-7"))
        assertNull(repository.nodeIdForRoom(""))
    }

    @Test
    fun `renaming a node never moves where its settings are published`() {
        val repository = repository()
        repository.noteNode(kitchen)
        repository.renameNode(kitchen, "Bread bin")

        assertEquals("Bread bin", repository.node(kitchen)!!.displayName)
        assertEquals("kitchen", repository.node(kitchen)!!.config().room)
    }

    @Test
    fun `the user can correct the firmware room, and it is slugified`() {
        val repository = repository()
        repository.noteNode(NodeId("node-41-7"))
        repository.setEspresenseRoom(NodeId("node-41-7"), "Living Room")

        val record = repository.node(NodeId("node-41-7"))!!
        assertEquals("living_room", record.espresenseRoom)
        assertTrue(record.roomIsConfirmed)
    }

    @Test
    fun `a node discovered by scanning has no confirmed room`() {
        val repository = repository()
        val fingerprint = Espresense.nodeFingerprint(41, 7)
        repository.noteNode(NodeId("node-41-7"), beaconFingerprint = fingerprint)
        assertFalse(repository.node(NodeId("node-41-7"))!!.roomIsConfirmed)
    }

    @Test
    fun `a node discovered over MQTT is discovered as its room`() {
        val repository = repository()
        repository.noteNode(kitchen)
        assertTrue(repository.node(kitchen)!!.roomIsConfirmed)
        assertEquals("kitchen", repository.node(kitchen)!!.espresenseRoom)
    }

    @Test
    fun `ref_rssi is unknown until it has been pushed`() {
        val repository = repository()
        repository.noteNode(kitchen)
        assertFalse(repository.node(kitchen)!!.refRssiIsKnown)

        repository.updateNode(kitchen) { it.copy(refRssiPushedAt = 1_700L) }
        assertTrue(repository.node(kitchen)!!.refRssiIsKnown)
    }

    @Test
    fun `a node config round trips, and cannot move the room`() {
        val repository = repository()
        repository.noteNode(kitchen)
        repository.updateNodeConfig(kitchen, NodeConfig("somewhere-else", -61, 3.1, 6.0))

        assertEquals(NodeConfig("kitchen", -61, 3.1, 6.0), repository.node(kitchen)!!.config())
    }

    @Test
    fun `neither a config edit nor an announcement adds a room to the chip row`() {
        // Rooms are the user's vocabulary and come from surveys and from the Survey screen.
        // A firmware slug sitting beside "Kitchen" in the chip row is noise.
        val repository = repository()
        repository.noteNode(kitchen)
        repository.updateNodeConfig(kitchen, NodeConfig("kitchen", -61, 3.1, 6.0))
        repository.learnBrokerRoom(Espresense.nodeFingerprint(41, 7), "hall")

        assertTrue(repository.rooms.value.isEmpty())
    }

    @Test
    fun `hidden nodes are excluded from the visible list`() {
        val repository = repository()
        repository.noteNode(kitchen)
        repository.noteNode(hall)
        repository.updateNode(hall) { it.copy(hidden = true) }

        assertEquals(listOf(kitchen), repository.visibleNodes().map { it.id })
    }

    private fun nodeBeaconAdvertisement(major: Int, minor: Int): BleAdvertisement {
        val uuid = Espresense.NODE_BEACON_UUID.replace("-", "")
            .chunked(2).map { it.toInt(16).toByte() }
        val frame = byteArrayOf(0x02, 0x15) + uuid + byteArrayOf(
            ((major shr 8) and 0xFF).toByte(),
            (major and 0xFF).toByte(),
            ((minor shr 8) and 0xFF).toByte(),
            (minor and 0xFF).toByte(),
            (-59).toByte(),
        )
        return BleAdvertisement(
            address = "AA:BB:CC:DD:EE:FF",
            localName = null,
            rssi = -62,
            manufacturerData = mapOf(IBeaconParser.APPLE_COMPANY_ID to frame),
        )
    }

    @Test
    fun `updating an unknown node does nothing`() {
        val repository = repository()
        repository.updateNode(kitchen) { it.copy(displayName = "Ghost") }
        assertNull(repository.node(kitchen))
    }

    @Test
    fun `rooms are added once and can be removed`() {
        val repository = repository()
        repository.addRoom("Hall")
        repository.addRoom("  Hall  ")
        repository.addRoom("")
        assertEquals(listOf("Hall"), repository.rooms.value)

        repository.removeRoom("Hall")
        assertTrue(repository.rooms.value.isEmpty())
    }

    @Test
    fun `deleting a room's surveys leaves other rooms alone`() {
        val repository = repository()
        repository.addSurvey(survey("Kitchen", 1_000L, mapOf(kitchen to -55.0)))
        repository.addSurvey(survey("Hallway", 1_000L, mapOf(hall to -66.0)))
        repository.deleteSurveys("Kitchen")

        assertEquals(listOf("Hallway"), repository.surveys.value.map { it.room })
    }
}

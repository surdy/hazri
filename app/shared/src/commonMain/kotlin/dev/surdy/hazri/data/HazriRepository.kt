package dev.surdy.hazri.data

import dev.surdy.hazri.domain.CoverageMatrix
import dev.surdy.hazri.domain.Node
import dev.surdy.hazri.domain.NodeConfig
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.protocol.Espresense
import dev.surdy.hazri.source.beaconNodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Everything the app remembers between launches: surveys, node aliases and configs, the
 * room list, and the settings.
 *
 * Four JSON documents in a [FileStore] rather than a database. The whole dataset is a
 * handful of rooms times a handful of nodes — a survey is already reduced to a mean and a
 * sigma before it gets here — so the cost of a schema, a migration story and a Gradle
 * plugin buys nothing. See `app/README.md`.
 *
 * State is exposed as [StateFlow]s that are the single source of truth in the process. A
 * write updates the flow synchronously and schedules the file write on [writeScope], so
 * nothing on the main thread ever waits on a filesystem — which matters because
 * [noteNode] is called from the sample pipeline, dozens of times a second.
 *
 * ## Why the writes are ticketed and not just locked
 *
 * A [Mutex] alone serialises the writers but not their *order*: two saves of the same
 * document each launch a coroutine, and on a multi-threaded dispatcher whichever one the
 * pool happens to resume first takes the lock first. A rename followed by a hide could
 * persist the pre-hide JSON, and the app would come back from a restart with the hide
 * undone.
 *
 * So every save takes a ticket in caller order and carries its own encoded content. A
 * writer that reaches the lock with a ticket older than the one already applied to that
 * document has been superseded and does nothing. The result is last-write-wins on the
 * caller's order, whatever the dispatcher does with the coroutines in between. The default
 * scope is single-threaded as well, so the common case never has to rely on it.
 *
 * Pass an unconfined scope in tests to make writes synchronous.
 */
class HazriRepository(
    private val store: FileStore,
    private val writeScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
) {

    private val writeLock = Mutex()

    /** Handed out in caller order. Only ever touched from the calling thread. */
    private var nextTicket: Long = 0L

    /** The newest ticket already written per document. Only ever touched under [writeLock]. */
    private val appliedTicket = mutableMapOf<String, Long>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val settingsState = MutableStateFlow(load(SETTINGS, AppSettings.DEFAULT))
    private val surveysState = MutableStateFlow(load(SURVEYS, emptyList<StoredSurvey>()))
    private val nodesState = MutableStateFlow(load(NODES, emptyList<NodeRecord>()))
    private val roomsState = MutableStateFlow(load(ROOMS, RoomList()).rooms)

    /** The app settings. */
    val settings: StateFlow<AppSettings> = settingsState.asStateFlow()

    /** Every survey ever recorded, oldest first. */
    val surveys: StateFlow<List<StoredSurvey>> = surveysState.asStateFlow()

    /** Every node the app has been told about, in discovery order. */
    val nodes: StateFlow<List<NodeRecord>> = nodesState.asStateFlow()

    /** The room names the Survey screen offers, in user order. */
    val rooms: StateFlow<List<String>> = roomsState.asStateFlow()

    /** Replaces the settings. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsState.value = transform(settingsState.value).also { save(SETTINGS, it) }
    }

    /** Appends a finished survey. Older surveys of the same room are kept, not replaced. */
    fun addSurvey(survey: RoomSurvey) {
        surveysState.value = (surveysState.value + StoredSurvey.of(survey)).also { save(SURVEYS, it) }
        addRoom(survey.room)
    }

    /** The node id the beacon [fingerprint] is already known by, or `null`. */
    fun nodeIdForFingerprint(fingerprint: String): NodeId? =
        nodesState.value.firstOrNull { it.beaconFingerprint == fingerprint }?.nodeId

    /**
     * The node id already known for the firmware [room], or `null`.
     *
     * What stops one physical node becoming two records. A retained settings config normally
     * arrives before the first device report, and it creates the record under the beacon's
     * own id — so keying the report's samples on the room instead would create a second
     * record with no fingerprint, and the two would never be reconciled: direct samples on
     * one, MQTT samples on the other, a column each in Coverage, and nothing for Compare
     * sources to pair.
     *
     * Only confirmed rooms match. An unconfirmed one is a placeholder equal to the node's
     * own id, and binding a real MQTT room to a guess would be the same bug pointing the
     * other way.
     */
    fun nodeIdForRoom(room: String): NodeId? {
        val slug = Espresense.slugifyRoom(room)
        if (slug.isEmpty()) return null
        return nodesState.value
            .firstOrNull { it.roomIsConfirmed && it.espresenseRoom == slug }
            ?.nodeId
    }

    /** Forgets every survey of [room]. */
    fun deleteSurveys(room: String) {
        surveysState.value = surveysState.value.filterNot { it.room == room }
            .also { save(SURVEYS, it) }
    }

    /** The most recent survey of each room, newest first. */
    fun latestSurveys(): List<RoomSurvey> = surveysState.value
        .map { it.toDomain() }
        .groupBy { it.room }
        .map { (_, forRoom) -> forRoom.maxBy { it.endedAt } }
        .sortedByDescending { it.endedAt }

    /** The coverage grid, built from the latest survey of each room. */
    fun coverage(): CoverageMatrix = CoverageMatrix.of(
        surveys = surveysState.value.map { it.toDomain() },
        nodeOrder = nodesState.value.filterNot { it.hidden }.map { it.nodeId },
        thresholds = settingsState.value.thresholds(),
    )

    /** Adds [room] to the room list if it is not already there. */
    fun addRoom(room: String) {
        val trimmed = room.trim()
        if (trimmed.isEmpty() || trimmed in roomsState.value) return
        roomsState.value = (roomsState.value + trimmed).also { save(ROOMS, RoomList(it)) }
    }

    /** Removes [room] from the room list. Its surveys are left alone. */
    fun removeRoom(room: String) {
        roomsState.value = roomsState.value.filterNot { it == room }
            .also { save(ROOMS, RoomList(it)) }
    }

    /**
     * Records that [nodeId] exists, creating a default record if it is new.
     *
     * Called from the sample pipeline, so it must be cheap and idempotent: the common case
     * is a node that is already known, which does no work and writes nothing.
     */
    fun noteNode(nodeId: NodeId, beaconFingerprint: String? = null) {
        val existing = nodesState.value.firstOrNull { it.id == nodeId.value }
        if (existing != null) {
            if (beaconFingerprint == null || existing.beaconFingerprint == beaconFingerprint) return
            updateNode(nodeId) { it.copy(beaconFingerprint = beaconFingerprint) }
            return
        }
        // A node discovered by MQTT is discovered *as* its room — the id is the topic's last
        // segment — so its room is confirmed. One discovered by scanning is a
        // `node-<major>-<minor>` with no room at all until the broker or the user says.
        val fromScan = beaconNodeId(beaconFingerprint.orEmpty()) == nodeId
        nodesState.value = (
            nodesState.value + NodeRecord.forDiscovered(
                nodeId = nodeId,
                beaconFingerprint = beaconFingerprint,
                roomIsConfirmed = !fromScan,
            )
            ).also { save(NODES, it) }
    }

    /** Edits one node's record. No-op if the node is unknown. */
    fun updateNode(nodeId: NodeId, transform: (NodeRecord) -> NodeRecord) {
        val current = nodesState.value
        if (current.none { it.id == nodeId.value }) return
        nodesState.value = current
            .map { if (it.id == nodeId.value) transform(it) else it }
            .also { save(NODES, it) }
    }

    /**
     * Renames a node, on the user's instruction.
     *
     * Display name only. It is never turned into a topic segment — see [NodeRecord] — and
     * marking [NodeRecord.nameIsUserSet] stops a later retained config overwriting it.
     */
    fun renameNode(nodeId: NodeId, displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) return
        updateNode(nodeId) { it.copy(displayName = trimmed, nameIsUserSet = true) }
    }

    /**
     * Corrects the firmware room a node's settings are published to.
     *
     * The escape hatch for direct-scan mode with no broker: nothing in an advertisement
     * carries the room, so the user has to supply it before a push can go anywhere useful.
     * Slugified on the way in, because that is what the firmware does to it.
     */
    fun setEspresenseRoom(nodeId: NodeId, room: String) {
        val slug = Espresense.slugifyRoom(room)
        if (slug.isEmpty()) return
        updateNode(nodeId) { it.copy(espresenseRoom = slug, roomIsConfirmed = true) }
    }

    /**
     * Applies a fingerprint-to-room mapping a node announced about itself on
     * `espresense/settings/<fingerprint>/config`.
     *
     * Three cases, and the second two are why direct-scan mode can name anything at all:
     *
     *  - a record already carries the fingerprint: take the room;
     *  - a record exists for the room but with no fingerprint — the normal MQTT-discovered
     *    node — so attach the fingerprint to it, which is what lets a later scan resolve to
     *    the same node rather than a second one;
     *  - nothing matches: create the record now, keyed by [beaconNodeId], so the mapping is
     *    ready before the scan ever sees the beacon.
     */
    fun learnBrokerRoom(beaconFingerprint: String, room: String) {
        val slug = Espresense.slugifyRoom(room)
        if (slug.isEmpty()) return

        val current = nodesState.value
        val byFingerprint = current.firstOrNull { it.beaconFingerprint == beaconFingerprint }
        if (byFingerprint != null) {
            nodesState.value = current
                .map { if (it.id == byFingerprint.id) it.withAnnouncedRoom(slug) else it }
                .also { save(NODES, it) }
            return
        }

        val byRoom = current.firstOrNull { it.espresenseRoom == slug || it.id == slug }
        if (byRoom != null) {
            nodesState.value = current
                .map {
                    if (it.id == byRoom.id) {
                        it.withAnnouncedRoom(slug).copy(beaconFingerprint = beaconFingerprint)
                    } else {
                        it
                    }
                }
                .also { save(NODES, it) }
            return
        }

        val nodeId = beaconNodeId(beaconFingerprint) ?: return
        nodesState.value = (
            current + NodeRecord.forDiscovered(
                nodeId = nodeId,
                beaconFingerprint = beaconFingerprint,
                espresenseRoom = slug,
                roomIsConfirmed = true,
            ).copy(displayName = slug.replaceFirstChar { it.uppercase() })
            ).also { save(NODES, it) }
    }

    /**
     * Writes one node's tuning.
     *
     * The room is not part of it: rooms come from surveys and from the user, and a firmware
     * slug landing in the Survey screen's chip row beside the user's own "Kitchen" is
     * noise, not information.
     */
    fun updateNodeConfig(nodeId: NodeId, config: NodeConfig) {
        updateNode(nodeId) { it.withConfig(config) }
    }

    /** The record for [nodeId], or `null`. */
    fun node(nodeId: NodeId): NodeRecord? = nodesState.value.firstOrNull { it.id == nodeId.value }

    /** Every visible node as a domain [Node]. */
    fun visibleNodes(): List<Node> = nodesState.value.filterNot { it.hidden }.map { it.toNode() }

    /** Display names for every known node, for suggestion text and chart labels. */
    fun displayNames(): Map<NodeId, String> =
        nodesState.value.associate { it.nodeId to it.displayName }

    /** Replaces the whole persisted state. Used by the seeder and by tests. */
    fun replaceAll(
        settings: AppSettings = settingsState.value,
        surveys: List<StoredSurvey> = surveysState.value,
        nodes: List<NodeRecord> = nodesState.value,
        rooms: List<String> = roomsState.value,
    ) {
        settingsState.value = settings.also { save(SETTINGS, it) }
        surveysState.value = surveys.also { save(SURVEYS, it) }
        nodesState.value = nodes.also { save(NODES, it) }
        roomsState.value = rooms.also { save(ROOMS, RoomList(it)) }
    }

    /** Whether anything has ever been stored. The seeder's gate. */
    fun isEmpty(): Boolean =
        surveysState.value.isEmpty() && nodesState.value.isEmpty() && roomsState.value.isEmpty()

    private inline fun <reified T> load(name: String, fallback: T): T {
        val raw = store.read(name) ?: return fallback
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
    }

    private inline fun <reified T> save(name: String, value: T) {
        stage(name, json.encodeToString(value))
    }

    private fun stage(name: String, encoded: String) {
        val ticket = ++nextTicket
        writeScope.launch {
            writeLock.withLock {
                if (ticket < appliedTicket.getOrElse(name) { 0L }) return@withLock
                appliedTicket[name] = ticket
                store.write(name, encoded)
            }
        }
    }

    private companion object {
        const val SETTINGS = "settings.json"
        const val SURVEYS = "surveys.json"
        const val NODES = "nodes.json"
        const val ROOMS = "rooms.json"
    }
}

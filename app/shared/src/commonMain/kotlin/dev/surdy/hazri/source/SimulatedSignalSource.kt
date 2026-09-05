package dev.surdy.hazri.source

import dev.surdy.hazri.domain.DistanceModel
import dev.surdy.hazri.domain.Node
import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.SignalSample
import dev.surdy.hazri.domain.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A point on the house's floor plan, in metres. */
data class Point(val x: Double, val y: Double) {
    fun distanceTo(other: Point): Double {
        val dx = x - other.x
        val dy = y - other.y
        return sqrt(dx * dx + dy * dy)
    }
}

/** A room in the virtual house: a name and the spot the walk stands in. */
data class SimulatedRoom(val name: String, val centre: Point)

/**
 * A node in the virtual house.
 *
 * @param model this node's own distance model. Different per node on purpose, so the
 *   calibration screen has something to correct.
 * @param wallLossDb extra attenuation applied to every reading from this node, standing in
 *   for the walls between it and the rest of the house. Without it every node is a clean
 *   inverse-square from its own position and no room is ever Tight.
 */
data class SimulatedNode(
    val node: Node,
    val position: Point,
    val model: DistanceModel = DistanceModel.DEFAULT,
    val wallLossDb: Double = 0.0,
)

/**
 * The floor plan the simulator walks.
 *
 * [DEFAULT] is a six-room, five-node house sized to reproduce the three verdicts the app
 * has to be able to show: most rooms Clear, the Hallway Tight because the Hall and Living
 * nodes are both close to it, and the Garage Blind because no node is within range.
 */
data class VirtualHouse(
    val rooms: List<SimulatedRoom>,
    val nodes: List<SimulatedNode>,
) {
    companion object {
        /**
         * Six rooms and five nodes, sized so that the three verdicts all occur:
         *
         *  - Kitchen, Living room, Bedroom and Office each have a node in the corner and
         *    the next-nearest node several metres away, so each is Clear by 15-25 dB.
         *  - The Hallway sits between the Hall node and the Living node at roughly equal
         *    distance, so it is Tight by a couple of dB. That is the case the app exists
         *    to find, so the simulator has to be able to produce it.
         *  - The Garage is eight metres past the nearest node through a wall, so nothing
         *    hears it above the -85 dBm floor and it is Blind.
         *
         * Every node's own model is near the firmware defaults but not equal to them,
         * which is what gives the calibration screen something real to correct.
         */
        val DEFAULT: VirtualHouse = VirtualHouse(
            rooms = listOf(
                SimulatedRoom("Kitchen", Point(2.0, 2.0)),
                SimulatedRoom("Living room", Point(9.0, 2.5)),
                SimulatedRoom("Hallway", Point(6.5, 3.5)),
                SimulatedRoom("Bedroom", Point(2.5, 9.0)),
                SimulatedRoom("Office", Point(9.5, 9.0)),
                SimulatedRoom("Garage", Point(17.0, 13.0)),
            ),
            nodes = listOf(
                SimulatedNode(
                    node = Node(NodeId("kitchen"), "Kitchen", "kitchen"),
                    position = Point(1.5, 1.5),
                    model = DistanceModel(refRssi = -63, absorption = 2.6),
                ),
                SimulatedNode(
                    node = Node(NodeId("living"), "Living room", "living"),
                    position = Point(9.5, 2.0),
                    model = DistanceModel(refRssi = -66, absorption = 2.7),
                ),
                SimulatedNode(
                    node = Node(NodeId("hall"), "Hallway", "hall"),
                    position = Point(4.0, 4.0),
                    model = DistanceModel(refRssi = -64, absorption = 2.5),
                    wallLossDb = 2.0,
                ),
                SimulatedNode(
                    node = Node(NodeId("bedroom"), "Bedroom", "bedroom"),
                    position = Point(2.0, 9.5),
                    model = DistanceModel(refRssi = -67, absorption = 2.8),
                    wallLossDb = 3.0,
                ),
                SimulatedNode(
                    node = Node(NodeId("office"), "Office", "office"),
                    position = Point(10.0, 9.5),
                    model = DistanceModel(refRssi = -62, absorption = 2.7),
                    wallLossDb = 3.0,
                ),
            ),
        )

        /** The noise-free RSSI [node] would report for a phone standing at [position]. */
        fun cleanRssi(node: SimulatedNode, position: Point, minMetres: Double = 0.4): Double {
            val metres = node.position.distanceTo(position).coerceAtLeast(minMetres)
            return node.model.rssiAt(metres) - node.wallLossDb
        }
    }
}

/**
 * A signal source with no hardware behind it: a phone walking a scripted loop through
 * [house], with RSSI from each node's own log-distance model plus Gaussian noise and
 * occasional dropped packets.
 *
 * Deterministic for a given [seed] — the same seed produces the same readings in the same
 * order, which is what makes the domain testable against it. Real elapsed time does not
 * enter into the values, only into when they are emitted.
 *
 * This is the default source in debug builds. Every screen has to be fully usable against
 * it, so it produces all three verdicts and a node that drops in and out.
 *
 * @param tickMillis how often each node reports. Five nodes at 120 ms is about 40 pkt/s,
 *   which is the order ESPresense actually produces.
 * @param dwellMillis how long the walk stands in each room before moving to the next.
 * @param travelMillis how long the walk takes between two rooms.
 * @param noiseSigmaDb standard deviation of the Gaussian added to every reading.
 * @param dropRate probability that any one node's reading is not emitted on a given tick.
 */
class SimulatedSignalSource(
    val house: VirtualHouse = VirtualHouse.DEFAULT,
    private val seed: Int = DEFAULT_SEED,
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    private val dwellMillis: Long = DEFAULT_DWELL_MILLIS,
    private val travelMillis: Long = DEFAULT_TRAVEL_MILLIS,
    private val noiseSigmaDb: Double = DEFAULT_NOISE_SIGMA_DB,
    private val dropRate: Double = DEFAULT_DROP_RATE,
    private val clock: MillisClock = SystemClock,
    private val scope: CoroutineScope,
) : SignalSource {

    override val source: Source = Source.SIMULATED

    private val emitted = MutableSharedFlow<SignalSample>(extraBufferCapacity = 256)
    override val samples: Flow<SignalSample> = emitted.asSharedFlow()

    private var random = Random(seed)
    private var job: Job? = null
    private var elapsed: Long = 0L

    /** The nodes this house contains, in floor-plan order. */
    val nodes: List<Node> = house.nodes.map { it.node }

    /** Where the walk is now, in metres. Moves only while the source is running. */
    var phonePosition: Point = house.rooms.first().centre
        private set

    /** The room the walk is standing in or heading towards. */
    var currentRoom: String = house.rooms.first().name
        private set

    private var pinnedRoom: SimulatedRoom? = null

    /**
     * Holds the walk in one room until unpinned, or resumes the loop when [room] is `null`.
     *
     * A survey is a claim about one room, and the simulated walker does not know which room
     * the user tapped. Without this, recording "Kitchen" while the scripted loop happens to
     * be in the hallway files a Kitchen survey won by the Hall node — a plausible-looking
     * number that is simply wrong. Pinning makes the simulator answer the question the user
     * actually asked.
     *
     * Unknown room names are ignored rather than treated as an error: the user can add a
     * room the virtual house does not have, and the honest response is to keep walking.
     */
    fun pinTo(room: String?) {
        pinnedRoom = room?.let { name -> house.rooms.firstOrNull { it.name.equals(name, true) } }
        pinnedRoom?.let {
            phonePosition = it.centre
            currentRoom = it.name
        }
    }

    override suspend fun start() {
        if (job != null) return
        random = Random(seed)
        elapsed = 0L
        job = scope.launch {
            while (isActive) {
                advance(tickMillis)
                val now = clock.now()
                house.nodes.forEach { simulated ->
                    if (random.nextDouble() >= dropRate) {
                        emitted.emit(
                            SignalSample(
                                nodeId = simulated.node.id,
                                rssi = rssiFrom(simulated, phonePosition),
                                timestamp = now,
                                source = Source.SIMULATED,
                            )
                        )
                    }
                }
                delay(tickMillis)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Produces one tick's worth of samples without any coroutine or clock, for tests and
     * for the Compare sources tool's second stream.
     *
     * @param at the timestamp to stamp on the samples.
     */
    fun tick(at: Long): List<SignalSample> {
        advance(tickMillis)
        return house.nodes.mapNotNull { simulated ->
            if (random.nextDouble() < dropRate) return@mapNotNull null
            SignalSample(
                nodeId = simulated.node.id,
                rssi = rssiFrom(simulated, phonePosition),
                timestamp = at,
                source = Source.SIMULATED,
            )
        }
    }

    /** The RSSI [node] would report for a phone at [position], noise included. */
    private fun rssiFrom(node: SimulatedNode, position: Point): Int {
        val clean = VirtualHouse.cleanRssi(node, position, MIN_DISTANCE_METRES)
        return (clean + gaussian() * noiseSigmaDb).toInt().coerceIn(MIN_RSSI, MAX_RSSI)
    }

    /**
     * Moves the walk on by [millis].
     *
     * The loop is: stand in a room for [dwellMillis], then walk to the next room over
     * [travelMillis], interpolating in a straight line. Room order is the order in
     * [VirtualHouse.rooms], repeating forever.
     */
    private fun advance(millis: Long) {
        pinnedRoom?.let { room ->
            phonePosition = room.centre
            currentRoom = room.name
            return
        }
        elapsed += millis
        val legMillis = dwellMillis + travelMillis
        val loopMillis = legMillis * house.rooms.size
        val inLoop = elapsed % loopMillis
        val legIndex = (inLoop / legMillis).toInt()
        val inLeg = inLoop % legMillis

        val from = house.rooms[legIndex]
        val to = house.rooms[(legIndex + 1) % house.rooms.size]

        if (inLeg < dwellMillis) {
            phonePosition = from.centre
            currentRoom = from.name
        } else {
            val progress = (inLeg - dwellMillis).toDouble() / travelMillis
            phonePosition = Point(
                x = from.centre.x + (to.centre.x - from.centre.x) * progress,
                y = from.centre.y + (to.centre.y - from.centre.y) * progress,
            )
            currentRoom = if (progress < 0.5) from.name else to.name
        }
    }

    /** Box-Muller. [Random] has no Gaussian and this needs to stay seed-deterministic. */
    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(TWO_PI * u2)
    }

    companion object {
        const val DEFAULT_SEED: Int = 20260904
        const val DEFAULT_TICK_MILLIS: Long = 120L
        const val DEFAULT_DWELL_MILLIS: Long = 14_000L
        const val DEFAULT_TRAVEL_MILLIS: Long = 4_000L
        const val DEFAULT_NOISE_SIGMA_DB: Double = 3.0
        const val DEFAULT_DROP_RATE: Double = 0.06

        private const val MIN_DISTANCE_METRES: Double = 0.4
        private const val MIN_RSSI: Int = -100
        private const val MAX_RSSI: Int = -20
        private const val TWO_PI: Double = 6.283185307179586
    }
}

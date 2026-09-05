package dev.surdy.hazri.data

import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.source.SimulatedSignalSource
import dev.surdy.hazri.source.VirtualHouse

/**
 * Fills an empty install with the simulated house's surveys.
 *
 * The app has to be fully usable with no hardware, and two of its five screens — Coverage
 * and the Surveyed list — render nothing at all until somebody has walked a room. Seeding
 * is what makes them show something on first launch.
 *
 * The means are computed from the virtual house's geometry with the noise left out, so what
 * Coverage shows is exactly what the house is designed to produce: four Clear rooms, a
 * Tight Hallway and a Blind Garage. Walking a room in the simulator then overwrites its
 * seeded survey with a real one, which is the behaviour a real install has.
 *
 * Debug builds only, and only when nothing has ever been stored.
 */
object DemoSeed {

    /** Seeds [repository] if it is empty. Returns whether anything was written. */
    fun seedIfEmpty(
        repository: HazriRepository,
        now: Long,
        house: VirtualHouse = VirtualHouse.DEFAULT,
    ): Boolean {
        if (!repository.isEmpty()) return false

        val nodes = house.nodes.map { simulated ->
            NodeRecord(
                id = simulated.node.id.value,
                displayName = simulated.node.displayName,
                espresenseRoom = simulated.node.room,
                roomIsConfirmed = true,
                beaconFingerprint = null,
                refRssi = simulated.model.refRssi,
                absorption = simulated.model.absorption,
                maxDistance = SEED_MAX_DISTANCE_METRES,
                refRssiPushedAt = null,
            )
        }

        val surveys = house.rooms.mapIndexed { index, room ->
            val endedAt = now - (house.rooms.size - index) * SEED_SPACING_MILLIS
            StoredSurvey.of(
                RoomSurvey(
                    room = room.name,
                    startedAt = endedAt - SEED_DURATION_MILLIS,
                    endedAt = endedAt,
                    source = Source.SIMULATED,
                    stats = house.nodes.map { simulated ->
                        NodeSurveyStat(
                            nodeId = simulated.node.id,
                            mean = VirtualHouse.cleanRssi(simulated, room.centre),
                            sigma = SEED_SIGMA_DB,
                            count = SEED_SAMPLE_COUNT,
                        )
                    }.sortedByDescending { it.mean },
                )
            )
        }

        repository.replaceAll(
            settings = AppSettings.DEFAULT.copy(sourceKind = SourceKind.SIMULATED),
            surveys = surveys,
            nodes = nodes,
            rooms = house.rooms.map { it.name },
        )
        return true
    }

    private const val SEED_SPACING_MILLIS = 7 * 60_000L
    private const val SEED_DURATION_MILLIS = 45_000L
    private const val SEED_SIGMA_DB = SimulatedSignalSource.DEFAULT_NOISE_SIGMA_DB
    private const val SEED_SAMPLE_COUNT = 118
    private const val SEED_MAX_DISTANCE_METRES = 16.0
}

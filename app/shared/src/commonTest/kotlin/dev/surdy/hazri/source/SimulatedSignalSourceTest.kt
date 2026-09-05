package dev.surdy.hazri.source

import dev.surdy.hazri.domain.RoomVerdict
import dev.surdy.hazri.domain.Source
import dev.surdy.hazri.domain.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedSignalSourceTest {

    private fun source(seed: Int = 1) = SimulatedSignalSource(
        seed = seed,
        scope = CoroutineScope(Job()),
    )

    @Test
    fun `the same seed produces the same readings`() {
        val first = (1..50).flatMap { source(seed = 42).tick(it.toLong()) }
        val second = (1..50).flatMap { source(seed = 42).tick(it.toLong()) }
        assertEquals(first, second)
    }

    @Test
    fun `different seeds produce different readings`() {
        val a = (1..200).flatMap { source(seed = 1).tick(it.toLong()) }
        val b = (1..200).flatMap { source(seed = 2).tick(it.toLong()) }
        assertTrue(a != b)
    }

    @Test
    fun `every sample is marked simulated and stays in a plausible range`() {
        val simulator = source()
        val samples = (1..500).flatMap { simulator.tick(it.toLong()) }
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.source == Source.SIMULATED })
        assertTrue(samples.all { it.rssi in -100..-20 }, "rssi outside plausible range")
    }

    @Test
    fun `packets are dropped often enough to be visible and rarely enough to be usable`() {
        val simulator = source()
        val ticks = 1_000
        val emitted = (1..ticks).sumOf { simulator.tick(it.toLong()).size }
        val expected = ticks * VirtualHouse.DEFAULT.nodes.size
        val dropRate = 1.0 - emitted.toDouble() / expected
        assertTrue(dropRate > 0.01, "expected some drops, rate was $dropRate")
        assertTrue(dropRate < 0.15, "expected few drops, rate was $dropRate")
    }

    @Test
    fun `the walk visits every room`() {
        val simulator = source()
        val visited = mutableSetOf<String>()
        repeat(3_000) { index ->
            simulator.tick(index.toLong())
            visited += simulator.currentRoom
        }
        assertEquals(VirtualHouse.DEFAULT.rooms.map { it.name }.toSet(), visited)
    }

    @Test
    fun `the house is built to produce all three verdicts`() {
        // The whole point of the simulator is that every screen is exercisable, which means
        // Coverage has to be able to show a Clear, a Tight and a Blind room.
        val verdicts = VirtualHouse.DEFAULT.rooms.associate { room ->
            room.name to RoomVerdict.of(
                room = room.name,
                means = VirtualHouse.DEFAULT.nodes.associate { node ->
                    node.node.id to VirtualHouse.cleanRssi(node, room.centre)
                },
            ).verdict
        }

        assertEquals(Verdict.CLEAR, verdicts["Kitchen"])
        assertEquals(Verdict.TIGHT, verdicts["Hallway"])
        assertEquals(Verdict.BLIND, verdicts["Garage"])
    }

    @Test
    fun `standing in a room makes that room's node the loudest`() {
        val kitchen = VirtualHouse.DEFAULT.rooms.first { it.name == "Kitchen" }
        val loudest = VirtualHouse.DEFAULT.nodes
            .maxBy { VirtualHouse.cleanRssi(it, kitchen.centre) }
        assertEquals("kitchen", loudest.node.id.value)
    }
}

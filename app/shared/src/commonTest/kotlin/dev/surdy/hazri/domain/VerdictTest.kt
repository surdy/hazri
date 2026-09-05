package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VerdictTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")
    private val living = NodeId("living")

    @Test
    fun `a gap of exactly the threshold is clear`() {
        val verdict = RoomVerdict.of("Kitchen", mapOf(kitchen to -60.0, hall to -65.0))
        assertEquals(Verdict.CLEAR, verdict.verdict)
        assertEquals(5.0, verdict.margin!!, TOLERANCE)
    }

    @Test
    fun `a gap just under the threshold is tight`() {
        val verdict = RoomVerdict.of("Hallway", mapOf(hall to -60.0, living to -64.9))
        assertEquals(Verdict.TIGHT, verdict.verdict)
        assertEquals(4.9, verdict.margin!!, TOLERANCE)
    }

    @Test
    fun `the best and runner-up are reported in strength order`() {
        val verdict = RoomVerdict.of(
            room = "Hallway",
            means = mapOf(living to -70.0, hall to -66.0, kitchen to -88.0),
        )
        assertEquals(hall, verdict.best)
        assertEquals(living, verdict.runnerUp)
        assertEquals(-66.0, verdict.bestMean!!, TOLERANCE)
        assertEquals(-70.0, verdict.runnerUpMean!!, TOLERANCE)
    }

    @Test
    fun `nothing above the floor is blind whatever the gap`() {
        // A 20 dB gap, and still blind: there is nothing to be ambiguous about.
        val verdict = RoomVerdict.of("Garage", mapOf(hall to -89.0, living to -109.0))
        assertEquals(Verdict.BLIND, verdict.verdict)
        assertNull(verdict.margin)
        assertEquals(hall, verdict.best)
    }

    @Test
    fun `a reading exactly on the floor is blind`() {
        val verdict = RoomVerdict.of("Garage", mapOf(hall to -85.0))
        assertEquals(Verdict.BLIND, verdict.verdict)
    }

    @Test
    fun `a reading just above the floor is not blind`() {
        val verdict = RoomVerdict.of("Garage", mapOf(hall to -84.9))
        assertEquals(Verdict.CLEAR, verdict.verdict)
    }

    @Test
    fun `a single node above the floor is clear with no margin`() {
        val verdict = RoomVerdict.of("Study", mapOf(kitchen to -60.0))
        assertEquals(Verdict.CLEAR, verdict.verdict)
        assertNull(verdict.margin)
        assertNull(verdict.runnerUp)
    }

    @Test
    fun `no nodes at all is blind`() {
        val verdict = RoomVerdict.of("Loft", emptyMap())
        assertEquals(Verdict.BLIND, verdict.verdict)
        assertNull(verdict.best)
    }

    @Test
    fun `thresholds are configurable in both directions`() {
        val means = mapOf(kitchen to -60.0, hall to -67.0)

        val strict = RoomVerdict.of("Kitchen", means, VerdictThresholds(marginDb = 10.0))
        assertEquals(Verdict.TIGHT, strict.verdict)

        val lenient = RoomVerdict.of("Kitchen", means, VerdictThresholds(marginDb = 3.0))
        assertEquals(Verdict.CLEAR, lenient.verdict)

        val highFloor = RoomVerdict.of("Kitchen", means, VerdictThresholds(floorDbm = -50.0))
        assertEquals(Verdict.BLIND, highFloor.verdict)
    }

    private companion object {
        const val TOLERANCE = 1e-9
    }
}

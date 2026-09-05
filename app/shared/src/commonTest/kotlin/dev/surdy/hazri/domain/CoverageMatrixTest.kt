package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageMatrixTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")
    private val living = NodeId("living")

    private fun survey(room: String, endedAt: Long, means: Map<NodeId, Double>) = RoomSurvey(
        room = room,
        startedAt = endedAt - 1_000L,
        endedAt = endedAt,
        source = Source.SIMULATED,
        stats = means.map { (nodeId, mean) -> NodeSurveyStat(nodeId, mean, 1.0, 10) },
    )

    @Test
    fun `only the newest survey of a room is used`() {
        val matrix = CoverageMatrix.of(
            listOf(
                survey("Kitchen", 1_000L, mapOf(kitchen to -90.0)),
                survey("Kitchen", 5_000L, mapOf(kitchen to -55.0)),
            )
        )
        assertEquals(-55.0, matrix.cell("Kitchen", kitchen)!!.mean)
    }

    @Test
    fun `a node absent from a survey gets an empty cell`() {
        val matrix = CoverageMatrix.of(
            listOf(survey("Kitchen", 1_000L, mapOf(kitchen to -55.0))),
            nodeOrder = listOf(kitchen, hall),
        )
        assertNull(matrix.cell("Kitchen", hall)!!.mean)
    }

    @Test
    fun `the strongest cell in each row is marked`() {
        val matrix = CoverageMatrix.of(
            listOf(survey("Hallway", 1_000L, mapOf(hall to -66.0, living to -70.0)))
        )
        assertTrue(matrix.cell("Hallway", hall)!!.isStrongestInRoom)
        assertFalse(matrix.cell("Hallway", living)!!.isStrongestInRoom)
    }

    @Test
    fun `the column order given is respected and unknown nodes are appended`() {
        val matrix = CoverageMatrix.of(
            listOf(survey("Hallway", 1_000L, mapOf(living to -70.0, hall to -66.0))),
            nodeOrder = listOf(kitchen, hall),
        )
        assertEquals(listOf(kitchen, hall, living), matrix.nodes)
    }

    @Test
    fun `rooms are listed alphabetically`() {
        val matrix = CoverageMatrix.of(
            listOf(
                survey("Kitchen", 1_000L, mapOf(kitchen to -55.0)),
                survey("Bedroom", 1_000L, mapOf(kitchen to -75.0)),
            )
        )
        assertEquals(listOf("Bedroom", "Kitchen"), matrix.rooms)
    }

    @Test
    fun `problem rooms are the ones that are not clear`() {
        val matrix = CoverageMatrix.of(
            listOf(
                survey("Kitchen", 1_000L, mapOf(kitchen to -55.0, hall to -75.0)),
                survey("Hallway", 1_000L, mapOf(hall to -66.0, living to -68.0)),
                survey("Garage", 1_000L, mapOf(hall to -92.0)),
            )
        )
        assertEquals(
            listOf("Garage" to Verdict.BLIND, "Hallway" to Verdict.TIGHT),
            matrix.problemRooms().map { it.room to it.verdict },
        )
    }

    @Test
    fun `an empty matrix has nothing in it`() {
        val matrix = CoverageMatrix.of(emptyList())
        assertTrue(matrix.rooms.isEmpty())
        assertTrue(matrix.nodes.isEmpty())
        assertTrue(matrix.problemRooms().isEmpty())
    }
}

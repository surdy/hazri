package dev.surdy.hazri.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuggestionsTest {

    private val hall = NodeId("hall")
    private val living = NodeId("living")
    private val names = mapOf(hall to "Hall", living to "Living")

    @Test
    fun `a clear room produces no advice`() {
        val verdict = RoomVerdict.of("Kitchen", mapOf(hall to -55.0, living to -75.0))
        assertNull(Suggestions.forRoom(verdict, names))
    }

    @Test
    fun `a tight room names both nodes and both remedies`() {
        val verdict = RoomVerdict.of("Hallway", mapOf(hall to -65.0, living to -68.0))
        val suggestion = Suggestions.forRoom(verdict, names)!!

        assertEquals("Hallway", suggestion.room)
        assertEquals(Verdict.TIGHT, suggestion.verdict)
        assertTrue("Hall" in suggestion.text, suggestion.text)
        assertTrue("Living" in suggestion.text, suggestion.text)
        assertTrue("3 dB" in suggestion.text, suggestion.text)
        assertTrue("max_distance" in suggestion.text, suggestion.text)
        assertTrue("Move the Hall node" in suggestion.text, suggestion.text)
    }

    @Test
    fun `the proposed max_distance comes from the intruder's own model`() {
        // The Living node's model puts -80 dBm at 10^((-65+80)/27) = 3.6 m, so the advice
        // is to cap it at 4 m: just inside where it heard the phone from.
        val verdict = RoomVerdict.of("Hallway", mapOf(hall to -78.0, living to -80.0))
        val suggestion = Suggestions.forRoom(
            verdict = verdict,
            displayNames = names,
            models = mapOf(living to DistanceModel(refRssi = -65, absorption = 2.7)),
        )!!
        assertTrue("set Living max_distance to 4 m" in suggestion.text, suggestion.text)
    }

    @Test
    fun `a blind room with one faint node reports what it heard`() {
        val verdict = RoomVerdict.of("Garage", mapOf(hall to -89.0))
        val suggestion = Suggestions.forRoom(verdict, names)!!

        assertEquals(Verdict.BLIND, suggestion.verdict)
        assertTrue("Only the Hall node hears the phone here" in suggestion.text, suggestion.text)
        assertTrue("−89 dBm" in suggestion.text || "-89 dBm" in suggestion.text, suggestion.text)
        assertTrue("Add a node, or treat Garage as away." in suggestion.text, suggestion.text)
    }

    @Test
    fun `a blind room with no nodes at all says so`() {
        val verdict = RoomVerdict.of("Loft", emptyMap())
        val suggestion = Suggestions.forRoom(verdict, names)!!
        assertTrue("No node hears the phone in Loft." in suggestion.text, suggestion.text)
    }

    @Test
    fun `an unnamed node falls back to its id`() {
        val verdict = RoomVerdict.of("Garage", mapOf(NodeId("shed") to -90.0))
        val suggestion = Suggestions.forRoom(verdict, emptyMap())!!
        assertTrue("shed" in suggestion.text, suggestion.text)
    }

    @Test
    fun `a matrix produces advice only for the rooms that need it`() {
        val surveys = listOf(
            survey("Kitchen", mapOf(hall to -55.0, living to -75.0)),
            survey("Hallway", mapOf(hall to -65.0, living to -67.0)),
            survey("Garage", mapOf(hall to -90.0)),
        )
        val matrix = CoverageMatrix.of(surveys)
        val suggestions = Suggestions.forMatrix(matrix, names)

        assertEquals(listOf("Garage", "Hallway"), suggestions.map { it.room })
    }

    private fun survey(room: String, means: Map<NodeId, Double>) = RoomSurvey(
        room = room,
        startedAt = 0L,
        endedAt = 1_000L,
        source = Source.SIMULATED,
        stats = means.map { (nodeId, mean) -> NodeSurveyStat(nodeId, mean, 1.0, 10) },
    )
}

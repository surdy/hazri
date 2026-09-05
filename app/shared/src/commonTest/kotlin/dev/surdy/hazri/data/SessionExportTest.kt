package dev.surdy.hazri.data

import dev.surdy.hazri.domain.NodeId
import dev.surdy.hazri.domain.NodeSurveyStat
import dev.surdy.hazri.domain.RoomSurvey
import dev.surdy.hazri.domain.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionExportTest {

    private val kitchen = NodeId("kitchen")
    private val hall = NodeId("hall")

    private fun populated(): HazriRepository = HazriRepository(InMemoryFileStore()).apply {
        noteNode(kitchen)
        noteNode(hall)
        addSurvey(
            RoomSurvey(
                room = "Kitchen",
                startedAt = 1_000L,
                endedAt = 2_000L,
                source = Source.SIMULATED,
                stats = listOf(
                    NodeSurveyStat(kitchen, -55.123, 1.8, 118),
                    NodeSurveyStat(hall, -77.0, 2.6, 110),
                ),
            )
        )
    }

    @Test
    fun `the csv has a header and one row per node`() {
        val lines = SessionExport.toCsv(populated()).trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.first().startsWith("room,node,node_id,mean_rssi"))
        assertTrue(lines[1].startsWith("Kitchen,Kitchen,kitchen,-55.12,1.8,118,SIMULATED"))
    }

    @Test
    fun `every row carries the room's verdict and margin`() {
        val rows = SessionExport.toCsv(populated()).trim().lines().drop(1)
        assertTrue(rows.all { it.endsWith("CLEAR,21.88") }, rows.toString())
    }

    @Test
    fun `a room name containing a comma is quoted`() {
        val repository = HazriRepository(InMemoryFileStore()).apply {
            addSurvey(
                RoomSurvey(
                    room = "Kitchen, back",
                    startedAt = 0L,
                    endedAt = 1L,
                    source = Source.DIRECT,
                    stats = listOf(NodeSurveyStat(kitchen, -55.0, 1.0, 5)),
                )
            )
        }
        assertTrue(SessionExport.toCsv(repository).contains("\"Kitchen, back\""))
    }

    @Test
    fun `an empty session still produces a header`() {
        val csv = SessionExport.toCsv(HazriRepository(InMemoryFileStore()))
        assertEquals(1, csv.trim().lines().size)
    }

    @Test
    fun `the json carries settings nodes rooms and surveys`() {
        val json = SessionExport.toJson(populated(), exportedAt = 1_700L)
        assertTrue("\"exportedAt\": 1700" in json, json.take(200))
        assertTrue("\"room\": \"Kitchen\"" in json)
        assertTrue("\"sourceKind\"" in json)
        assertTrue("\"nodes\"" in json)
    }
}

package dev.surdy.hazri.data

import dev.surdy.hazri.domain.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoSeedTest {

    @Test
    fun `an empty install is seeded with the whole virtual house`() {
        val repository = HazriRepository(InMemoryFileStore())
        assertTrue(DemoSeed.seedIfEmpty(repository, now = 1_000_000L))

        assertEquals(6, repository.rooms.value.size)
        assertEquals(5, repository.nodes.value.size)
        assertEquals(6, repository.surveys.value.size)
        assertFalse(repository.isEmpty())
    }

    @Test
    fun `the seeded coverage shows all three verdicts`() {
        val repository = HazriRepository(InMemoryFileStore())
        DemoSeed.seedIfEmpty(repository, now = 1_000_000L)

        val verdicts = repository.coverage().verdicts
        assertEquals(Verdict.CLEAR, verdicts["Kitchen"]!!.verdict)
        assertEquals(Verdict.TIGHT, verdicts["Hallway"]!!.verdict)
        assertEquals(Verdict.BLIND, verdicts["Garage"]!!.verdict)
    }

    @Test
    fun `an install with anything in it is left alone`() {
        val repository = HazriRepository(InMemoryFileStore())
        repository.addRoom("Attic")
        assertFalse(DemoSeed.seedIfEmpty(repository, now = 1_000_000L))
        assertEquals(listOf("Attic"), repository.rooms.value)
    }

    @Test
    fun `seeded surveys are ordered in the past`() {
        val repository = HazriRepository(InMemoryFileStore())
        DemoSeed.seedIfEmpty(repository, now = 1_000_000L)
        assertTrue(repository.surveys.value.all { it.endedAt < 1_000_000L })
        assertTrue(repository.latestSurveys().first().endedAt > repository.latestSurveys().last().endedAt)
    }
}

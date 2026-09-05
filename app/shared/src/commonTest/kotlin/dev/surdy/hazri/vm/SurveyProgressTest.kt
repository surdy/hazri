package dev.surdy.hazri.vm

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two strings the survey notification is made of.
 *
 * They live in commonMain because they are arithmetic and grammar, not platform: the
 * Android side of the service builds a notification out of them and holds no formatting
 * of its own.
 */
class SurveyProgressTest {

    private fun progress(elapsedMillis: Long, sampleCount: Int = 0) =
        SurveyProgress(room = "Kitchen", elapsedMillis = elapsedMillis, sampleCount = sampleCount)

    @Test
    fun `seconds are padded and minutes are not`() {
        assertEquals("0:00", progress(0).elapsedLabel)
        assertEquals("0:07", progress(7_400).elapsedLabel)
        assertEquals("1:00", progress(60_000).elapsedLabel)
        assertEquals("9:59", progress(599_999).elapsedLabel)
        assertEquals("23:04", progress(1_384_000).elapsedLabel)
    }

    @Test
    fun `the hour field appears only once there is an hour`() {
        assertEquals("59:59", progress(3_599_000).elapsedLabel)
        assertEquals("1:00:00", progress(3_600_000).elapsedLabel)
        assertEquals("2:05:09", progress(7_509_000).elapsedLabel)
    }

    @Test
    fun `a clock that went backwards reads as zero`() {
        assertEquals("0:00", progress(-5_000).elapsedLabel)
    }

    @Test
    fun `the summary counts samples, and one sample is singular`() {
        assertEquals("0:00 · 0 samples", progress(0).summary)
        assertEquals("0:01 · 1 sample", progress(1_000, sampleCount = 1).summary)
        assertEquals("23:14 · 914 samples", progress(1_394_000, sampleCount = 914).summary)
    }
}

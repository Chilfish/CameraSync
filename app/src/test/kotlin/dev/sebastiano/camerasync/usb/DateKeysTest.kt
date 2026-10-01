package dev.sebastiano.camerasync.usb

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** BY_DATE bucketing keys (R25) — pure, time-zone aware formatting. */
class DateKeysTest {

    private val zone = ZoneId.systemDefault()

    private fun millisAt(day: LocalDate, hour: Int): Long =
        day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    @Test
    fun `dateKey formats epoch millis as local yyyy-MM-dd`() {
        assertEquals("2026-10-01", dateKey(millisAt(LocalDate.of(2026, 10, 1), 12)))
    }

    @Test
    fun `photos on the same local day share one key`() {
        val day = LocalDate.of(2026, 10, 1)
        assertEquals(dateKey(millisAt(day, 9)), dateKey(millisAt(day, 23)))
    }

    @Test
    fun `different local days get different keys, newest first when sorted descending`() {
        val older = dateKey(millisAt(LocalDate.of(2026, 9, 30), 12))
        val newer = dateKey(millisAt(LocalDate.of(2026, 10, 1), 12))
        assertEquals(listOf(newer, older), listOf(older, newer).sortedDescending())
    }
}

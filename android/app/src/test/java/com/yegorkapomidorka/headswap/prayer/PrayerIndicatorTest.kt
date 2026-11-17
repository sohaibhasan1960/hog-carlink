package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrayerIndicatorTest {
    private val M = 60_000L
    // фаджр 5:00, восход 6:30, зухр 12:00, аср 15:00, магриб 17:00, иша 18:30 (минуты от полуночи)
    private fun day(base: Long) = DayTimes(LocalDate.of(2026, 11, 20),
        longArrayOf(base + 300 * M, base + 390 * M, base + 720 * M, base + 900 * M, base + 1020 * M, base + 1110 * M))
    private val today = day(0)
    private val tomorrow = day(1440 * M)
    private val iqama = { _: Prayer -> 10 }

    @Test
    fun beforeAsrShowsNextAdhanAndTimeLeft() {
        val s = PrayerIndicator.state(860 * M, today, tomorrow, iqama, false, false)!!
        assertEquals(PrayerIndicator.Phase.NEXT, s.phase)
        assertEquals(Prayer.ASR, s.prayer)
        assertEquals(40 * M, s.remainMs)
    }

    @Test
    fun lastMinuteBeforeAdhanIsSoon() {
        val s = PrayerIndicator.state(900 * M - 30_000, today, tomorrow, iqama, false, false)!!
        assertEquals(PrayerIndicator.Phase.SOON, s.phase)
    }

    @Test
    fun betweenAdhanAndIqamaCountsToIqama() {
        val s = PrayerIndicator.state(903 * M, today, tomorrow, iqama, false, false)!!
        assertEquals(PrayerIndicator.Phase.IQAMA, s.phase)
        assertEquals(Prayer.ASR, s.prayer)
        assertEquals(7 * M, s.remainMs)
        assertEquals(10 * M, s.spanMs)
    }

    @Test
    fun afterIshaIqamaNextIsTomorrowFajr() {
        val s = PrayerIndicator.state(1130 * M, today, tomorrow, iqama, false, false)!!
        assertEquals(Prayer.FAJR, s.prayer)
        assertEquals(tomorrow[Prayer.FAJR], s.adhanAt)
    }

    @Test
    fun fridayDhuhrIsJumuahWithoutIqamaCountdown() {
        val before = PrayerIndicator.state(700 * M, today, tomorrow, iqama, true, false)!!
        assertTrue(before.friday)
        assertEquals(Prayer.DHUHR, before.prayer)
        val after = PrayerIndicator.state(725 * M, today, tomorrow, iqama, true, false)!!
        assertFalse("в пятницу после азана зухра - уже аср, без отсчёта икамы", after.phase == PrayerIndicator.Phase.IQAMA)
        assertEquals(Prayer.ASR, after.prayer)
    }

    @Test
    fun clockFormats() {
        assertEquals("01:12:45", PrayerIndicator.clock((72 * 60 + 45) * 1000L))
        assertEquals("08:42", PrayerIndicator.clock((8 * 60 + 42) * 1000L))
        assertEquals("1 س 12 د", PrayerIndicator.minutes(72 * M, "س", "د"))
    }

    @Test
    fun noLaterPrayerMeansNull() {
        assertNull(PrayerIndicator.state(5000 * M, today, tomorrow, iqama, false, false))
    }
}

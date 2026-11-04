package com.yegorkapomidorka.headswap.prayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Эталон - api.aladhan.com (2026-11-04), допуск ±2 минуты. */
class PrayerCalcTest {
    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    private fun check(date: String, lat: Double, lon: Double, tz: String, m: CalcMethod, madhab: Madhab, expected: List<String>) {
        val zone = ZoneId.of(tz)
        val d = PrayerCalc.day(LocalDate.parse(date), lat, lon, m, madhab)!!
        for (p in Prayer.values()) {
            val got = Instant.ofEpochMilli(d[p]).atZone(zone).toLocalTime()
            val want = java.time.LocalTime.parse(expected[p.ordinal])
            val diff = Math.abs(java.time.Duration.between(want, got).toMinutes())
            assertTrue("$date $tz $m $p: ${hm.format(got)} vs ${expected[p.ordinal]}", diff <= 2)
        }
    }

    @Test fun ammanWinter() = check("2026-11-04", 31.9539, 35.9106, "Asia/Amman", CalcMethod.JORDAN, Madhab.SHAFI,
        listOf("05:32", "06:55", "12:20", "15:23", "17:49", "19:07"))

    @Test fun ammanSummer() = check("2026-06-21", 31.9539, 35.9106, "Asia/Amman", CalcMethod.JORDAN, Madhab.SHAFI,
        listOf("03:51", "05:31", "12:38", "16:18", "19:50", "21:26"))

    @Test fun riyadh() = check("2026-11-04", 24.7136, 46.6753, "Asia/Riyadh", CalcMethod.MAKKAH, Madhab.SHAFI,
        listOf("04:42", "06:02", "11:37", "14:49", "17:11", "18:41"))

    @Test fun cairo() = check("2026-03-15", 30.0444, 31.2357, "Africa/Cairo", CalcMethod.EGYPT, Madhab.SHAFI,
        listOf("04:39", "06:05", "12:04", "15:28", "18:03", "19:20"))

    @Test fun karachiHanafi() = check("2026-11-04", 24.8607, 67.0011, "Asia/Karachi", CalcMethod.KARACHI, Madhab.HANAFI,
        listOf("05:23", "06:41", "12:16", "16:14", "17:50", "19:08"))

    @Test fun istanbul() = check("2027-01-01", 41.0082, 28.9784, "Europe/Istanbul", CalcMethod.TURKEY, Madhab.SHAFI,
        listOf("06:50", "08:22", "13:12", "15:32", "17:53", "19:20"))

    @Test fun dubai() = check("2026-11-04", 25.2048, 55.2708, "Asia/Dubai", CalcMethod.DUBAI, Madhab.SHAFI,
        listOf("05:10", "06:28", "12:05", "15:14", "17:39", "18:55"))

    @Test fun adjustmentAndCountry() {
        val zone = ZoneId.of("Asia/Amman")
        val base = PrayerCalc.day(LocalDate.parse("2026-11-04"), 31.95, 35.91, CalcMethod.JORDAN)!!
        val adj = PrayerCalc.day(LocalDate.parse("2026-11-04"), 31.95, 35.91, CalcMethod.JORDAN, adjust = intArrayOf(0, 0, 3, 0, 0, 0))!!
        assertEquals(3 * 60_000L, adj[Prayer.DHUHR] - base[Prayer.DHUHR])
        assertEquals(CalcMethod.JORDAN, CalcMethod.forCountry("jo"))
        assertEquals(CalcMethod.MWL, CalcMethod.forCountry(null))
        assertEquals(Madhab.HANAFI, Madhab.forCountry("PK"))
    }

    /** День перехода на летнее время (Каир, последняя пятница апреля 2026 = 24.04, 00:00 -> 01:00): Фаджр не сдвинут на час. */
    @Test fun dstDayCairo() {
        val zone = ZoneId.of("Africa/Cairo")
        val before = PrayerCalc.day(LocalDate.parse("2026-04-23"), 30.0444, 31.2357, CalcMethod.EGYPT)!!
        val dst = PrayerCalc.day(LocalDate.parse("2026-04-24"), 30.0444, 31.2357, CalcMethod.EGYPT)!!
        val f0 = Instant.ofEpochMilli(before[Prayer.FAJR]).atZone(zone).toLocalTime()
        val f1 = Instant.ofEpochMilli(dst[Prayer.FAJR]).atZone(zone).toLocalTime()
        // за сутки Фаджр сдвигается на минуту-две, а часы переведены на +1: местное время больше на ~59 мин
        val diff = java.time.Duration.between(f0, f1).toMinutes()
        assertTrue("Фаджр $f0 -> $f1", diff in 55..62)
        // между днями (в UTC) - почти ровно сутки
        assertTrue(Math.abs(dst[Prayer.FAJR] - before[Prayer.FAJR] - 86_400_000L) < 5 * 60_000L)
    }

    @Test fun polarNightHasNoTimes() {
        assertEquals(null, PrayerCalc.day(LocalDate.parse("2026-12-21"), 78.22, 15.65, CalcMethod.MWL))
    }

    @Test fun highLatitudeHasAllTimes() {
        val zone = ZoneId.of("Europe/Oslo")
        val d = PrayerCalc.day(LocalDate.parse("2026-06-21"), 59.91, 10.75, CalcMethod.MWL)!!
        for (i in 1 until 6) assertTrue(d.millis[i] > d.millis[i - 1])
    }
}

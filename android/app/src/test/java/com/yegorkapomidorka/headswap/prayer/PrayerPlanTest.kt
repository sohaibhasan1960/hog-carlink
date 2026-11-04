package com.yegorkapomidorka.headswap.prayer

import com.yegorkapomidorka.headswap.prayer.PrayerPlan.Kind
import com.yegorkapomidorka.headswap.prayer.PrayerPlan.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrayerPlanTest {
    private val h = 3_600_000L

    // упрощённый день: Фаджр 5:00, восход 6:30, Зухр 12:00, Аср 15:00, Магриб 17:30, Иша 19:00
    private fun day(base: Long) = DayTimes(LocalDate.of(2026, 11, 4).plusDays(base / (24 * h)), longArrayOf(5 * h, 6 * h + 30 * MIN, 12 * h, 15 * h, 17 * h + 30 * MIN, 19 * h).map { it + base }.toLongArray())
    private val today = day(0)
    private val tomorrow = day(24 * h)

    private fun t(e: PrayerPlan.Event?) = e?.let { Triple(it.kind, it.at, it.prayer) }

    @Test
    fun nextAdhanIqamaAdhkar() {
        val cfg = PrayerPlan.Config()
        val all = PrayerPlan.events(today, cfg)
        // всё до момента [upTo] уже сработало
        fun fired(upTo: Long) = all.filter { it.at <= upTo }.map { it.key }.toSet()
        assertEquals(Triple(Kind.ADHAN, 5 * h, Prayer.FAJR), t(PrayerPlan.next(0, today, tomorrow, cfg)))
        assertEquals(Triple(Kind.IQAMA, 5 * h + 25 * MIN, Prayer.FAJR), t(PrayerPlan.next(5 * h, today, tomorrow, cfg, fired(5 * h))))
        assertEquals(Triple(Kind.MORNING, 5 * h + 30 * MIN, null), t(PrayerPlan.next(5 * h + 25 * MIN, today, tomorrow, cfg, fired(5 * h + 25 * MIN))))
        assertEquals(Triple(Kind.EVENING, 15 * h + 30 * MIN, null), t(PrayerPlan.next(15 * h + 15 * MIN, today, tomorrow, cfg, fired(15 * h + 15 * MIN))))
        // после Иша и икамы - Фаджр завтра
        assertEquals(Triple(Kind.ADHAN, 29 * h, Prayer.FAJR), t(PrayerPlan.next(20 * h, today, tomorrow, cfg, fired(20 * h))))
    }

    @Test
    fun firedEventsDoNotRepeatAndSameMinuteBothFire() {
        // утренние азкары в ту же минуту, что икама Фаджра
        val cfg = PrayerPlan.Config(morningAfterFajr = 25)
        val iqama = PrayerPlan.next(5 * h + 20 * MIN, today, tomorrow, cfg)!!
        assertEquals(Kind.IQAMA, iqama.kind)
        val morning = PrayerPlan.next(5 * h + 25 * MIN + 1000, today, tomorrow, cfg, setOf(iqama.key))!!
        assertEquals(Triple(Kind.MORNING, 5 * h + 25 * MIN, null), t(morning))
        // азан Зухра сработал; место сменилось, Зухр стал на 2 минуты позже - не повторяется
        val dhuhr = PrayerPlan.next(11 * h, today, tomorrow, cfg)!!
        val moved = DayTimes(today.date, today.millis.copyOf().also { it[Prayer.DHUHR.ordinal] += 2 * MIN })
        val after = PrayerPlan.next(12 * h + MIN, moved, tomorrow, cfg, setOf(dhuhr.key))!!
        assertEquals(Triple(Kind.IQAMA, 12 * h + 17 * MIN, Prayer.DHUHR), t(after))
    }

    @Test
    fun disabledPieces() {
        val cfg = PrayerPlan.Config(adhan = setOf(Prayer.MAGHRIB), iqama = false, morning = false, evening = false)
        assertEquals(Triple(Kind.ADHAN, 17 * h + 30 * MIN, Prayer.MAGHRIB), t(PrayerPlan.next(0, today, tomorrow, cfg)))
        assertNull(PrayerPlan.next(0, today, tomorrow, PrayerPlan.Config(adhan = emptySet(), iqama = false, morning = false, evening = false)))
    }

    @Test
    fun dhikrGrid() {
        val cfg = PrayerPlan.Config(dhikrEvery = 60)
        // ночью - первое после восхода: 7:30
        assertEquals(7 * h + 30 * MIN, PrayerPlan.nextDhikr(2 * h, today, tomorrow, cfg)!!.at)
        assertEquals(8 * h + 30 * MIN, PrayerPlan.nextDhikr(7 * h + 30 * MIN, today, tomorrow, cfg)!!.at)
        // 11:30 и 12:30 далеко от Зухра 12:00 (30 мин) - разрешены
        assertEquals(11 * h + 30 * MIN, PrayerPlan.nextDhikr(11 * h, today, tomorrow, cfg)!!.at)
        // после Иша - завтра
        assertEquals(31 * h + 30 * MIN, PrayerPlan.nextDhikr(19 * h, today, tomorrow, cfg)!!.at)
        assertNull(PrayerPlan.nextDhikr(0, today, tomorrow, PrayerPlan.Config(dhikrEvery = 0)))
        // шаг 30 мин: 17:30 совпадает с Магрибом - пропуск до 18:00
        val c30 = PrayerPlan.Config(dhikrEvery = 30)
        assertEquals(18 * h, PrayerPlan.nextDhikr(17 * h + 10 * MIN, today, tomorrow, c30)!!.at)
    }

    @Test
    fun stale() {
        assertFalse(PrayerPlan.stale(100 * MIN, 105 * MIN))
        assertTrue(PrayerPlan.stale(100 * MIN, 111 * MIN))
    }
}

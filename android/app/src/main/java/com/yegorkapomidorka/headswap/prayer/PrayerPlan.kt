package com.yegorkapomidorka.headswap.prayer

/**
 * HOG CarLink: что и когда звучит. Без Android: ближайшее событие считается из времён дня и настроек, тест проверяет.
 * Будильник один на ближайшее событие намаза ([Kind.ADHAN], [Kind.IQAMA], [Kind.MORNING], [Kind.EVENING])
 * и отдельный на напоминание зикра ([Kind.DHIKR]); после срабатывания планируется следующее.
 */
object PrayerPlan {
    enum class Kind { ADHAN, IQAMA, MORNING, EVENING, DHIKR }

    /** Пять намазов (восход - не намаз, азана нет). */
    val FARD = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

    data class Event(val kind: Kind, val at: Long, val prayer: Prayer? = null, val date: java.time.LocalDate? = null) {
        /** Что это за событие независимо от точного времени: дата + вид + намаз. Сработавшие не повторяются. */
        val key: String get() = "$date:$kind:${prayer ?: ""}"
    }

    /** Настройки, которые нужны для расписания (из PrayerPrefs). */
    data class Config(
        val adhan: Set<Prayer> = FARD.toSet(),
        val iqama: Boolean = true,
        /** Минут от азана до икамы по каждому намазу. */
        val iqamaMinutes: Map<Prayer, Int> = DEFAULT_IQAMA,
        val morning: Boolean = true,
        /** Утренние азкары: минут после Фаджра. */
        val morningAfterFajr: Int = 30,
        val evening: Boolean = true,
        /** Вечерние азкары: минут после Асра. */
        val eveningAfterAsr: Int = 30,
        /** Напоминание зикра каждые N минут между восходом и Иша; 0 - выключено. */
        val dhikrEvery: Int = 0,
    )

    val DEFAULT_IQAMA = mapOf(Prayer.FAJR to 25, Prayer.DHUHR to 15, Prayer.ASR to 15, Prayer.MAGHRIB to 7, Prayer.ISHA to 15)

    /** События одного дня по порядку (без зикра). */
    fun events(day: DayTimes, cfg: Config): List<Event> {
        val out = ArrayList<Event>()
        for (p in FARD) {
            if (p in cfg.adhan) out.add(Event(Kind.ADHAN, day[p], p, day.date))
            if (cfg.iqama) out.add(Event(Kind.IQAMA, day[p] + (cfg.iqamaMinutes[p] ?: 0) * MIN, p, day.date))
        }
        if (cfg.morning) out.add(Event(Kind.MORNING, day[Prayer.FAJR] + cfg.morningAfterFajr * MIN, null, day.date))
        if (cfg.evening) out.add(Event(Kind.EVENING, day[Prayer.ASR] + cfg.eveningAfterAsr * MIN, null, day.date))
        out.sortWith(compareBy({ it.at }, { it.kind.ordinal }))
        return out
    }

    /**
     * Ближайшее ещё не сработавшее событие: позже [now] или в ту же минуту, что только что сработавшее ([fired] - ключи
     * сработавших). Время могло сдвинуться (новое место) - сработавший азан не повторится; два события в одну минуту
     * сработают оба.
     */
    fun next(now: Long, today: DayTimes, tomorrow: DayTimes, cfg: Config, fired: Set<String> = emptySet()): Event? =
        (events(today, cfg) + events(tomorrow, cfg)).firstOrNull { it.key !in fired && it.at > now - SAME_MINUTE }

    /** Окно «то же событие только что»: будильник в прошлом сработает сразу. */
    const val SAME_MINUTE = 2 * 60_000L

    /**
     * Ближайшее напоминание зикра: на сетке каждые [Config.dhikrEvery] минут от восхода, не ближе 10 минут к азану
     * и только до Иша. Ночью - следующий восход.
     */
    fun nextDhikr(now: Long, today: DayTimes, tomorrow: DayTimes, cfg: Config): Event? {
        val step = cfg.dhikrEvery * MIN
        if (step <= 0) return null
        for (d in listOf(today, tomorrow)) {
            val from = d[Prayer.SUNRISE] + step
            val to = d[Prayer.ISHA]
            var t = if (now < from) from else from + ((now - from) / step + 1) * step
            while (t < to) {
                val nearAdhan = FARD.any { p -> kotlin.math.abs(d[p] - t) < 10 * MIN }
                if (!nearAdhan) return Event(Kind.DHIKR, t)
                t += step
            }
        }
        return null
    }

    /** Будильник опоздал (телефон спал, Doze): старое событие не проигрываем. */
    fun stale(eventAt: Long, now: Long): Boolean = now - eventAt > 10 * MIN

    const val MIN = 60_000L
}

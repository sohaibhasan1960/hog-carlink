package com.yegorkapomidorka.headswap.prayer

/**
 * HOG CarLink: «следующий намаз / до икамы» - как индикатор в Hocom. Чистая логика (тест), подписи - в [Labels].
 *
 *  - [Phase.NEXT]  - следующий азан и сколько до него;
 *  - [Phase.SOON]  - до азана не больше [SOON_MS] («اقترب أذان العصر»);
 *  - [Phase.IQAMA] - между азаном и икамой: сколько до икамы.
 * В пятницу зухр - «الجمعة», и отсчёта икамы нет (время хутбы).
 */
object PrayerIndicator {
    enum class Phase { NEXT, SOON, IQAMA }

    data class State(
        val phase: Phase,
        val prayer: Prayer,
        val friday: Boolean,
        val adhanAt: Long,
        val iqamaAt: Long,
        /** До азана (NEXT, SOON) или до икамы (IQAMA), мс. */
        val remainMs: Long,
        /** Вся длина отрезка (для кольца отсчёта икамы), мс. */
        val spanMs: Long,
    )

    const val SOON_MS = 60_000L

    /**
     * [today] / [tomorrow] - времена дней; [iqamaMin] - минут от азана до икамы; [todayFriday] / [tomorrowFriday] -
     * пятница ли этот день. null - времён нет.
     */
    fun state(
        now: Long,
        today: DayTimes,
        tomorrow: DayTimes,
        iqamaMin: (Prayer) -> Int,
        todayFriday: Boolean,
        tomorrowFriday: Boolean,
    ): State? {
        for (p in PrayerPlan.FARD) {
            val jumuah = todayFriday && p == Prayer.DHUHR
            if (jumuah) continue
            val a = today[p]
            val i = a + iqamaMin(p) * PrayerPlan.MIN
            if (now in a until i) return State(Phase.IQAMA, p, false, a, i, i - now, i - a)
        }
        for ((day, fri) in listOf(today to todayFriday, tomorrow to tomorrowFriday)) {
            for (p in PrayerPlan.FARD) {
                val a = day[p]
                if (a <= now) continue
                val friday = fri && p == Prayer.DHUHR
                val i = a + (if (friday) 0 else iqamaMin(p)) * PrayerPlan.MIN
                val left = a - now
                return State(if (left <= SOON_MS) Phase.SOON else Phase.NEXT, p, friday, a, i, left, left)
            }
        }
        return null
    }

    /** «01:12:45» больше часа, иначе «12:45». */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** «1 س 12 د» / «12 د» для мест, которые обновляются раз в минуту (списки Android Auto). */
    fun minutes(ms: Long, hourShort: String, minShort: String): String {
        val m = (ms.coerceAtLeast(0) + 59_999) / 60_000
        return if (m >= 60) "${m / 60} $hourShort ${m % 60} $minShort" else "$m $minShort"
    }
}

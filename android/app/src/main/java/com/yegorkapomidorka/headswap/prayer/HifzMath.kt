package com.yegorkapomidorka.headswap.prayer

import com.yegorkapomidorka.headswap.quran.QuranPages

/**
 * HOG CarLink: чистая арифметика плана заучивания (без плеера и Android, под тесты). Вынесена из [PrayerPlayer]:
 * какой аят звучит по позиции в WAV страницы и какой отрезок WAV играет каждый элемент очереди.
 */
object HifzMath {
    /** Номер аята страницы (0..), который звучит в позиции [posMs] WAV: последний, чьё начало уже пройдено. */
    fun verseAt(startMs: LongArray, posMs: Long): Int {
        var k = 0
        while (k + 1 < startMs.size && startMs[k + 1] <= posMs) k++
        return k
    }

    /**
     * Индекс в очереди плана того, что звучит. [whole] пусто - элемент плеера и есть элемент очереди
     * ([mediaIndex]); иначе элемент плеера - повтор целой страницы, начинающийся с whole[mediaIndex],
     * и аят внутри - по [posMs] и началам аятов [pageStartMs] (null - WAV страницы неизвестен).
     */
    fun indexNow(queueSize: Int, whole: List<Int>, mediaIndex: Int, pageStartMs: LongArray?, posMs: Long): Int {
        if (queueSize <= 0) return 0
        if (whole.isEmpty()) return mediaIndex.coerceIn(0, queueSize - 1)
        val start = whole[mediaIndex.coerceIn(0, whole.size - 1)]
        val p = pageStartMs ?: return start
        return (start + verseAt(p, posMs)).coerceAtMost(queueSize - 1)
    }

    /**
     * Для каждого элемента очереди - номер отрезка (аята) в WAV его страницы. Очередь идёт по порядку
     * QuranPages.files; новая страница или новый повтор страницы - с нуля; повтор аята подряд
     * (одинаковые элементы) - тот же отрезок.
     */
    fun segments(queue: List<QuranPages.Item>): IntArray {
        var prev: QuranPages.Item? = null
        var cursor = 0
        return IntArray(queue.size) { i ->
            val it = queue[i]
            val pv = prev
            cursor = when {
                pv == null || pv.page != it.page || pv.rep != it.rep -> 0
                pv == it -> cursor
                else -> cursor + 1
            }
            prev = it
            cursor
        }
    }
}

package com.yegorkapomidorka.headswap.quran

/**
 * HOG CarLink: чистая математика «мусхаф идёт за чтецом» для целой суры (без Android, под тесты).
 *
 * Сура - один файл чтеца, поэтому где мы в ней, известно только по доле прослушанного. Страницы суры делят время
 * пропорционально длине её текста на каждой странице: [ends] - доля текста к концу каждой страницы, [slot] -
 * какая страница звучит и насколько она пройдена.
 */
object MushafFollow {
    /** Доли (0..1] к концу каждой страницы по весам (длина текста суры на странице, минимум 1). */
    fun ends(weights: IntArray): DoubleArray {
        val w = IntArray(weights.size) { weights[it].coerceAtLeast(1) }
        val total = w.sum().toDouble()
        var acc = 0.0
        return DoubleArray(w.size) { i -> acc += w[i]; acc / total }
    }

    /** Звучащая страница (индекс в [ends]) и доля её пройденности 0..1; null - длительность ещё неизвестна. */
    fun slot(ends: DoubleArray, posMs: Long, durMs: Long): Pair<Int, Double>? {
        if (durMs <= 0 || ends.isEmpty()) return null
        val f = (posMs.toDouble() / durMs).coerceIn(0.0, 1.0)
        var i = 0
        while (i < ends.size - 1 && f > ends[i]) i++
        val start = if (i == 0) 0.0 else ends[i - 1]
        val inPage = ((f - start) / (ends[i] - start).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        return i to inPage
    }
}

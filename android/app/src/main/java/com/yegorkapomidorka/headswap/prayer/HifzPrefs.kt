package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import com.yegorkapomidorka.headswap.quran.QuranPages

/**
 * HOG CarLink: план заучивания по страницам мусхафа. Текущая страница (1..604), сколько раз повторять каждую,
 * сколько страниц за одно прослушивание и чтец «по аятам». Последняя дослушанная страница - для «продолжить».
 */
class HifzPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("hifz", Context.MODE_PRIVATE)

    var page: Int
        get() = sp.getInt("page", 1).coerceIn(1, QuranPages.PAGES)
        set(v) = sp.edit().putInt("page", v.coerceIn(1, QuranPages.PAGES)).apply()

    /** Повторов каждой страницы; 0 - без конца. */
    var repeat: Int
        get() = sp.getInt("repeat", 3).let { r -> if (r in QuranPages.REPEATS) r else 3 }
        set(v) = sp.edit().putInt("repeat", v).apply()

    /** Страниц за одно прослушивание (1..5). */
    var pagesPerSession: Int
        get() = sp.getInt("pages", 1).coerceIn(1, MAX_PAGES)
        set(v) = sp.edit().putInt("pages", v.coerceIn(1, MAX_PAGES)).apply()

    var voice: QuranPages.Voice
        get() = sp.getString("voice", null)?.let { n -> QuranPages.Voice.values().firstOrNull { it.name == n } }
            ?: QuranPages.Voice.HUSARY_MUALLIM
        set(v) = sp.edit().putString("voice", v.name).apply()

    /** Дослушанных до конца страниц всего (прогресс плана). */
    var done: Int
        get() = sp.getInt("done", 0)
        set(v) = sp.edit().putInt("done", v.coerceAtLeast(0)).apply()

    companion object {
        const val MAX_PAGES = 5
    }
}

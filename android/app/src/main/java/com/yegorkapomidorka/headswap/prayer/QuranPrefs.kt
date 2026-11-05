package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import com.yegorkapomidorka.headswap.quran.Quran

/** HOG CarLink: чтец, последняя сура и позиция («Продолжить»), непрерывное чтение. */
class QuranPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("quran", Context.MODE_PRIVATE)

    var reciter: Quran.Reciter
        get() = sp.getString("reciter", null)?.let { n -> Quran.Reciter.values().firstOrNull { it.name == n } } ?: Quran.Reciter.ALAFASY
        set(v) = sp.edit().putString("reciter", v.name).apply()

    var lastSurah: Int
        get() = sp.getInt("last_surah", 0)
        set(v) {
            if (v != lastSurah) sp.edit().putInt("last_surah", v).putLong("last_pos", 0).apply()
        }

    var lastPosMs: Long
        get() = sp.getLong("last_pos", 0)
        set(v) = sp.edit().putLong("last_pos", v).apply()

    /** После суры - следующая. */
    var continuous: Boolean
        get() = sp.getBoolean("continuous", true)
        set(v) = sp.edit().putBoolean("continuous", v).apply()

    /** Скорость чтения: 1, 1.5 или 2 ([Quran.SPEEDS]); чужое значение - ближайшее допустимое. */
    var speed: Float
        get() = Quran.snapSpeed(sp.getFloat("speed", 1f))
        set(v) = sp.edit().putFloat("speed", Quran.snapSpeed(v)).apply()
}

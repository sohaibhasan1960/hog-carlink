package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** HOG CarLink: настройки намаза, азана, икамы и азкаров. */
class PrayerPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    /** Главный выключатель: без него будильники не ставятся. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    val hasLocation: Boolean get() = sp.contains("lat")
    val lat: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("lat", 0))
    val lon: Double get() = java.lang.Double.longBitsToDouble(sp.getLong("lon", 0))
    val place: String get() = sp.getString("place", "") ?: ""
    val country: String? get() = sp.getString("country", null)

    fun setLocation(lat: Double, lon: Double, place: String, country: String?) {
        sp.edit().putLong("lat", java.lang.Double.doubleToRawLongBits(lat)).putLong("lon", java.lang.Double.doubleToRawLongBits(lon))
            .putString("place", place).putString("country", country).apply()
    }

    /** null - по стране. */
    var methodOverride: CalcMethod?
        get() = sp.getString("method", null)?.let { n -> CalcMethod.values().firstOrNull { it.name == n } }
        set(v) = sp.edit().putString("method", v?.name).apply()

    var madhabOverride: Madhab?
        get() = sp.getString("madhab", null)?.let { n -> Madhab.values().firstOrNull { it.name == n } }
        set(v) = sp.edit().putString("madhab", v?.name).apply()

    val method: CalcMethod get() = methodOverride ?: CalcMethod.forCountry(country)
    val madhab: Madhab get() = madhabOverride ?: Madhab.forCountry(country)

    /** Поправка в минутах к расчёту (по [Prayer]), чтобы совпасть с мечетью. */
    fun adjust(p: Prayer): Int = sp.getInt("adj_${p.name}", 0)
    fun setAdjust(p: Prayer, v: Int) = sp.edit().putInt("adj_${p.name}", v.coerceIn(-30, 30)).apply()

    fun adhanOn(p: Prayer): Boolean = sp.getBoolean("adhan_${p.name}", true)
    fun setAdhanOn(p: Prayer, v: Boolean) = sp.edit().putBoolean("adhan_${p.name}", v).apply()

    var iqamaOn: Boolean
        get() = sp.getBoolean("iqama", true)
        set(v) = sp.edit().putBoolean("iqama", v).apply()

    fun iqamaMinutes(p: Prayer): Int = sp.getInt("iqama_${p.name}", PrayerPlan.DEFAULT_IQAMA[p] ?: 15)
    fun setIqamaMinutes(p: Prayer, v: Int) = sp.edit().putInt("iqama_${p.name}", v.coerceIn(1, 60)).apply()

    /** Звук азана: true - всегда, false - только когда телефон подключён к машине (иначе уведомление). */
    var soundAlways: Boolean
        get() = sp.getBoolean("sound_always", false)
        set(v) = sp.edit().putBoolean("sound_always", v).apply()

    /** Свой файл азана (content://, постоянное разрешение); пусто - встроенная запись. */
    var adhanUri: String
        get() = sp.getString("adhan_uri", "") ?: ""
        set(v) = sp.edit().putString("adhan_uri", v).apply()

    var adhanName: String
        get() = sp.getString("adhan_name", "") ?: ""
        set(v) = sp.edit().putString("adhan_name", v).apply()

    var morningOn: Boolean
        get() = sp.getBoolean("morning", true)
        set(v) = sp.edit().putBoolean("morning", v).apply()

    var eveningOn: Boolean
        get() = sp.getBoolean("evening", true)
        set(v) = sp.edit().putBoolean("evening", v).apply()

    var morningAfterFajr: Int
        get() = sp.getInt("morning_after", 30)
        set(v) = sp.edit().putInt("morning_after", v.coerceIn(0, 180)).apply()

    var eveningAfterAsr: Int
        get() = sp.getInt("evening_after", 30)
        set(v) = sp.edit().putInt("evening_after", v.coerceIn(0, 180)).apply()

    /** Азкары утра/вечера: сами начинают звучать в машине (иначе - уведомление с кнопкой «Слушать»). */
    var adhkarAutoPlay: Boolean
        get() = sp.getBoolean("adhkar_autoplay", true)
        set(v) = sp.edit().putBoolean("adhkar_autoplay", v).apply()

    /** Полное число повторов (100 раз); иначе длинные повторы читаются один раз с указанием числа. */
    var fullRepeat: Boolean
        get() = sp.getBoolean("full_repeat", false)
        set(v) = sp.edit().putBoolean("full_repeat", v).apply()

    /** Голос синтеза речи (имя Voice); пусто - по умолчанию. */
    var ttsVoice: String
        get() = sp.getString("tts_voice", "") ?: ""
        set(v) = sp.edit().putString("tts_voice", v).apply()

    /** Своя запись азкаров утра/вечера (content://, постоянное разрешение); пусто - синтез речи. */
    fun adhkarUri(morning: Boolean): String = sp.getString(if (morning) "morning_uri" else "evening_uri", "") ?: ""
    fun adhkarName(morning: Boolean): String = sp.getString(if (morning) "morning_name" else "evening_name", "") ?: ""
    fun setAdhkarFile(morning: Boolean, uri: String, name: String) =
        sp.edit().putString(if (morning) "morning_uri" else "evening_uri", uri).putString(if (morning) "morning_name" else "evening_name", name).apply()

    /** Напоминание зикра каждые N минут; 0 - выключено. */
    var dhikrEvery: Int
        get() = sp.getInt("dhikr_every", 60)
        set(v) = sp.edit().putInt("dhikr_every", v).apply()

    /** Порядковый номер следующего короткого зикра (по кругу). */
    var dhikrIndex: Int
        get() = sp.getInt("dhikr_index", 0)
        set(v) = sp.edit().putInt("dhikr_index", v).apply()

    /** Ключи сработавших событий (PrayerPlan.Event.key) за последние дни. */
    val fired: Set<String> get() = sp.getStringSet("fired", emptySet()) ?: emptySet()

    fun markFired(key: String, today: LocalDate) {
        val keep = fired.filter { k -> k.substringBefore(':').let { d -> d >= today.minusDays(1).toString() } }.toMutableSet()
        keep.add(key)
        sp.edit().putStringSet("fired", keep).apply()
    }

    fun config() = PrayerPlan.Config(
        adhan = PrayerPlan.FARD.filter { adhanOn(it) }.toSet(),
        iqama = iqamaOn,
        iqamaMinutes = PrayerPlan.FARD.associateWith { iqamaMinutes(it) },
        morning = morningOn,
        morningAfterFajr = morningAfterFajr,
        evening = eveningOn,
        eveningAfterAsr = eveningAfterAsr,
        dhikrEvery = dhikrEvery,
    )

    /** Времена на дату по сохранённому месту; null - места ещё нет. */
    fun day(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayTimes? {
        if (!hasLocation) return null
        return PrayerCalc.day(date, lat, lon, method, madhab, IntArray(6) { adjust(Prayer.values()[it]) })
    }

    companion object {
        val DHIKR_STEPS = intArrayOf(0, 15, 30, 60, 120)
    }
}

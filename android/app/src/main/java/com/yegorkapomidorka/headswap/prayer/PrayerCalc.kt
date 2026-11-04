package com.yegorkapomidorka.headswap.prayer

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * HOG CarLink: время намаза без сети и без библиотек - формулы положения Солнца (как praytimes.org / aladhan).
 * Проверено тестом против api.aladhan.com для Аммана, Эр-Рияда, Каира, Карачи, Стамбула и Дубая (±2 мин).
 * Без Android: всё в java.time.
 */
enum class Prayer { FAJR, SUNRISE, DHUHR, ASR, MAGHRIB, ISHA }

/** Способ расчёта: углы Фаджра/Иша (или Иша через N минут после Магриба), сдвиг Магриба. Параметры - как у aladhan. */
enum class CalcMethod(
    val fajrAngle: Double,
    val ishaAngle: Double?,
    val ishaMinutes: Int = 0,
    val maghribMinutes: Int = 0,
    val maghribAngle: Double? = null,
    /** Постоянные поправки ведомства, мин (Фаджр, восход, Зухр, Аср, Магриб, Иша) - как у aladhan. */
    val offsets: IntArray = IntArray(6),
) {
    JORDAN(18.0, 18.0, maghribMinutes = 5),            // Министерство вакфов Иордании
    MAKKAH(18.5, null, ishaMinutes = 90),               // Умм аль-Кура (в Рамадан Иша +120)
    EGYPT(19.5, 17.5),
    MWL(18.0, 17.0),
    ISNA(15.0, 15.0),
    KARACHI(18.0, 18.0),
    GULF(19.5, null, ishaMinutes = 90),
    KUWAIT(18.0, 17.5),
    QATAR(18.0, null, ishaMinutes = 90),
    DUBAI(18.2, 18.2, offsets = intArrayOf(0, 0, 3, 0, 3, 0)),
    TURKEY(18.0, 17.0, offsets = intArrayOf(0, -7, 5, 4, 7, 0)),
    SINGAPORE(20.0, 18.0),
    JAKIM(20.0, 18.0),
    KEMENAG(20.0, 18.0),
    TUNISIA(18.0, 18.0),
    ALGERIA(18.0, 17.0),
    MOROCCO(19.0, 17.0, offsets = intArrayOf(0, 0, 5, 0, 5, 0)),
    FRANCE(12.0, 12.0),
    RUSSIA(16.0, 15.0),
    TEHRAN(17.7, 14.0, maghribAngle = 4.5);

    companion object {
        /** Способ по стране (ISO 3166 alpha-2); неизвестная страна - Всемирная исламская лига. */
        fun forCountry(iso: String?): CalcMethod = when (iso?.uppercase()) {
            "JO", "PS" -> JORDAN
            "SA", "YE" -> MAKKAH
            "EG", "SD", "LY", "SY", "LB", "IQ" -> EGYPT
            "AE" -> DUBAI
            "KW" -> KUWAIT
            "QA" -> QATAR
            "BH", "OM" -> GULF
            "PK", "IN", "BD", "AF" -> KARACHI
            "TR" -> TURKEY
            "US", "CA" -> ISNA
            "SG" -> SINGAPORE
            "MY" -> JAKIM
            "ID" -> KEMENAG
            "TN" -> TUNISIA
            "DZ" -> ALGERIA
            "MA" -> MOROCCO
            "FR" -> FRANCE
            "RU" -> RUSSIA
            "IR" -> TEHRAN
            else -> MWL
        }
    }
}

/** Аср: тень = 1 (шафиитский, маликитский, ханбалитский) или 2 (ханафитский) длины предмета. */
enum class Madhab(val shadow: Int) {
    SHAFI(1), HANAFI(2);

    companion object {
        fun forCountry(iso: String?): Madhab = if (iso?.uppercase() in setOf("PK", "IN", "BD", "AF")) HANAFI else SHAFI
    }
}

/** Времена одного дня, эпоха в мс. */
data class DayTimes(val date: LocalDate, val millis: LongArray) {
    operator fun get(p: Prayer): Long = millis[p.ordinal]
}

object PrayerCalc {
    private const val SUN_ANGLE = 0.833

    /**
     * Времена на дату. [adjust] - поправка в минутах по каждому [Prayer] (как в мечети рядом).
     * Округление до минуты - как в таблицах (aladhan округляет до ближайшей).
     */
    fun day(
        date: LocalDate,
        lat: Double,
        lon: Double,
        method: CalcMethod,
        madhab: Madhab = Madhab.SHAFI,
        adjust: IntArray = IntArray(6),
    ): DayTimes? {
        val jd = julian(date.year, date.monthValue, date.dayOfMonth) - lon / (15.0 * 24.0)
        // начальные приближения в часах (как praytimes), две итерации уточнения
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0)   // fajr, sunrise, dhuhr, asr, sunset, maghrib, isha
        repeat(2) {
            val p = DoubleArray(7) { t[it] / 24.0 }
            t = doubleArrayOf(
                angleTime(jd, method.fajrAngle, p[0], lat, ccw = true),
                angleTime(jd, SUN_ANGLE, p[1], lat, ccw = true),
                midDay(jd, p[2]),
                asrTime(jd, madhab.shadow, p[3], lat),
                angleTime(jd, SUN_ANGLE, p[4], lat, ccw = false),
                method.maghribAngle?.let { angleTime(jd, it, p[5], lat, ccw = false) } ?: angleTime(jd, SUN_ANGLE, p[5], lat, ccw = false),
                method.ishaAngle?.let { angleTime(jd, it, p[6], lat, ccw = false) } ?: 0.0,
            )
        }
        val sunset = t[4]
        val sunrise = t[1]
        // полярный день/ночь: восхода или заката нет - времени намаза по этой формуле нет (будильники не ставим)
        if (sunrise.isNaN() || sunset.isNaN()) return null
        var fajr = t[0]
        var isha = t[6]
        val maghrib = if (method.maghribAngle != null) t[5] else sunset + method.maghribMinutes / 60.0
        if (method.ishaAngle == null) {
            val ramadan = method == CalcMethod.MAKKAH && hijriMonth(date) == 9
            isha = maghrib + (if (ramadan) 120 else method.ishaMinutes) / 60.0
        }
        // высокие широты: угол не достигается или ночь короткая - доля ночи по углу (angle-based, как aladhan)
        val night = 24.0 - (sunset - sunrise)
        val fajrMax = method.fajrAngle / 60.0 * night
        if (fajr.isNaN() || sunrise - fajr > fajrMax) fajr = sunrise - fajrMax
        if (method.ishaAngle != null) {
            val ishaMax = method.ishaAngle / 60.0 * night
            if (isha.isNaN() || isha - sunset > ishaMax) isha = sunset + ishaMax
        }
        val hours = doubleArrayOf(fajr, sunrise, t[2], t[3], maghrib, isha)
        // часы - местное солнечное время; минус долгота/15 = UTC от полуночи UTC этой даты. Пояс не нужен:
        // смена летнего времени днём не сдвигает моменты (раньше смещение бралось на полдень - в день перехода ±1 ч)
        val utcMidnight = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val ms = LongArray(6) { i ->
            val utc = hours[i] - lon / 15.0 + (method.offsets[i] + adjust.getOrElse(i) { 0 }) / 60.0
            val raw = utcMidnight + (utc * 3_600_000.0).toLong()
            // ровно в минуту: +30 с и вниз
            (raw + 30_000) / 60_000 * 60_000
        }
        return DayTimes(date, ms)
    }

    private fun hijriMonth(date: LocalDate): Int = try {
        HijrahDate.from(date).get(ChronoField.MONTH_OF_YEAR)
    } catch (_: Exception) {
        0
    }

    // ---- астрономия (Jean Meeus, упрощённо как praytimes.org) ----

    private fun julian(y0: Int, m0: Int, d: Int): Double {
        var y = y0
        var m = m0
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** Склонение Солнца и уравнение времени (часы). */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    private fun midDay(jd: Double, t: Double): Double = fixHour(12 - sun(jd + t).second)

    private fun angleTime(jd: Double, angle: Double, t: Double, lat: Double, ccw: Boolean): Double {
        val decl = sun(jd + t).first
        val noon = midDay(jd, t)
        val c = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
        if (c < -1 || c > 1) return Double.NaN
        val h = darccos(c) / 15.0
        return noon + if (ccw) -h else h
    }

    private fun asrTime(jd: Double, factor: Int, t: Double, lat: Double): Double {
        val decl = sun(jd + t).first
        val angle = -darccot(factor + dtan(abs(lat - decl)))
        return angleTime(jd, angle, t, lat, ccw = false)
    }

    private fun rad(d: Double) = d * Math.PI / 180.0
    private fun deg(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1 / x))
    private fun fixAngle(a: Double) = a - 360.0 * floor(a / 360.0)
    private fun fixHour(a: Double) = a - 24.0 * floor(a / 24.0)
}

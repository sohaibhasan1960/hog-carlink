package com.yegorkapomidorka.headswap.prayer

import android.content.Context
import com.yegorkapomidorka.headswap.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** HOG CarLink: подписи индикатора намаза ([PrayerIndicator]) на языке приложения, с временем по месту из настроек. */
object PrayerIndicatorText {
    class Shown(
        val state: PrayerIndicator.State,
        /** Имя намаза («العصر», в пятницу зухр - «الجمعة»). */
        val name: String,
        /** Строка капсулы с секундами: «العصر 2:58 م · بعد 01:12:45» / «اقترب أذان العصر 00:42» / «إقامة العصر بعد 08:42». */
        val title: String,
        /** То же по минутам - для списков Android Auto и приборки (обновляются раз в минуту). */
        val short: String,
        /** Подсказка: «أذان العصر: 2:58 م — الإقامة: 3:08 م». */
        val tip: String,
    )

    fun now(ctx: Context, now: Long = System.currentTimeMillis()): Shown? {
        val prefs = PrayerPrefs(ctx)
        val zone = ZoneId.systemDefault()
        val d = LocalDate.now(zone)
        val today = prefs.day(d, zone) ?: return null
        val tomorrow = prefs.day(d.plusDays(1), zone) ?: return null
        val s = PrayerIndicator.state(now, today, tomorrow, { prefs.iqamaMinutes(it) },
            d.dayOfWeek == DayOfWeek.FRIDAY, d.plusDays(1).dayOfWeek == DayOfWeek.FRIDAY) ?: return null
        val name = if (s.friday) ctx.getString(R.string.pr_jumuah) else PrayerPlayer.prayerName(ctx, s.prayer)
        val locale = ctx.resources.configuration.locales[0] ?: Locale.getDefault()
        val hm = DateTimeFormatter.ofPattern("h:mm a", locale)
        fun t(ms: Long) = hm.format(Instant.ofEpochMilli(ms).atZone(zone))
        val mins = PrayerIndicator.minutes(s.remainMs, ctx.getString(R.string.pr_ind_h), ctx.getString(R.string.pr_ind_m))
        val clock = PrayerIndicator.clock(s.remainMs)
        val (title, short) = when (s.phase) {
            PrayerIndicator.Phase.NEXT -> ctx.getString(R.string.pr_ind_next, name, t(s.adhanAt), clock) to
                ctx.getString(R.string.pr_ind_next, name, t(s.adhanAt), mins)
            PrayerIndicator.Phase.SOON -> "${ctx.getString(R.string.pr_ind_soon, name)} $clock" to ctx.getString(R.string.pr_ind_soon, name)
            PrayerIndicator.Phase.IQAMA -> ctx.getString(R.string.pr_ind_iqama, name, clock) to ctx.getString(R.string.pr_ind_iqama, name, mins)
        }
        val tip = if (s.friday) ctx.getString(R.string.pr_ind_tip_friday, name, t(s.adhanAt))
            else ctx.getString(R.string.pr_ind_tip, name, t(s.adhanAt), t(s.iqamaAt))
        return Shown(s, name, title, short, tip)
    }
}

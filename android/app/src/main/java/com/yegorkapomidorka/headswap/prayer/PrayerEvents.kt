package com.yegorkapomidorka.headswap.prayer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R
import com.yegorkapomidorka.headswap.prayer.PrayerPlan.Kind
import com.yegorkapomidorka.headswap.prayer.PrayerPlayer.What

/**
 * HOG CarLink: что делать, когда наступило событие. В машине (или «звук всегда») - звук через PrayerPlayer;
 * иначе уведомление: азан - со звуком канала, азкары - с кнопкой «Слушать», зикр и икама - тихо.
 */
object PrayerEvents {
    private const val NOTIF_EVENT = 7301

    fun fire(ctx: Context, kind: Kind, prayer: Prayer?) {
        val prefs = PrayerPrefs(ctx)
        val car = PrayerAlarms.inCar()
        val sound = car || prefs.soundAlways
        AppLog.i("намаз: событие $kind ${prayer ?: ""} в машине=$car")
        when (kind) {
            Kind.ADHAN -> if (sound) PrayerPlayer.start(ctx, What.ADHAN, prayer)
                else post(ctx, PrayerPlayer.CH_ADHAN, ctx.getString(R.string.pr_adhan_of, name(ctx, prayer)), ctx.getString(R.string.pr_adhan_now), null)
            Kind.IQAMA -> if (sound) PrayerPlayer.start(ctx, What.IQAMA, prayer)
                else post(ctx, PrayerPlayer.CH_INFO, ctx.getString(R.string.pr_iqama_of, name(ctx, prayer)), "", null)
            Kind.MORNING, Kind.EVENING -> {
                val w = if (kind == Kind.MORNING) What.MORNING else What.EVENING
                if (car && prefs.adhkarAutoPlay) PrayerPlayer.start(ctx, w)
                else post(ctx, PrayerPlayer.CH_INFO, ctx.getString(if (w == What.MORNING) R.string.pr_morning else R.string.pr_evening),
                    ctx.getString(R.string.pr_adhkar_tap), w)
            }
            Kind.DHIKR -> {
                val i = prefs.dhikrIndex
                val text = Adhkar.SHORT[i % Adhkar.SHORT.size]
                prefs.dhikrIndex = (i + 1) % Adhkar.SHORT.size
                if (car) PrayerPlayer.start(ctx, What.DHIKR, text = text)
                else post(ctx, PrayerPlayer.CH_INFO, ctx.getString(R.string.pr_dhikr), text, null)
            }
        }
    }

    private fun name(ctx: Context, p: Prayer?) = PrayerPlayer.prayerName(ctx, p ?: Prayer.DHUHR)

    private fun post(ctx: Context, channel: String, title: String, text: String, listen: What?) {
        PrayerPlayer.channels(ctx)
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PRAYER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (listen != null) b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.pr_listen), PrayerPlayer.intent(ctx, listen)).build())
        try {
            ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_EVENT, b.build())
        } catch (_: SecurityException) {
        }
    }
}

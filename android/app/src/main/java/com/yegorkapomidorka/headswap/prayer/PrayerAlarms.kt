package com.yegorkapomidorka.headswap.prayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.CarLifeState
import com.yegorkapomidorka.headswap.MainActivity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * HOG CarLink: будильники намаза. Событие намаза (азан, икама, азкары) - setAlarmClock: Doze и менеджер батареи Honor
 * его не откладывают. Напоминание зикра - точный будильник (USE_EXACT_ALARM). После срабатывания - следующий.
 */
object PrayerAlarms {
    private const val REQ_MAIN = 7101
    private const val REQ_DHIKR = 7102
    const val ACTION_ALARM = "com.hog.carlink.PRAYER_ALARM"
    const val EXTRA_KIND = "kind"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_AT = "at"

    /** Пересчитать и поставить оба будильника (или снять, если выключено). */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val prefs = PrayerPrefs(app)
        val main = pending(app, REQ_MAIN, null)
        val dhikr = pending(app, REQ_DHIKR, null)
        am.cancel(main)
        am.cancel(dhikr)
        if (!prefs.enabled || !prefs.hasLocation) return
        val now = System.currentTimeMillis()
        val today = prefs.day(LocalDate.now()) ?: return
        val tomorrow = prefs.day(LocalDate.now().plusDays(1)) ?: return
        val cfg = prefs.config()
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.US)
        PrayerPlan.next(now, today, tomorrow, cfg)?.let { e ->
            val show = PendingIntent.getActivity(app, REQ_MAIN, Intent(app, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PRAYER, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(e.at, show), pending(app, REQ_MAIN, e))
                AppLog.i("намаз: будильник ${e.kind} ${e.prayer ?: ""} ${fmt.format(Date(e.at))}")
            } catch (x: SecurityException) {
                AppLog.i("намаз: будильник не поставлен: ${x.message}")
            }
        }
        PrayerPlan.nextDhikr(now, today, tomorrow, cfg)?.let { e ->
            try {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pending(app, REQ_DHIKR, e))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, e.at, pending(app, REQ_DHIKR, e))
            } catch (x: SecurityException) {
                AppLog.i("намаз: напоминание зикра не поставлено: ${x.message}")
            }
        }
    }

    private fun pending(ctx: Context, req: Int, e: PrayerPlan.Event?): PendingIntent {
        val i = Intent(ctx, PrayerReceiver::class.java).setAction(ACTION_ALARM)
        if (e != null) i.putExtra(EXTRA_KIND, e.kind.name).putExtra(EXTRA_PRAYER, e.prayer?.name).putExtra(EXTRA_AT, e.at)
        return PendingIntent.getBroadcast(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Телефон в машине: идёт сессия моста или выбранная машина рядом по Bluetooth. */
    fun inCar(): Boolean = CarLifeState.running || CarLifeState.carAuto
}

/** Срабатывание будильника и события системы (перезагрузка, смена времени/пояса, обновление приложения). */
class PrayerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != PrayerAlarms.ACTION_ALARM) {
            AppLog.init(ctx)
            AppLog.i("намаз: ${intent.action} - пересчёт будильников")
            PrayerAlarms.reschedule(ctx)
            return
        }
        AppLog.init(ctx)
        val kind = intent.getStringExtra(PrayerAlarms.EXTRA_KIND)?.let { k -> PrayerPlan.Kind.values().firstOrNull { it.name == k } }
        val prayer = intent.getStringExtra(PrayerAlarms.EXTRA_PRAYER)?.let { p -> Prayer.values().firstOrNull { it.name == p } }
        val at = intent.getLongExtra(PrayerAlarms.EXTRA_AT, 0)
        val now = System.currentTimeMillis()
        if (kind != null && !PrayerPlan.stale(at, now)) PrayerEvents.fire(ctx, kind, prayer)
        else AppLog.i("намаз: событие $kind опоздало или пустое, пропускаю")
        PrayerAlarms.reschedule(ctx)
    }
}

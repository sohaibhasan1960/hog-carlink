package com.yegorkapomidorka.headswap.support

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R

/**
 * HOG CarLink: фоновая проверка поддержки раз в ~30 мин при сети. Владельцу - уведомление о новых запросах,
 * клиенту - о пришедшем ответе. Без сервера и push: опрос Firestore (SupportCloud).
 */
class SupportJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            try {
                check(applicationContext)
            } catch (e: Exception) {
                AppLog.i("поддержка: фоновая проверка не прошла: ${e.javaClass.simpleName}: ${e.message}")
            }
            jobFinished(params, false)
        }.apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 4711
        private const val CHANNEL = "support"
        private const val NOTIF_OWNER = 4712
        private const val NOTIF_REPLY = 4713
        private const val PERIOD_MS = 30 * 60_000L

        /** Запланировать, если есть что проверять (владелец или клиент с запросами). */
        fun schedule(ctx: Context) {
            val c = SupportCloud(ctx)
            if (!c.isOwner && !c.hasSent) return
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, SupportJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS)
                    .setPersisted(false)
                    .build(),
            )
        }

        /** Одна проверка (блокирующая): новые запросы для владельца, новые ответы для клиента. */
        fun check(ctx: Context) {
            val c = SupportCloud(ctx)
            if (c.isOwner) {
                val list = c.all(50)
                val seen = c.ownerSeen
                val fresh = list.filter { it.status == "new" && it.createdAt > seen }
                if (fresh.isNotEmpty()) notify(ctx, NOTIF_OWNER, ctx.getString(R.string.sup_notif_new_title),
                    ctx.getString(R.string.sup_notif_new_text, fresh.size, fresh.first().name.ifBlank { "?" }))
            }
            if (c.hasSent) {
                val answered = c.mine().filter { it.reply.isNotBlank() && it.repliedAt > c.replySeen(it.id) }
                if (answered.isNotEmpty()) notify(ctx, NOTIF_REPLY, ctx.getString(R.string.sup_notif_reply_title),
                    answered.first().reply.take(120))
            }
        }

        private fun notify(ctx: Context, id: Int, title: String, text: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.sup_title), NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                ctx, id, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_SUPPORT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = android.app.Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_headswap)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                nm.notify(id, n)
            } catch (_: SecurityException) {
            }
        }
    }
}

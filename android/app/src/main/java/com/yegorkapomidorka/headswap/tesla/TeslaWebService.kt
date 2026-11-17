package com.yegorkapomidorka.headswap.tesla

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.MainActivity
import com.yegorkapomidorka.headswap.R

/**
 * HOG CarLink: держит процесс со страницей «شاشة تسلا» ([TeslaWeb]), пока она включена: без foreground-сервиса
 * Android усыпит процесс, и браузер Tesla потеряет страницу. Уведомление показывает адрес для браузера.
 */
class TeslaWebService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !TeslaWeb.enabled(this)) {
            TeslaWeb.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tesla_title), NotificationManager.IMPORTANCE_LOW))
        val addr = TeslaWeb.addresses().firstOrNull()?.let { TeslaWeb.url(this, it) } ?: getString(R.string.tesla_no_ip)
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_headswap)
            .setContentTitle(getString(R.string.tesla_notif))
            .setContentText(addr)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            AppLog.e("Tesla: foreground не дали", e)
            stopSelf()
            return START_NOT_STICKY
        }
        TeslaWeb.start(this)
        return START_STICKY
    }

    override fun onDestroy() {
        TeslaWeb.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tesla"
        private const val NOTIF_ID = 86
        private const val ACTION_STOP = "com.hog.carlink.TESLA_STOP"

        /** Включить / выключить страницу (из настроек, приложение на экране). */
        fun apply(ctx: Context) {
            val i = Intent(ctx, TeslaWebService::class.java)
            try {
                if (TeslaWeb.enabled(ctx)) ctx.startForegroundService(i)
                else ctx.startService(i.setAction(ACTION_STOP))
            } catch (e: Exception) {
                AppLog.e("Tesla: сервис не запустился", e)
            }
        }
    }
}

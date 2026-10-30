package com.yegorkapomidorka.headswap.link.car

import android.content.Context
import android.content.Intent
import com.yegorkapomidorka.headswap.AppLog
import com.yegorkapomidorka.headswap.BridgeService
import com.yegorkapomidorka.headswap.BuildConfig
import com.yegorkapomidorka.headswap.link.diag.WLog

/**
 * HOG CarLink: one entry point for "the selected car is near / gone", shared by the ACL receiver ([CarAclReceiver]) and
 * the CDM presence service ([CarPresenceService]). Both may fire for the same connection: BridgeService treats a repeated
 * ACTION_WIFI_B_AUTO as auto-mark, and a repeated auto-off only re-arms the debounce. [source] goes to WL to show which
 * path actually woke the app.
 */
object CarTrigger {
    fun near(ctx: Context, source: String) {
        if (!BuildConfig.WIRELESS_BETA) return
        WLog.ev(WLog.TRIGGER, "car-near", "src" to source)
        try {
            ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO))
        } catch (e: Exception) {      // ForegroundServiceStartNotAllowedException и пр.: из фона без исключений запуск запрещён
            AppLog.e("$source: старт сервиса", e)
            WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName, "src" to source)
        }
    }

    fun gone(ctx: Context, source: String) {
        if (!BuildConfig.WIRELESS_BETA) return
        WLog.ev(WLog.TRIGGER, "car-gone", "src" to source)
        // сервиса нет - гасить нечего; startService на пустой сервис из фона не нужен
        if (!BridgeService.alive) return
        try {
            ctx.startService(Intent(ctx, BridgeService::class.java).setAction(BridgeService.ACTION_WIFI_B_AUTO_OFF))
        } catch (e: Exception) {
            AppLog.e("$source: отбой сервису", e)
            WLog.ev(WLog.TRIGGER, "fgs-fail", "err" to e.javaClass.simpleName, "action" to "off", "src" to source)
        }
    }
}

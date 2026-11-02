package com.yegorkapomidorka.headswap.link.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager

/**
 * HOG CarLink: сети Wi-Fi рядом - чтобы выбрать сеть машины из списка, а не печатать имя. Результаты сканирования Android
 * считает геоданными: нужна ACCESS_FINE_LOCATION на любой версии (NEARBY_WIFI_DEVICES с neverForLocation их не даёт)
 * и включённая геолокация. Сохранённые в телефоне сети и их пароли приложениям недоступны - пароль вводится вручную.
 */
object WifiScan {
    const val PERMISSION = Manifest.permission.ACCESS_FINE_LOCATION

    /** Android 12 молча отклоняет FINE без COARSE в том же запросе. */
    val PERMISSIONS: Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Сколько ждать после startScan, прежде чем читать результаты. */
    const val SETTLE_MS = 2500L

    data class Net(val ssid: String, val level: Int)

    fun granted(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun locationOn(ctx: Context): Boolean = try {
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    } catch (_: Exception) {
        false
    }

    /** Запустить скан; система может отказать по лимиту (4 за 2 мин) - тогда [nearby] отдаст последние результаты. */
    fun start(ctx: Context) {
        try {
            @Suppress("DEPRECATION")
            ctx.applicationContext.getSystemService(WifiManager::class.java)?.startScan()
        } catch (_: Exception) {
        }
    }

    /** Сети по убыванию сигнала, без скрытых и без дублей (у одной сети несколько точек/диапазонов). */
    fun nearby(ctx: Context): List<Net> {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val results = try {
            @Suppress("DEPRECATION")
            wm.scanResults
        } catch (_: SecurityException) {
            return emptyList()
        }
        val best = HashMap<String, Int>()
        for (r in results) {
            @Suppress("DEPRECATION")
            val s = r.SSID ?: continue
            if (s.isBlank()) continue
            val prev = best[s]
            if (prev == null || r.level > prev) best[s] = r.level
        }
        return best.entries.map { Net(it.key, it.value) }.sortedByDescending { it.level }
    }

    /** Сила сигнала для списка. */
    fun bars(level: Int): String = when {
        level >= -55 -> "\u2582\u2584\u2586\u2588"
        level >= -67 -> "\u2582\u2584\u2586"
        level >= -78 -> "\u2582\u2584"
        else -> "\u2582"
    }
}
